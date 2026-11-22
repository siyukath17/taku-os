package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent Client Protocol (ACP) client runtime: drives a local ACP agent (the on-device
 * {@code android_arm64} {@code agy acp}) over newline-delimited JSON-RPC 2.0 on stdio.
 *
 * <p>Lifecycle per task: a warm agent process from {@link AcpAgentPool} (already past
 * {@code initialize} and agy's backend boot) or a cold one → {@code initialize} (cold only) →
 * {@code session/new} (with the phone MCP server declarations; an
 * {@code auth_required} error becomes {@code needs_user}) → {@code session/prompt} while
 * {@code session/update} notifications stream progress → {@code session/cancel} when the task's
 * {@code cancel} file appears or the turn budget runs out → the process goes back to the pool when the
 * turn ended with the agent idle, otherwise it is destroyed (a crash never leaks into the next task).
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
    /**
     * The broker's root-only filesystem socket for the phone MCP host (see PhoneMcpHost). When it
     * exists, the {@code taku_phone} server is a native {@code sh | toybox nc -U} relay (a few ms)
     * instead of a {@code app_process} Java relay (~750ms of JVM start per session).
     */
    static final String PHONE_MCP_SOCKET = "/data/local/agentos/acp/run/phone-mcp.sock";
    private static final int MAX_LINE_CHARS = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 1024 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 3_000_000;
    /** The native phone MCP relay: the handshake line, then stdio both ways over the broker's socket. */
    static final String NATIVE_RELAY_SCRIPT =
            "{ printf '%s\\n' \"$TAKU_MCP_HELLO\"; exec cat; } | exec @TOYBOX@ nc -U \"$TAKU_MCP_SOCKET\"";

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
        /**
         * Use the native relay ({@link #phoneMcpSocket} through {@link #toybox} nc) while the socket
         * exists; the Java {@link #phoneMcp} relay otherwise. Off when runtime.json sets its own phoneMcp.
         */
        boolean phoneMcpNative = true;
        File phoneMcpSocket = new File(PHONE_MCP_SOCKET);
        String toybox = "/system/bin/toybox";
        String shell = "/system/bin/sh";
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
         * Bounded automatic continuation after an external, transient failure (no response, the agent
         * exiting on a network error, an agent 429/503/UNAVAILABLE/DEADLINE_EXCEEDED). agy retries model
         * calls itself (its RetryPolicy); this only resumes a turn the agent process could not finish.
         */
        int autoContinueMax = 2;
        /** Delay before each automatic continuation; the last value repeats. */
        List<Long> autoContinueBackoffMillis = new ArrayList<>(List.of(2000L, 8000L));
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
            if (c.has("phoneMcp")) {
                o.phoneMcp = c.optJSONObject("phoneMcp");
                o.phoneMcpNative = false;
            }
            o.phoneMcpNative = c.optBoolean("phoneMcpNative", o.phoneMcpNative);
            o.turnLimitMillis = c.optLong("turnLimitMillis", o.turnLimitMillis);
            o.cancelGraceMillis = c.optLong("cancelGraceMillis", o.cancelGraceMillis);
            o.requestTimeoutMillis = c.optLong("requestTimeoutMillis", o.requestTimeoutMillis);
            o.progressIntervalMillis = c.optLong("progressIntervalMillis", o.progressIntervalMillis);
            o.autoApprove = c.optBoolean("autoApprove", o.autoApprove);
            o.subagentSettleMillis = c.optLong("subagentSettleMillis", o.subagentSettleMillis);
            o.subagentNudgeMillis = c.optLong("subagentNudgeMillis", o.subagentNudgeMillis);
            o.subagentIdleMillis = c.optLong("subagentIdleMillis", o.subagentIdleMillis);
            o.subagentPollMillis = c.optLong("subagentPollMillis", o.subagentPollMillis);
            o.autoContinueMax = Math.max(0, Math.min(5, c.optInt("autoContinueMax", o.autoContinueMax)));
            JSONArray backoff = c.optJSONArray("autoContinueBackoffMillis");
            if (backoff != null && backoff.length() > 0) {
                o.autoContinueBackoffMillis = new ArrayList<>();
                for (int i = 0; i < backoff.length(); i++) o.autoContinueBackoffMillis.add(Math.max(0, backoff.optLong(i, 0)));
            }
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
    /** Warm agent processes shared across tasks; null starts (and destroys) one process per task. */
    private final AcpAgentPool pool;

    AcpRuntime(Options options, AcpProcessTransport transport, Notifier notifier, Runnable idle) {
        this(options, transport, notifier, idle, null);
    }

    AcpRuntime(Options options, AcpProcessTransport transport, Notifier notifier, Runnable idle, ChatHost chatHost) {
        this(options, transport, notifier, idle, chatHost, null);
    }

    AcpRuntime(Options options, AcpProcessTransport transport, Notifier notifier, Runnable idle, ChatHost chatHost, AcpAgentPool pool) {
        this.options = options;
        this.transport = transport;
        this.notifier = notifier == null ? id -> {} : notifier;
        this.idle = idle == null ? () -> {} : idle;
        this.chatHost = chatHost;
        this.pool = pool;
    }

    /**
     * Starts a warm agent process in the background (see {@link AcpAgentPool#prewarm}) for the command
     * line most chat turns use (full access); never blocks. A no-op without a pool or a binary.
     */
    void prewarm(String reason) {
        if (pool == null || options.binary.isEmpty()) return;
        try {
            for (String path : new String[]{options.home, options.cwd})
                if (!path.isEmpty()) new File(path).mkdirs();
            pool.prewarm(launch(new JSONObject().put("permissionMode", "full")), reason);
        } catch (Exception unavailable) {
            System.err.println("acp prewarm skipped: " + unavailable);
        }
    }

    /** The {@code initialize} parameters, shared by turns and the pool's warm-up. */
    static JSONObject initializeParams() throws Exception {
        return new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("clientCapabilities", new JSONObject()
                        .put("fs", new JSONObject().put("readTextFile", false).put("writeTextFile", false))
                        .put("terminal", false))
                .put("clientInfo", new JSONObject().put("name", "taku-os").put("version", "1"));
    }

    private AcpAgentPool.Agent startAgent(AcpProcessTransport.Launch launch) throws IOException {
        if (pool != null) {
            AcpAgentPool.Agent warm = pool.take(launch, idle);
            if (warm != null) return warm;
            return pool.startCold(launch);
        }
        return new AcpAgentPool.Agent(transport.start(launch), "", "");
    }

    private void releaseAgent(AcpAgentPool.Agent agent, boolean reusable) {
        if (pool != null) pool.release(agent, reusable);
        else agent.destroy(Math.min(2000, options.cancelGraceMillis));
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
        // Nobody can answer a terminal prompt: git must fail fast instead of blocking the turn on
        // a username/password read (seen with a broken credential helper on the Pixel).
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
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
        // The turn owns its phone thread from here until finish(): its gesture owner and background
        // screen are released when it ends, not when an MCP connection closes (a warm agent keeps it).
        phoneThread = phoneThread(request);
        phoneTurn = chat != null ? chat.turnId : "acp-task-" + request.getString("id");
        AcpPhoneTurns.begin(phoneThread, phoneTurn);
        try {
            runTurns(directory, request, chat);
        } finally {
            endPhoneTurn();
        }
    }

    private String phoneThread = "", phoneTurn = "";

    /** Closes the task's phone turn once (finish() does it before {@code done}; run() as a backstop). */
    private void endPhoneTurn() {
        if (phoneTurn.isEmpty()) return;
        String thread = phoneThread, turn = phoneTurn;
        phoneTurn = "";
        AcpPhoneTurns.end(thread, turn);
    }

    private void runTurns(File directory, JSONObject request, Chat chat) throws Exception {
        AcpAgentPool.Agent agent;
        try {
            // Best effort: a missing working directory would otherwise fail the start opaquely.
            // appDataDir is relative to $HOME/.gemini and created by the agent itself.
            for (String path : new String[]{options.home, options.cwd})
                if (!path.isEmpty()) new File(path).mkdirs();
            agent = startAgent(launch(request));
        } catch (IOException startFailed) {
            diagnostic(directory, "start-failed", startFailed.getMessage(), "");
            if (chat != null) chat.end("failed", null);
            finish(directory, request, "failed", "The on-device agent could not start. Check its installation, then retry.", null);
            return;
        }
        long budgetEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.turnLimitMillis);
        JSONObject attemptRequest = request;
        String resume = chat == null ? "" : chat.priorSession;
        activeChat = chat;
        try {
            for (int attempt = 0; ; attempt++) {
                Turn turn = new Turn(directory, attemptRequest, agent, chat, attempt, resume, budgetEnd);
                activeTurn = turn;
                Retry retry;
                try {
                    retry = turn.execute();
                } finally {
                    releaseAgent(agent, turn.reusable);
                    // A stop agy never confirmed: no process of this launch is trusted any more.
                    if (turn.stopUnconfirmed && pool != null) pool.invalidate("stop-unconfirmed");
                }
                if (retry == null) return;
                // An external, transient failure with continuations left: wait, then resume the same
                // turn in a fresh agent process (session/load of the interrupted session).
                if (!turn.sessionId.isEmpty()) resume = turn.sessionId;
                attemptRequest = retry.promptSent ? continuationRequest(request, "auto", attempt + 1) : request;
                if (!backoff(directory, request, attempt, turn)) return;
                // The continuation's text is a new message after whatever the interrupted attempt said.
                if (chat != null) chat.breakMessage();
                try {
                    if (pool != null) pool.invalidate("transient-failure");
                    agent = pool != null ? pool.startCold(launch(attemptRequest))
                            : new AcpAgentPool.Agent(transport.start(launch(attemptRequest)), "", "");
                } catch (IOException startFailed) {
                    diagnostic(directory, "start-failed", startFailed.getMessage(), "");
                    finish(directory, request, "failed", "The on-device agent could not start. Check its installation, then retry.", null);
                    return;
                }
            }
        } finally {
            activeChat = null;
            activeTurn = null;
            // A path that ended without finish() (an unexpected exception) still closes the turn.
            if (chat != null && !chat.ended) {
                try { chat.end("failed", null); }
                catch (Exception projection) { System.err.println("acp chat end ignored: " + projection); }
            }
        }
    }

    /** Sent by {@link Turn#execute} when an external, transient failure leaves continuations to try. */
    static final class Retry {
        final boolean promptSent;

        Retry(boolean promptSent) {
            this.promptSent = promptSent;
        }
    }

    /**
     * The wait before automatic continuation {@code attempt + 1}, shown on the island; a cancel during
     * the wait stops the task. Returns false when the task was finished instead.
     */
    private boolean backoff(File directory, JSONObject request, int attempt, Turn turn) throws Exception {
        List<Long> delays = options.autoContinueBackoffMillis;
        long delay = delays.isEmpty() ? 0 : delays.get(Math.min(attempt, delays.size() - 1));
        String text = continuingText(request, attempt + 1, options.autoContinueMax);
        turn.phase = "continuing";
        turn.progress(text, true);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        while (System.nanoTime() < until) {
            if (new File(directory, "cancel").exists()) {
                finish(directory, request, "cancelled", "Stopped. Completed actions will not be automatically undone.", null);
                return false;
            }
            try { idle.run(); } catch (RuntimeException failure) { System.err.println("acp idle hook: " + failure); }
            Thread.sleep(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))));
        }
        if (new File(directory, "cancel").exists()) {
            finish(directory, request, "cancelled", "Stopped. Completed actions will not be automatically undone.", null);
            return false;
        }
        return true;
    }

    static String continuingText(JSONObject request, int attempt, int max) {
        String locale = request.optString("responseLocale", java.util.Locale.getDefault().toLanguageTag());
        return locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh")
                ? "网络波动，正在继续（" + attempt + "/" + max + "）…"
                : "Network hiccup, continuing (" + attempt + "/" + max + ")…";
    }

    /** The system-level instruction that resumes an interrupted turn in its own session. */
    static final String CONTINUE_PROMPT =
            "上一轮在执行中被中断，请基于已有对话和工具执行结果从中断处继续完成原任务，不要重复已成功的步骤；对有副作用的操作先核对现状再执行。\n"
                    + "(The previous turn was interrupted mid-run. Using the conversation and tool results so far, continue the original task from where it stopped; "
                    + "do not repeat steps that already succeeded, and check the current state before any action with side effects.)";

    /** A copy of {@code request} that continues task {@code ofTaskId} (same id; the user's text is not resent). */
    static JSONObject continuationRequest(JSONObject request, String reason, int attempt) throws Exception {
        JSONObject copy = new JSONObject(request.toString());
        JSONObject continuation = new JSONObject().put("ofTaskId", request.optString("id")).put("reason", reason);
        if (attempt > 0) continuation.put("attempt", attempt);
        copy.put("continuation", continuation);
        copy.remove("screenshot");
        return copy;
    }

    /**
     * Whether a failure is external and transient, so the turn may be resumed automatically. Never:
     * a user cancel, sign-in, an unconfirmed stop, or anything raised inside the broker.
     */
    static boolean transientFailure(IOException failure, String stderr) {
        if (failure instanceof NoResponse) return true;
        if (failure instanceof AgentExited)
            return NETWORK_STDERR.matcher(stderr == null ? "" : stderr).find();
        if (failure instanceof AcpError) {
            AcpError error = (AcpError) failure;
            if (error.code == AUTH_REQUIRED) return false;
            return TRANSIENT_AGENT_ERROR.matcher(String.valueOf(error.getMessage())).find();
        }
        return false;
    }

    private static final java.util.regex.Pattern NETWORK_STDERR = java.util.regex.Pattern.compile(
            "UNAVAILABLE|(?i:connection reset|connection refused|broken pipe|\\bEOF\\b|proxy|tls handshake|i/o timeout|network is unreachable|no route to host)");
    private static final java.util.regex.Pattern TRANSIENT_AGENT_ERROR = java.util.regex.Pattern.compile(
            "\\b(?:429|503)\\b|UNAVAILABLE|DEADLINE_EXCEEDED");

    // ---- terminal records -------------------------------------------------------------------

    /** The chat turn of the task currently running on this runtime instance, if any. */
    private Chat activeChat;
    /** The agent process conversation currently running on this runtime instance, if any. */
    private Turn activeTurn;

    private void finish(File directory, JSONObject request, String status, String message, String reply) throws Exception {
        // Steers still waiting for agy are settled before the turn and the task close ("done").
        if (activeTurn != null) activeTurn.closeSteers();
        // Agent text can link or quote agent-internal paths; never shown or stored in results.
        if (reply != null) reply = AcpToolLabels.sanitize(reply);
        // The conversation shows the turn's end before the task record does.
        if (activeChat != null && !activeChat.ended) {
            try {
                activeChat.end(status, reply);
            } catch (Exception projection) {
                // The task's own records below still close it.
                System.err.println("acp chat end ignored: " + projection);
            }
        }
        if (request.has("conversationId") && chatHost != null) {
            // Chat turns use the chat result contract (as CodexAppServer does): the readable reply as
            // plain text, written only for a completed turn; the state message carries everything else.
            if ("completed".equals(status)) TaskRecords.write(new File(directory, "result.txt"), reply == null ? "" : reply);
        } else if (reply != null || !"cancelled".equals(status))
            TaskRecords.write(new File(directory, "result.txt"), new JSONObject()
                    .put("status", "cancelled".equals(status) ? "failed" : status)
                    .put("message", reply == null || reply.isEmpty() ? message : reply)
                    .put("evidence", new JSONArray()).toString());
        // The turn's background screen and gesture owner are released before the task closes.
        endPhoneTurn();
        TaskRecords.state(directory, request, status, message);
        TaskRecords.write(new File(directory, "done"), status);
        notifier.taskUpdated(request.getString("id"));
    }

    private static void diagnostic(File directory, String type, String detail, String stderrTail) throws Exception {
        // Plain text, not JSON: the stderr tail keeps its newest end; JSONObject escapes both values.
        TaskRecords.write(new File(directory, "diagnostic.json"), new JSONObject()
                .put("runtime", "acp").put("type", type)
                .put("detail", clip(redact(detail == null ? "" : detail), MAX_REDACTED_CHARS))
                .put("stderrTail", tail(redact(stderrTail == null ? "" : stderrTail), MAX_REDACTED_CHARS)).toString());
    }

    /** Longest single redacted value kept in records and the chat projection. */
    static final int MAX_REDACTED_CHARS = 4000;

    /**
     * Hides anything that looks like a credential in one piece of plain text. It never truncates
     * and must never be run over serialized JSON: replacing text can eat escapes and break the
     * document (an {@code echo "… TOKEN ==="} command did, as JSONException, on the Pixel). For a
     * structure, use {@link #redactJson}, which redacts each string value separately.
     */
    static String redact(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[hidden]")
                .replaceAll("ya29\\.[A-Za-z0-9_.-]+", "[hidden]")
                .replaceAll("1//[A-Za-z0-9_-]{20,}", "[hidden]")
                .replaceAll("AIza[A-Za-z0-9_-]{30,}", "[hidden]")
                .replaceAll("gh[pousr]_[A-Za-z0-9]{20,}", "[hidden]")
                .replaceAll("(?i)(Bearer\\s+|(?:access_token|refresh_token|id_token|token|secret|password|api_key|code)[\"']?\\s*[=:]\\s*[\"']?)[^\\s&\"',}]+", "$1[hidden]");
    }

    /** Object keys whose string values are credentials however they look. */
    private static final java.util.regex.Pattern SECRET_KEY = java.util.regex.Pattern.compile(
            "(?i).*(?:token|secret|password|passwd|api_?key|authorization|cookie|credential).*");

    /**
     * A redacted deep copy of a JSON value: every string value is {@link #redact}ed (values under
     * credential-named keys are hidden whole), sanitized of agent-internal paths and clipped at its
     * head to {@code maxValueChars}; structure, numbers and booleans are kept, so the result is
     * always a valid JSON value.
     */
    static Object redactJson(Object value, int maxValueChars) {
        if (value instanceof JSONObject) {
            JSONObject in = (JSONObject) value, out = new JSONObject();
            for (java.util.Iterator<String> it = in.keys(); it.hasNext(); ) {
                String key = it.next();
                Object child = in.opt(key);
                try {
                    out.put(key, child instanceof String && SECRET_KEY.matcher(key).matches() && !((String) child).isEmpty()
                            ? "[hidden]" : redactJson(child, maxValueChars));
                } catch (Exception impossible) {
                    // A finite, non-null value always fits; skip anything that does not.
                }
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray in = (JSONArray) value, out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(redactJson(in.opt(i), maxValueChars));
            return out;
        }
        if (value instanceof String) return clip(AcpToolLabels.sanitize(redact((String) value)), maxValueChars);
        return value == null ? JSONObject.NULL : value;
    }

    static String tail(String text, int max) {
        if (text.length() <= max) return text;
        int start = text.length() - max + 1;
        if (Character.isLowSurrogate(text.charAt(start))) start++;
        return "…" + text.substring(start);
    }

    // ---- protocol errors ----------------------------------------------------------------------

    static final class AcpError extends IOException {
        final int code;

        AcpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class AgentExited extends IOException {
        AgentExited(String detail) { super(detail); }
    }

    private static final class CancelledEarly extends IOException {
        CancelledEarly() { super("cancelled"); }
    }

    private static final class StopUnconfirmed extends IOException {
        StopUnconfirmed() { super("stop unconfirmed"); }
    }

    static final class NoResponse extends IOException {
        NoResponse(String method) { super("no response to " + method); }
    }

    // ---- steering -----------------------------------------------------------------------------

    /** A steer agy has not refused within this long was taken into the turn (refusals come at once). */
    static final long STEER_ACCEPT_MILLIS = 1500;
    /** Larger images are passed by path instead of inline. */
    static final long MAX_ATTACHMENT_IMAGE_BYTES = 10_000_000L;

    /** One steer's {@code session/prompt} awaiting agy's answer. */
    static final class Steer {
        final String requestId;
        final long id;
        final long sentAt = System.nanoTime();
        boolean accepted;

        Steer(String requestId, long id) {
            this.requestId = requestId;
            this.id = id;
        }
    }

    /**
     * agy's refusal of a steer prompt (agy 1.5.14, -32603 "Can't add this prompt to session …: the
     * running prompt is waiting for a permission or question answer; …" / "… is being cancelled; …")
     * as the error code the chat page explains.
     */
    static String steerRejection(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("waiting for a permission") || m.contains("question answer")) return AcpTurnControl.STEER_WAITING_ANSWER;
        if (m.contains("being cancelled") || m.contains("being canceled")) return AcpTurnControl.STEER_CANCELLING;
        return AcpTurnControl.STEER_REJECTED;
    }

    private static String readSmall(File f) throws IOException {
        if (f.length() > 1_000_000) throw new IOException("INVALID_ARGUMENT");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---- one task's conversation with the agent -------------------------------------------------

    /** One chat projection step (see {@code Turn#display}). */
    interface DisplayStep {
        void run() throws Exception;
    }

    private static final Object EOF = AcpAgentPool.EOF;

    private final class Turn {
        final File directory;
        final JSONObject request;
        final AcpAgentPool.Agent agent;
        final AcpProcessTransport.Connection connection;
        final BlockingQueue<Object> inbox;
        final StringBuilder reply = new StringBuilder();
        final Map<String, JSONObject> toolCalls = new LinkedHashMap<>();
        final String permissionMode;
        String sessionId = "";
        /** The turn ended with the agent idle: its process may serve the next turn (see AcpAgentPool). */
        boolean reusable;
        /** session/cancel was never answered: the process was given up. */
        boolean stopUnconfirmed;
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
        /** 0 for the task's first agent process, n for its n-th automatic continuation. */
        final int attempt;
        /** The agent session to load first (the conversation's, or the interrupted attempt's). */
        final String resumeSession;
        /** The task's whole turn budget ends here, across automatic continuations. */
        final long budgetEnd;
        /** Unexpected display-layer failures (chat projection), counted, never fatal to the turn. */
        int ignored;
        /**
         * Steering: user messages sent into the running turn as further {@code session/prompt}s of the
         * same session, by JSON-RPC id, until agy answers them (see {@link #pollSteers}).
         */
        final Map<Long, Steer> pendingSteers = new LinkedHashMap<>();
        /** The agent accepts image prompt blocks ({@code promptCapabilities.image}). */
        boolean promptImages;

        Turn(File directory, JSONObject request, AcpAgentPool.Agent agent, Chat chat,
             int attempt, String resumeSession, long budgetEnd) {
            this.attempt = attempt;
            this.resumeSession = resumeSession == null ? "" : resumeSession;
            this.budgetEnd = budgetEnd;
            this.chat = chat;
            this.directory = directory;
            this.request = request;
            this.agent = agent;
            this.connection = agent.connection;
            this.inbox = agent.inbox;
            this.permissionMode = request.optString("permissionMode", "full");
            this.lastIdle = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        }

        String stderr() {
            return agent.stderr();
        }

        /**
         * Runs one agent process's part of the task. Returns null once the task is finished, or a
         * {@link Retry} when an external, transient failure leaves an automatic continuation to try.
         */
        Retry execute() throws Exception {
            try {
                return executeTurn();
            } finally {
                closeSteers();
            }
        }

        Retry executeTurn() throws Exception {
            String id = request.getString("id");
            try {
                // A warm process already did the handshake (and booted agy's backend).
                boolean warm = agent.init != null;
                JSONObject init = warm ? agent.init : call("initialize", initializeParams());
                agent.init = init;
                if (init.optInt("protocolVersion", -1) != PROTOCOL_VERSION) {
                    diagnostic(directory, "protocol-version", init.optString("protocolVersion"), stderr());
                    finish(directory, request, "failed", "The on-device agent speaks an unsupported ACP version. Update the ROM's agent, then retry.", null);
                    return null;
                }
                JSONArray authMethods = init.optJSONArray("authMethods");
                JSONObject capabilities = init.optJSONObject("agentCapabilities");
                boolean resumed = false;
                String loadFailure = "";
                // A chat (or an automatic continuation) resumes its previous agent session when the
                // agent can load it.
                if (!resumeSession.isEmpty()) {
                    if (capabilities != null && capabilities.optBoolean("loadSession")) {
                        try {
                            // History replayed by session/load arrives while sessionId is still empty,
                            // so notification() ignores it: the store already holds those turns.
                            call("session/load", new JSONObject()
                                    .put("sessionId", resumeSession)
                                    .put("cwd", options.cwd)
                                    .put("mcpServers", mcpServers()));
                            sessionId = resumeSession;
                            resumed = true;
                        } catch (AcpError error) {
                            if (error.code == AUTH_REQUIRED) {
                                needsUser(authMethods);
                                return null;
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
                            return null;
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
                        .put("turnStarted", true)
                        .put("agentProcess", warm ? "warm" : "cold");
                String model = AcpModelCatalog.current(configOptions, AcpModelCatalog.MODEL);
                String effort = AcpModelCatalog.current(configOptions, AcpModelCatalog.EFFORT);
                if (!model.isEmpty()) info.put("model", model);
                if (!effort.isEmpty()) info.put("effort", effort);
                parentModel = model;
                if (configRefusals.length() > 0) info.put("configRefusals", configRefusals);
                JSONObject continuation = request.optJSONObject("continuation");
                String original = request.optString("prompt", "");
                String text = original;
                if (continuation != null) {
                    info.put("continuation", continuation);
                    // A resumed session already holds the interrupted turn; otherwise the instruction
                    // follows the original request (an automatic continuation still carries it).
                    text = resumed || original.isBlank() ? CONTINUE_PROMPT
                            : "Original request:\n" + original + "\n\n" + CONTINUE_PROMPT;
                }
                if (chat != null) {
                    String bound = sessionId;
                    display("bind", () -> chat.bind(bound, init.optJSONObject("agentInfo")));
                    // The chat page reads threadId/turnId like a Codex chat turn's runtime-info.
                    info.put("threadId", chat.conversationId).put("conversationId", chat.conversationId)
                            .put("turnId", chat.turnId).put("sessionResumed", resumed);
                    if (!loadFailure.isEmpty()) info.put("sessionLoadFailure", loadFailure);
                    if (!resumed && !chat.transcript.isEmpty())
                        text = "Earlier messages in this conversation, for context (the previous agent session could not be resumed):\n"
                                + chat.transcript + "\n\n" + (continuation != null ? "" : "Current message:\n") + text;
                }
                if (attempt > 0) info.put("autoContinuation", attempt);
                TaskRecords.write(new File(directory, "runtime-info.json"), info.toString());
                JSONObject promptCaps = capabilities == null ? null : capabilities.optJSONObject("promptCapabilities");
                promptImages = promptCaps != null && promptCaps.optBoolean("image");
                JSONArray prompt = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
                JSONObject image = continuation != null ? null : screenshot(init);
                if (image != null) prompt.put(image);
                // The composer's attachments: images as image blocks, other files by name and path.
                if (continuation == null) {
                    try {
                        appendAttachments(prompt, AcpTurnControl.attachments(request.optJSONArray("attachments")));
                    } catch (IOException unavailable) {
                        info.put("attachmentsSkipped", clip(String.valueOf(unavailable.getMessage()), 80));
                        TaskRecords.write(new File(directory, "runtime-info.json"), info.toString());
                    }
                }
                phase = "working";
                progress("Taku is working. You can continue using your phone.", true);
                inPrompt = true;
                turnDeadline = Math.min(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.turnLimitMillis), budgetEnd);
                subagents = new AcpSubagents(AcpSubagents.sessionRoot(options.home, options.appDataDir, sessionId));
                subagents.baseline();
                JSONObject result;
                try {
                    result = call("session/prompt", new JSONObject().put("sessionId", sessionId).put("prompt", prompt));
                } catch (AcpError error) {
                    if (error.code == AUTH_REQUIRED) {
                        needsUser(authMethods);
                        return null;
                    }
                    throw error;
                }
                // A steer makes agy answer the running prompt at once (end_turn) and carry on in the
                // steer's prompt: the turn ends with the last of the chain (see ACP_RUNTIME.md, Steering).
                result = settleSteers(result);
                String stopReason = result.optString("stopReason", "");
                // The parent's turn ends while its subagents still run; keep the agent alive for their
                // reports and the parent's own follow-up reply (see AcpSubagents).
                if ("end_turn".equals(stopReason) && !subagentCalls.isEmpty() && !cancelSent) stopReason = awaitSubagents();
                refreshSubagentCards(true);
                completed(stopReason);
                return null;
            } catch (CancelledEarly cancelled) {
                // No prompt was sent; a request still in flight is answered later and dropped.
                reusable = connection.alive() && pendingSteers.isEmpty();
                finish(directory, request, "cancelled", "Cancelled before execution.", null);
            } catch (StopUnconfirmed unconfirmed) {
                stopUnconfirmed = true;
                diagnostic(directory, "stop-unconfirmed", "", stderr());
                finish(directory, request, "failed", "Stopping was not confirmed by the agent; its process was ended. Check the results of any actions.", null);
            } catch (AgentExited exited) {
                Integer code = waitExit();
                diagnostic(directory, "agent-exited", exited.getMessage() + " exit=" + code, stderr());
                if (continues(exited)) return new Retry(inPrompt);
                finish(directory, request, "failed", "The on-device agent stopped unexpectedly. Check the results of any actions before retrying.", reply.length() == 0 ? null : reply.toString());
            } catch (NoResponse silent) {
                diagnostic(directory, "no-response", silent.getMessage(), stderr());
                if (continues(silent)) return new Retry(inPrompt);
                finish(directory, request, "failed", "The on-device agent did not respond. Retry later.", null);
            } catch (AcpError error) {
                diagnostic(directory, "agent-error", error.code + " " + error.getMessage(), stderr());
                if (continues(error)) return new Retry(inPrompt);
                finish(directory, request, "failed", "The on-device agent reported an error. Check the results before retrying.", null);
            }
            return null;
        }

        /** An external, transient failure with an automatic continuation left, not cancelled or out of budget. */
        boolean continues(IOException failure) {
            if (attempt >= options.autoContinueMax || cancelSent || timedOut) return false;
            if (new File(directory, "cancel").exists() || System.nanoTime() >= budgetEnd) return false;
            return transientFailure(failure, stderr());
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
            display("break", () -> chat.breakMessage());
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
                pollSteers();
                acceptOverdueSteers();
                if (lastSteerError != null) throw lastSteerError;
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
                // A steer sent while waiting is a prompt of its own: never end before agy answered it.
                if (!pendingSteers.isEmpty()) continue;
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
            display("break", () -> chat.breakMessage());
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
            if (message.has("id") && !message.has("method")) {
                steerResponse(message); // any other late answer is dropped
                return;
            }
            if (message.has("id")) agentRequest(message);
            else safeNotification(message);
        }

        /**
         * {@link #notification} isolated from the turn: a malformed update or a display-layer failure
         * is counted and logged, never fatal. Agent I/O failures (the process going away) still end it.
         */
        void safeNotification(JSONObject message) throws Exception {
            try {
                notification(message);
            } catch (AgentExited | StopUnconfirmed | CancelledEarly fatal) {
                throw fatal;
            } catch (Exception malformed) {
                ignored++;
                System.err.println("acp update ignored: " + malformed);
            }
        }

        /** One chat projection step; its failure never stops the agent's turn. */
        void display(String what, DisplayStep step) {
            if (chat == null) return;
            try {
                step.run();
            } catch (Exception failure) {
                ignored++;
                System.err.println("acp chat " + what + " ignored: " + failure);
            }
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

        /**
         * Configured servers plus the phone tool relay, in ACP's stdio declaration shape.
         *
         * <p>The declaration is the same for every turn of a conversation (no turn id in it): agy keeps
         * one MCP server per distinct declaration for the life of its process and stops none on
         * {@code session/close}, so a per-turn value would start one more relay per turn in a warm
         * process. The broker resolves each call's turn through {@link AcpPhoneTurns}.
         */
        JSONArray mcpServers() throws Exception {
            JSONArray servers = new JSONArray(options.mcpServers.toString());
            JSONObject phone = options.phoneMcp;
            if (phone == null || phone.optString("command", "").isEmpty()) return servers;
            // The phone tools' session identity (screens, Apps, gestures): the conversation for a chat
            // turn, as for a Codex chat turn; a stable UUID per background task otherwise.
            String thread = phoneThread(request);
            String task = chat != null ? chat.conversationId : request.getString("id");
            agent.mcpConfigs.add(thread + "\n" + permissionMode);
            JSONArray env = new JSONArray();
            env.put(new JSONObject().put("name", "TAKU_MCP_TASK").put("value", task));
            env.put(new JSONObject().put("name", "TAKU_MCP_THREAD").put("value", thread));
            env.put(new JSONObject().put("name", "TAKU_MCP_PERMISSION").put("value", permissionMode));
            if (nativePhoneMcp()) {
                // The relay's handshake line is the Java relay's, prepared here; sh prints it, then cat
                // and toybox nc carry stdio to the broker's socket (no JVM start).
                env.put(new JSONObject().put("name", "TAKU_MCP_HELLO").put("value", new JSONObject()
                        .put("task", task).put("permissionMode", permissionMode).put("thread", thread).put("turn", "").toString()));
                env.put(new JSONObject().put("name", "TAKU_MCP_SOCKET").put("value", options.phoneMcpSocket.getPath()));
                return servers.put(new JSONObject()
                        .put("name", "taku_phone")
                        .put("command", options.shell)
                        .put("args", new JSONArray().put("-c").put(NATIVE_RELAY_SCRIPT.replace("@TOYBOX@", options.toybox)))
                        .put("env", env));
            }
            JSONObject configured = phone.optJSONObject("env");
            if (configured != null) for (java.util.Iterator<String> it = configured.keys(); it.hasNext(); ) {
                String key = it.next();
                env.put(new JSONObject().put("name", key).put("value", configured.getString(key)));
            }
            JSONArray args = phone.optJSONArray("args");
            return servers.put(new JSONObject()
                    .put("name", "taku_phone")
                    .put("command", phone.getString("command"))
                    .put("args", args == null ? new JSONArray() : new JSONArray(args.toString()))
                    .put("env", env));
        }

        boolean nativePhoneMcp() {
            return options.phoneMcpNative && options.phoneMcpSocket != null && options.phoneMcpSocket.exists()
                    && new File(options.toybox).canExecute() && new File(options.shell).canExecute();
        }

        Integer waitExit() {
            for (int i = 0; i < 20 && connection.alive(); i++) {
                try { Thread.sleep(25); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            return connection.exitCode();
        }

        void completed(String stopReason) throws Exception {
            // The prompt (and every steer) was answered and no subagent runs in the background: the
            // agent is idle. A budget timeout keeps nothing (its cancel may still be unwinding).
            reusable = pendingSteers.isEmpty() && subagentCalls.isEmpty() && !followUp && !timedOut && connection.alive();
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
            agent.send(message);
        }

        JSONObject call(String method, JSONObject params) throws Exception {
            long id = agent.nextId();
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
                    if (message.optLong("id", -1) != id) {
                        steerResponse(message); // otherwise a late answer to an abandoned request
                        continue;
                    }
                    if (message.has("error")) {
                        JSONObject error = message.optJSONObject("error");
                        throw new AcpError(error == null ? -32603 : error.optInt("code", -32603),
                                error == null ? "error" : error.optString("message", "error"));
                    }
                    // agy answers a running prompt as soon as a steer takes over: the steers sent
                    // before this answer were accepted.
                    acceptSteers(id);
                    JSONObject result = message.optJSONObject("result");
                    return result == null ? new JSONObject() : result;
                }
                if (message.has("id")) agentRequest(message);
                else safeNotification(message);
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
            pollSteers();
            acceptOverdueSteers();
        }

        // ---- steering -----------------------------------------------------------------------------

        /**
         * Picks up {@code steer-<requestId>.json} requests written by {@link AcpTurnControl#steer}: each
         * is claimed by an atomic rename to {@code .claimed} (a request the caller already withdrew
         * stays withdrawn), then sent as a further {@code session/prompt} of this session and recorded
         * in {@link #pendingSteers}; {@code .ack} marks it sent. Once the turn is stopping, a request is
         * refused ({@code STEER_CANCELLING}) without reaching the agent.
         */
        void pollSteers() throws Exception {
            if (sessionId.isEmpty()) return;
            File[] files = directory.listFiles((dir, name) -> name.startsWith("steer-") && name.endsWith(".json"));
            if (files == null || files.length == 0) return;
            java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for (File file : files) {
                String name = file.getName();
                String requestId = name.substring("steer-".length(), name.length() - ".json".length());
                if (!AcpTurnControl.validRequestId(requestId)) continue;
                File claimed = AcpTurnControl.steerFile(directory, requestId, ".claimed");
                if (!file.renameTo(claimed)) continue;
                if (cancelSent || new File(directory, "cancel").exists()) {
                    steerResult(requestId, false, AcpTurnControl.STEER_CANCELLING);
                    continue;
                }
                JSONObject steer;
                JSONArray prompt = new JSONArray();
                try {
                    steer = new JSONObject(readSmall(claimed));
                    String text = steer.optString("text", "");
                    if (!text.isBlank()) prompt.put(new JSONObject().put("type", "text").put("text", text));
                    appendAttachments(prompt, AcpTurnControl.attachments(steer.optJSONArray("attachments")));
                    if (prompt.length() == 0) throw new IOException("INVALID_ARGUMENT");
                } catch (Exception unusable) {
                    String code = unusable.getMessage() != null && unusable.getMessage().matches("[A-Z][A-Z_]{2,80}") ? unusable.getMessage() : "INVALID_ARGUMENT";
                    steerResult(requestId, false, code);
                    continue;
                }
                long id = agent.nextId();
                pendingSteers.put(id, new Steer(requestId, id));
                String text = steer.optString("text", "");
                JSONArray attachments = steer.optJSONArray("attachments");
                // The agent's answer to it is a new message after the user's.
                pendingSegment = reply.length();
                display("steer", () -> chat.steer(requestId, text, attachments));
                send(new JSONObject().put("id", id).put("method", "session/prompt")
                        .put("params", new JSONObject().put("sessionId", sessionId).put("prompt", prompt)));
                TaskRecords.write(AcpTurnControl.steerFile(directory, requestId, ".ack"), new JSONObject()
                        .put("requestId", requestId).put("state", "sent").put("sentAt", System.currentTimeMillis()).toString());
            }
        }

        /**
         * The answer to a steer's {@code session/prompt}. An error before the steer was accepted is agy
         * refusing it (mapped to a {@code STEER_*} code, the chat message withdrawn); after that it is
         * the turn's own failure. A result accepts it (and any steer sent before it). Returns false
         * when the message answers no pending steer.
         */
        boolean steerResponse(JSONObject message) throws Exception {
            Steer steer = pendingSteers.remove(message.optLong("id", -1));
            if (steer == null) return false;
            if (message.has("error")) {
                JSONObject error = message.optJSONObject("error");
                int code = error == null ? -32603 : error.optInt("code", -32603);
                String text = error == null ? "error" : error.optString("message", "error");
                if (steer.accepted) {
                    lastSteerError = new AcpError(code, text);
                    return true;
                }
                steerResult(steer.requestId, false, steerRejection(text));
                display("steer", () -> chat.withdrawSteer(steer.requestId));
                return true;
            }
            acceptSteers(steer.id);
            if (!steer.accepted) steerResult(steer.requestId, true, null);
            JSONObject result = message.optJSONObject("result");
            lastSteerResult = result == null ? new JSONObject() : result;
            return true;
        }

        /** The last answered steer of the chain (its stop reason ends the turn), or its failure. */
        JSONObject lastSteerResult;
        AcpError lastSteerError;

        /**
         * After the turn's prompt answered: waits, under the same cancel and budget supervision, until
         * every pending steer is answered, and returns the answer that ends the turn: the last of the
         * chain to arrive. agy answers the original prompt {@code end_turn} the moment a steer takes
         * over and the steer's prompt when the merged turn really ends (measured on the Pixel 9,
         * agy 1.5.14), but either order is handled.
         */
        JSONObject settleSteers(JSONObject result) throws Exception {
            JSONObject last = result;
            lastSteerResult = null; // answered before the prompt itself: the prompt's answer is later
            if (lastSteerError != null) throw lastSteerError;
            while (!pendingSteers.isEmpty()) {
                supervise();
                Object next = inbox.poll(100, TimeUnit.MILLISECONDS);
                runIdle();
                if (next == null) continue;
                if (next == EOF) throw new AgentExited("agent closed stdout during a steered prompt");
                if (next instanceof IOException) throw (IOException) next;
                JSONObject message;
                try {
                    message = new JSONObject((String) next);
                } catch (Exception notJson) {
                    ignoredLines++;
                    continue;
                }
                if (message.has("id") && !message.has("method")) {
                    if (steerResponse(message)) {
                        if (lastSteerError != null) throw lastSteerError;
                        if (lastSteerResult != null) last = lastSteerResult;
                        lastSteerResult = null;
                    }
                    continue;
                }
                if (message.has("id")) agentRequest(message);
                else safeNotification(message);
            }
            return last;
        }

        /** Steers sent before request {@code beforeId} was answered: agy took them over. */
        void acceptSteers(long beforeId) throws Exception {
            for (Steer steer : pendingSteers.values())
                if (steer.id > beforeId && !steer.accepted) steerResult(steer.requestId, true, null);
        }

        /** agy refuses a steer at once; one it has not refused within {@link #STEER_ACCEPT_MILLIS} runs. */
        void acceptOverdueSteers() throws Exception {
            long now = System.nanoTime();
            for (Steer steer : pendingSteers.values())
                if (!steer.accepted && now - steer.sentAt >= TimeUnit.MILLISECONDS.toNanos(STEER_ACCEPT_MILLIS))
                    steerResult(steer.requestId, true, null);
        }

        /** The turn is over: steers never accepted were not taken up (the caller may queue them instead). */
        void closeSteers() {
            for (Steer steer : pendingSteers.values()) {
                if (steer.accepted) continue;
                try {
                    steerResult(steer.requestId, false, "TURN_CHANGED");
                } catch (Exception unwritable) {
                    System.err.println("acp steer close: " + unwritable);
                }
                display("steer", () -> chat.withdrawSteer(steer.requestId));
            }
            pendingSteers.clear();
        }

        void steerResult(String requestId, boolean accepted, String code) throws Exception {
            for (Steer steer : pendingSteers.values()) if (steer.requestId.equals(requestId)) steer.accepted |= accepted;
            JSONObject result = new JSONObject().put("requestId", requestId).put("state", accepted ? "accepted" : "rejected")
                    .put("at", System.currentTimeMillis());
            if (!accepted) result.put("code", code == null ? AcpTurnControl.STEER_REJECTED : code);
            TaskRecords.write(AcpTurnControl.steerFile(directory, requestId, ".result"), result.toString());
            if (accepted) progress("Added your new instructions to this turn.", true);
        }

        /** Composer attachments as prompt blocks: images inline when the agent takes them, other files by path. */
        void appendAttachments(JSONArray prompt, JSONArray attachments) throws Exception {
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject item = attachments.getJSONObject(i);
                File file = new File(item.getString("path"));
                String mime = item.optString("mime", "");
                if (promptImages && mime.startsWith("image/") && file.length() <= MAX_ATTACHMENT_IMAGE_BYTES) {
                    prompt.put(new JSONObject().put("type", "image").put("mimeType", mime)
                            .put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()))));
                } else {
                    prompt.put(new JSONObject().put("type", "text").put("text",
                            "User-selected attachment: " + item.optString("name", file.getName()) + "\nLocal path: " + file.getPath()));
                }
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
                        display("break", () -> chat.breakMessage());
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
                        String chunk = content.optString("text");
                        reply.append(chunk);
                        display("message", () -> chat.message(chunk));
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
                    display("tool", () -> chat.tool(toolId, update));
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
                    String toolId = update.optString("toolCallId");
                    display("tool", () -> chat.tool(toolId, update));
                    subagentCall(update.optString("toolCallId"), call);
                    progress("Working: " + clip(call.getString("title"), 80), false);
                    break;
                }
                case "plan": {
                    JSONArray entries = update.optJSONArray("entries");
                    int total = entries == null ? 0 : entries.length(), done = 0;
                    for (int i = 0; i < total; i++) {
                        JSONObject entry = entries.optJSONObject(i);
                        if (entry != null && "completed".equals(entry.optString("status"))) done++;
                    }
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
        /** A tool call's redacted arguments kept whole up to this size; larger ones become a summary. */
        static final int MAX_ARGUMENT_CHARS = 16_000;
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

        /**
         * A message the user added while the turn runs: a {@code userMessage} item at this point of the
         * turn ({@code <turnId>-steer-<requestId>}); the agent's next text starts a new message after it.
         */
        void steer(String requestId, String text, JSONArray attachments) throws Exception {
            String id = turnId + "-steer-" + requestId;
            if (items.containsKey(id)) return;
            // The native userMessage shape (as ComposerInput): the text, then one part per attachment that
            // the chat page shows as an attachment chip (never its path).
            JSONArray content = new JSONArray();
            if (text != null && !text.isBlank()) content.put(new JSONObject().put("type", "text").put("text", AcpConversationStore.clip(text)));
            if (attachments != null) for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.optJSONObject(i);
                if (attachment == null) continue;
                content.put(new JSONObject().put("type", "text").put("text", "User-selected attachment: "
                        + attachment.optString("name", "attachment") + "\nLocal path: " + attachment.optString("path", "")));
            }
            JSONObject user = new JSONObject().put("type", "userMessage").put("id", id).put("content", content);
            items.put(id, user);
            message = null;
            flush(true);
        }

        /** A steer agy refused (or never took up) leaves the turn again. */
        void withdrawSteer(String requestId) throws Exception {
            if (items.remove(turnId + "-steer-" + requestId) == null) return;
            message = null;
            flush(true);
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
                // startedAt/completedAt (epoch ms) let the chat page show each step's duration.
                item = new JSONObject().put("type", "mcpToolCall").put("id", id).put("server", "agy")
                        .put("tool", "tool").put("status", "inProgress").put("startedAt", System.currentTimeMillis());
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
            if (!"inProgress".equals(item.optString("status")) && !item.has("completedAt")) item.put("completedAt", System.currentTimeMillis());
            Object input = update.opt("rawInput");
            if (input instanceof JSONObject) {
                // Each string value is redacted on its own; the serialized document is never edited.
                JSONObject arguments = (JSONObject) redactJson(input, MAX_REDACTED_CHARS);
                String serialized = arguments.toString();
                item.put("arguments", serialized.length() <= MAX_ARGUMENT_CHARS ? arguments
                        : new JSONObject().put("summary", clip(serialized, MAX_REDACTED_CHARS)));
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
                        new JSONArray().put(new JSONObject().put("type", "text").put("text", clip(AcpToolLabels.sanitize(redact(out.toString())), MAX_REDACTED_CHARS)))));
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
                // Its taku_phone tool identifiers (mcp_taku_phone_tap) read as the tool's name.
                if ("agentMessage".equals(copy.optString("type")))
                    copy.put("text", AcpToolLabels.humanizePhoneTools(AcpToolLabels.sanitize(copy.optString("text")), java.util.Locale.getDefault()));
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
