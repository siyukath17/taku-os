package org.agentos.lab;

import android.os.Process;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Device-local worker. UI never receives or reads the Codex login cache. */
public final class TaskBroker {
    static FileOutputStream guard;
    static java.nio.channels.FileLock guardLock;
    static final File ROOT = new File("/data/user/0/org.agentos.assistant/files/runtime"),
            TASKS = new File(ROOT, "tasks");

    static void retirePanelCapabilities() {
        File markers = new File(ROOT, "panel-tools");
        if (!markers.isDirectory()) return;
        try { SkillUninstall.deleteTree(markers.toPath()); }
        catch (Exception interrupted) { System.err.println("Retired Panel capability cleanup failed: " + interrupted.getMessage()); }
    }

    static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > 2097152) throw new IOException("record too large");
            }
            return out.toString("UTF-8");
        }
    }

    static void write(File f, String t) throws Exception {
        TaskRecords.write(f, t);
    }

    static void state(File dir, JSONObject r, String s, String m) throws Exception {
        TaskRecords.state(dir, r, s, m);
    }

    static final File RUNTIME_CONFIG = new File(ROOT, "runtime.json");

    /** The agent's own oauth-personal sign-in, opened in the phone's browser. Status only, never credentials. */
    static final AcpAuthHelper ACP_AUTH = new AcpAuthHelper(
            new File(ROOT, "acp-login.json"), AcpProcessTransport.process(),
            AcpAuthHelper.IntentLauncher.activityManager(), TimeUnit.MINUTES.toMillis(10));

    /** NativeBridge op {@code acpRuntime}: runtime.json read/update and the sign-in trigger. */
    static JSONObject acpRuntimeControl(JSONObject input) throws Exception {
        return AcpRuntimeSettings.control(RUNTIME_CONFIG, input, ACP_AUTH);
    }

    /** Codex unless runtime.json (or the request/env) positively selects a configured ACP agent. */
    static RuntimeRouter router() {
        return new RuntimeRouter(
                CodexTaskRuntime.INSTANCE,
                acpConfig -> new AcpRuntime(
                        AcpRuntime.Options.fromJson(acpConfig),
                        AcpProcessTransport.process(),
                        TaskBroker::notifyUser,
                        () -> {
                            // Keep native UI RPC and the shared Codex chat server serviced while
                            // the ACP turn owns this loop, as CodexAppServer.run does in its turn.
                            try {
                                CodexAppServer.tick();
                                controls();
                            } catch (Exception failure) {
                                System.err.println("acp idle: " + failure);
                            }
                        },
                        AcpConversations.HOST),
                // Same loader and bounds as the acpRuntime settings interface.
                () -> AcpRuntimeSettings.load(RUNTIME_CONFIG),
                System.getenv());
    }

    static String command(String... a) throws Exception {
        if (CleanupWork.active())
            return BoundedCommand.run(new File("/data/local/agentos/cleanup-output"), CleanupWork.timeout(), a);
        java.lang.Process p = new ProcessBuilder(a).redirectErrorStream(true).start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] x = new byte[4096];
            int n;
            while ((n = in.read(x)) > 0) b.write(x, 0, n);
        }
        int c = p.waitFor();
        if (c != 0) throw new IOException("command exit=" + c + ": " + b.toString("UTF-8"));
        return b.toString("UTF-8");
    }

    static void notifyUser(String id) {
        try {
            command(
                    "/system/bin/am",
                    "broadcast",
                    "-a",
                    "org.agentos.assistant.TASK_UPDATE",
                    "-n",
                    "org.agentos.assistant/.TaskNotificationReceiver",
                    "--es",
                    "task_id",
                    id);
        } catch (Exception e) {
            System.err.println("notification: " + e);
        }
    }

    static void recoverInterrupted() throws Exception {
        File[] directories = TASKS.listFiles();
        if (directories == null) return;
        for (File directory : directories) {
            if (!directory.isDirectory()
                    || !new File(directory, "ready").exists()
                    || new File(directory, "done").exists()) continue;
            try {
                JSONObject status = new JSONObject(read(new File(directory, "state.json")));
                if ("running".equals(status.optString("state"))) {
                    JSONObject request = new JSONObject(read(new File(directory, "request.json")));
                    state(directory, request, "failed", "The background service restarted without confirming this turn completed. Check any actions already taken before resubmitting.");
                    write(new File(directory, "done"), "failed");
                    notifyUser(request.getString("id"));
                }
            } catch (Exception invalidRecord) {
                quarantine(directory);
            }
        }
    }

    /** Preserve unreadable records in place; one interrupted write must not stop the queue. */
    static void quarantine(File directory) throws Exception {
        File status = new File(directory, "state.json");
        File preserved = new File(directory, "state.invalid.json");
        if (status.exists() && !preserved.exists() && !status.renameTo(preserved)) {
            throw new IOException("cannot preserve invalid state");
        }
        String message = "The local task record is damaged. It was preserved and this turn was stopped. Check the results before resubmitting.";
        write(
                new File(directory, "response.json"),
                new JSONObject().put("ok", false).put("message", message).toString());
        write(
                new File(directory, "state.json"),
                new JSONObject()
                        .put("id", directory.getName())
                        .put("state", "failed")
                        .put("prompt", "")
                        .put("message", message)
                        .toString());
        write(new File(directory, "done"), "invalid-record");
    }

    public static void main(String[] args) throws Exception {
        ROOT.mkdirs();
        TASKS.mkdirs();
        guard = new FileOutputStream(new File(ROOT, "broker.lock"));
        guardLock = guard.getChannel().tryLock();
        if (guardLock == null) return;
        command("/system/bin/chown", "system:system", ROOT.getParent());
        command("/system/bin/chown", "-R", "system:system", ROOT.getPath());
        command("/system/bin/restorecon", "-R", ROOT.getPath());
        write(new File(ROOT, "broker.pid"), Integer.toString(Process.myPid()));
        recoverInterrupted();
        retirePanelCapabilities();
        // ACP chat turns are part of their conversation's projection (thread/read, chat page).
        AgentViews.overlay = AcpConversations::overlay;
        AccountBridge.start();
        NativeBridge.start();
        try { PhoneMcpHost.start(); }
        catch (Exception unavailable) { System.err.println("phone mcp host unavailable: " + unavailable.getMessage()); }
        MagpieSettings.start();
        for (; ; ) {
            File[] dirs = TASKS.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparing(File::getName));
                for (File dir : dirs) {
                    if (!dir.isDirectory()
                            || !new File(dir, "ready").exists()
                            || new File(dir, "done").exists()) continue;
                    try {
                        run(dir);
                    } catch (Exception e) {
                        try {
                            JSONObject r = new JSONObject(read(new File(dir, "request.json")));
                            JSONObject diagnostic = new JSONObject().put("type", e.getClass().getName());
                            if (e.getStackTrace().length > 0)
                                diagnostic.put("site", e.getStackTrace()[0].toString());
                            String detail = e.getMessage() == null ? "" : e.getMessage();
                            if (detail.startsWith("Assistant connection") || detail.startsWith("command exit="))
                                diagnostic.put("operationError", detail.substring(0, Math.min(detail.length(), 400)));
                            write(new File(dir, "diagnostic.json"), diagnostic.toString());
                            state(dir, r, "failed", detail.startsWith("Assistant network connection failed") || detail.startsWith("The current model does not support images") || detail.startsWith("Cannot confirm the current model's image capability")
                                    ? detail : "Background execution failed. Check the results before retrying.");
                            CodexAppServer.writePanelCompletion(dir, r, "failed");
                            notifyUser(r.getString("id"));
                            write(new File(dir, "done"), "failed");
                        } catch (Exception invalidRecord) {
                            quarantine(dir);
                        }
                    }
                }
            }
            CodexAppServer.tick();
            Thread.sleep(150);
        }
    }

    static boolean handlingControls;

    static void controls() throws Exception {
        if (handlingControls) return;
        handlingControls = true;
        try {
            File[] directories = TASKS.listFiles();
            if (directories == null) return;
            for (File directory : directories) {
                if (!directory.isDirectory()
                        || !new File(directory, "ready").exists()
                        || new File(directory, "done").exists()) continue;
                JSONObject request;
                try {
                    request = new JSONObject(read(new File(directory, "request.json")));
                } catch (Exception invalidRecord) {
                    quarantine(directory);
                    continue;
                }
                if ("agent_control".equals(request.optString("kind")))
                    CodexAppServer.control(directory, request);
            }
        } finally {
            handlingControls = false;
        }
    }

    static void run(File dir) throws Exception {
        JSONObject r = new JSONObject(read(new File(dir, "request.json")));
        if ("agent_control".equals(r.optString("kind"))) {
            CodexAppServer.control(dir, r);
            return;
        }
        if (new File(dir, "cancel").exists()) {
            state(dir, r, "cancelled", "Cancelled before execution.");
            CodexAppServer.writePanelCompletion(dir, r, "cancelled");
            write(new File(dir, "done"), "cancelled");
            notifyUser(r.getString("id"));
            return;
        }
        state(dir, r, "running", "Preparing the independent execution screen…");
        notifyUser(r.getString("id"));
        Thread.sleep(700);
        File contextImage = new File(dir, "context.png");
        if (!r.has("conversationId")) {
            command("/system/bin/screencap", "-p", contextImage.getPath());
            r.put("screenshot", contextImage.getPath());
        }
        command("/system_ext/bin/phone-display", "start");
        String id = command("/system_ext/bin/phone-display", "id").trim();
        if (!id.matches("[1-9][0-9]*")) throw new IOException("invalid virtual display ID");
        write(new File(dir, "display.id"), id);
        String primary = command("/system/bin/dumpsys", "activity", "activities");
        write(new File(dir, "activity-before.txt"), primary);
        r.put("displayId",id);
        RuntimeRouter router = router();
        RuntimeRouter.Route route = router.route(r);
        write(new File(dir, "runtime-route.json"), route.json().toString());
        router.select(route).run(dir, r);
        notifyUser(r.getString("id"));
    }
}
