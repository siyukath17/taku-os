package org.agentos.lab;

import android.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** User-facing management of existing leases, never another task or result store. */
final class WorkspaceControl {
    private static final ExecutorService REVOCATION_WORKERS =
            Executors.newFixedThreadPool(WorkspacePolicy.MAX_ACTIVE_SCREENS, work -> {
                Thread worker = new Thread(work, "workspace-revocation");
                worker.setDaemon(true);
                return worker;
            });
    private static final Map<String, RevocationReceipt> REVOCATIONS = new LinkedHashMap<>();

    private record RevocationReceipt(DedicatedScreens.RevokedScreen screen, String status,
            String error, JSONObject release) {
        JSONObject json() throws JSONException {
            JSONObject result = new JSONObject().put("threadId", screen.thread())
                    .put("screenKey", screen.key()).put("generation", screen.generation())
                    .put("taskTitle", screen.taskTitle()).put("packageNames", new JSONArray(screen.packages()))
                    .put("status", status);
            if (release != null) for (String field : new String[]{"released", "heldApps", "screenRetained", "packageNames"})
                if (release.has(field)) result.put(field, release.get(field));
            if (!error.isEmpty()) result.put("error", error);
            return result;
        }
    }

    static JSONObject control(JSONObject request)throws Exception {
        String action=request.getString("action");
        if(action.equals("list"))return request.has("threadId")?list(request.getString("threadId")):list();
        if(action.equals("configure"))return configure(request.getJSONObject("patch"));
        if(action.equals("returnAndResume")||action.equals("addRequirement")||action.equals("executionStatus"))
            return WorkspaceExecutionRuntime.control(request);
        String thread=request.getString("threadId"),key=request.getString("screenKey"),generation=request.getString("generation");
        DedicatedScreens registry=DedicatedScreensOnDevice.shared();
        PhoneExecutionScreens.Lease binding=registry.exact(thread,key,generation);
        if(!binding.controlLock().tryLock())throw new IOException("WORKSPACE_CONTROL_BUSY");
        try{switch(action){
            case "pause":case "takeControl":
                pause(thread,key,generation,action.equals("takeControl"));
                return result(thread,false);
            case "release":
                close(thread, key, generation);
                return list();
            case "input":
                requireQuiescent(thread);
                Object revision=request.get("controlRevision");
                if(!(revision instanceof Integer)&&!(revision instanceof Long))throw new IOException("INVALID_SCREEN_CONTROL_REVISION");
                return registry.userInput(thread,key,generation,((Number)revision).longValue(),screen->input(screen,request.getJSONObject("input")));
            default:throw new IOException("UNKNOWN_WORKSPACE_ACTION");
        }}finally{binding.controlLock().unlock();}
    }
    static JSONObject runtime(String thread)throws Exception{return NativeBridge.workspaceRuntime(thread);}
    static boolean isQuiescent(JSONObject runtime)throws Exception {
        if(!runtime.optString("activeTurnId").isEmpty()||runtime.optInt("workers")!=0||runtime.optBoolean("waitingForUser")||runtime.optInt("pendingExecutions")!=0||runtime.optBoolean("realtimeActive"))return false;
        JSONObject completed=runtime.optJSONObject("completedTurn");
        if(completed!=null&&completed.optString("status").equals("interrupted")){
            JSONObject proof=runtime.optJSONObject("cancellationCleanup");
            return proof!=null&&proof.optBoolean("complete")&&completed.optString("id").equals(proof.optString("turnId"));
        }
        return true;
    }
    static void requireQuiescent(String thread)throws Exception {
        if(!isQuiescent(runtime(thread)))throw new IOException("WORKSPACE_EXECUTION_STOP_UNCONFIRMED");
        JSONObject proof=CodexAppServer.workspaceProcessProof(thread);
        if(!proof.optBoolean("verified")||!proof.optBoolean("complete"))throw new IOException("WORKSPACE_PROCESS_STOP_UNCONFIRMED");
    }
    static void pause(String thread,String key,String generation,boolean takeControl)throws Exception {
        DedicatedScreens registry=DedicatedScreensOnDevice.shared();
        PhoneExecutionScreens.Lease controlled=registry.exact(thread,key,generation);
        if(!controlled.controlLock().tryLock())throw new IOException("WORKSPACE_CONTROL_BUSY");
        try{registry.beginPause(thread,key,generation);
        try{
            stopExecution(thread,key,generation);
            registry.finishControl(thread,key,generation,takeControl?"user":"paused");
        }catch(Exception error){
            try{registry.controlFailed(thread,key,generation,NativeBridge.publicError(error));}catch(Exception saveError){error.addSuppressed(saveError);}
            throw error;
        }}finally{controlled.controlLock().unlock();}
    }
    /**
     * When Codex cannot tell whether the session is a sub-agent (it was deleted with its main
     * session, say), the screen still closes: closing is how the user clears such a screen.
     */
    static void close(String thread, String key, String generation) throws Exception {
        DedicatedScreens screens = DedicatedScreensOnDevice.shared();
        String taskTitle = screens.describe(thread).optString("taskTitle");
        screens.beginClose(thread, key, generation);
        stopAndClose(screens, thread, key, generation, taskTitle, List.of());
    }

