package org.agentos.lab;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Version-pinned stdio client; owned by TaskBroker, never a second scheduler. */
public final class CodexAppServer implements AutoCloseable {
    private static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;
    private static final Set<String> LIVE_TASK_FIELDS = Set.of("op", "action", "threadId", "taskId", "requestId", "text");
    private static final Set<String> LIVE_TASK_STATES = Set.of("queued", "running", "completed", "failed", "needs_user", "cancelled");
    private static final Set<String> LIVE_TERMINAL_STATES = Set.of("completed", "failed", "needs_user", "cancelled");
    private static final Set<String> LIVE_GOAL_STATES = Set.of("active", "paused", "blocked", "usageLimited", "budgetLimited", "complete");
    private static final java.util.regex.Pattern LIVE_UUID = java.util.regex.Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final java.util.regex.Pattern LIVE_TASK_ID = java.util.regex.Pattern.compile("[0-9]{1,20}");
    interface Events {
        void accept(JSONObject event) throws Exception;
    }

    private final java.lang.Process process;
    private final TurnProcesses processes;
    private final BufferedWriter input;
    private final BlockingQueue<String> output = new LinkedBlockingQueue<>(256);
    private final Map<Integer, JSONObject> responses = new HashMap<>();
    private final Set<Integer> legacyPending = new HashSet<>();
    private final Events events;
    private final Thread reader;
    private volatile String readerFailure = "";
    private final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, JSONObject> freshThreads = new HashMap<>();
    private static final class MissingThread extends IOException {}
    private volatile boolean closed;
    private final boolean magpie;
    private String defaultModel;
    private final Map<String, String> threadModels = new HashMap<>();
    private static final String EOF = "\u0000";
    private static final long TASK_EXECUTION_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(15);
    private TaskStart taskStart;

    private static final class TaskStart {
        final int requestId;
        final File directory;
        final String taskId, threadId;
        final ArrayDeque<JSONObject> deferred = new ArrayDeque<>();
        TaskStart(int requestId, File directory, String taskId, String threadId) {
            this.requestId = requestId;
            this.directory = directory;
            this.taskId = taskId;
            this.threadId = threadId;
        }
        String bind(JSONObject result) throws Exception {
            String turn = result.getJSONObject("turn").getString("id");
            if (turn.isEmpty()) throw new IOException("SOURCE_CHANGED");
            File file = new File(directory, "runtime-info.json");
            JSONObject value = file.isFile() ? new JSONObject(TaskBroker.read(file)) : new JSONObject();
            if (value.has("taskId") && !taskId.equals(value.getString("taskId"))
                    || value.has("threadId") && !threadId.equals(value.getString("threadId"))
                    || value.has("turnId") && !turn.equals(value.getString("turnId"))) throw new IOException("SOURCE_CHANGED");
            TaskBroker.write(file, value.put("taskId", taskId).put("threadId", threadId).put("turnId", turn).toString());
            return turn;
        }
    }

    private static final class LiveTask {
        final File directory;
        final JSONObject request;
        final String id, thread, state, turn;
        final boolean terminal;

        LiveTask(JSONObject input) throws Exception {
            id = input.getString("taskId");
            thread = input.getString("threadId");
            directory = new File(new File(TaskBroker.ROOT, "tasks"), id);
            if (!new File(directory, "ready").isFile()) throw new IOException("LIVE_TASK_UNCONFIRMED");
            request = record("request.json");
            if (!id.equals(request.optString("id")) || !thread.equals(request.optString("conversationId"))
                    || !input.getString("text").equals(request.optString("prompt"))
                    || request.has("kind") || request.has("panelUpdate") || request.has("composerRequestId"))
                throw new IOException("LIVE_TASK_MISMATCH");
            JSONObject status = record("state.json");
            state = status.optString("state");
            if (!id.equals(status.optString("id")) || !LIVE_TASK_STATES.contains(state))
                throw new IOException("LIVE_TASK_MISMATCH");
            terminal = new File(directory, "done").isFile();
            if (terminal && (!LIVE_TERMINAL_STATES.contains(state)
                    || !state.equals(TaskBroker.read(new File(directory, "done")).trim())))
                throw new IOException("LIVE_TASK_MISMATCH");
            if (new File(directory, "runtime-info.json").isFile()) {
                JSONObject runtime = record("runtime-info.json");
                Object boundTurn = runtime.opt("turnId");
                if (!id.equals(runtime.optString("taskId")) || !thread.equals(runtime.optString("threadId"))
                        || runtime.has("conversationId") && !thread.equals(runtime.optString("conversationId"))
                        || !(boundTurn instanceof String) || ((String) boundTurn).isBlank() || ((String) boundTurn).length() > 160)
                    throw new IOException("LIVE_TASK_MISMATCH");
                turn = (String) boundTurn;
            } else {
                if (state.equals("completed")) throw new IOException("LIVE_TASK_UNCONFIRMED");
                turn = "";
            }
        }

        private JSONObject record(String name) throws Exception {
            File file = new File(directory, name);
            if (!file.isFile()) throw new IOException("LIVE_TASK_UNCONFIRMED");
            return new JSONObject(TaskBroker.read(file));
        }

