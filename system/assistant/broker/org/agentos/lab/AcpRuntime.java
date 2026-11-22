package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent Client Protocol (ACP) client runtime: drives a local ACP agent (the on-device
 * {@code android_arm64} {@code agy acp}) over newline-delimited JSON-RPC 2.0 on stdio.
 *
 * <p>Lifecycle per task, one agent process per task so a crash never leaks into the next task:
 * {@code initialize} → {@code session/new} (with the phone MCP server declarations; an
 * {@code auth_required} error becomes {@code needs_user}) → {@code session/prompt} while
 * {@code session/update} notifications stream progress → {@code session/cancel} when the task's
 * {@code cancel} file appears or the turn budget runs out → process reclaimed.
 *
 * <p>Credentials: this runtime never reads, writes, copies or embeds credentials. Sign-in is the
 * agent's own {@code oauth-personal} flow, performed by the user in the phone's browser; the
 * runtime only reports that sign-in is needed.
 */
final class AcpRuntime implements TaskRuntime {
    static final int PROTOCOL_VERSION = 1;
    /** ACP's {@code auth_required} error code. */
    static final int AUTH_REQUIRED = -32000;
    static final String DEFAULT_LOGIN_METHOD = "oauth-personal";
    static final String DEFAULT_APP_DATA_DIR = "taku-acp";
    private static final int MAX_LINE_CHARS = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 1024 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 3_000_000;

    interface Notifier {
        void taskUpdated(String taskId);
    }

    /**
     * Chat turns (requests with {@code conversationId}). The store keeps the conversation's ACP
     * session and turns; {@link #changed} republishes the conversation's display projection so the
     * chat page streams the turn; {@link #history} supplies earlier display turns (any runtime) for a
     * fresh session that could not resume the previous one.
     */
    interface ChatHost {
        AcpConversationStore store();

        void changed(String conversationId);

        JSONArray history(String conversationId) throws Exception;
    }

    /** Earlier turns carried into a fresh session, bounded so the prompt stays small. */
    static final int TRANSCRIPT_CHARS = 12_000;

    /** Parsed {@code runtime.json "acp"} object. Paths are on-device paths. */
    static final class Options {
        String binary = "";
        String home = "/data/local/agentos/acp/home";
        /**
         * {@code agy --app-data-dir}: a plain name that agy resolves under {@code $HOME/.gemini/}.
         * agy rejects absolute paths ("language server failed to start: must not be absolute"),
         * verified on the Pixel with android_arm64 1.5.14. Invalid values fall back to the default.
         */
        String appDataDir = DEFAULT_APP_DATA_DIR;
        String cwd = "/data/local/agentos/work";
        String httpsProxy = "";
        String loginMethod = DEFAULT_LOGIN_METHOD;
        List<String> args = new ArrayList<>(List.of("acp"));
        List<String> extraArgs = new ArrayList<>();
        Map<String, String> extraEnv = new LinkedHashMap<>();
        JSONArray mcpServers = new JSONArray();
        /** The taku_phone MCP relay (see PhoneMcpHost); null disables it. */
        JSONObject phoneMcp = defaultPhoneMcp();
        long turnLimitMillis = TimeUnit.MINUTES.toMillis(15);
        long cancelGraceMillis = TimeUnit.SECONDS.toMillis(10);
        long requestTimeoutMillis = TimeUnit.SECONDS.toMillis(60);
        long progressIntervalMillis = 500;
        /**
         * Start the agent with {@code --dangerously-skip-permissions} for {@code permissionMode: full}
         * tasks. agy routes only the parent's approvals through {@code session/request_permission}
         * (which a full-mode task already answers with allow); a subagent's approval has no ACP route and
         * blocks it forever in {@code waiting_for_input} (Pixel 9, 1.5.14). The flag gives subagents the
         * same full-mode policy; agy still blocks its system command denylist.
         */
        boolean autoApprove = true;
        /** After every started subagent reported back and was delivered, the parent's silence that ends the task. */
        long subagentSettleMillis = TimeUnit.SECONDS.toMillis(6);
        /** A report the parent has not consumed this long (while it is silent) gets one follow-up prompt. */
        long subagentNudgeMillis = TimeUnit.SECONDS.toMillis(20);
        /** Without a readable subagent store: the parent's silence that ends the wait. */
        long subagentIdleMillis = TimeUnit.MINUTES.toMillis(5);
        long subagentPollMillis = 1000;
        /**
         * Where the agent's model list ({@code session/new} configOptions) is saved for the model
         * picker ({@link AcpModelCatalog}); null keeps it unsaved. Set by the broker, not runtime.json.
         */
        File modelCatalog;

