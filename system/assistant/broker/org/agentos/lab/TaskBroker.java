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
    /** Cuts the loop's pause short when a task is published (see {@link TaskWake}). */
    static final TaskWake TASK_WAKE = new TaskWake(TASKS);
    /** Kept referenced: a collected FileObserver stops watching. */
    static android.os.FileObserver taskObserver;
    /**
     * Task directories already closed with {@code done} (a mark never removed): skipped without a stat,
     * so a pass over ~800 records costs one listing instead of ~10ms of stats every 150ms.
     */
    static final Set<String> CLOSED = ConcurrentHashMap.newKeySet();

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
    /** The ACP agent's model list as last reported (see AcpModelCatalog): the picker's source while on ACP. */
    static final File ACP_MODELS = new File(ROOT, "acp-models.json");

    static AcpRuntime.Options acpOptions(JSONObject acpConfig) throws Exception {
        AcpRuntime.Options options = AcpRuntime.Options.fromJson(acpConfig);
        options.modelCatalog = ACP_MODELS;
        return options;
    }

    /** The agent's own oauth-personal sign-in, opened in the phone's browser. Status only, never credentials. */
    static final AcpAuthHelper ACP_AUTH = new AcpAuthHelper(
            new File(ROOT, "acp-login.json"), AcpProcessTransport.process(),
            AcpAuthHelper.IntentLauncher.activityManager(), TimeUnit.MINUTES.toMillis(10),
            new AcpAuthHelper.Windows() {
                public Map<Integer, String> list() throws Exception {
                    Map<Integer, String> main = new LinkedHashMap<>();
                    for (PhoneTasks.Task t : PhoneTasks.DEVICE.list())
                        if (t.displayId() == DedicatedScreens.MAIN_DISPLAY) main.put(t.id(), t.packageName());
                    return main;
                }

                public void remove(int id) throws Exception {
                    PhoneTasks.DEVICE.remove(id);
                }
            });

    static final File ACP_LOGIN = new File(ROOT, "acp-login.json");

    /**
     * The warm {@code agy acp} process shared by ACP turns (see AcpAgentPool). Its stamp is the broker
     * state a warm process depends on: a changed runtime.json or sign-in status retires it.
     */
    static final AcpAgentPool ACP_POOL = new AcpAgentPool(AcpProcessTransport.process(),
            () -> stamp(RUNTIME_CONFIG) + "|" + stamp(ACP_LOGIN));

    private static String stamp(File f) {
        return f.length() + ":" + f.lastModified();
    }

    /**
     * Starts the warm ACP process in the background when new conversations route to ACP and the agent
     * is signed in (broker start, {@code thread/start}). Never blocks the caller and never throws.
     */
    static void prewarmAcp(String reason) {
        Thread worker = new Thread(() -> prewarmAcpNow(reason), "acp-prewarm-trigger");
        worker.setDaemon(true);
        worker.start();
    }

    /** {@link #prewarmAcp}'s body on the caller's thread (already a background one); never throws. */
    static void prewarmAcpNow(String reason) {
        try {
            if (!"signed_in".equals(new JSONObject(read(ACP_LOGIN)).optString("state"))) {
                System.err.println("acp prewarm (" + reason + ") skipped: not-signed-in");
                return;
            }
            RuntimeRouter.Route route = router().newConversation();
            if (!RuntimeRouter.ACP.equals(route.runtime) || route.acpConfig == null) {
                System.err.println("acp prewarm (" + reason + ") skipped: default=" + route.runtime);
                return;
            }
            new AcpRuntime(acpOptions(route.acpConfig), AcpProcessTransport.process(), null, null, null, ACP_POOL)
                    .prewarm(reason);
        } catch (Throwable unavailable) {
            // A background helper must never end the broker (an uncaught throwable kills app_process).
            System.err.println("acp prewarm (" + reason + ") skipped: " + unavailable);
        }
    }

    /** Opening an existing ACP conversation warms the agent (see {@link AcpReadWarmup}). */
    static final AcpReadWarmup ACP_READ_WARMUP = new AcpReadWarmup(ACP_POOL::empty, TaskBroker::prewarmAcpNow,
            () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));

    /** NativeBridge op {@code acpRuntime}: runtime.json read/update and the sign-in trigger. */
    static JSONObject acpRuntimeControl(JSONObject input) throws Exception {
        return AcpRuntimeSettings.control(RUNTIME_CONFIG, input, ACP_AUTH);
    }

    /**
     * Keep native UI RPC and the shared Codex chat server serviced while an ACP or remote T3 turn
     * owns this loop, as CodexAppServer.run does in its turn.
     */
    static void serviceWhileTurnRuns(String lane) {
        try {
            CodexAppServer.tick();
            controls();
        } catch (Exception failure) {
            System.err.println(lane + " idle: " + failure);
        }
    }

    /**
     * Codex unless runtime.json (or the request/env) positively selects a configured ACP agent or
     * remote T3 host. The remote token is resolved per task from the broker environment or a
     * root-owned file named in runtime.json; it is never stored there.
     */
    static RuntimeRouter router() {
        return new RuntimeRouter(
                CodexTaskRuntime.INSTANCE,
                acpConfig -> new AcpRuntime(
                        acpOptions(acpConfig),
                        AcpProcessTransport.process(),
                        TaskBroker::notifyUser,
                        () -> serviceWhileTurnRuns("acp"),
                        AcpConversations.HOST,
                        ACP_POOL),
                remoteConfig -> {
                    RemoteT3Runtime.Options options = RemoteT3Runtime.Options.fromJson(remoteConfig);
                    return new RemoteT3Runtime(
                            options,
                            RemoteT3Transport.http(),
                            RemoteT3Runtime.tokenSource(options, System.getenv()),
                            TaskBroker::notifyUser,
                            () -> serviceWhileTurnRuns("remote_t3"),
                            RemoteT3Runtime.Clock.SYSTEM);
                },
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

    /** Task update broadcasts, sent in order off the task loop (see CoalescingNotifier). */
    static final CoalescingNotifier NOTIFIER = new CoalescingNotifier(TaskBroker::broadcastUpdate);

    static void notifyUser(String id) {
        NOTIFIER.notify(id);
    }

    /** One {@code am broadcast}; its own process, never the cleanup-bounded {@link #command}. */
    private static void broadcastUpdate(String id) {
        // No ProcessBuilder.Redirect.DISCARD: ART's core-oj lacks it (NoSuchFieldError on the Pixel 9).
        try {
            java.lang.Process p = new ProcessBuilder("/system/bin/am", "broadcast", "-a", "org.agentos.assistant.TASK_UPDATE",
                    "-n", "org.agentos.assistant/.TaskNotificationReceiver", "--es", "task_id", id)
                    .redirectErrorStream(true).start();
            try (InputStream out = p.getInputStream()) {
                byte[] sink = new byte[1024];
                while (out.read(sink) > 0) { /* discarded */ }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.err.println("notification: am broadcast timed out");
            } else if (p.exitValue() != 0) System.err.println("notification: am exit=" + p.exitValue());
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
                safeQuarantine(directory);
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
        // init runs the broker with stderr on /dev/null: keep its diagnostics in a size-capped file.
        BrokerLog.install("broker start pid " + Process.myPid());
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
        // ACP turns open and close their phone turn explicitly (gestures, background screen).
        AcpPhoneTurns.hooks = PhoneMcpHost.TURN_HOOKS;
        try { PhoneMcpHost.start(); }
        catch (Exception unavailable) { System.err.println("phone mcp host unavailable: " + unavailable.getMessage()); }
        MagpieSettings.start();
        // Once the broker settled: warm the ACP agent so the first turn skips agy's backend boot.
        Thread warmup = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException stopped) { return; }
            prewarmAcp("startup");
        }, "acp-prewarm-start");
        warmup.setDaemon(true);
        warmup.start();
        try {
            taskObserver = new android.os.FileObserver(TASKS, android.os.FileObserver.CREATE | android.os.FileObserver.MOVED_TO) {
                @Override public void onEvent(int event, String path) { TASK_WAKE.created(path); }
            };
            taskObserver.startWatching();
        } catch (Exception | LinkageError unavailable) {
            System.err.println("task watch unavailable: " + unavailable);
        }
        for (; ; ) {
            // Nothing a task, a damaged record or a runtime does may end this loop: it is the broker.
            try {
                serviceTasks();
            } catch (Exception | LinkageError unexpected) {
                System.err.println("broker loop: " + unexpected);
            }
            // tick() never throws: a Codex app server exit is logged and retired, not fatal here.
            CodexAppServer.tick();
            TASK_WAKE.await(150);
        }
    }

    /** A published task not closed yet; a closed one is remembered in {@link #CLOSED} and never stat'ed again. */
    static boolean open(File dir) {
        String name = dir.getName();
        if (CLOSED.contains(name)) return false;
        if (new File(dir, "done").exists()) {
            CLOSED.add(name);
            return false;
        }
        return dir.isDirectory() && new File(dir, "ready").exists();
    }

    /** One pass over ready tasks; each task's failure is recorded on that task alone. */
    static void serviceTasks() {
            File[] dirs = TASKS.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparing(File::getName));
                Set<String> present = new HashSet<>();
                for (File dir : dirs) present.add(dir.getName());
                CLOSED.retainAll(present);
                for (File dir : dirs) {
                    if (!open(dir)) continue;
                    try {
                        run(dir);
                    } catch (Exception | LinkageError e) {
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
                            safeQuarantine(dir);
                        }
                    }
                }
            }
    }

    /**
     * {@link #quarantine} that never throws: when even preserving the record fails, the task is
     * still closed with a {@code done} marker so the loop does not retry it forever.
     */
    static void safeQuarantine(File directory) {
        try {
            quarantine(directory);
        } catch (Exception failure) {
            System.err.println("quarantine " + directory.getName() + ": " + failure);
            try {
                write(new File(directory, "done"), "failed");
            } catch (Exception unwritable) {
                System.err.println("quarantine " + directory.getName() + " unclosable: " + unwritable);
            }
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
                if (!open(directory)) continue;
                JSONObject request;
                try {
                    request = new JSONObject(read(new File(directory, "request.json")));
                } catch (Exception invalidRecord) {
                    safeQuarantine(directory);
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
        if (AcpConversations.removed(r.optString("conversationId", ""))) {
            // A removed ACP conversation never runs again (Continue, a queued turn): no display, no
            // runtime, and above all no Codex app server start for a thread it never had.
            state(dir, r, "failed", "THREAD_UNAVAILABLE");
            write(new File(dir, "done"), "failed");
            notifyUser(r.getString("id"));
            return;
        }
        // The route first: an ACP turn uses none of the Codex execution display (its phone tools open
        // dedicated screens on demand), so it skips the display preparation entirely.
        RuntimeRouter router = router();
        RuntimeRouter.Route route = router.route(r);
        boolean display = RuntimeRouter.preparesExecutionDisplay(route);
        state(dir, r, "running", display ? "Preparing the independent execution screen…" : "Starting the on-device agent…");
        notifyUser(r.getString("id"));
        if (display || RuntimeRouter.capturesContext(r)) {
            // Lets the assistant surface leave the screen before the context screenshot.
            Thread.sleep(700);
        }
        File contextImage = new File(dir, "context.png");
        if (RuntimeRouter.capturesContext(r)) {
            command("/system/bin/screencap", "-p", contextImage.getPath());
            r.put("screenshot", contextImage.getPath());
        }
        if (display) {
            command("/system_ext/bin/phone-display", "start");
            String id = command("/system_ext/bin/phone-display", "id").trim();
            if (!id.matches("[1-9][0-9]*")) throw new IOException("invalid virtual display ID");
            write(new File(dir, "display.id"), id);
            String primary = command("/system/bin/dumpsys", "activity", "activities");
            write(new File(dir, "activity-before.txt"), primary);
            r.put("displayId", id);
        }
        write(new File(dir, "runtime-route.json"), route.json().toString());
        // "Continue" after a failed turn carries no user text. ACP resumes its agent session; other
        // runtimes get the continuation instruction as the turn's text.
        if (r.optJSONObject("continuation") != null && r.optString("prompt").isBlank() && !RuntimeRouter.ACP.equals(route.runtime))
            r.put("prompt", AcpRuntime.CONTINUE_PROMPT);
        router.select(route).run(dir, r);
        notifyUser(r.getString("id"));
    }
}