        JSONObject snapshot(JSONObject current) throws Exception {
            boolean needsUser = state.equals("needs_user");
            if (!terminal && !turn.isEmpty()) for (JSONObject pending : NativeBridge.requests.values()) {
                JSONObject params = pending.optJSONObject("params");
                if (params != null && thread.equals(params.optString("threadId")) && turn.equals(params.optString("turnId")))
                    needsUser = true;
            }
            JSONObject result = new JSONObject().put("taskId", id).put("state", state).put("terminal", terminal)
                    .put("needsUser", needsUser).put("cancellationRequested", new File(directory, "cancel").isFile()
                            || !turn.isEmpty() && current != null && turn.equals(current.optString("phoneStoppedTurnId")));
            if (!turn.isEmpty()) result.put("turnId", turn);
            if (current != null && current.has("goal")) result.put("goalActive", goalActive(current));
            File reply = new File(directory, "result.txt");
            if (terminal && state.equals("completed") && reply.isFile()) {
                char[] text = new char[4001];
                int length = 0;
                try (Reader reader = new InputStreamReader(new FileInputStream(reply), StandardCharsets.UTF_8)) {
                    while (length < text.length) {
                        int count = reader.read(text, length, text.length - length);
                        if (count < 0) break;
                        length += count;
                    }
                }
                int visible = Math.min(length, 4000);
                if (visible > 0 && Character.isHighSurrogate(text[visible - 1])) visible--;
                result.put("reply", new String(text, 0, visible)).put("replyTruncated", length > visible);
            }
            return result;
        }
    }

    private static boolean goalActive(JSONObject state) {
        JSONObject goal = state.optJSONObject("goal");
        return goal != null && goal.optString("status").equals("active");
    }