        static JSONObject defaultPhoneMcp() {
            try {
                return new JSONObject()
                        .put("command", "/system/bin/app_process")
                        .put("args", new JSONArray().put("/system/bin").put("org.agentos.lab.PhoneMcpHost").put("relay"))
                        .put("env", new JSONObject().put("CLASSPATH", "/system_ext/framework/agentos-task-broker.jar"));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static Options fromJson(JSONObject c) throws Exception {
            Options o = new Options();
            if (c == null) return o;
            o.binary = c.optString("binary", o.binary).trim();
            o.home = c.optString("home", o.home);
            o.appDataDir = appDataName(c.optString("appDataDir", o.appDataDir));
            o.cwd = c.optString("cwd", o.cwd);
            o.httpsProxy = c.optString("httpsProxy", o.httpsProxy);
            o.loginMethod = c.optString("loginMethod", o.loginMethod);
            if (c.optJSONArray("args") != null) o.args = strings(c.getJSONArray("args"));
            if (c.optJSONArray("extraArgs") != null) o.extraArgs = strings(c.getJSONArray("extraArgs"));
            JSONObject env = c.optJSONObject("env");
            if (env != null) for (java.util.Iterator<String> it = env.keys(); it.hasNext(); ) { String key = it.next(); o.extraEnv.put(key, env.getString(key)); }
            if (c.optJSONArray("mcpServers") != null) o.mcpServers = new JSONArray(c.getJSONArray("mcpServers").toString());
            if (c.has("phoneMcp")) o.phoneMcp = c.optJSONObject("phoneMcp");
            o.turnLimitMillis = c.optLong("turnLimitMillis", o.turnLimitMillis);
            o.cancelGraceMillis = c.optLong("cancelGraceMillis", o.cancelGraceMillis);
            o.requestTimeoutMillis = c.optLong("requestTimeoutMillis", o.requestTimeoutMillis);
            o.progressIntervalMillis = c.optLong("progressIntervalMillis", o.progressIntervalMillis);
            o.autoApprove = c.optBoolean("autoApprove", o.autoApprove);
            o.subagentSettleMillis = c.optLong("subagentSettleMillis", o.subagentSettleMillis);
            o.subagentNudgeMillis = c.optLong("subagentNudgeMillis", o.subagentNudgeMillis);
            o.subagentIdleMillis = c.optLong("subagentIdleMillis", o.subagentIdleMillis);
            o.subagentPollMillis = c.optLong("subagentPollMillis", o.subagentPollMillis);
            return o;
        }

        /** A safe single path segment for {@code --app-data-dir}, else the default. */
        static String appDataName(String value) {
            String v = value == null ? "" : value.trim();
            if (v.matches("[A-Za-z0-9._-]{1,64}") && !v.equals(".") && !v.equals("..")) return v;
            return DEFAULT_APP_DATA_DIR;
        }

        private static List<String> strings(JSONArray array) throws Exception {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
            return out;
        }
    }

    private final Options options;
    private final AcpProcessTransport transport;
    private final Notifier notifier;
    private final Runnable idle;
    private final ChatHost chatHost;

    AcpRuntime(Options options, AcpProcessTransport transport, Notifier notifier, Runnable idle) {
        this(options, transport, notifier, idle, null);
    }

    AcpRuntime(Options options, AcpProcessTransport transport, Notifier notifier, Runnable idle, ChatHost chatHost) {
        this.options = options;
        this.transport = transport;
        this.notifier = notifier == null ? id -> {} : notifier;
        this.idle = idle == null ? () -> {} : idle;
        this.chatHost = chatHost;
    }

    @Override
    public String name() {
        return RuntimeRouter.ACP;
    }

    /** The agent command line and environment. Auto-update is always disabled. */
    AcpProcessTransport.Launch launch() {
        return launch(null);
    }

    /** The task's command line: {@link #launch()} plus the full-mode approval policy (see {@link Options#autoApprove}). */
    AcpProcessTransport.Launch launch(JSONObject request) {
        List<String> command = new ArrayList<>();
        command.add(options.binary);
        command.addAll(options.args);
        if (!options.appDataDir.isEmpty()) {
            command.add("--app-data-dir");
            command.add(options.appDataDir);
        }
        command.addAll(options.extraArgs);
        if (request != null && options.autoApprove && "full".equals(request.optString("permissionMode", "full"))
                && !command.contains("--dangerously-skip-permissions"))
            command.add("--dangerously-skip-permissions");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", options.home);
        // A pinned on-device binary must never replace itself; the ROM owns its version.
        // AGY_CLI_DISABLE_AUTO_UPDATE is the switch the android_arm64 agy binary checks ("Auto-update
        // disabled via environment variable %s"); it has no CLI flag for this. The rest are generic.
        env.put("AGY_CLI_DISABLE_AUTO_UPDATE", "1");
        env.put("AGY_DISABLE_AUTO_UPDATE", "1");
        env.put("DISABLE_AUTO_UPDATE", "1");
        env.put("NO_UPDATE_NOTIFIER", "1");
        if (!options.httpsProxy.isEmpty()) {
            env.put("HTTPS_PROXY", options.httpsProxy);
            env.put("https_proxy", options.httpsProxy);
        }
        env.putAll(options.extraEnv);
        return new AcpProcessTransport.Launch(command, env, options.cwd.isEmpty() ? null : new File(options.cwd));
    }

    @Override
    public void run(File directory, JSONObject request) throws Exception {
        if (new File(directory, "cancel").exists()) {
            finish(directory, request, "cancelled", "Cancelled before execution.", null);
            return;
        }
        Chat chat = Chat.begin(chatHost, request, Math.min(250, options.progressIntervalMillis));
        TaskRecords.state(directory, request, "running", "Starting the on-device agent…");
        notifier.taskUpdated(request.getString("id"));
        AcpProcessTransport.Connection connection;
        try {
            // Best effort: a missing working directory would otherwise fail the start opaquely.
            // appDataDir is relative to $HOME/.gemini and created by the agent itself.
            for (String path : new String[]{options.home, options.cwd})
                if (!path.isEmpty()) new File(path).mkdirs();
            connection = transport.start(launch(request));
        } catch (IOException startFailed) {
            diagnostic(directory, "start-failed", startFailed.getMessage(), "");
            if (chat != null) chat.end("failed", null);
            finish(directory, request, "failed", "The on-device agent could not start. Check its installation, then retry.", null);
            return;
        }
        Turn turn = new Turn(directory, request, connection, chat);
        activeChat = chat;
        try {
            turn.execute();
        } finally {
            activeChat = null;
            connection.destroy(Math.min(2000, options.cancelGraceMillis));
            // A path that ended without finish() (an unexpected exception) still closes the turn.
            if (chat != null && !chat.ended) chat.end("failed", null);
        }
    }

    // ---- terminal records -------------------------------------------------------------------

    /** The chat turn of the task currently running on this runtime instance, if any. */
    private Chat activeChat;

    private void finish(File directory, JSONObject request, String status, String message, String reply) throws Exception {
        // Agent text can link or quote agent-internal paths; never shown or stored in results.
        if (reply != null) reply = AcpToolLabels.sanitize(reply);
        // The conversation shows the turn's end before the task record does.
        if (activeChat != null && !activeChat.ended) activeChat.end(status, reply);
        if (request.has("conversationId") && chatHost != null) {
            // Chat turns use the chat result contract (as CodexAppServer does): the readable reply as
            // plain text, written only for a completed turn; the state message carries everything else.
            if ("completed".equals(status)) TaskRecords.write(new File(directory, "result.txt"), reply == null ? "" : reply);
        } else if (reply != null || !"cancelled".equals(status))
            TaskRecords.write(new File(directory, "result.txt"), new JSONObject()
                    .put("status", "cancelled".equals(status) ? "failed" : status)
                    .put("message", reply == null || reply.isEmpty() ? message : reply)
                    .put("evidence", new JSONArray()).toString());
        TaskRecords.state(directory, request, status, message);
        TaskRecords.write(new File(directory, "done"), status);
        notifier.taskUpdated(request.getString("id"));
    }

    private static void diagnostic(File directory, String type, String detail, String stderrTail) throws Exception {
        TaskRecords.write(new File(directory, "diagnostic.json"), new JSONObject()
                .put("runtime", "acp").put("type", type)
                .put("detail", redact(detail == null ? "" : detail))
                .put("stderrTail", redact(stderrTail)).toString());
    }

    /** Diagnostics may hold agent stderr; never persist anything that looks like a credential. */
    static String redact(String s) {
        String out = s.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[hidden]")
                .replaceAll("ya29\\.[A-Za-z0-9_.-]+", "[hidden]")
                .replaceAll("1//[A-Za-z0-9_-]{20,}", "[hidden]")
                .replaceAll("AIza[A-Za-z0-9_-]{30,}", "[hidden]")
                .replaceAll("(?i)(Bearer\\s+|(?:access_token|refresh_token|id_token|token|secret|password|api_key|code)[\"']?\\s*[=:]\\s*[\"']?)[^\\s&\"',}]+", "$1[hidden]");
        return out.length() > 4000 ? out.substring(out.length() - 4000) : out;
    }

    // ---- protocol errors ----------------------------------------------------------------------

    static final class AcpError extends IOException {
        final int code;

        AcpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class AgentExited extends IOException {
        AgentExited(String detail) { super(detail); }
    }

    private static final class CancelledEarly extends IOException {
        CancelledEarly() { super("cancelled"); }
    }

    private static final class StopUnconfirmed extends IOException {
        StopUnconfirmed() { super("stop unconfirmed"); }
    }

    private static final class NoResponse extends IOException {
        NoResponse(String method) { super("no response to " + method); }
    }

    // ---- one task's conversation with the agent -------------------------------------------------

    private static final Object EOF = new Object();

    private final class Turn {
        final File directory;
        final JSONObject request;
        final AcpProcessTransport.Connection connection;
        final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        final StringBuilder stderrTail = new StringBuilder();
        final StringBuilder reply = new StringBuilder();
        final Map<String, JSONObject> toolCalls = new LinkedHashMap<>();
        final String permissionMode;
        final OutputStream out;
        long nextId = 1;
        String sessionId = "";
        boolean cancelSent, timedOut, inPrompt;
        long turnDeadline, stopDeadline;
        String summary = "", phase = "starting", plan = "", currentTool = "";
        long lastProgressWrite, lastIdle;
        int permissionsAllowed, permissionsRejected, ignoredLines;
        final Chat chat;
        /** Completed {@code invoke_subagent} calls of this task (the parent's turns, including wake-ups). */
        final java.util.Set<String> subagentCalls = new java.util.LinkedHashSet<>();
        AcpSubagents subagents;
        AcpSubagents.Status subagentStatus;
        /** The session's model (agy's id), the card's model for children that inherit it. */
        String parentModel = "";
        long lastCardRefresh;
        boolean followUp;
        /** Last session/update of this session, and last reply text chunk (nanoTime). */
        long lastUpdate, lastChunk, lastChunkWall;
        /** Where the parent's latest wake-up text starts in {@link #reply} (its headline), and a pending start. */
        int lastSegment, pendingSegment = -1;
        String currentToolKey = "";

        Turn(File directory, JSONObject request, AcpProcessTransport.Connection connection, Chat chat) {
            this.chat = chat;
            this.directory = directory;
            this.request = request;
            this.connection = connection;
            this.permissionMode = request.optString("permissionMode", "full");
            this.out = connection.stdin();
            this.lastIdle = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
            startReaders();
        }

        void startReaders() {
            Thread stdout = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.stdout(), StandardCharsets.UTF_8))) {
                    StringBuilder line = new StringBuilder();
                    int c;
                    while ((c = reader.read()) != -1) {
                        if (c == '\n') {
                            inbox.put(line.toString());
                            line.setLength(0);
                        } else if (line.length() < MAX_LINE_CHARS) {
                            line.append((char) c);
                        } else {
                            inbox.put(new AgentExited("agent sent an oversized message"));
                            return;
                        }
                    }
                    if (line.length() > 0) inbox.put(line.toString());
                } catch (Exception ignored) {
                    // A broken pipe is the agent going away: report EOF below.
                } finally {
                    inbox.offer(EOF);
                }
            }, "acp-stdout");
            stdout.setDaemon(true);
            stdout.start();
            Thread stderr = new Thread(() -> {
                try (InputStream in = connection.stderr()) {
                    if (in == null) return;
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) synchronized (stderrTail) {
                        stderrTail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (stderrTail.length() > 8192) stderrTail.delete(0, stderrTail.length() - 8192);
                    }
                } catch (Exception ignored) {
                    // Diagnostics only.
                }
            }, "acp-stderr");
            stderr.setDaemon(true);
            stderr.start();
        }

        String stderr() {
            synchronized (stderrTail) {
                return stderrTail.toString();
            }
        }

        void execute() throws Exception {
            String id = request.getString("id");
            try {
                JSONObject init = call("initialize", new JSONObject()
                        .put("protocolVersion", PROTOCOL_VERSION)
                        .put("clientCapabilities", new JSONObject()
                                .put("fs", new JSONObject().put("readTextFile", false).put("writeTextFile", false))
                                .put("terminal", false))
                        .put("clientInfo", new JSONObject().put("name", "taku-os").put("version", "1")));
                if (init.optInt("protocolVersion", -1) != PROTOCOL_VERSION) {
                    diagnostic(directory, "protocol-version", init.optString("protocolVersion"), stderr());
                    finish(directory, request, "failed", "The on-device agent speaks an unsupported ACP version. Update the ROM's agent, then retry.", null);
                    return;
                }
                JSONArray authMethods = init.optJSONArray("authMethods");
                JSONObject capabilities = init.optJSONObject("agentCapabilities");
                boolean resumed = false;
                String loadFailure = "";
                // A chat continues its previous agent session when the agent can load it.
                if (chat != null && !chat.priorSession.isEmpty()) {
                    if (capabilities != null && capabilities.optBoolean("loadSession")) {
                        try {
                            // History replayed by session/load arrives while sessionId is still empty,
                            // so notification() ignores it: the store already holds those turns.
                            call("session/load", new JSONObject()
                                    .put("sessionId", chat.priorSession)
                                    .put("cwd", options.cwd)
                                    .put("mcpServers", mcpServers()));
                            sessionId = chat.priorSession;
                            resumed = true;
                        } catch (AcpError error) {
                            if (error.code == AUTH_REQUIRED) {
                                needsUser(authMethods);
                                return;
                            }
                            loadFailure = error.code + " " + clip(redact(error.getMessage() == null ? "" : error.getMessage()), 200);
                        }
                    } else loadFailure = "loadSession unsupported";
                }
                JSONArray configOptions = null;
                if (resumed) configOptions = AcpModelCatalog.stored(options.modelCatalog, options.binary);
                if (!resumed) {
                    JSONObject session;
                    try {
                        session = call("session/new", new JSONObject()
                                .put("cwd", options.cwd)
                                .put("mcpServers", mcpServers()));
                    } catch (AcpError error) {
                        if (error.code == AUTH_REQUIRED) {
                            needsUser(authMethods);
                            return;
                        }
                        throw error;
                    }
                    sessionId = session.getString("sessionId");
                    configOptions = session.optJSONArray("configOptions");
                    AcpModelCatalog.remember(options.modelCatalog, options.binary, configOptions);
                }
                configOptions = applyModelSelection(configOptions);
                JSONObject info = new JSONObject()
                        .put("taskId", id)
                        .put("runtime", "acp")
                        .put("protocolVersion", PROTOCOL_VERSION)
                        .put("agentInfo", init.optJSONObject("agentInfo") == null ? JSONObject.NULL : init.getJSONObject("agentInfo"))
                        .put("sessionId", sessionId)
                        .put("turnStarted", true);
                String model = AcpModelCatalog.current(configOptions, AcpModelCatalog.MODEL);
                String effort = AcpModelCatalog.current(configOptions, AcpModelCatalog.EFFORT);
                if (!model.isEmpty()) info.put("model", model);
                if (!effort.isEmpty()) info.put("effort", effort);
                parentModel = model;
                if (configRefusals.length() > 0) info.put("configRefusals", configRefusals);
                String text = request.getString("prompt");
                if (chat != null) {
                    chat.bind(sessionId, init.optJSONObject("agentInfo"));
                    // The chat page reads threadId/turnId like a Codex chat turn's runtime-info.
                    info.put("threadId", chat.conversationId).put("conversationId", chat.conversationId)
                            .put("turnId", chat.turnId).put("sessionResumed", resumed);
                    if (!loadFailure.isEmpty()) info.put("sessionLoadFailure", loadFailure);
                    if (!resumed && !chat.transcript.isEmpty())
                        text = "Earlier messages in this conversation, for context (the previous agent session could not be resumed):\n"
                                + chat.transcript + "\n\nCurrent message:\n" + text;
                }
                TaskRecords.write(new File(directory, "runtime-info.json"), info.toString());
                JSONArray prompt = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
                JSONObject image = screenshot(init);
                if (image != null) prompt.put(image);
                phase = "working";
                progress("Taku is working. You can continue using your phone.", true);
                inPrompt = true;
                turnDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.turnLimitMillis);
                subagents = new AcpSubagents(AcpSubagents.sessionRoot(options.home, options.appDataDir, sessionId));
                subagents.baseline();
                JSONObject result;
                try {
                    result = call("session/prompt", new JSONObject().put("sessionId", sessionId).put("prompt", prompt));
                } catch (AcpError error) {
                    if (error.code == AUTH_REQUIRED) {
                        needsUser(authMethods);
                        return;
                    }
                    throw error;
                }
                String stopReason = result.optString("stopReason", "");
                // The parent's turn ends while its subagents still run; keep the agent alive for their
                // reports and the parent's own follow-up reply (see AcpSubagents).
                if ("end_turn".equals(stopReason) && !subagentCalls.isEmpty() && !cancelSent) stopReason = awaitSubagents();
                refreshSubagentCards(true);
                completed(stopReason);
            } catch (CancelledEarly cancelled) {
                finish(directory, request, "cancelled", "Cancelled before execution.", null);
            } catch (StopUnconfirmed unconfirmed) {
                diagnostic(directory, "stop-unconfirmed", "", stderr());
                finish(directory, request, "failed", "Stopping was not confirmed by the agent; its process was ended. Check the results of any actions.", null);
            } catch (AgentExited exited) {
                Integer code = waitExit();
                diagnostic(directory, "agent-exited", exited.getMessage() + " exit=" + code, stderr());
                finish(directory, request, "failed", "The on-device agent stopped unexpectedly. Check the results of any actions before retrying.", reply.length() == 0 ? null : reply.toString());
            } catch (NoResponse silent) {
                diagnostic(directory, "no-response", silent.getMessage(), stderr());
                finish(directory, request, "failed", "The on-device agent did not respond. Retry later.", null);
            } catch (AcpError error) {
                diagnostic(directory, "agent-error", error.code + " " + error.getMessage(), stderr());
                finish(directory, request, "failed", "The on-device agent reported an error. Check the results before retrying.", null);
            }
        }

        /**
         * After the parent's {@code end_turn} with subagents started: stream the agent's unsolicited
         * updates (agy wakes the parent when a subagent reports) until every started subagent reported,
         * its report was delivered and the parent answered and fell silent; or the task is cancelled
         * ({@code session/cancel}) or its fixed budget runs out. A report the silent parent never picked
         * up gets one follow-up {@code session/prompt} in the same session. Returns the stop reason.
         */
        String awaitSubagents() throws Exception {
            followUp = true;
            pendingSegment = reply.length();
            if (chat != null) chat.breakMessage();
            long now = System.nanoTime(), lastPoll = now, allReportedAt = 0, nudgedAt = 0;
            lastUpdate = now;
            long settle = TimeUnit.MILLISECONDS.toNanos(options.subagentSettleMillis);
            long nudge = TimeUnit.MILLISECONDS.toNanos(options.subagentNudgeMillis);
            subagentStatus = subagents.status();
            phase = "subagents";
            progress(subagentSummary(), true);
            refreshSubagentCards(true);
            for (; ; ) {
                boolean cancel = new File(directory, "cancel").exists();
                if (cancel || System.nanoTime() > turnDeadline) {
                    timedOut = !cancel;
                    // No prompt is pending, so nothing answers the cancel: stop, drain briefly, reclaim.
                    send(new JSONObject().put("method", "session/cancel").put("params", new JSONObject().put("sessionId", sessionId)));
                    cancelSent = true;
                    phase = "stopping";
                    progress("Stopping. Completed actions will not be automatically undone.", true);
                    long drainUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(1000, options.cancelGraceMillis));
                    while (System.nanoTime() < drainUntil) {
                        Object next = inbox.poll(50, TimeUnit.MILLISECONDS);
                        if (next == null || next == EOF || next instanceof IOException) { if (next != null) break; continue; }
                        unsolicited((String) next);
                    }
                    return "cancelled";
                }
                Object next = inbox.poll(100, TimeUnit.MILLISECONDS);
                runIdle();
                if (next == EOF) throw new AgentExited("agent closed stdout while subagents were running");
                if (next instanceof IOException) throw (IOException) next;
                if (next != null) unsolicited((String) next);
                now = System.nanoTime();
                if (now - lastPoll >= TimeUnit.MILLISECONDS.toNanos(options.subagentPollMillis)) {
                    lastPoll = now;
                    refreshSubagentCards(false);
                    int before = subagentStatus.reportedCount() * 1000 + subagentStatus.started();
                    subagentStatus = subagents.status();
                    int after = subagentStatus.reportedCount() * 1000 + subagentStatus.started();
                    if (after != before) {
                        allReportedAt = 0;
                        if (!"stopping".equals(phase)) phase = "subagents";
                        progress(subagentSummary(), true);
                    }
                }
                long quiet = now - lastUpdate;
                boolean known = subagentStatus.readable && subagentStatus.started() >= subagentCalls.size();
                if (!known) {
                    // No readable store (another agy layout): fall back to the parent's long silence.
                    if (quiet >= TimeUnit.MILLISECONDS.toNanos(options.subagentIdleMillis)) return "end_turn";
                    continue;
                }
                if (!subagentStatus.allReported()) continue;
                if (allReportedAt == 0) allReportedAt = now;
                // The parent answered the last report: reply text written after that report's file.
                boolean answered = lastChunkWall > 0 && lastChunkWall >= subagentStatus.lastReportMillis;
                if (answered && subagentStatus.allDelivered() && quiet >= settle) return "end_turn";
                if (!answered && quiet >= nudge && now - allReportedAt >= nudge) {
                    if (nudgedAt == 0) {
                        nudgedAt = now;
                        String reason = nudgeParent();
                        if (!"end_turn".equals(reason)) return reason;
                        lastUpdate = System.nanoTime();
                    } else if (now - nudgedAt >= 2 * nudge) {
                        return "end_turn"; // The parent stays silent: end with what it said.
                    }
                }
            }
        }

        /** One follow-up prompt in the same session when the parent did not pick up its subagents' reports. */
        String nudgeParent() throws Exception {
            pendingSegment = reply.length();
            if (chat != null) chat.breakMessage();
            JSONObject result = call("session/prompt", new JSONObject().put("sessionId", sessionId).put("prompt", new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text",
                            "All sub-tasks you started have reported back. Read their results and give the user the complete combined answer now, in the user's language."))));
            return result.optString("stopReason", "end_turn");
        }

        String subagentSummary() {
            int total = Math.max(subagentCalls.size(), subagentStatus == null ? 0 : subagentStatus.started());
            int done = subagentStatus == null ? 0 : subagentStatus.reportedCount();
            return done >= total && total > 0 ? "Sub-tasks finished. Writing the summary…"
                    : "Waiting for sub-tasks (" + Math.min(done, total) + "/" + total + " done)…";
        }

        /** A line that arrives outside any request: agent requests are answered, late responses dropped. */
        void unsolicited(String line) throws Exception {
            JSONObject message;
            try {
                message = new JSONObject(line);
            } catch (Exception notJson) {
                ignoredLines++;
                return;
            }
            if (message.has("id") && !message.has("method")) return;
            if (message.has("id")) agentRequest(message);
            else notification(message);
        }

        /**
         * The composer's model and reasoning effort ({@code request.model} / {@code request.effort},
         * from the ACP catalog the picker showed) applied to the session with
         * {@code session/set_config_option}, when the agent offers that value and it is not already
         * current. A refusal leaves the agent's own choice in place: the turn still runs.
         * Returns the session's configOptions after the changes (null when unknown).
         */
        final JSONArray configRefusals = new JSONArray();

        JSONArray applyModelSelection(JSONArray configOptions) throws Exception {
            if (configOptions == null) return null;
            String[][] wanted = {{AcpModelCatalog.MODEL, request.optString("model", "")},
                    {AcpModelCatalog.EFFORT, request.optString("effort", "")}};
            for (String[] pair : wanted) {
                String configId = pair[0], value = pair[1];
                if (value.isEmpty() || value.equals(AcpModelCatalog.current(configOptions, configId))
                        || !AcpModelCatalog.values(configOptions, configId).contains(value)) continue;
                try {
                    JSONObject changed = call("session/set_config_option", new JSONObject()
                            .put("sessionId", sessionId).put("configId", configId).put("value", value));
                    JSONArray next = changed.optJSONArray("configOptions");
                    if (next != null) configOptions = next;
                    else {
                        JSONObject option = AcpModelCatalog.select(configOptions, configId);
                        if (option != null) option.put("currentValue", value);
                    }
                } catch (AcpError refused) {
                    configRefusals.put(new JSONObject().put("configId", configId).put("value", value)
                            .put("code", refused.code).put("message", clip(redact(String.valueOf(refused.getMessage())), 200)));
                }
            }
            return configOptions;
        }

        /** Configured servers plus the per-task phone tool relay, in ACP's stdio declaration shape. */
        JSONArray mcpServers() throws Exception {
            JSONArray servers = new JSONArray(options.mcpServers.toString());
            JSONObject phone = options.phoneMcp;
            if (phone == null || phone.optString("command", "").isEmpty()) return servers;
            JSONArray env = new JSONArray();
            JSONObject configured = phone.optJSONObject("env");
            if (configured != null) for (java.util.Iterator<String> it = configured.keys(); it.hasNext(); ) {
                String key = it.next();
                env.put(new JSONObject().put("name", key).put("value", configured.getString(key)));
            }
            env.put(new JSONObject().put("name", "TAKU_MCP_TASK").put("value", request.getString("id")));
            // The phone tools' session identity (screens, Apps, gestures): the conversation for a chat
            // turn, as for a Codex chat turn; a stable UUID per background task otherwise.
            env.put(new JSONObject().put("name", "TAKU_MCP_THREAD").put("value", phoneThread(request)));
            env.put(new JSONObject().put("name", "TAKU_MCP_TURN").put("value", chat != null ? chat.turnId : "acp-task-" + request.getString("id")));
            env.put(new JSONObject().put("name", "TAKU_MCP_PERMISSION").put("value", permissionMode));
            JSONArray args = phone.optJSONArray("args");
            return servers.put(new JSONObject()
                    .put("name", "taku_phone")
                    .put("command", phone.getString("command"))
                    .put("args", args == null ? new JSONArray() : new JSONArray(args.toString()))
                    .put("env", env));
        }

        Integer waitExit() {
            for (int i = 0; i < 20 && connection.alive(); i++) {
                try { Thread.sleep(25); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            return connection.exitCode();
        }

        void completed(String stopReason) throws Exception {
            String text = AcpToolLabels.sanitize(reply.toString()).trim();
            // After subagents, the parent's last wake-up is the answer; its first line heads the task.
            String last = lastSegment > 0 ? AcpToolLabels.sanitize(reply.substring(Math.min(lastSegment, reply.length()))).trim() : "";
            String status, message;
            switch (stopReason) {
                case "end_turn":
                    status = "completed";
                    message = text.isEmpty() ? "This turn has ended." : headline(last.isEmpty() ? text : last);
                    break;
                case "cancelled":
                    status = timedOut ? "failed" : "cancelled";
                    message = timedOut ? "Execution timed out. Check the results of any actions." : "Stopped. Completed actions will not be automatically undone.";
                    break;
                case "refusal":
                    status = "failed";
                    message = "The agent declined this request.";
                    break;
                default:
                    status = "failed";
                    message = "The agent stopped early (" + (stopReason.isEmpty() ? "unknown" : stopReason) + "). Check the results of any actions.";
            }
            progress(message, true);
            finish(directory, request, status, message, text.isEmpty() ? null : text);
        }

        void needsUser(JSONArray methods) throws Exception {
            JSONArray ids = new JSONArray();
            boolean login = false;
            if (methods != null) for (int i = 0; i < methods.length(); i++) {
                JSONObject method = methods.optJSONObject(i);
                if (method == null) continue;
                ids.put(method.optString("id"));
                if (options.loginMethod.equals(method.optString("id"))) login = true;
            }
            TaskRecords.write(new File(directory, "acp-auth.json"), new JSONObject()
                    .put("state", "needs_user")
                    .put("methods", ids)
                    .put("loginMethod", login ? options.loginMethod : JSONObject.NULL)
                    .put("updatedAt", System.currentTimeMillis()).toString());
            finish(directory, request, "needs_user", login
                    ? "Sign in to the on-device agent first: open Taku settings and sign in with Google in this phone's browser. Your request was kept; resubmit it after signing in."
                    : "The on-device agent needs sign-in, but offers no supported sign-in method. Your request was kept.", null);
        }

        JSONObject screenshot(JSONObject init) throws Exception {
            JSONObject capabilities = init.optJSONObject("agentCapabilities");
            JSONObject prompt = capabilities == null ? null : capabilities.optJSONObject("promptCapabilities");
            File file = new File(request.optString("screenshot", ""));
            if (prompt == null || !prompt.optBoolean("image") || !file.isFile() || file.length() > MAX_SCREENSHOT_BYTES) return null;
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
            }
            return new JSONObject().put("type", "image").put("mimeType", "image/png").put("data", Base64.getEncoder().encodeToString(bytes));
        }

        // ---- JSON-RPC ---------------------------------------------------------------------------

        void send(JSONObject message) throws Exception {
            byte[] line = (message.put("jsonrpc", "2.0").toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                synchronized (out) {
                    out.write(line);
                    out.flush();
                }
            } catch (IOException broken) {
                throw new AgentExited("agent stdin closed");
            }
        }

        JSONObject call(String method, JSONObject params) throws Exception {
            long id = nextId++;
            send(new JSONObject().put("id", id).put("method", method).put("params", params));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.requestTimeoutMillis);
            for (; ; ) {
                if (inPrompt) supervise();
                else {
                    if (new File(directory, "cancel").exists()) throw new CancelledEarly();
                    if (System.nanoTime() > deadline) throw new NoResponse(method);
                }
                Object next = inbox.poll(100, TimeUnit.MILLISECONDS);
                runIdle();
                if (next == null) continue;
                if (next == EOF) throw new AgentExited("agent closed stdout during " + method);
                if (next instanceof IOException) throw (IOException) next;
                JSONObject message;
                try {
                    message = new JSONObject((String) next);
                } catch (Exception notJson) {
                    ignoredLines++; // Agents may log to stdout; protocol lines are JSON objects.
                    continue;
                }
                boolean response = message.has("id") && !message.has("method");
                if (response) {
                    if (message.optLong("id", -1) != id) continue; // A late answer to an abandoned request.
                    if (message.has("error")) {
                        JSONObject error = message.optJSONObject("error");
                        throw new AcpError(error == null ? -32603 : error.optInt("code", -32603),
                                error == null ? "error" : error.optString("message", "error"));
                    }
                    JSONObject result = message.optJSONObject("result");
                    return result == null ? new JSONObject() : result;
                }
                if (message.has("id")) agentRequest(message);
                else notification(message);
            }
        }

        /** Cancellation and the fixed turn budget; the budget is never renewed by activity. */
        void supervise() throws Exception {
            long now = System.nanoTime();
            if (!cancelSent) {
                boolean cancel = new File(directory, "cancel").exists();
                if (cancel || now > turnDeadline) {
                    timedOut = !cancel;
                    send(new JSONObject().put("method", "session/cancel").put("params", new JSONObject().put("sessionId", sessionId)));
                    cancelSent = true;
                    stopDeadline = now + TimeUnit.MILLISECONDS.toNanos(options.cancelGraceMillis);
                    phase = "stopping";
                    progress("Stopping. Completed actions will not be automatically undone.", true);
                }
            } else if (now > stopDeadline) {
                throw new StopUnconfirmed();
            }
        }

        /** At most every 150ms, even while the agent streams continuously. */
        void runIdle() {
            long now = System.nanoTime();
            if (now - lastIdle < TimeUnit.MILLISECONDS.toNanos(150)) return;
            lastIdle = now;
            try {
                idle.run();
            } catch (RuntimeException failure) {
                System.err.println("acp idle hook: " + failure);
            }
        }

        void agentRequest(JSONObject message) throws Exception {
            Object id = message.get("id");
            String method = message.optString("method");
            if ("session/request_permission".equals(method)) {
                send(new JSONObject().put("id", id).put("result", new JSONObject().put("outcome", permission(message.optJSONObject("params")))));
                return;
            }
            // We declared no fs/terminal capabilities; anything else is unsupported.
            send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32601).put("message", "Method not found: " + method)));
        }

        /** Map the task's permission mode onto the agent's offered options. */
        JSONObject permission(JSONObject params) throws Exception {
            if (cancelSent || params == null) return new JSONObject().put("outcome", "cancelled");
            JSONArray offered = params.optJSONArray("options");
            boolean allow = "full".equals(permissionMode);
            String[] preference = allow ? new String[]{"allow_once", "allow_always"} : new String[]{"reject_once", "reject_always"};
            if (offered != null) for (String kind : preference)
                for (int i = 0; i < offered.length(); i++) {
                    JSONObject option = offered.optJSONObject(i);
                    if (option != null && kind.equals(option.optString("kind"))) {
                        if (allow) permissionsAllowed++;
                        else permissionsRejected++;
                        return new JSONObject().put("outcome", "selected").put("optionId", option.optString("optionId"));
                    }
                }
            permissionsRejected++;
            return new JSONObject().put("outcome", "cancelled");
        }

        void notification(JSONObject message) throws Exception {
            if (!"session/update".equals(message.optString("method"))) return;
            JSONObject params = message.optJSONObject("params");
            if (params == null || !sessionId.equals(params.optString("sessionId"))) return;
            JSONObject update = params.optJSONObject("update");
            if (update == null) return;
            lastUpdate = System.nanoTime();
            // Children run while the parent is still in its turn: their cards follow from the first update on.
            refreshSubagentCards(false);
            switch (update.optString("sessionUpdate")) {
                case "usage_update":
                    // agy ends each model step (and each wake-up of the parent) with one.
                    if (followUp) {
                        if (chat != null) chat.breakMessage();
                        pendingSegment = reply.length();
                    }
                    break;
                case "agent_message_chunk": {
                    lastChunk = System.nanoTime();
                    lastChunkWall = System.currentTimeMillis();
                    JSONObject content = update.optJSONObject("content");
                    if (content != null && "text".equals(content.optString("type")) && reply.length() < MAX_TEXT_CHARS) {
                        if (pendingSegment >= 0 && !content.optString("text").isBlank()) {
                            lastSegment = pendingSegment;
                            pendingSegment = -1;
                        }
                        reply.append(content.optString("text"));
                        if (chat != null) chat.message(content.optString("text"));
                    }
                    if (!"stopping".equals(phase)) phase = "replying";
                    progress("Writing the result…", false);
                    break;
                }
                case "agent_thought_chunk":
                    if (!"stopping".equals(phase)) phase = "thinking";
                    progress("Thinking…", false);
                    break;
                case "tool_call": {
                    String toolId = update.optString("toolCallId");
                    AcpToolLabels.Tool tool = AcpToolLabels.identify(update.optString("name", ""), update.optString("title", ""), update.optString("kind", ""));
                    JSONObject call = new JSONObject().put("title", AcpToolLabels.label(tool, java.util.Locale.getDefault()))
                            .put("key", tool.key).put("name", tool.name)
                            .put("kind", update.optString("kind", "other")).put("status", update.optString("status", "pending"));
                    toolCalls.put(toolId, call);
                    if (chat != null) chat.tool(toolId, update);
                    subagentCall(toolId, call);
                    currentTool = call.getString("title");
                    currentToolKey = tool.key;
                    if (!"stopping".equals(phase)) phase = "tool";
                    progress("Working: " + clip(call.getString("title"), 80), true);
                    break;
                }
                case "tool_call_update": {
                    JSONObject call = toolCalls.get(update.optString("toolCallId"));
                    if (call == null) {
                        call = new JSONObject().put("title", "tool").put("kind", "other").put("status", "pending");
                        toolCalls.put(update.optString("toolCallId"), call);
                    }
                    if ((update.has("title") && !update.isNull("title")) || update.has("name")) {
                        AcpToolLabels.Tool tool = AcpToolLabels.identify(update.optString("name", ""), update.optString("title", ""), update.optString("kind", ""));
                        call.put("title", AcpToolLabels.label(tool, java.util.Locale.getDefault())).put("key", tool.key).put("name", tool.name);
                    }
                    currentTool = call.getString("title");
                    currentToolKey = call.optString("key");
                    if (update.has("status") && !update.isNull("status")) call.put("status", update.optString("status"));
                    if (chat != null) chat.tool(update.optString("toolCallId"), update);
                    subagentCall(update.optString("toolCallId"), call);
                    progress("Working: " + clip(call.getString("title"), 80), false);
                    break;
                }
                case "plan": {
                    JSONArray entries = update.optJSONArray("entries");
                    int total = entries == null ? 0 : entries.length(), done = 0;
                    for (int i = 0; i < total; i++)
                        if ("completed".equals(entries.getJSONObject(i).optString("status"))) done++;
                    plan = done + "/" + total;
                    progress("Plan " + plan + " steps done", false);
                    break;
                }
                default:
                    break;
            }
        }

        /** Counts a completed {@code invoke_subagent}: the task then waits for that subagent's report. */
        void subagentCall(String toolId, JSONObject call) {
            if ("invoke_subagent".equals(call.optString("key")) && "completed".equals(call.optString("status"))) subagentCalls.add(toolId);
        }

        /**
         * Brings the chat's subagent cards up to date with agy's store (each child's latest step, model
         * and report). Throttled to the subagent poll interval unless forced; never fails the turn.
         */
        void refreshSubagentCards(boolean force) {
            if (chat == null || subagents == null || !chat.hasSubagents()) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastCardRefresh < options.subagentPollMillis) return;
            lastCardRefresh = now;
            try {
                chat.subagents(subagents.details(), parentModel, java.util.Locale.getDefault());
            } catch (Exception unreadable) {
                System.err.println("acp subagent cards: " + unreadable);
            }
        }

        /** Throttled progress for the Dynamic Island: progress.json plus a short state message. */
        void progress(String text, boolean force) throws Exception {
            long now = System.currentTimeMillis();
            boolean changed = !text.equals(summary);
            if (!force && (!changed || now - lastProgressWrite < options.progressIntervalMillis)) return;
            summary = text;
            lastProgressWrite = now;
            int completedTools = 0;
            for (JSONObject call : toolCalls.values()) if ("completed".equals(call.optString("status"))) completedTools++;
            TaskRecords.write(new File(directory, "progress.json"), new JSONObject()
                    .put("taskId", request.getString("id"))
                    .put("runtime", "acp")
                    .put("phase", phase)
                    .put("summary", text)
                    // Titles can quote commands or paths: redacted and clipped for the island.
                    .put("tool", clip(AcpToolLabels.sanitize(redact(currentTool)), 80))
                    .put("toolKey", currentToolKey)
                    .put("toolCalls", toolCalls.size())
                    .put("toolCallsCompleted", completedTools)
                    .put("plan", plan)
                    .put("replyChars", reply.length())
                    .put("subagents", Math.max(subagentCalls.size(), subagentStatus == null ? 0 : subagentStatus.started()))
                    .put("subagentsDone", subagentStatus == null ? 0 : subagentStatus.reportedCount())
                    .put("permissionsAllowed", permissionsAllowed)
                    .put("permissionsRejected", permissionsRejected)
                    .put("updatedAt", now).toString());
            TaskRecords.state(directory, request, "running", text);
            if (changed) notifier.taskUpdated(request.getString("id"));
        }
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * The phone-tool thread identity for a task. The dedicated-screen, App and gesture registries key
     * sessions by native thread UUID ({@code [a-f0-9-]{36}}), so a chat turn uses its conversation and a
     * background task a name-based UUID of its task id.
     */
    static String phoneThread(JSONObject request) {
        return phoneThread(request.optString("conversationId", ""), request.optString("id"));
    }

    static String phoneThread(String conversationId, String taskId) {
        if (AcpConversationStore.validId(conversationId)) return conversationId;
        return java.util.UUID.nameUUIDFromBytes(("taku-acp-task:" + taskId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---- chat turns ---------------------------------------------------------------------------

    /**
     * One chat turn's projection into {@link AcpConversationStore}: the user's message is recorded
     * before the agent starts, then agent messages and tool calls stream into native-shaped items
     * (throttled), and {@link #end} records the terminal turn status.
     */
    static final class Chat {
        final ChatHost host;
        final AcpConversationStore store;
        final String conversationId, turnId, priorSession, transcript;
        final long intervalMillis;
        /** Items after the user's message, in arrival order. */
        final Map<String, JSONObject> items = new LinkedHashMap<>();
        JSONObject message;
        int messages;
        long lastFlush;
        boolean ended;
        /** Subagent card item id → agy child id, once bound (see {@link #subagents}). */
        final Map<String, String> boundChildren = new LinkedHashMap<>();

        private Chat(ChatHost host, String conversationId, String turnId, String priorSession, String transcript, long intervalMillis) {
            this.host = host;
            this.store = host.store();
            this.conversationId = conversationId;
            this.turnId = turnId;
            this.priorSession = priorSession;
            this.transcript = transcript;
            this.intervalMillis = intervalMillis;
        }

        static Chat begin(ChatHost host, JSONObject request, long intervalMillis) throws Exception {
            String conversation = request.optString("conversationId", "");
            if (host == null || conversation.isEmpty()) return null;
            if (!AcpConversationStore.validId(conversation)) throw new IOException("Invalid conversation identifier");
            AcpConversationStore store = host.store();
            String prior = store.sessionId(conversation);
            String transcript = "";
            try {
                transcript = AcpConversationStore.transcript(host.history(conversation), TRANSCRIPT_CHARS);
            } catch (Exception unavailable) {
                // No carried-over context is better than no turn.
            }
            String turn = store.beginTurn(conversation, request.getString("id"), request.getString("prompt"));
            Chat chat = new Chat(host, conversation, turn, prior, transcript, intervalMillis);
            host.changed(conversation);
            return chat;
        }

        void bind(String sessionId, JSONObject agentInfo) throws Exception {
            store.bindSession(conversationId, sessionId, agentInfo);
        }

        /** The next text starts a new message item (a wake-up of the parent is a new message). */
        void breakMessage() {
            message = null;
        }

        void message(String chunk) throws Exception {
            if (message == null) {
                message = new JSONObject().put("type", "agentMessage").put("id", turnId + "-msg-" + (++messages)).put("text", "");
                items.put(message.getString("id"), message);
            }
            if (message.optString("text").length() < MAX_TEXT_CHARS) message.put("text", message.optString("text") + chunk);
            flush(false);
        }

        void tool(String toolCallId, JSONObject update) throws Exception {
            String id = turnId + "-tool-" + (toolCallId == null || toolCallId.isEmpty() ? "unknown" : toolCallId);
            JSONObject item = items.get(id);
            if (item == null) {
                item = new JSONObject().put("type", "mcpToolCall").put("id", id).put("server", "agy")
                        .put("tool", "tool").put("status", "inProgress");
                items.put(id, item);
                // Text after a tool call is a new message, so the order of events is kept.
                message = null;
            }
            if ((update.has("title") && !update.isNull("title")) || update.has("name")) {
                // The chat page shows "title"; "tool" is the canonical id it translates
                // (taku_phone__<tool> with namespace taku_phone, else the agent's tool name), and
                // "toolName" the agent's own name, for diagnostics. agy's terminal-style title can hold
                // internal paths ("File ../acp/home/.gemini/…"), so it is never shown.
                AcpToolLabels.Tool tool = AcpToolLabels.identify(update.optString("name", ""), update.optString("title", ""), update.optString("kind", ""));
                item.put("tool", tool.canonical()).put("toolName", clip(tool.name, 120))
                        .put("title", AcpToolLabels.label(tool, java.util.Locale.getDefault()));
                if (tool.phone) item.put("namespace", "taku_phone");
                else item.remove("namespace");
            }
            if (update.has("kind") && !update.isNull("kind")) item.put("kind", update.optString("kind"));
            if (update.has("status") && !update.isNull("status")) item.put("status", toolStatus(update.optString("status")));
            Object input = update.opt("rawInput");
            if (input instanceof JSONObject) {
                String raw = AcpToolLabels.sanitize(redact(input.toString()));
                item.put("arguments", raw.length() <= 4000 ? new JSONObject(raw) : new JSONObject().put("summary", clip(raw, 4000)));
            }
            if ("invoke_subagent".equals(item.optString("toolName"))) {
                // A task card for the child: the page shows it instead of a generic activity row.
                JSONObject card = item.optJSONObject("subagent");
                int ordinal = card != null ? 0 : 1 + subagentCards().size();
                card = AcpSubagentCards.fromArguments(card, input instanceof JSONObject ? (JSONObject) input : null, ordinal, System.currentTimeMillis());
                if (update.has("status") && !update.isNull("status")) AcpSubagentCards.toolStatus(card, update.optString("status"), System.currentTimeMillis());
                item.put("subagent", card);
            }
            JSONArray content = update.optJSONArray("content");
            if (content != null) {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    JSONObject inner = part == null ? null : part.optJSONObject("content");
                    if (inner != null && "text".equals(inner.optString("type"))) {
                        if (out.length() > 0) out.append('\n');
                        out.append(inner.optString("text"));
                    }
                }
                if (out.length() > 0) item.put("result", new JSONObject().put("content",
                        new JSONArray().put(new JSONObject().put("type", "text").put("text", clip(AcpToolLabels.sanitize(redact(out.toString())), 4000)))));
            }
            if ("failed".equals(item.optString("status")) && !item.has("error")) item.put("error", toolError(item, update));
            flush(true);
        }

        /**
         * A failed tool call's {@code error} ({@code {code, message}}, the message redacted and clipped),
         * so the stored turn says why instead of a bare "failed". Sources, best first: the agent's own
         * error text ({@code rawOutput}, then the item's result text); for taku_phone tools the failure
         * this broker's {@link PhoneMcpBridge} returned in this turn (agy drops the MCP error text from
         * {@code tool_call_update}); otherwise {@code TOOL_FAILED} with a generic message.
         */
        JSONObject toolError(JSONObject item, JSONObject update) throws Exception {
            String text = outputText(update.opt("rawOutput"));
            if (text.isEmpty()) {
                JSONObject result = item.optJSONObject("result");
                JSONArray content = result == null ? null : result.optJSONArray("content");
                JSONObject first = content == null ? null : content.optJSONObject(0);
                if (first != null) text = first.optString("text");
            }
            String phoneTool = phoneToolName(item.optString("toolName", item.optString("tool")));
            if (text.isBlank() && phoneTool != null) {
                JSONObject recorded = PhoneMcpBridge.Failures.take(turnId, phoneTool);
                if (recorded != null) return new JSONObject().put("code", recorded.optString("code", "TOOL_FAILED"))
                        .put("message", recorded.optString("message")).put("source", "phone-mcp");
            }
            if (text.isBlank())
                return new JSONObject().put("code", "TOOL_FAILED").put("message", "The agent reported this tool failed without details.")
                        .put("source", "agent");
            String code = PhoneMcpBridge.Failures.code(text);
            if (phoneTool != null) PhoneMcpBridge.Failures.take(turnId, phoneTool);
            return new JSONObject().put("code", code == null ? "TOOL_FAILED" : code)
                    .put("message", clip(AcpToolLabels.sanitize(redact(text.trim())), 300)).put("source", "agent");
        }

        /** {@code taku_phone} tool name in an agent tool title such as "Run mcp_taku_phone_device_info". */
        static String phoneToolName(String title) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:mcp_)?taku_phone(?:__|_|\\.|/)([a-z_]+)").matcher(title == null ? "" : title);
            return m.find() ? m.group(1) : null;
        }

        /** Text of an ACP {@code rawOutput}: a string, {@code {error|message}}, or MCP {@code {content:[{text}]}}. */
        static String outputText(Object raw) {
            if (raw instanceof String) return (String) raw;
            if (!(raw instanceof JSONObject)) return "";
            JSONObject o = (JSONObject) raw;
            Object error = o.opt("error");
            if (error instanceof String) return (String) error;
            if (error instanceof JSONObject) return ((JSONObject) error).optString("message", error.toString());
            JSONArray content = o.optJSONArray("content");
            if (content != null) for (int i = 0; i < content.length(); i++) {
                JSONObject part = content.optJSONObject(i);
                if (part != null && !part.optString("text").isEmpty()) return part.optString("text");
            }
            return o.optString("message", "");
        }

        static String toolStatus(String acp) {
            switch (acp) {
                case "completed": return "completed";
                case "failed": return "failed";
                default: return "inProgress";
            }
        }

        boolean hasSubagents() {
            return !subagentCards().isEmpty();
        }

        /** Items that carry a subagent card, in dispatch order. */
        List<JSONObject> subagentCards() {
            List<JSONObject> out = new java.util.ArrayList<>();
            for (JSONObject item : items.values()) if (item.optJSONObject("subagent") != null) out.add(item);
            return out;
        }

        /**
         * Binds each card to an agy child (same role first, then dispatch order) and merges the child's
         * state into it; the turn is rewritten only when a card changed.
         */
        void subagents(List<AcpSubagents.Child> children, String parentModel, java.util.Locale locale) throws Exception {
            List<JSONObject> cards = subagentCards();
            Map<String, AcpSubagents.Child> byId = new LinkedHashMap<>();
            for (AcpSubagents.Child child : children) byId.put(child.id, child);
            java.util.Set<String> taken = new java.util.HashSet<>(boundChildren.values());
            for (int pass = 0; pass < 2; pass++) for (JSONObject item : cards) {
                String key = item.getString("id");
                if (boundChildren.containsKey(key)) continue;
                String role = item.getJSONObject("subagent").optString("role").trim();
                for (AcpSubagents.Child child : children) {
                    if (taken.contains(child.id)) continue;
                    if (pass == 0 && (role.isEmpty() || !role.equalsIgnoreCase(AcpSubagentCards.clean(child.role, AcpSubagentCards.MAX_TITLE).replace('\n', ' ').trim()))) continue;
                    boundChildren.put(key, child.id);
                    taken.add(child.id);
                    break;
                }
            }
            boolean changed = false;
            for (JSONObject item : cards) {
                AcpSubagents.Child child = byId.get(boundChildren.get(item.getString("id")));
                if (child != null && AcpSubagentCards.merge(item.getJSONObject("subagent"), child, parentModel, locale)) changed = true;
            }
            if (changed) flush(true);
        }

        void flush(boolean force) throws Exception {
            long now = System.currentTimeMillis();
            if (!force && now - lastFlush < intervalMillis) return;
            lastFlush = now;
            store.updateTurn(conversationId, turnId, snapshot(false), "inProgress");
            host.changed(conversationId);
        }

        JSONArray snapshot(boolean terminal) throws Exception {
            JSONArray out = new JSONArray();
            String last = "";
            if (terminal) for (JSONObject item : items.values())
                if ("agentMessage".equals(item.optString("type")) && !item.optString("text").isBlank()) last = item.getString("id");
            for (JSONObject item : items.values()) {
                JSONObject copy = new JSONObject(item.toString());
                // Agent text can link internal files and subagent conversations (file:///…/.gemini/…,
                // conversation://…); the whole text is sanitized because chunks split links anywhere.
                if ("agentMessage".equals(copy.optString("type"))) copy.put("text", AcpToolLabels.sanitize(copy.optString("text")));
                if (terminal && "agentMessage".equals(copy.optString("type")))
                    copy.put("phase", copy.getString("id").equals(last) ? "final_answer" : "commentary");
                if (terminal && "mcpToolCall".equals(copy.optString("type")) && "inProgress".equals(copy.optString("status")))
                    copy.put("status", "interrupted");
                out.put(copy);
            }
            return out;
        }

        /** Task status → native turn status; the reply text, if the agent sent none as chunks. */
        void end(String taskStatus, String reply) throws Exception {
            if (ended) return;
            ended = true;
            if (messages == 0 && reply != null && !reply.isBlank() && "completed".equals(taskStatus)) message(reply);
            long now = System.currentTimeMillis();
            for (JSONObject item : subagentCards()) AcpSubagentCards.end(item.getJSONObject("subagent"), now);
            String status = "completed".equals(taskStatus) ? "completed" : "cancelled".equals(taskStatus) ? "interrupted" : "failed";
            store.updateTurn(conversationId, turnId, snapshot(true), status);
            host.changed(conversationId);
        }
    }

    static String headline(String text) {
        String first = text.strip();
        int newline = first.indexOf('\n');
        if (newline > 0) first = first.substring(0, newline).strip();
        return clip(first, 240);
    }
}
