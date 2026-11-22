package org.agentos.lab;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the real broker pump against a controlled stdio runtime. */
public final class CodexTaskOwnershipTest {
    private static final String THREAD="10000000-0000-4000-8000-000000000001";
    private static final String OTHER="20000000-0000-4000-8000-000000000002";
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static JSONObject result(JSONObject request,JSONObject value) {return new JSONObject().put("id",request.getInt("id")).put("result",value);}
    private static JSONObject turn(String id,String status) {return new JSONObject().put("id",id).put("status",status);}
    private static void emit(JSONObject value) {System.out.println(value);System.out.flush();}
    private static void event(String method,String thread,String turn,JSONObject item) {
        JSONObject params=new JSONObject().put("threadId",thread).put("turnId",turn);
        if(method.startsWith("turn/"))params.put("turn",turn(turn,method.equals("turn/completed")?"completed":"inProgress"));
        if(item!=null)params.put("item",item);
        emit(new JSONObject().put("method",method).put("params",params));
    }
    private static JSONObject message(String id) {return new JSONObject().put("id",id).put("type","agentMessage").put("text",id);}
    private static void runtime() throws Exception {
        JSONObject owner=null;List<JSONObject> nativeStarts=new ArrayList<>();
        JSONObject goal = new JSONObject().put("goal", JSONObject.NULL);
        JSONObject starting = null;
        JSONArray interruptions = new JSONArray();
        int goalMutations = 0;
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            String line;
            while((line=input.readLine())!=null) {
                JSONObject request=new JSONObject(line);String method=request.optString("method");
                if(!request.has("id"))continue;
                JSONObject params=request.optJSONObject("params");
                if(method.equals("turn/start")) {
                    String text=params.getJSONArray("input").getJSONObject(0).getString("text");
                    if (text.equals("owned:live")) {
                        emit(result(request, new JSONObject().put("turn", turn("live-turn", "inProgress"))));
                        event("turn/started", THREAD, "live-turn", null);
                        continue;
                    }
                    if (text.equals("owned:starting")) { starting = request; continue; }
                    if (text.equals("owned:completed")) {
                        emit(result(request, new JSONObject().put("turn", turn("completed-turn", "inProgress"))));
                        event("turn/started", THREAD, "completed-turn", null);
                        event("item/completed", THREAD, "completed-turn", message("completed-reply"));
                        event("turn/completed", THREAD, "completed-turn", null);
                        continue;
                    }
                    if(text.startsWith("owned:"))owner=request;else nativeStarts.add(request);
                    if(owner==null||nativeStarts.size()!=3)continue;
                    for(int i=0;i<2;i++) {
                        JSONObject nativeStart=nativeStarts.get(i);String thread=nativeStart.getJSONObject("params").getString("threadId"),turn="foreign-"+i;
                        emit(result(nativeStart,new JSONObject().put("turn",turn(turn,"inProgress"))));
                        event("turn/started",thread,turn,null);event("item/completed",thread,turn,message(turn));
                    }
                    event("turn/started",THREAD,"owned-turn",null);
                    event("item/completed",THREAD,"owned-turn",message("owned-reply"));
                    if(owner.getJSONObject("params").getJSONArray("input").getJSONObject(0).getString("text").equals("owned:reject")) {
                        emit(new JSONObject().put("id",owner.getInt("id")).put("error",new JSONObject().put("code",-32600).put("message","rejected")));
                    } else emit(result(owner,new JSONObject().put("turn",turn("owned-turn","inProgress"))));
                    JSONObject late=nativeStarts.get(2);
                    emit(result(late,new JSONObject().put("turn",turn("late-foreign","inProgress"))));
                    event("turn/started",THREAD,"late-foreign",null);
                    event("item/completed",THREAD,"late-foreign",message("late-foreign"));
                    emit(new JSONObject().put("method","turn/completed").put("params",new JSONObject().put("threadId",THREAD).put("turn",turn("late-foreign","interrupted"))));
                    event("turn/completed",THREAD,"owned-turn",null);
                } else if(method.equals("account/read"))emit(result(request,new JSONObject().put("account",new JSONObject().put("type","host"))));
                else if(method.equals("model/list"))emit(result(request,new JSONObject().put("data",new JSONArray().put(new JSONObject().put("model","host-model").put("isDefault",true)))));
                else if(method.equals("thread/resume")||method.equals("thread/read"))emit(result(request,new JSONObject().put("model","host-model").put("thread",new JSONObject().put("id",params.getString("threadId")).put("turns",new JSONArray()))));
                else if (method.equals("thread/goal/get")) {
                    emit(result(request, goal));
                    if (starting != null) {
                        emit(result(starting, new JSONObject().put("turn", turn("starting-turn", "inProgress"))));
                        event("turn/started", THREAD, "starting-turn", null);
                        event("item/completed", THREAD, "starting-turn", message("starting-reply"));
                        event("turn/completed", THREAD, "starting-turn", null);
                        starting = null;
                    }
                }
                else if (method.equals("test/goal")) { goal = new JSONObject().put("goal", params.opt("goal") == null ? JSONObject.NULL : params.opt("goal")); emit(result(request, new JSONObject())); }
                else if (method.equals("test/goal-response")) { goal = params; emit(result(request, new JSONObject())); }
                else if (method.equals("thread/goal/set") || method.equals("thread/goal/clear")) {
                    goalMutations++; emit(result(request, new JSONObject()));
                } else if (method.equals("turn/interrupt")) {
                    interruptions.put(params);
                    emit(result(request, new JSONObject()));
                    emit(new JSONObject().put("method", "turn/completed").put("params", new JSONObject().put("threadId", params.getString("threadId"))
                            .put("turn", turn(params.getString("turnId"), "interrupted"))));
                } else if (method.equals("test/observed")) emit(result(request, new JSONObject().put("interruptions", interruptions).put("goalMutations", goalMutations)));
                else emit(result(request,new JSONObject()));
            }
        }
    }
    private static void ownership(String mode) throws Exception {
        File root=Files.createTempDirectory("codex-task-ownership-").toFile();TaskBroker.ROOT=root;
        File directory=new File(root,"101");directory.mkdirs();File info=new File(directory,"runtime-info.json");
        String prior=new JSONObject().put("taskId","101").put("threadId",OTHER).put("turnId","immutable-original").toString();
        if(mode.equals("conflict"))TaskBroker.write(info,prior);
        List<NativeBridge.Call> foreign=new ArrayList<>();List<String> replies=new ArrayList<>();
        HarnessContext.prepareHook=request->{
            if(!request.has("id"))return;
            for(String thread:List.of(OTHER,THREAD,THREAD)) {
                NativeBridge.Call call=new NativeBridge.Call("turn/start",new JSONObject().put("threadId",thread)
                        .put("input",new JSONArray().put(new JSONObject().put("type","text").put("text","foreign"))));
                check(NativeBridge.enqueue(call),"Foreign native start was not admitted");foreign.add(call);
            }
        };
        AgentViews.eventHook=event->{
            JSONObject params=event.optJSONObject("params");if(params==null||!event.optString("method").equals("item/completed"))return;
            String thread=params.getString("threadId"),turn=params.getString("turnId");
            if(thread.equals(OTHER)) {
                check(mode.equals("conflict")?Files.readString(info.toPath()).equals(prior):!info.exists(),"An unrelated native start rebound the active task before its own response");
                return;
            }
            check(mode.equals("success"),"A reply escaped without a successful immutable task binding");
            JSONObject association=new JSONObject(Files.readString(info.toPath()));
            check(association.getString("taskId").equals("101")&&association.getString("threadId").equals(THREAD)&&association.getString("turnId").equals("owned-turn"),"A reply became visible before the owning RPC persisted its exact task/thread/turn");
            replies.add(turn);
        };
        JSONObject request=new JSONObject().put("id","101").put("conversationId",THREAD).put("prompt","owned:"+mode)
                .put("panelUpdate",new JSONObject().put("originThreadId",THREAD));
        try {
            try {CodexAppServer.run(directory,request);check(mode.equals("success"),"A conflicting or rejected start unexpectedly completed");}
            catch(IOException failure) {
                check(!mode.equals("success"),"The owning start failed: "+failure.getMessage());
                if(mode.equals("conflict"))check(failure.getMessage().equals("SOURCE_CHANGED"),"Conflicting runtime association was not rejected");
            }
            if(mode.equals("success")) {
                check(foreign.size()==3&&foreign.stream().allMatch(call->call.result.isDone()),"The response interleaving did not exercise all native starts");
                check(replies.equals(List.of("foreign-1","owned-turn","late-foreign")),"Deferred replies lost their stream order");
                JSONObject association=new JSONObject(Files.readString(info.toPath()));
                check(association.getString("taskId").equals("101")&&association.getString("threadId").equals(THREAD)&&association.getString("turnId").equals("owned-turn"),"A later native response or same-thread notification replaced immutable task ownership");
                JSONObject manifest=new JSONObject(Files.readString(new File(directory,"panel-completion.json").toPath()));
                check(manifest.getString("taskId").equals("101")&&manifest.getString("threadId").equals(THREAD)&&manifest.getString("turnId").equals("owned-turn"),"Panel completion used a foreign turn");
                check(Files.readString(new File(directory,"done").toPath()).equals("completed"),"A foreign terminal turn completed the task");
            } else {
                check(replies.isEmpty(),"Rejected binding exposed buffered replies");
                check(mode.equals("conflict")?Files.readString(info.toPath()).equals(prior):!info.exists(),"Unowned start wrote task runtime information");
            }
        } finally {
            HarnessContext.prepareHook=null;AgentViews.eventHook=null;
            if(CodexAppServer.shared!=null)CodexAppServer.shared.close();CodexAppServer.shared=null;
            NativeBridge.inbox.clear();
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(String code, Checked operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) {
            check(code.equals(expected.getMessage()), "Expected " + code + ", got " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected " + code);
    }
    private static JSONObject liveInput(String id, String text, String action) {
        return new JSONObject().put("op", "liveTask").put("action", action).put("threadId", THREAD).put("taskId", id)
                .put("requestId", "30000000-0000-4000-8000-000000000003").put("text", text);
    }
    private static JSONObject live(JSONObject input, NativeBridge.Call... following) throws Exception {
        NativeBridge.Call call = new NativeBridge.Call("taku/liveTask", input);
        check(NativeBridge.enqueue(call), "Live control was not admitted");
        for (NativeBridge.Call pending : following) check(NativeBridge.enqueue(pending), "Concurrent control was not admitted");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!call.result.isDone()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Live control did not finish");
            CodexAppServer.shared.pump(10);
        }
        try { return call.result.get(); }
        catch (java.util.concurrent.ExecutionException failure) { throw (Exception) failure.getCause(); }
    }
    private static File publish(String id, String text, String permission) throws Exception {
        File directory = new File(new File(TaskBroker.ROOT, "tasks"), id); directory.mkdirs();
        JSONObject request = new JSONObject().put("id", id).put("conversationId", THREAD).put("prompt", text).put("permissionMode", permission);
        TaskBroker.write(new File(directory, "request.json"), request.toString());
        TaskBroker.state(directory, request, "queued", "");
        TaskBroker.write(new File(directory, "ready"), "1");
        return directory;
    }
    private static void liveTaskControl(String action) throws Exception {
        TaskBroker.ROOT = Files.createTempDirectory("live-task-control-").toFile();
        NativeBridge.state.clear(); NativeBridge.requests.clear(); NativeBridge.phonePermissions.clear();
        try {
            CodexAppServer server = CodexAppServer.shared();
            File queued = publish("201", "Queued query", "readOnly");
            JSONObject read = liveInput("201", "Queued query", "read"), control = liveInput("201", "Queued query", action);
            JSONObject queuedState = live(read);
            check(!queuedState.getBoolean("terminal") && !queuedState.has("turnId") && !new File(queued, "cancel").exists(), "Reading queued work changed it");
            rejects("LIVE_TASK_MISMATCH", () -> live(new JSONObject(read.toString()).put("threadId", OTHER)));
            rejects("LIVE_TASK_MISMATCH", () -> live(new JSONObject(control.toString()).put("threadId", OTHER)));
            rejects("LIVE_TASK_MISMATCH", () -> live(new JSONObject(control.toString()).put("text", "Changed query")));
            NativeBridge.state.put(THREAD, new JSONObject().put("activeTurnId", "unrelated"));
            rejects("TURN_CHANGED", () -> live(control));
            check(!new File(queued, "cancel").exists(), "Live cancellation affected unrelated active work");
            NativeBridge.state.clear();
            server.call("test/goal", new JSONObject().put("goal", new JSONObject().put("status", "active")));
            rejects("LIVE_GOAL_ACTIVE", () -> live(control));
            check(!new File(queued, "cancel").exists(), "An authoritative active goal was changed");
            NativeBridge.Call outer = new NativeBridge.Call("taku/liveTask", control), nested = new NativeBridge.Call("taku/liveTask", read);
            boolean[] overlapped = {false};
            nested.result.whenComplete((value, failure) -> overlapped[0] = !outer.result.isDone());
            check(NativeBridge.enqueue(outer) && NativeBridge.enqueue(nested), "Live controls were not admitted");
            long drained = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!outer.result.isDone() || !nested.result.isDone()) {
                if (System.nanoTime() > drained) throw new AssertionError("Queued live controls did not finish");
                server.pump(10);
            }
            check(!overlapped[0], "An inbox call ran inside another live control's blocking goal read");
            rejects("LIVE_GOAL_ACTIVE", () -> {
                try { outer.result.get(); }
                catch (java.util.concurrent.ExecutionException failure) { throw (Exception) failure.getCause(); }
            });
            for (JSONObject unconfirmed : new JSONObject[]{new JSONObject(), new JSONObject().put("goal", new JSONObject()),
                    new JSONObject().put("goal", 7), new JSONObject().put("goal", new JSONObject().put("status", " ")),
                    new JSONObject().put("goal", new JSONObject().put("status", "unknown"))}) {
                server.call("test/goal-response", unconfirmed);
                rejects("LIVE_GOAL_UNCONFIRMED", () -> live(control));
                check(!new File(queued, "cancel").exists(), "An unconfirmed goal allowed cancellation");
            }
            server.call("test/goal", new JSONObject());
            for (String method : new String[]{"thread/goal/set", "thread/goal/clear"}) {
                NativeBridge.Call mutation = new NativeBridge.Call(method, new JSONObject().put("threadId", THREAD).put("status", "paused"));
                rejects("LIVE_GOAL_UNCONFIRMED", () -> live(control, mutation));
                server.call("test/observed", new JSONObject());
                check(mutation.result.isDone() && !new File(queued, "cancel").exists(), "An in-flight goal change allowed cancellation");
            }
            File unrestricted = publish("202", "Unrestricted query", "full");
            rejects("LIVE_TASK_NOT_READ_ONLY", () -> live(liveInput("202", "Unrestricted query", "replace")));
            if (action.equals("cancel")) {
                JSONObject cancelled = live(liveInput("202", "Unrestricted query", "cancel"));
                check(cancelled.getBoolean("cancellationRequested") && !cancelled.getBoolean("terminal"), "Explicit cancellation required read-only permission");
                CodexAppServer.run(unrestricted, new JSONObject(TaskBroker.read(new File(unrestricted, "request.json"))));
                check(live(liveInput("202", "Unrestricted query", "cancel")).getString("state").equals("cancelled"), "Repeated cancellation lost the terminal state");
            }
            JSONObject cancellation = live(control);
            check(cancellation.getBoolean("cancellationRequested") && !cancellation.getBoolean("terminal") && new File(queued, "cancel").isFile(), "Queued cancellation was reported as completion");
            check(live(control).getBoolean("cancellationRequested"), "Repeated queued cancellation lost its receipt");
            CodexAppServer.run(queued, new JSONObject(TaskBroker.read(new File(queued, "request.json"))));
            check(live(read).getBoolean("terminal") && live(read).getString("state").equals("cancelled"), "Queued cancellation never reached its actual terminal record");
            File startingTask = publish("204", "owned:starting", "readOnly");
            NativeBridge.Call startingControl = new NativeBridge.Call("taku/liveTask", liveInput("204", "owned:starting", action));
            HarnessContext.prepareHook = request -> {
                if (request.optString("id").equals("204")) check(NativeBridge.enqueue(startingControl), "Startup control was not admitted");
            };
            CodexAppServer.run(startingTask, new JSONObject(TaskBroker.read(new File(startingTask, "request.json"))));
            HarnessContext.prepareHook = null;
            rejects("LIVE_TASK_STARTING", () -> {
                try { startingControl.result.get(); }
                catch (java.util.concurrent.ExecutionException failure) { throw (Exception) failure.getCause(); }
            });
            check(!new File(startingTask, "cancel").exists() && TaskBroker.read(new File(startingTask, "done")).equals("completed"), "Unbound startup cancellation affected the running turn");
            File running = publish("203", "owned:live", action.equals("cancel") ? "full" : "readOnly");
            AgentViews.eventHook = event -> {
                if (!event.optString("method").equals("turn/started")) return;
                JSONObject params = event.getJSONObject("params");
                if (!params.getJSONObject("turn").getString("id").equals("live-turn")) return;
                JSONObject query = liveInput("203", "owned:live", "read"), stop = liveInput("203", "owned:live", action);
                check(live(query).getString("turnId").equals("live-turn"), "Live read lost the exact owning turn");
                NativeBridge.requests.put("foreign", new JSONObject().put("params", new JSONObject().put("threadId", THREAD).put("turnId", "other-turn")));
                check(!live(query).getBoolean("needsUser"), "An unrelated pending request leaked into the owned task");
                NativeBridge.requests.put("owned", new JSONObject().put("params", new JSONObject().put("threadId", THREAD).put("turnId", "live-turn")));
                check(live(query).getBoolean("needsUser"), "The owned pending request was omitted");
                NativeBridge.requests.clear();
                File info = new File(running, "runtime-info.json");
                String binding = TaskBroker.read(info);
                TaskBroker.write(info, new JSONObject(binding).put("turnId", "foreign-turn").toString());
                rejects("LIVE_TASK_MISMATCH", () -> live(query));
                rejects("LIVE_TASK_MISMATCH", () -> live(stop));
                TaskBroker.write(info, binding);
                NativeBridge.state.get(THREAD).put("activeTurnId", "unrelated");
                rejects("TURN_CHANGED", () -> live(stop));
                NativeBridge.state.get(THREAD).put("activeTurnId", "live-turn");
                NativeBridge.phonePermissions.put(THREAD, "full");
                rejects("LIVE_TASK_NOT_READ_ONLY", () -> live(liveInput("203", "owned:live", "replace")));
                if (action.equals("replace")) NativeBridge.phonePermissions.put(THREAD, "readOnly");
                NativeBridge.state.get(THREAD).put("goal", new JSONObject().put("status", "active"));
                rejects("LIVE_GOAL_ACTIVE", () -> live(stop));
                NativeBridge.state.get(THREAD).remove("goal");
                check(server.call("test/observed", new JSONObject()).getJSONArray("interruptions").length() == 0, "A rejected live cancellation interrupted work");
                JSONObject stopped = live(stop);
                check(stopped.getBoolean("cancellationRequested") && !stopped.getBoolean("terminal"), "Interruption receipt claimed terminal completion");
            };
            CodexAppServer.run(running, new JSONObject(TaskBroker.read(new File(running, "request.json"))));
            AgentViews.eventHook = null;
            JSONObject stopped = live(liveInput("203", "owned:live", "read"));
            check(stopped.getBoolean("terminal") && stopped.getString("state").equals("cancelled")
                    && stopped.getString("turnId").equals("live-turn") && !stopped.has("reply"), "Cancelled work exposed a completion reply");
            check(!new File(running, "cancel").exists(), "Running cancellation armed the ordinary queued-cancel path");
            JSONObject observed = server.call("test/observed", new JSONObject());
            JSONArray interruptions = observed.getJSONArray("interruptions");
            check(interruptions.length() == 1 && interruptions.getJSONObject(0).getString("threadId").equals(THREAD)
                    && interruptions.getJSONObject(0).getString("turnId").equals("live-turn") && observed.getInt("goalMutations") == 2,
                    "Live cancellation changed a goal or interrupted another turn");
            TaskBroker.state(running, new JSONObject().put("id", "203"), "completed", "");
            TaskBroker.write(new File(running, "done"), "completed");
            TaskBroker.write(new File(running, "result.txt"), "a".repeat(3999) + "\uD83D\uDE00" + "latest");
            JSONObject completed = live(liveInput("203", "owned:live", "read"));
            check(completed.getString("reply").equals("a".repeat(3999)) && completed.getBoolean("replyTruncated"), "Bounded final reply split a surrogate or leaked excess content");
            TaskBroker.write(new File(running, "done"), "cancelled");
            rejects("LIVE_TASK_MISMATCH", () -> live(liveInput("203", "owned:live", "read")));
            File finished = publish("205", "owned:completed", action.equals("cancel") ? "full" : "readOnly");
            CodexAppServer.run(finished, new JSONObject(TaskBroker.read(new File(finished, "request.json"))));
            JSONObject finishedControl = liveInput("205", "owned:completed", action);
            NativeBridge.state.put(THREAD, new JSONObject().put("activeTurnId", "new-unrelated-turn")
                    .put("goal", new JSONObject().put("status", "active")));
            JSONObject before = live(liveInput("205", "owned:completed", "read"));
            String current = NativeBridge.state.get(THREAD).toString();
            String terminal = TaskBroker.read(new File(finished, "done"));
            String reply = TaskBroker.read(new File(finished, "result.txt"));
            server.call("test/goal", new JSONObject().put("goal", new JSONObject().put("status", "active")));
            JSONObject completedControl = live(finishedControl);
            check(completedControl.similar(before) && completedControl.getBoolean("terminal")
                    && completedControl.getString("state").equals("completed") && !completedControl.getBoolean("cancellationRequested")
                    && completedControl.getString("reply").equals("Completed reply"), "Cancellation replaced a completed outcome");
            server.call("test/goal-response", new JSONObject());
            check(live(finishedControl).similar(before), "Repeated terminal cancellation depended on goal confirmation");
            check(!new File(finished, "cancel").exists() && TaskBroker.read(new File(finished, "done")).equals(terminal)
                    && TaskBroker.read(new File(finished, "result.txt")).equals(reply)
                    && NativeBridge.state.get(THREAD).toString().equals(current), "Terminal cancellation mutated task or conversation state");
            check(server.call("test/observed", new JSONObject()).similar(observed), "Terminal cancellation interrupted a turn or changed a goal");
        } finally {
            HarnessContext.prepareHook = null; AgentViews.eventHook = null;
            if (CodexAppServer.shared != null) CodexAppServer.shared.close(); CodexAppServer.shared = null;
            NativeBridge.inbox.clear(); NativeBridge.requests.clear(); NativeBridge.state.clear(); NativeBridge.phonePermissions.clear();
        }
    }
    /** agent_control calls naming a removed ACP conversation answer THREAD_UNAVAILABLE without starting Codex. */
    private static void removedAcpConversationControl() throws Exception {
        TaskBroker.ROOT=Files.createTempDirectory("removed-acp-control-").toFile();
        String removed="66666666-2222-4333-8444-555555555555";
        CodexAppServer previous=CodexAppServer.shared;CodexAppServer.shared=null;AcpConversations.REMOVED.add(removed);
        try{
            for(String method:new String[]{"thread/read","thread/name/set","harness/read","harness/refresh","skills/read"}){
                File directory=new File(new File(TaskBroker.ROOT,"tasks"),"control-"+method.replace('/','-'));directory.mkdirs();
                CodexAppServer.control(directory,new JSONObject().put("kind","agent_control").put("method",method)
                    .put("params",new JSONObject().put("threadId",removed).put("name","x")));
                JSONObject answer=new JSONObject(TaskBroker.read(new File(directory,"response.json")));
                check(!answer.getBoolean("ok")&&"THREAD_UNAVAILABLE".equals(answer.optString("code"))
                    &&"THREAD_UNAVAILABLE".equals(answer.optString("message")),"removed "+method+": THREAD_UNAVAILABLE "+answer);
                check(CodexAppServer.shared==null,"removed "+method+": Codex app server not started");
            }
        }finally{AcpConversations.REMOVED.clear();CodexAppServer.shared=previous;}
    }

    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("runtime")){runtime();return;}
        removedAcpConversationControl();
        ownership("success");ownership("conflict");ownership("reject");
        liveTaskControl("replace");
        liveTaskControl("cancel");
        System.out.println("CodexTaskOwnershipTest: "+checks+" ownership assertions passed");
    }
}

final class TaskBroker {
    static File ROOT=new File(System.getProperty("java.io.tmpdir"),"unused-task-broker");
    static String read(File file) throws IOException {return Files.readString(file.toPath());}
    static void write(File file,String value) throws IOException {
        file.getParentFile().mkdirs();File temporary=new File(file.getPath()+".tmp");
        Files.writeString(temporary.toPath(),value);
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static void state(File directory,JSONObject request,String state,String message) throws IOException {write(new File(directory,"state.json"),new JSONObject().put("id",request.getString("id")).put("state",state).toString());}
    static String command(String... command) {throw new AssertionError("Device commands are outside this host test");}
    static void controls() {}
}