    static JSONObject liveTask(JSONObject input) throws Exception {
        if (input.length() != LIVE_TASK_FIELDS.size()) throw new IOException("INVALID_ARGUMENT");
        for (String field : LIVE_TASK_FIELDS) if (!(input.opt(field) instanceof String)) throw new IOException("INVALID_ARGUMENT");
        if (!LIVE_UUID.matcher(input.getString("threadId")).matches() || !LIVE_UUID.matcher(input.getString("requestId")).matches()
                || !LIVE_TASK_ID.matcher(input.getString("taskId")).matches()
                || input.getString("text").isBlank() || input.getString("text").length() > 16000
                || !input.getString("action").equals("read") && !input.getString("action").equals("replace")
                    && !input.getString("action").equals("cancel")) throw new IOException("INVALID_ARGUMENT");
        boolean replace = input.getString("action").equals("replace");
        boolean cancel = !input.getString("action").equals("read");
        LiveTask task = new LiveTask(input);
        if (replace && !task.request.optString("permissionMode").equals("readOnly")) throw new IOException("LIVE_TASK_NOT_READ_ONLY");
        CodexAppServer server = shared;
        JSONObject goal = null;
        if (cancel && !task.terminal) {
            if (server == null || server.closed) throw new IOException("RUNTIME_UNAVAILABLE");
            goal = server.call("thread/goal/get", new JSONObject().put("threadId", task.thread));
            task = new LiveTask(input);
        }
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject current = NativeBridge.state.get(task.thread);
            if (currentRequest != null && task.id.equals(currentRequest.optString("id"))
                    && (!task.thread.equals(currentRequest.optString("conversationId"))
                        || !activeThread.isEmpty() && !task.thread.equals(activeThread)
                        || !activeTurn.isEmpty() && !task.turn.equals(activeTurn))) throw new IOException("LIVE_TASK_MISMATCH");
            if (replace && !task.request.optString("permissionMode").equals("readOnly")) throw new IOException("LIVE_TASK_NOT_READ_ONLY");
            JSONObject result = task.snapshot(current);
            if (!cancel || task.terminal || !task.state.equals("queued") && !task.state.equals("running")) return result;
            if (server != shared || server.closed) throw new IOException("RUNTIME_UNAVAILABLE");
            JSONObject detail = goal.optJSONObject("goal");
            if (!goal.has("goal") || !goal.isNull("goal") && (detail == null
                    || !(detail.opt("status") instanceof String) || !LIVE_GOAL_STATES.contains(detail.getString("status"))))
                throw new IOException("LIVE_GOAL_UNCONFIRMED");
            if (goalActive(goal) || current != null && goalActive(current)) throw new IOException("LIVE_GOAL_ACTIVE");
            for (NativeBridge.Call pending : NativeBridge.calls.values()) requireNoGoalMutation(pending, task.thread);
            for (NativeBridge.Call pending : NativeBridge.inbox) requireNoGoalMutation(pending, task.thread);
            result.put("goalActive", false);
            if (task.turn.isEmpty()) {
                if (current != null && !current.optString("activeTurnId").isEmpty()) throw new IOException("TURN_CHANGED");
                if (server.taskStart != null && task.thread.equals(server.taskStart.threadId)) throw new IOException("LIVE_TASK_STARTING");
                TaskBroker.write(new File(task.directory, "cancel"), "1");
            } else {
                if (currentRequest == null || !task.id.equals(currentRequest.optString("id"))
                        || !task.thread.equals(activeThread) || !task.turn.equals(activeTurn)
                        || current == null || !task.turn.equals(current.optString("activeTurnId"))) throw new IOException("TURN_CHANGED");
                if (replace && !"readOnly".equals(NativeBridge.phonePermissions.get(task.thread))) throw new IOException("LIVE_TASK_NOT_READ_ONLY");
                // This check and exact-turn send run on the task pump without another event dispatch.
                server.interrupt(task.thread, task.turn);
            }
            return result.put("cancellationRequested", true);
        }
    }

    private static void requireNoGoalMutation(NativeBridge.Call pending, String thread) throws IOException {
        if ((pending.method.equals("thread/goal/set") || pending.method.equals("thread/goal/clear"))
                && pending.params != null && thread.equals(pending.params.optString("threadId")))
            throw new IOException("LIVE_GOAL_UNCONFIRMED");
    }

    CodexAppServer(File diagnostics, Events events) throws Exception {
        this.events = events;
        String instance = UUID.randomUUID().toString();
        magpie = ModelConnection.local();
        ProcessBuilder builder = new ProcessBuilder(ModelConnection.command())
                .redirectError(new File("/dev/null"));
        if (magpie) builder.environment().put("TAKU_MAGPIE_KEY", "taku-android");
        builder.environment().put(TurnProcesses.OWNER_ENV, instance);
        process = builder.start();
        try { processes = new TurnProcesses(instance); }
        catch (Exception unavailable) { process.destroyForcibly(); throw unavailable; }
        input =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader =
                new Thread(
                        () -> {
                            try (BufferedReader stream =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while (!closed && (line = readBoundedLine(stream)) != null)
                                    output.put(line);
                            } catch (Exception failure) {
                                if (!closed) readerFailure = failure.getMessage() == null
                                        ? "Failed to read assistant output" : failure.getMessage();
                            } finally {
                                if (!closed)
                                    try {
                                        output.put(EOF);
                                    } catch (InterruptedException ignored) {
                                    }
                            }
                        },
                        "codex-stdio-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static String readBoundedLine(BufferedReader stream) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = stream.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value == '\r') continue;
            if (line.length() >= MAX_MESSAGE_CHARS)
                throw new IOException("A single assistant message exceeds the safety limit of 8 Mi characters");
            line.append((char) value);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private synchronized void send(JSONObject value) throws Exception {
        input.write(value.toString());
        input.newLine();
        input.flush();
    }

    private int request(String method, JSONObject params) throws Exception {
        WorkspaceAdmissions.Ticket admission = NativeBridge.admissions.queue(method, params == null ? "" : params.optString("threadId"));
        try {
            prepareExecution(method, params);
            int id = nextId.incrementAndGet();
            NativeBridge.admissions.beforeSend(id, admission);
            send(new JSONObject().put("id", id).put("method", method).put("params", params));
            return id;
        } catch (Exception error) {
            NativeBridge.admissions.failedBeforeSend(admission);
            throw error;
        }
    }

    void nativeRequest(NativeBridge.Call call) throws Exception {
        if (call.cleanup) {
            // Nonblocking inbox admission already happened on the removal worker. No execution
            // admission or observation callback is needed for its state queries and mutations.
            if (call.result.isDone()) return;
            int id = nextId.incrementAndGet();
            NativeBridge.calls.put(id, call);
            if (call.result.isDone()) { NativeBridge.calls.remove(id, call); return; }
            JSONObject request = new JSONObject().put("id", id).put("method", call.method);
            if (call.params != null) request.put("params", call.params);
            call.nativeSent = true;
            send(request);
            return;
        }
        NativeBridge.admissions.requireCurrent(call.admission);
        if (call.method.equals("taku/workspacePreflight")) {
            call.result.complete(workspacePreflight(call.params));
            return;
        }
        // Native per-screen threads bypass the legacy TaskBroker.run path. Give
        // them the same actual device/app context before the model plans a turn.
        // Memory organizer threads are not agent conversations: they get only the organizer's own
        // instructions and evidence, never the agent background (device, skills, board, card).
        if ((call.method.equals("turn/start") || call.method.equals("thread/realtime/start")) && call.params != null && !MemoryGen.owns(call.params.optString("threadId"))) {
            String thread = call.params.getString("threadId");
            AgentSkills.refresh(this, false);
            HarnessContext.prepare(this, thread, new JSONObject().put("conversationId", thread)
                    .put("includeScreenshot", false).put("replyMode", call.method.equals("turn/start") && !call.params.has("outputSchema") ? "primary" : "disabled"));
        }
        if (call.params != null && (call.method.equals("turn/start") || call.method.equals("thread/realtime/start")) && MemoryGen.owns(call.params.optString("threadId")))
            AgentViews.prepareReply(call.params.getString("threadId"), null, false);
        NativeBridge.admissions.requireCurrent(call.admission);
        prepareExecution(call.method, call.params);
        if (call.workspaceGuard != null) call.workspaceGuard.check();
        int id = nextId.incrementAndGet();
        NativeBridge.admissions.beforeSend(id, call.admission);
        NativeBridge.calls.put(id, call);
        JSONObject request = new JSONObject().put("id", id).put("method", call.method);
        if (call.params != null) request.put("params", call.params);
        call.nativeSent = true;
        send(request);
    }

    /** Readiness of this native thread's selected model; no inference or ownership change. */
    private JSONObject workspacePreflight(JSONObject params) throws Exception {
        SystemAi.requireEnabled();
        if (!signedIn()) throw new IOException("WORKSPACE_MODEL_UNAVAILABLE");
        String thread = params.getString("threadId");
        String selected = threadModels.getOrDefault(thread,
                magpie ? ModelConnection.settings().optString("model") : defaultModel);
        JSONArray models = call("model/list", new JSONObject().put("limit", 100)).getJSONArray("data");
        JSONObject chosen = null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (selected != null && !selected.isEmpty() && selected.equals(model.optString("model"))) chosen = model;
            else if ((selected == null || selected.isEmpty()) && model.optBoolean("isDefault")) chosen = model;
        }
        if (chosen == null) throw new IOException("WORKSPACE_MODEL_UNAVAILABLE");
        if (params.optBoolean("image")) {
            JSONArray modalities = chosen.optJSONArray("inputModalities");
            boolean image = false;
            if (modalities != null) for (int i = 0; i < modalities.length(); i++) image |= "image".equals(modalities.optString(i));
            if (!image) throw new IOException("WORKSPACE_IMAGE_MODEL_REQUIRED");
        }
        return new JSONObject().put("model", chosen.getString("model"))
                .put("provider", magpie ? "magpie" : "openai")
                .put("systemRevision", SystemAi.config().getLong("revision"));
    }

    private void prepareExecution(String method, JSONObject params) throws Exception {
        if (WorkspaceAdmissions.execution(method) && params != null
                && SessionRemovalRuntime.pending(params.optString("threadId"))) throw new IOException("SCREEN_SESSION_REMOVING");
        if (method.equals("turn/start") || method.equals("turn/steer") || method.equals("thread/realtime/start")) {
            SystemAi.requireEnabled();
            if (params != null && !DedicatedScreensOnDevice.shared().agentAdmitted(params.optString("threadId")))
                throw new IOException("SCREEN_AGENT_PAUSED");
        }
        if (params == null) return;
        if (method.equals("turn/steer")) validateImages(params);
        if (method.equals("thread/start") || method.equals("thread/resume") || method.equals("thread/fork")) {
            params.put("modelProvider", magpie ? "magpie" : "openai");
            if (!params.has("model")) {
                if (magpie) defaultModel = ModelConnection.settings().getString("model");
                else if (defaultModel == null) {
                        JSONArray models = call("model/list", new JSONObject().put("limit", 100)).getJSONArray("data");
                        for (int i = 0; i < models.length(); i++)
                            if (models.getJSONObject(i).optBoolean("isDefault"))
                                defaultModel = models.getJSONObject(i).getString("model");
                }
                if (defaultModel != null) params.put("model", defaultModel);
            }
        }
        if (method.equals("turn/start")) {
            validateImages(params);
            processes.starting(params.getString("threadId"));
            JSONObject sandbox=params.optJSONObject("sandboxPolicy");
            if(sandbox!=null)NativeBridge.setPhonePermission(params.getString("threadId"),sandbox.optString("type").equals("readOnly")?"readOnly":sandbox.optString("type").equals("workspaceWrite")?"ask":"full");
        }
        if (method.equals("turn/interrupt")) {
            NativeBridge.stopPhoneTurn(params.getString("threadId"),params.getString("turnId"));
            PhoneGestures.endTurn(params.getString("threadId"),params.getString("turnId"));
            processes.interrupting(params.getString("threadId"), params.getString("turnId"));
        }
    }

    private void validateImages(JSONObject params) throws Exception {
        if (!magpie) return;
        JSONArray input = params.optJSONArray("input");
        boolean image = false;
        if (input != null) for (int i = 0; i < input.length(); i++) {
            JSONObject part = input.optJSONObject(i);
            if (part != null) image |= part.optString("type").equals("image") || part.optString("type").equals("localImage");
        }
        if (!image) return;
        String selected = params.optString("model", threadModels.getOrDefault(params.optString("threadId"), defaultModel));
        JSONArray models = call("model/list", new JSONObject().put("limit", 100)).getJSONArray("data");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (!model.optString("model").equals(selected)) continue;
            JSONArray modalities = model.optJSONArray("inputModalities");
            if (modalities != null) for (int j = 0; j < modalities.length(); j++)
                if (modalities.optString(j).equals("image")) return;
            throw new IOException("The current model does not support images. Select a model that supports images, or remove images and screenshots before sending.");
        }
        throw new IOException("Cannot confirm the current model's image capability. Refresh the model catalog and retry.");
    }

    void nativeTurnStarted(String thread) { freshThreads.remove(thread); }

    void rememberNativeThread(JSONObject result) throws Exception {
        if (result.has("thread")) {
            String id = result.getJSONObject("thread").getString("id");
            freshThreads.put(id, result);
        }
    }

    void rememberModel(String method, JSONObject params, JSONObject result) throws Exception {
        if (result.has("thread") && result.has("model"))
            threadModels.put(result.getJSONObject("thread").getString("id"), result.getString("model"));
        if (method.equals("turn/start") && params != null && params.has("model"))
            threadModels.put(params.getString("threadId"), params.getString("model"));
    }

    void nativeReject(Object id) throws Exception {
        send(new JSONObject().put("id",id).put("error",new JSONObject().put("code",-32800).put("message","User cancelled native request")));
    }

    void nativeReply(Object id, JSONObject result) throws Exception {
        send(new JSONObject().put("id", id).put("result", result));
    }

    JSONObject call(String method, JSONObject params) throws Exception {
        return awaitResponse(method, params, request(method, params));
    }

    private JSONObject startTaskTurn(File directory, JSONObject task, JSONObject params) throws Exception {
        int id = request("turn/start", params);
        taskStart = new TaskStart(id, directory, task.getString("id"), params.getString("threadId"));
        try {
            return awaitResponse("turn/start", params, id);
        } finally {
            // Without the owning response, no buffered reply can become authoritative.
            taskStart = null;
        }
    }

    private JSONObject awaitResponse(String method, JSONObject params, int id) throws Exception {
        legacyPending.add(id);
        try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!responses.containsKey(id)) {
            pump(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            if (System.nanoTime() > deadline) throw new IOException("Assistant connection timed out: " + method);
        }
        JSONObject response = responses.remove(id);
        if (response.has("error") && response.getJSONObject("error").optInt("code") == -32600
                && response.getJSONObject("error").optString("message").startsWith("no rollout found for thread id "))
            throw new MissingThread();
        if (response.has("error"))
            if (response.getJSONObject("error").optString("message").contains("routing discovery"))
                throw new IOException("Assistant network connection failed. Check the phone network and local proxy.");
        if (response.has("error"))
            throw new IOException(
                    "Assistant connection failed: "
                            + method
                            + "（"
                            + response.getJSONObject("error").optInt("code")
                            + "）");
        JSONObject result = response.getJSONObject("result");
        rememberModel(method, params, result);
        return result;
        } finally { legacyPending.remove(id); responses.remove(id); }
    }

    void initialize() throws Exception {
        call(
                "initialize",
                new JSONObject()
                        .put(
                                "clientInfo",
                                new JSONObject()
                                        .put("name", "taku_android")
                                        .put("title", "Taku Android")
                                        .put("version", "0.1.0"))
                        .put("capabilities", new JSONObject().put("experimentalApi", true)));
        send(new JSONObject().put("method", "initialized"));
    }

    boolean signedIn() throws Exception {
        if (magpie) {
            JSONObject state = ModelConnection.read();
            boolean ready = state.optBoolean("running") && state.getJSONArray("models").length() > 0;
            AgentAccount.gateway(ready, state.optString("message"));
            return ready;
        }
        JSONObject account = call("account/read", new JSONObject().put("refreshToken", false));
        AgentAccount.read(account);
        return !account.isNull("account");
    }

    void pump(long millis) throws Exception {
        NativeBridge.tick();
        String line = output.poll(Math.min(millis, 1000), TimeUnit.MILLISECONDS);
        if (line == null) return;
        if (EOF.equals(line)) throw new IOException(readerFailure.isEmpty() ? "The assistant service has exited" : readerFailure);
        JSONObject event = new JSONObject(line);
        if (event.has("id") && !event.has("method")) {
            NativeBridge.admissions.response(event.getInt("id"), !event.has("error"));
            if (NativeBridge.complete(event)) return;
            if (legacyPending.contains(event.getInt("id"))) responses.put(event.getInt("id"), event);
            TaskStart start = taskStart;
            if (start != null && start.requestId == event.getInt("id")) {
                if (!event.has("error")) activeTurn = start.bind(event.getJSONObject("result"));
                taskStart = null;
                if (!event.has("error")) while (!start.deferred.isEmpty()) dispatch(start.deferred.removeFirst());
            }
            return;
        }
        TaskStart start = taskStart;
        JSONObject params = event.optJSONObject("params");
        if (start != null && params != null && start.threadId.equals(params.optString("threadId"))) {
            if (start.deferred.size() >= 256) throw new IOException("TASK_START_RESPONSE_PENDING");
            start.deferred.addLast(event);
            return;
        }
        dispatch(event);
    }

    private void dispatch(JSONObject event) throws Exception {
        if (event.has("id")) {
            NativeBridge.request(this, event);
            return;
        }
        JSONObject cleanup = null;
        try { cleanup = processes.event(event); }
        catch (Exception unavailable) {
            cleanup = new JSONObject().put("complete", false).put("reason", "process-inspection-unavailable");
        }
        if (cleanup != null) NativeBridge.cancellation(event.getJSONObject("params").optString("threadId"), cleanup);
        NativeBridge.event(event);
        events.accept(event);
    }

    void interrupt(String thread, String turn) throws Exception {
        PhoneGestures.endTurn(thread,turn);
        AgentViews.interruptReply(thread, turn);
        request("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", turn));
    }

    @Override
    public void close() {
        processes.close();
        NativeBridge.closed();
        closed = true;
        reader.interrupt();
        try {
            input.close();
        } catch (Exception ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    static volatile CodexAppServer shared;
    static JSONObject workspaceProcessProof(String thread) throws Exception {
        CodexAppServer server = shared;
        if (server == null || server.closed || !server.process.isAlive())
            return new JSONObject().put("verified", false).put("complete", false)
                    .put("reason", "runtime-unavailable").put("observedAt", System.currentTimeMillis());
        return server.processes.workspaceProof(thread);
    }
    static boolean booting;
    static JSONObject currentRequest;
    static String finalText = "", completion = "", activeThread = "", activeTurn = "";

    static CodexAppServer shared() throws Exception {
        if (shared == null) {
            AgentViews.runtimeClosed();
            File diagnostics = new File(TaskBroker.ROOT, "agent-view");
            diagnostics.mkdirs();
            shared =
                    new CodexAppServer(
                            diagnostics,
                            event -> {
                                AgentAccount.event(event);
                                AgentViews.event(event);
                                try { HarnessContext.event(event); }
                                catch (Exception auditUnavailable) { HarnessContext.auditIncomplete = true; }
                                JSONObject params = event.optJSONObject("params");
                                if (params == null
                                        || currentRequest == null
                                        || !activeThread.equals(params.optString("threadId")))
                                    return;
                                String method = event.optString("method");
                                JSONObject turn = params.optJSONObject("turn");
                                String eventTurn = turn == null ? params.optString("turnId") : turn.optString("id");
                                if (activeTurn.isEmpty() || !activeTurn.equals(eventTurn)) return;
                                if ("item/completed".equals(method)) {
                                    JSONObject item = params.optJSONObject("item");
                                    if (item != null
                                            && "agentMessage".equals(item.optString("type")))
                                        finalText = item.optString("text");
                                }
                                if ("turn/completed".equals(method)) {
                                    JSONObject ended = params.getJSONObject("turn");
                                    if (activeTurn.equals(ended.getString("id")))
                                        completion = ended.getString("status");
                                }
                            });
            try {
                booting=true;
                try { shared.initialize(); SkillUninstall.recover(shared); } finally { booting=false; }
            } catch (Exception error) {
                shared.close();
                shared = null;
                throw error;
            }
        }
        return shared;
    }

    static JSONObject options(String instructions) throws Exception {
        JSONObject options = new JSONObject()
                .put("cwd", "/data/local/agentos/work")
                .put("approvalPolicy", "never")
                .put("sandbox", "danger-full-access");
        if (instructions != null) options.put("developerInstructions", instructions);
        return options;
    }

    static void control(File directory, JSONObject request) throws Exception {
        JSONObject answer = new JSONObject();
        try {
            String method = request.getString("method");
            if (AcpConversations.servesRead(request)) {
                // An ACP conversation's turns live in AcpConversationStore, not in the app server.
                answer.put("ok", true).put("result", AcpConversations.read(request.getJSONObject("params").getString("threadId")));
                TaskBroker.write(new File(directory, "response.json"), answer.toString());
                TaskBroker.write(new File(directory, "done"), "control");
                return;
            }
            CodexAppServer server;
            try {
                server = shared();
            } catch (Exception unavailable) {
                // ACP conversations stay listable while the Codex app server cannot start.
                if (!"thread/list".equals(method) || AcpConversations.STORE.ids().isEmpty()) throw unavailable;
                server = null;
            }
            JSONObject params = request.optJSONObject("params");
            if (params == null) params = new JSONObject();
            JSONObject result;
            if (method.startsWith("skills/")) {
                boolean active = currentRequest != null;
                try (CleanupWork.Held bridgeHeld = NativeBridge.lock()) {
                    for (JSONObject state : NativeBridge.state.values())
                        active |= !state.optString("activeTurnId").isEmpty();
                }
                if (active && !method.equals("skills/read")) throw new IOException("A task is running. Manage skills after it finishes.");
                if (active) result = AgentSkills.readWhileRunning(params.optString("threadId"));
                else {
                    boolean admitted = false;
                    try { NativeBridge.admissions.beginSkillMaintenance(); admitted = true; }
                    catch (IOException busy) { if (!method.equals("skills/read")) throw busy; }
                    if (!admitted) result = AgentSkills.readWhileRunning(params.optString("threadId"));
                    else try { result = AgentSkills.control(server, method, params); }
                    finally { NativeBridge.admissions.endSkillMaintenance(); }
                }
            } else if (method.equals("harness/read") || method.equals("harness/refresh")) {
                String id = params.optString("threadId");
                if (method.equals("harness/refresh")) {
                    if (currentRequest != null) throw new IOException("A task is running. The update will apply to the next turn.");
                    if (!server.freshThreads.containsKey(id)) server.call("thread/resume", options(HarnessContext.background(id)).put("threadId", id).put("excludeTurns", true));
                    AgentSkills.refresh(server, false);
                    JSONObject context = new JSONObject(params.toString()).put("conversationId", id);
                    try { context.put("displayId", TaskBroker.command("/system_ext/bin/phone-display", "id").trim()); } catch (Exception missingDisplay) { context.put("displayId", ""); }
                    HarnessContext.prepare(server, id, context);
                }
                result = HarnessContext.read(id);
            } else if (method.startsWith("account/")) {
                result = accountControl(server, method, params);
            } else if ("thread/start".equals(method)) {
                AgentSkills.refresh(server, false);
                JSONObject seed = HarnessContext.seed();
                result = server.call(method, options(seed.getString("text")).put("dynamicTools", NativeBridge.toolDefinitions()));
                HarnessContext.started(result.getJSONObject("thread").getString("id"), seed);
                AgentViews.save(result.getJSONObject("thread"));
                server.freshThreads.put(result.getJSONObject("thread").getString("id"), result);
            } else if ("thread/list".equals(method)) {
                if (server != null) {
                    result =
                            server.call(
                                    method,
                                    new JSONObject()
                                            .put("cwd", "/data/local/agentos/work")
                                            .put("limit", 100)
                                            .put("sourceKinds", new JSONArray()));
                    JSONArray data = result.getJSONArray("data");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject row = data.getJSONObject(i);
                        if (!AgentViews.threads.containsKey(row.getString("id"))) AgentViews.saveMetadata(row);
                    }
                }
                AcpConversations.publishAll();
                AgentViews.index();
                result = new JSONObject(TaskBroker.read(new File(AgentViews.ROOT, "threads.json")));
                SessionRemovalRuntime.filter(result);
            } else if ("thread/name/set".equals(method)) {
                String id = params.getString("threadId"), name = params.getString("name").trim();
                if (!id.matches("[a-f0-9-]{36}") || name.isEmpty() || name.length() > 120)
                    throw new IOException("Invalid conversation title");
                server.call(method, new JSONObject().put("threadId", id).put("name", name));
                if (server.freshThreads.containsKey(id)) {
                    result = server.freshThreads.get(id);
                    result.getJSONObject("thread").put("name", name);
                } else result =
                        server.call(
                                "thread/read",
                                new JSONObject().put("threadId", id).put("includeTurns", false));
                JSONObject updated = result.getJSONObject("thread");
                AgentViews.saveMetadata(updated);
                result = new JSONObject().put("threadId", id).put("loaded", true);
            } else if ("thread/read".equals(method)) {
                String id = params.getString("threadId");
                if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid conversation identifier");
                boolean fresh = server.freshThreads.containsKey(id);
                if (fresh) {
                    result = new JSONObject(server.freshThreads.get(id).toString());
                    JSONObject projected = AgentViews.snapshot(id);
                    if (projected != null) result.put("thread", projected);
                } else try {
                    result =
                        server.call(
                                "thread/read",
                                new JSONObject().put("threadId", id).put("includeTurns", false));
                } catch (MissingThread missing) {
                    File projection = new File(AgentViews.ROOT, id + ".json");
                    JSONObject prior = new JSONObject(TaskBroker.read(projection));
                    if (prior.optJSONArray("turns") == null || prior.getJSONArray("turns").length() != 0)
                        throw missing;
                    result = new JSONObject().put("threadUnavailable", true);
                }
            if (result.has("thread")) {
                JSONObject nativeThread = result.getJSONObject("thread");
                AgentViews.saveMetadata(nativeThread);
                JSONArray turns = nativeThread.optJSONArray("turns");
                result = new JSONObject().put("threadId", nativeThread.getString("id")).put("loaded", true)
                        .put("freshEmpty", fresh && turns != null && turns.length() == 0);
            }
            } else throw new IOException("Unsupported conversation operation");
            if (result.has("thread")) {
                String id = result.getJSONObject("thread").getString("id");
                if (method.equals("thread/read") || method.equals("thread/name/set")) AgentViews.prepareReply(id, HarnessCatalog.replyContract(), true);
                result.put("thread", AgentViews.snapshot(id));
            }
            answer.put("ok", true).put("result", result);
        } catch (Exception error) {
            answer.put("ok", false).put("message", NativeBridge.publicError(error));
        }
        TaskBroker.write(new File(directory, "response.json"), answer.toString());
        TaskBroker.write(new File(directory, "done"), "control");
    }

    private static JSONObject accountControl(
            CodexAppServer server, String method, JSONObject params) throws Exception {
        try {
            if ("account/read".equals(method)) {
                server.signedIn();
            } else if ("account/login/start".equals(method)) {
                if (server.magpie) throw new IOException("Manage Magpie services in Model connection.");
                if (currentRequest != null) throw new IOException("execution active");
                if (server.signedIn()) return new JSONObject();
                if (!AgentAccount.loginId().isEmpty()) return new JSONObject();
                String type = params.optString("type");
                if (!type.equals("chatgpt") && !type.equals("chatgptDeviceCode"))
                    throw new IOException("unsupported account mode");
                AgentAccount.starting();
                JSONObject login = server.call(method, new JSONObject().put("type", type));
                AgentAccount.started(login);
            } else if ("account/login/cancel".equals(method)) {
                String id = AgentAccount.loginId();
                if (!id.isEmpty()) server.call(method, new JSONObject().put("loginId", id));
                AgentAccount.cancelled();
            } else throw new IOException("unsupported account operation");
            // Queue responses contain no account metadata, code, URL, or token.
            return new JSONObject();
        } catch (Exception error) {
            AgentAccount.failed();
            throw new IOException("account operation failed");
        }
    }

    static void tick() throws Exception {
        NativeBridge.tick();
        if (shared != null) {
            shared.pump(1);
            // Drain already-arrived events even while idle, up to 2000 lines; processing one long-output event per 150ms would delay turn completion by minutes.
            for (int i = 0; i < 2000 && shared != null && !shared.output.isEmpty(); i++) shared.pump(0);
            if (AgentAccount.refreshRequested) {
                AgentAccount.refreshRequested = false;
                shared.signedIn();
            }
        }
    }

    static void run(File directory, JSONObject request) throws Exception {
        CodexAppServer server = shared();
        currentRequest = request;
        finalText = "";
        completion = "";
        activeThread = "";
        activeTurn = "";
        HarnessContext.auditIncomplete = false;
        boolean chat = request.has("conversationId");
        try {
            if (cancelledBeforeTurn(directory, request)) return;
            if (!server.signedIn()) {
                TaskBroker.state(
                        directory, request, "needs_user", "This device has no connected account. Connect an account to start a conversation; your message draft remains on the phone.");
                writePanelCompletion(directory, request, "needs_user");
                TaskBroker.write(new File(directory, "done"), "needs_user");
                return;
            }
            AgentSkills.refresh(server, false);
            JSONObject result;
            if (chat) {
                String requested = request.getString("conversationId");
                result = server.freshThreads.containsKey(requested) ? server.freshThreads.get(requested)
                        : server.call("thread/resume", options(HarnessContext.background(requested)).put("threadId", requested).put("excludeTurns", true));
            } else {
                JSONObject seed = HarnessContext.seed();
                result = server.call("thread/start", options(seed.getString("text")).put("dynamicTools", NativeBridge.toolDefinitions()));
                HarnessContext.started(result.getJSONObject("thread").getString("id"), seed);
            }
            JSONObject thread = result.getJSONObject("thread");
            activeThread = thread.getString("id");
            if (chat) AgentViews.saveMetadata(thread);
            else AgentViews.save(thread);
            HarnessContext.prepare(server, activeThread,
                    new JSONObject(request.toString()).put("replyMode", chat ? "primary" : "disabled"));
            JSONArray content = ComposerInput.text(request.getString("prompt"));
            if ((!chat || request.optBoolean("includeScreenshot", false))
                    && new File(request.optString("screenshot")).isFile())
                content.put(
                        new JSONObject()
                                .put("type", "localImage")
                                .put("path", request.getString("screenshot")));
            if (chat) {
                JSONArray selected = AgentSkills.selected(activeThread);
                for (int i = 0; i < selected.length(); i++) {
                    JSONObject skill = selected.getJSONObject(i);
                    content.put(new JSONObject().put("type", "skill").put("name", skill.getString("name")).put("path", skill.getString("path")));
                }
            }
            ComposerInput.appendAttachments(content, request.optJSONArray("attachments"));
            if (cancelledBeforeTurn(directory, request)) return;
            JSONObject params =
                    new JSONObject().put("threadId", activeThread).put("input", content);
            for (String key : new String[]{"model", "effort", "multiAgentMode", "serviceTier"})
                if (request.has(key)) params.put(key, request.opt(key));
            String permission=request.optString("permissionMode","full");
            NativeBridge.setPhonePermission(activeThread,permission);
            params.put("approvalPolicy",permission.equals("ask")?"on-request":"never");
            params.put("sandboxPolicy",new JSONObject().put("type",permission.equals("readOnly")?"readOnly":permission.equals("ask")?"workspaceWrite":"dangerFullAccess"));
            if (!chat)
                params.put(
                        "outputSchema",
                        new JSONObject(
                                TaskBroker.read(
                                        new File(
                                                "/system_ext/etc/agentos/task-result.schema.json"))));
            JSONObject resultTurn = server.startTaskTurn(directory, request, params);
            server.freshThreads.remove(activeThread);
            activeTurn = resultTurn.getJSONObject("turn").getString("id");
            try { HarnessContext.turnStarted(activeThread, activeTurn, request.getString("id")); }
            catch (Exception auditUnavailable) { HarnessContext.auditIncomplete = true; }
            TaskBroker.write(
                    new File(directory, "runtime-info.json"),
                    new JSONObject()
                            .put("taskId", request.getString("id"))
                            .put("threadId", activeThread)
                            .put("turnId", activeTurn)
                            .put("runtime", "codex-0.161.0")
                            .put("model", result.getString("model"))
                            .put("turnStarted", true)
                            .put("harnessAuditComplete", !HarnessContext.auditIncomplete)
                            .put("screenshotAttached", (!chat || request.optBoolean("includeScreenshot", false)) && new File(request.optString("screenshot")).isFile())
                            .toString());
            TaskBroker.state(directory, request, "running", "Taku is working. You can continue using your phone.");
            // One fixed budget for the turn, including its final result. Streaming
            // messages, tool calls and user replies must not renew this deadline.
            long deadline = System.nanoTime() + TASK_EXECUTION_LIMIT_NANOS;
            boolean stopped = false, timedOut = false;
            while (completion.isEmpty()) {
                if (!stopped
                        && (new File(directory, "cancel").exists()
                                || System.nanoTime() > deadline)) {
                    timedOut = !new File(directory, "cancel").exists();
                    server.interrupt(activeThread, activeTurn);
                    stopped = true;
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    TaskBroker.state(directory, request, "running", "Stopping. Completed actions will not be automatically undone.");
                }
                if (stopped && System.nanoTime() > deadline) {
                    server.close();
                    shared = null;
                    throw new IOException("Stopping has not been confirmed. Check the results on your phone");
                }
                server.pump(100);
                TaskBroker.controls();
            }
            String status = "failed", message = "This turn failed. Please retry.";
            if ("interrupted".equals(completion)) {
                status = timedOut ? "failed" : "cancelled";
                message = timedOut ? "Execution timed out. Check the results of any actions." : "Stopped. Completed actions will not be automatically undone.";
                JSONObject cleanup = NativeBridge.state(activeThread).optJSONObject("cancellationCleanup");
                if (cleanup != null && !cleanup.optBoolean("complete")) {
                    status = "failed";
                    message = "The conversation was interrupted, but the underlying process has not confirmed stopping. Check the results of any actions.";
                }
            } else if ("completed".equals(completion)) {
                if (chat) {
                    status = "completed";
                    message = "This turn has ended. See the reply for the actual results.";
                } else {
                    JSONObject answer = new JSONObject(finalText);
                    status = answer.getString("status");
                    message = answer.getString("message");
                    if (!Arrays.asList("completed", "failed", "needs_user").contains(status))
                        throw new IOException("Invalid runtime result format");
                }
                TaskBroker.write(new File(directory, "result.txt"), chat ? java.util.Objects.toString(AgentViews.readableTurn(activeThread, activeTurn), "") : finalText);
            }
            if (chat) {
                try {
                    JSONObject canonical =
                            server.call(
                                    "thread/read",
                                    new JSONObject()
                                            .put("threadId", activeThread)
                                            .put("includeTurns", false));
                    AgentViews.saveMetadata(canonical.getJSONObject("thread"));
                } catch (Exception historyUnavailable) {
                    // Execution has already reached a real terminal state; a view refresh
                    // failure must not turn completed device work into a failed task.
                    message += " History has not fully synchronized. Reopen the conversation to refresh.";
                }
            }
            if (HarnessContext.auditIncomplete) message += " Some Harness receipts have not been saved; native execution results are unchanged.";
            TaskBroker.state(directory, request, status, message);
            writePanelCompletion(directory, request, status);
            TaskBroker.write(new File(directory, "done"), status);
        } finally {
            PhoneGestures.endTurn(activeThread,activeTurn);
            currentRequest = null;
            activeThread = "";
            activeTurn = "";
        }
    }

    /**
     * Panel updates publish only from exactly one completed card reply of this task's turn, so the
     * manifest records every candidate and the failure code instead of guessing the newest reply.
     */
    static void writePanelCompletion(File directory, JSONObject request, String status) throws Exception {
        if (request.optJSONObject("panelUpdate") == null) return;
        String thread = request.getJSONObject("panelUpdate").getString("originThreadId"), turn = "";
        File info = new File(directory, "runtime-info.json");
        if (info.isFile()) {
            JSONObject value = new JSONObject(TaskBroker.read(info));
            if (!thread.equals(value.optString("threadId"))) throw new IOException("SOURCE_CHANGED");
            turn = value.optString("turnId");
        }
        JSONObject manifest = new JSONObject().put("schema", "taku.panel-completion/1")
                .put("taskId", request.optString("id")).put("threadId", thread).put("turnId", turn)
                .put("status", status).put("candidates", new JSONArray()).put("reply", JSONObject.NULL);
        if ("completed".equals(status) && !thread.isEmpty() && !turn.isEmpty()) {
            JSONArray replies = AgentViews.panelCompletion(thread, turn).optJSONArray("replies");
            JSONArray candidates = new JSONArray();
            if (replies != null) for (int i = 0; i < replies.length(); i++) {
                JSONObject reply = replies.getJSONObject(i), provenance = reply.optJSONObject("provenance");
                candidates.put(new JSONObject().put("replyId", reply.optString("id"))
                        .put("key", reply.optString("key")).put("revision", reply.getLong("revision"))
                        .put("contractHash", provenance == null ? "" : provenance.optString("contractHash"))
                        .put("anchorOrdinal", provenance == null ? -1 : provenance.optInt("anchorOrdinal", -1)));
            }
            manifest.put("candidates", candidates);
            if (replies != null && replies.length() == 1)
                manifest.put("reply", new JSONObject(replies.getJSONObject(0).toString()));
            else manifest.put("code", candidates.length() == 0 ? "REPLY_INCOMPLETE" : "PANEL_UPDATE_AMBIGUOUS");
        }
        TaskBroker.write(new File(directory, "panel-completion.json"), manifest.toString());
    }

    private static boolean cancelledBeforeTurn(File directory, JSONObject request)
            throws Exception {
        if (!new File(directory, "cancel").exists()) return false;
        TaskBroker.state(directory, request, "cancelled", "Cancelled before execution.");
        writePanelCompletion(directory, request, "cancelled");
        TaskBroker.write(new File(directory, "done"), "cancelled");
        return true;
    }
}