    private static void stopAndClose(DedicatedScreens screens, String thread, String key,
            String generation, String taskTitle, List<String> revoked) throws Exception {
        DedicatedScreens.ParentNote note = null;
        try {
            stopExecution(thread, key, generation);
            JSONObject sub = WorkspaceExecutionRuntime.subAgent(thread, !revoked.isEmpty());
            if (sub != null) {
                String text = PhoneSubAgents.closedText(sub.getString("path"), taskTitle);
                if (!revoked.isEmpty())
                    text = "The user revoked Agent access to " + String.join(", ", revoked) + ". " + text;
                note = new DedicatedScreens.ParentNote(sub.getString("parent"), text);
            }
            screens.close(thread, key, generation, note, !revoked.isEmpty());
        } catch (Exception error) {
            try {
                screens.controlFailed(thread, key, generation, NativeBridge.publicError(error));
            } catch (Exception saveError) {
                error.addSuppressed(saveError);
            }
            throw error;
        }
        if (note != null) NativeBridge.tellRunningTurn(note.parent(), note.text());
    }

    static void stopExecution(String thread,String key,String generation)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        Set<String> interrupted=new HashSet<>();boolean realtimeStopSent=false;
        while(true){
            JSONObject observed=runtime(thread);String turn=observed.optString("activeTurnId");
            String method="";JSONObject params=new JSONObject().put("threadId",thread);
            if(observed.optBoolean("realtimeActive")&&!realtimeStopSent){method="thread/realtime/stop";realtimeStopSent=true;}
            else if(!turn.isEmpty()&&interrupted.add(turn)){method="turn/interrupt";params.put("turnId",turn);}
            if(!method.isEmpty()){
                // No registry/NativeBridge monitor is held while the runtime drains.
                NativeBridge.Call call=new NativeBridge.Call(method,params);
                if(!NativeBridge.enqueue(call))throw new IOException("WORKSPACE_RUNTIME_BUSY");
                long left=deadline-System.nanoTime();if(left<=0)throw new IOException("WORKSPACE_EXECUTION_STOP_UNCONFIRMED");
                try{call.result.get(left,TimeUnit.NANOSECONDS);}catch(java.util.concurrent.ExecutionException error){if(!isQuiescent(runtime(thread)))throw error;}
                continue;
            }
            if(isQuiescent(observed))break;
            if(System.nanoTime()>=deadline)throw new IOException("WORKSPACE_EXECUTION_STOP_UNCONFIRMED");
            Thread.sleep(50);
        }
        PhoneGestures.cancelThread(thread);
        PhoneExecutionScreens.Screen screen = DedicatedScreensOnDevice.shared().exact(thread, key, generation).screenHandle();
        PhoneGestures.prepare(screen);PhoneGestures.requireIdle(screen.displayId);
        requireQuiescent(thread);
    }
    static JSONObject result(String thread,boolean resumeRequired)throws Exception {
        JSONObject screen=DedicatedScreensOnDevice.shared().describe(thread);
        // describe reads only the ledger, since it also runs right before a turn is sent; the UI's
        // answer takes the list item instead, which adds the current App read from Android.
        JSONArray items = DedicatedScreensOnDevice.shared().list(thread);
        if (items.length() != 0) screen = items.getJSONObject(0);
        return new JSONObject().put("screen",screen).put("resumeRequired",resumeRequired);
    }
    static JSONObject list()throws Exception {return list(null);}
    static JSONObject list(String thread)throws Exception {
        if(thread!=null&&!thread.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("INVALID_SCREEN_THREAD");
        JSONArray screens=DedicatedScreensOnDevice.shared().list(thread);
        return new JSONObject().put("schema","taku.workspaces/1").put("policy",WorkspacePolicy.snapshot()).put("limits", WorkspacePolicy.limits()).put("screens",screens)
            .put("revocations", revocations(thread))
            .put("controlBoundary","Managed phone tools only; independent displays share app accounts and data.");
    }
    static JSONObject configure(JSONObject patch)throws Exception {
        DedicatedScreens screens = DedicatedScreensOnDevice.shared();
        synchronized (REVOCATIONS) {
            // Save policy and bind its notice to the same native turn. Cleanup never holds this monitor.
            try (CleanupWork.Held bridgeHeld = NativeBridge.lock()) {
                for (DedicatedScreens.RevokedScreen target : screens.configurePolicy(patch)) {
                    String id = target.thread() + "/" + target.key() + "/" + target.generation();
                    RevocationReceipt existing = REVOCATIONS.get(id);
                    if (existing != null && existing.status().equals("pending")) continue;
                    REVOCATIONS.remove(id);
                    REVOCATIONS.put(id, new RevocationReceipt(target, "pending", "", null));
                    CompletableFuture<String> reminder = NativeBridge.phonePolicyChanged(target.thread());
                    REVOCATION_WORKERS.execute(() -> {
                        RevocationReceipt result;
                        Set<String> released = new LinkedHashSet<>(), affected = new LinkedHashSet<>(target.packages());
                        while (true) {
                            result = revoke(screens, target);
                            synchronized (REVOCATIONS) {
                                try {
                                    if (result.release() != null) {
                                        JSONArray packages = result.release().optJSONArray("released");
                                        if (packages != null) for (int i = 0; i < packages.length(); i++) released.add(packages.getString(i));
                                        packages = result.release().optJSONArray("packageNames");
                                        if (packages != null) for (int i = 0; i < packages.length(); i++) affected.add(packages.getString(i));
                                    }
                                    DedicatedScreens.RevokedScreen remaining = screens.beginRevocation(target);
                                    if (remaining != null) affected.addAll(remaining.packages());
                                    // configure uses this monitor too: a newly blocked App is either
                                    // drained here or schedules a fresh worker after this receipt settles.
                                    if (result.status().equals("released") && remaining != null && !remaining.userControlled()) continue;
                                    String status = remaining != null && remaining.userControlled() ? "retained" : result.status();
                                    if (status.equals("superseded") && !released.isEmpty()) status = "released";
                                    JSONObject actual = screens.revocationState(target, List.copyOf(released))
                                            .put("packageNames", new JSONArray(affected));
                                    result = new RevocationReceipt(target, status, result.error(), actual);
                                } catch (Exception error) {
                                    result = new RevocationReceipt(target, "failed", NativeBridge.publicError(error), result.release());
                                }
                                REVOCATIONS.put(id, result);
                                Iterator<RevocationReceipt> oldest = REVOCATIONS.values().iterator();
                                while (REVOCATIONS.size() > 2 * WorkspacePolicy.MAX_ACTIVE_SCREENS
                                        && oldest.hasNext())
                                    if (!oldest.next().status().equals("pending")) oldest.remove();
                                break;
                            }
                        }
                        if (reminder != null) reminder.complete(revocationText(result));
                        if (!result.status().equals("superseded")) notifyRevocation(result);
                    });
                }
            }
        }
        return list();
    }

    private static JSONArray revocations(String thread) throws JSONException {
        JSONArray result = new JSONArray();
        synchronized (REVOCATIONS) {
            for (RevocationReceipt receipt : REVOCATIONS.values())
                if (thread == null || thread.equals(receipt.screen().thread())) result.put(receipt.json());
        }
        return result;
    }

    private static RevocationReceipt revoke(DedicatedScreens screens,
            DedicatedScreens.RevokedScreen target) {
        try {
            PhoneExecutionScreens.Lease lease;
            try {
                lease = screens.exact(target.thread(), target.key(), target.generation());
            } catch (IOException stale) {
                return new RevocationReceipt(target, "superseded", "", null);
            }
            if (!lease.controlLock().tryLock(40, TimeUnit.SECONDS))
                throw new IOException("WORKSPACE_CONTROL_BUSY");
            try {
                JSONObject release = screens.releaseRevoked(target, screen -> {
                    PhoneGestures.prepare(screen);
                    PhoneGestures.cancelThread(target.thread());
                    PhoneGestures.requireIdle(screen.displayId);
                    return new JSONObject();
                });
                return new RevocationReceipt(target, release.getString("status"), "", release);
            } finally {
                lease.controlLock().unlock();
            }
        } catch (Exception error) {
            return new RevocationReceipt(target, "failed", NativeBridge.publicError(error), null);
        }
    }

    private static String revocationText(RevocationReceipt receipt) {
        String result = switch (receipt.status()) {
            case "released" -> "The user changed App permissions. Access cleanup for the blocked App is complete.";
            case "retained" -> "The user changed App permissions. The user has taken over the dedicated screen, so related claims are temporarily retained.";
            case "failed" -> "The user changed App permissions, but cleanup is incomplete and some claims remain: " + receipt.error();
            default -> "App permissions or dedicated-screen state changed. A subsequent state superseded this revocation.";
        };
        if (receipt.release() != null)
            result += " Released: " + receipt.release().optJSONArray("released")
                    + "; still held: " + receipt.release().optJSONArray("heldApps") + ". "
                    + (receipt.release().optBoolean("screenRetained")
                            ? "This conversation still holds its original dedicated screen." : "This conversation no longer holds its original dedicated screen.");
        return result + " Reassess the task using the remaining App permissions. Continue if possible; otherwise end this turn and "
                + "explain the restriction and completed work to the user. This tool operation was not performed. Choose the next step again.";
    }

    private static void notifyRevocation(RevocationReceipt receipt) {
        try {
            TaskBroker.command("/system/bin/am", "broadcast",
                    "--user", Integer.toString(receipt.screen().userId()),
                    "-a", "org.agentos.assistant.WORKSPACE_REVOKED",
                    "-n", "org.agentos.assistant/.TaskNotificationReceiver",
                    "--es", "revocation", receipt.json().toString());
        } catch (Exception error) {
            android.util.Log.w("TakuDedicatedScreens", "revocation notification: " + NativeBridge.publicError(error));
        }
    }
    static final class Channel implements Closeable {
        final LocalSocket socket=new LocalSocket();final PhoneExecutionScreens.Screen screen;
        final DataInputStream input;final OutputStream output;
        Channel(PhoneExecutionScreens.Screen screen)throws Exception {
            this.screen=screen;
            try{
                socket.connect(new LocalSocketAddress("taku-mirror-"+screen.screenKey,LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(5000);input=new DataInputStream(socket.getInputStream());output=socket.getOutputStream();status();
            }catch(Exception error){socket.close();throw error;}
        }
        byte[] call(String command)throws Exception {
            output.write((command+"\n").getBytes(StandardCharsets.UTF_8));output.flush();int size=input.readInt();
            if(size<1||size>65536)throw new IOException("INVALID_WORKSPACE_RESPONSE");
            byte[] data=new byte[size];input.readFully(data);return data;
        }
        JSONObject status()throws Exception {
            JSONObject meta=new JSONObject(new String(call("status"),StandardCharsets.UTF_8));
            Credentials peer=socket.getPeerCredentials();screen.validateLive(meta,peer.getPid(),peer.getUid());return meta;
        }
        public void close()throws IOException{socket.close();}
    }
    static int coordinate(JSONObject input,String field,int limit)throws Exception {
        Object v=input.get(field);if(!(v instanceof Integer)||((Integer)v)<0||((Integer)v)>=limit)throw new IOException("INVALID_WORKSPACE_COORDINATE");return (Integer)v;
    }
    static JSONObject input(PhoneExecutionScreens.Screen screen,JSONObject input)throws Exception {
        try(Channel channel=new Channel(screen)){
            JSONObject meta=channel.status();int width=meta.getInt("width"),height=meta.getInt("height");String command;
            switch(input.getString("type")){
                case "tap":command="tap "+coordinate(input,"x",width)+" "+coordinate(input,"y",height);break;
                case "swipe":
                    Object duration=input.get("durationMs");if(!(duration instanceof Integer)||(Integer)duration<100||(Integer)duration>2000)throw new IOException("INVALID_WORKSPACE_GESTURE_DURATION");
                    command="swipe "+coordinate(input,"fromX",width)+" "+coordinate(input,"fromY",height)+" "+coordinate(input,"toX",width)+" "+coordinate(input,"toY",height)+" "+duration;break;
                case "key":if(!(input.get("keyCode") instanceof Integer)||input.getInt("keyCode")!=4)throw new IOException("UNSUPPORTED_WORKSPACE_KEY");command="key 4";break;
                default:throw new IOException("UNSUPPORTED_WORKSPACE_INPUT");
            }
            JSONObject result=new JSONObject(new String(channel.call(command),StandardCharsets.UTF_8));
            if(!result.optBoolean("ok"))throw new IOException("WORKSPACE_INPUT_FAILED");
            channel.status();return new JSONObject().put("screen",screen.json()).put("inputAccepted",true);
        }
    }
}
