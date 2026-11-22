package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * An in-process ACP agent speaking newline-delimited JSON-RPC over pipes, standing in for
 * {@code agy acp}. Each {@link #start} consumes the next scripted scenario, so one transport can
 * model "crash, then a healthy fresh process".
 */
final class FakeAcpAgent implements AcpProcessTransport {
    enum Scenario {
        NORMAL, NEEDS_AUTH, PROMPT_AUTH, HANG_UNTIL_CANCEL, IGNORE_CANCEL, CRASH,
        /** Partial answer, then exits with a network error on stderr (transient). */
        CRASH_NETWORK,
        /** session/prompt answers a 503 UNAVAILABLE agent error (transient). */
        PROMPT_UNAVAILABLE,
        /** session/prompt answers an internal agent error (not transient). */
        PROMPT_INTERNAL,
        /** authenticate: URL on stderr, succeeds once the browser "visited" it. */
        AUTH_URL_STDERR,
        /** authenticate: URL JSON-escaped inside a protocol notification. */
        AUTH_URL_JSON,
        /**
         * authenticate like the real android_arm64 agy: no URL in any output; runs {@code xdg-open <url>}
         * from the launch PATH (discarding its output) and fails if there is none.
         */
        AUTH_XDG_OPEN,
        /** authenticate: URL, then an error. */
        AUTH_ERROR,
        /** authenticate: URL, then waits; like agy, answers -32800 "Sign-in was cancelled." when stdin closes. */
        AUTH_CANCEL_ON_EOF,
        /** authenticate: never answers. */
        AUTH_SILENT,
        /** initialize offers no oauth-personal. */
        AUTH_NO_METHOD,
        /**
         * Like agy 1.5.14 (Pixel 9 probe): two invoke_subagent calls, end_turn while they run; each child
         * later records its report in agy's store and the parent wakes by itself, streaming more updates
         * with no session/prompt reply.
         */
        SUBAGENTS,
        /** As SUBAGENTS, but the children finish inside the parent's turn (agy waited for them). */
        SUBAGENTS_INLINE,
        /** The children report, but the parent never consumes the reports until prompted again. */
        SUBAGENTS_SILENT_PARENT,
        /** The children never report. */
        SUBAGENTS_HANG,
        /** As SUBAGENTS, but agy's store is missing (another layout): the runtime falls back to silence. */
        SUBAGENTS_NO_STORE,
        /**
         * Steering like agy 1.5.14 (Pixel 9 probe): the prompt runs until a second session/prompt
         * arrives; agy then answers the running prompt end_turn (no usage) at once and carries on in the
         * new prompt, answering it when the merged turn ends. With {@link #steerRefusal} it refuses the
         * second prompt with agy's -32603 message instead and finishes the first.
         */
        STEER,
        /** As STEER, but the steer's answer arrives before the original prompt's. */
        STEER_REVERSED
    }

    /** agy's refusal text for a steer prompt, or null to accept it. */
    volatile String steerRefusal;
    /** Steer prompts after which the merged turn ends (1: the first steer ends it). */
    volatile int steersBeforeEnd = 1;

    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=fake.apps.googleusercontent.com"
            + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A4321%2Foauth2callback&response_type=code&state=st-1&code_challenge=cc";
    /** Counted down by the test's IntentLauncher: the user finished the browser step. */
    volatile CountDownLatch browserVisited = new CountDownLatch(1);

    final Deque<Scenario> scenarios = new ArrayDeque<>();
    final List<Launch> launches = new CopyOnWriteArrayList<>();
    final List<JSONObject> received = new CopyOnWriteArrayList<>();
    final List<Process> processes = new CopyOnWriteArrayList<>();
    volatile CountDownLatch promptStarted = new CountDownLatch(1);
    IOException startFailure;
    /** initialize advertises loadSession; session/load replays history then succeeds unless loadFails. */
    volatile boolean loadSession;
    volatile boolean loadFails;
    /** Session ids handed out by session/new, in order (default always "s-1"). */
    final Deque<String> newSessionIds = new ArrayDeque<>();
    volatile String currentSession = "s-1";
    /**
     * Runs before the normal turn finishes, with the phone relay's TAKU_MCP_TURN from the session's
     * mcpServers (as the relay would see it); returns extra session updates to send.
     */
    volatile java.util.function.Function<String, List<JSONObject>> beforeFinish;
    volatile String phoneTurn = "";
    /** When set, session/new answers with these configOptions (agy's shape) and set_config_option edits them. */
    volatile JSONArray configOptions;
    /** session/set_config_option values refused with -32602 (as agy does for unknown model ids). */
    final java.util.Set<String> refusedConfigValues = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Delay before each subagent report in the SUBAGENTS scenarios. */
    volatile long subagentDelayMillis = 300;
    /** Delays the warm-up's session/list answer (a turn then waits for the warm-up in flight). */
    volatile long sessionListDelayMillis;

    FakeAcpAgent(Scenario... scenarios) {
        for (Scenario scenario : scenarios) this.scenarios.add(scenario);
    }

    /**
     * The turn a phone tool call would run in, as the broker resolves it: the current ACP turn of the
     * relay's TAKU_MCP_THREAD (the declaration itself names no turn).
     */
    static String phoneTurnOf(JSONObject message) {
        JSONArray servers = message.getJSONObject("params").optJSONArray("mcpServers");
        if (servers != null) for (int i = 0; i < servers.length(); i++) {
            JSONArray env = servers.getJSONObject(i).optJSONArray("env");
            if (env != null) for (int j = 0; j < env.length(); j++)
                if ("TAKU_MCP_THREAD".equals(env.getJSONObject(j).optString("name"))) {
                    String turn = AcpPhoneTurns.current(env.getJSONObject(j).getString("value"));
                    return turn == null ? "" : turn;
                }
        }
        return "";
    }

    List<JSONObject> methods(String method) {
        List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONObject message : received) if (method.equals(message.optString("method"))) out.add(message);
        return out;
    }

    JSONObject response(Object id) {
        for (JSONObject message : received)
            if (!message.has("method") && String.valueOf(id).equals(String.valueOf(message.opt("id")))) return message;
        return null;
    }

    @Override
    public Connection start(Launch launch) throws IOException {
        if (startFailure != null) throw startFailure;
        launches.add(launch);
        Process process = new Process(scenarios.isEmpty() ? Scenario.NORMAL : scenarios.poll(), launch);
        processes.add(process);
        process.thread.start();
        return process;
    }

    final class Process implements Connection {
        final Scenario scenario;
        final Launch launch;
        final PipedInputStream clientReads = new PipedInputStream(1 << 20);
        final PipedOutputStream agentWrites = new PipedOutputStream();
        final PipedInputStream agentReads = new PipedInputStream(1 << 20);
        final PipedOutputStream clientWrites = new PipedOutputStream();
        final Thread thread;
        volatile boolean destroyed;
        volatile Integer exit;
        Object promptId;
        volatile Object authId;
        final PipedInputStream errReads = new PipedInputStream(1 << 16);
        final PipedOutputStream errWrites = new PipedOutputStream();

        Process(Scenario scenario, Launch launch) throws IOException {
            this.scenario = scenario;
            this.launch = launch;
            agentWrites.connect(clientReads);
            clientWrites.connect(agentReads);
            errWrites.connect(errReads);
            thread = new Thread(this::serve, "fake-acp-" + scenario);
            thread.setDaemon(true);
        }

        void send(JSONObject message) throws IOException {
            synchronized (agentWrites) {
                agentWrites.write((message.put("jsonrpc", "2.0") + "\n").getBytes(StandardCharsets.UTF_8));
                agentWrites.flush();
            }
        }

        void update(JSONObject update) throws IOException {
            send(new JSONObject().put("method", "session/update")
                    .put("params", new JSONObject().put("sessionId", currentSession).put("update", update)));
        }

        void chunk(String text) throws IOException {
            update(new JSONObject().put("sessionUpdate", "agent_message_chunk")
                    .put("content", new JSONObject().put("type", "text").put("text", text)));
        }

        void serve() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(agentReads, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject message = new JSONObject(line);
                    received.add(message);
                    if (handle(message)) return;
                }
                if (scenario == Scenario.AUTH_CANCEL_ON_EOF && authId != null)
                    send(new JSONObject().put("id", authId).put("error", new JSONObject().put("code", -32800).put("message", "Sign-in was cancelled.")));
                exit = 0;
            } catch (Exception closed) {
                if (exit == null) exit = destroyed ? 143 : 1;
            } finally {
                try { agentWrites.close(); } catch (IOException ignored) {}
                try { errWrites.close(); } catch (IOException ignored) {}
            }
        }

        boolean auth() {
            return scenario.name().startsWith("AUTH_");
        }

        void err(String line) throws IOException {
            errWrites.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            errWrites.flush();
        }

        void authenticate(Object id) throws Exception {
            // Noise first: a credential-looking line and a URL on a host that is not allowed.
            err("debug: access_token=ya29.SECRETSECRET refresh_token=1//SECRETSECRETSECRETSECRET");
            synchronized (agentWrites) {
                agentWrites.write("see https://evil.example.com/phish?x=1 for help\n".getBytes(StandardCharsets.UTF_8));
            }
            switch (scenario) {
                case AUTH_URL_JSON:
                    send(new JSONObject().put("method", "auth/progress").put("params",
                            new JSONObject().put("message", "Open " + AUTH_URL + " to continue")));
                    break;
                case AUTH_XDG_OPEN: {
                    java.io.File opener = null;
                    String path = launch.env.get("PATH");
                    if (path != null) for (String dir : path.split(":")) {
                        java.io.File candidate = new java.io.File(dir, "xdg-open");
                        if (candidate.canExecute()) { opener = candidate; break; }
                    }
                    if (opener == null) {
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32603)
                                .put("message", "Couldn't sign in: couldn't open a web browser: exec: \"xdg-open\": executable file not found in $PATH")));
                        return;
                    }
                    ProcessBuilder builder = new ProcessBuilder(opener.getPath(), AUTH_URL)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
                    builder.environment().clear();
                    builder.environment().putAll(launch.env);
                    if (builder.start().waitFor() != 0) {
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32603).put("message", "browser failed")));
                        return;
                    }
                    break;
                }
                case AUTH_CANCEL_ON_EOF:
                    authId = id;
                    err("Please visit the following URL to sign in: " + AUTH_URL);
                    return; // serve() answers when stdin closes.
                case AUTH_SILENT:
                    // Stay alive (a dead writer thread reads as a closed pipe) and never answer.
                    while (!destroyed) Thread.sleep(20);
                    return;
                default:
                    err("Please visit the following URL to sign in: " + AUTH_URL + " .");
            }
            if (!browserVisited.await(5, java.util.concurrent.TimeUnit.SECONDS)) return;
            if (scenario == Scenario.AUTH_ERROR)
                send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32603).put("message", "login failed")));
            else send(new JSONObject().put("id", id).put("result", new JSONObject()));
        }

        /** Returns true when the scripted agent "exits". */
        boolean handle(JSONObject message) throws Exception {
            String method = message.optString("method");
            Object id = message.opt("id");
            if ("session/list".equals(method) && sessionListDelayMillis > 0) Thread.sleep(sessionListDelayMillis);
            switch (method) {
                case "initialize":
                    send(new JSONObject().put("id", id).put("result", new JSONObject()
                            .put("protocolVersion", 1)
                            .put("agentCapabilities", new JSONObject().put("loadSession", loadSession)
                                    .put("promptCapabilities", new JSONObject().put("image", true)))
                            .put("authMethods", scenario == Scenario.AUTH_NO_METHOD
                                    ? new JSONArray().put(new JSONObject().put("id", "gemini-api-key").put("name", "Use API key"))
                                    : new JSONArray()
                                    .put(new JSONObject().put("id", "oauth-personal").put("name", "Log in with Google"))
                                    .put(new JSONObject().put("id", "gemini-api-key").put("name", "Use API key")))
                            .put("agentInfo", new JSONObject().put("name", "fake-agy").put("version", "0"))));
                    return false;
                case "session/new":
                    phoneTurn = phoneTurnOf(message);
                    if (scenario == Scenario.NEEDS_AUTH)
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32000).put("message", "Authentication required")));
                    else {
                        currentSession = newSessionIds.isEmpty() ? "s-1" : newSessionIds.poll();
                        JSONObject result = new JSONObject().put("sessionId", currentSession);
                        if (configOptions != null) result.put("configOptions", new JSONArray(configOptions.toString()));
                        send(new JSONObject().put("id", id).put("result", result));
                    }
                    return false;
                case "session/load": {
                    phoneTurn = phoneTurnOf(message);
                    String loaded = message.getJSONObject("params").getString("sessionId");
                    if (loadFails) {
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32002).put("message", "Session not found")));
                        return false;
                    }
                    currentSession = loaded;
                    // ACP replays the loaded conversation before answering; the client must not treat it as new output.
                    update(new JSONObject().put("sessionUpdate", "user_message_chunk")
                            .put("content", new JSONObject().put("type", "text").put("text", "REPLAYED USER")));
                    chunk("REPLAYED ANSWER");
                    send(new JSONObject().put("id", id).put("result", JSONObject.NULL));
                    return false;
                }
                case "authenticate": {
                    Thread t = new Thread(() -> {
                        try { authenticate(id); } catch (Exception ignored) {}
                    }, "fake-auth");
                    t.setDaemon(true);
                    t.start();
                    return false;
                }
                case "session/prompt":
                    if (scenario.name().startsWith("STEER") && promptId != null) return steer(message);
                    // Like agy: a session the process holds is prompted (and answered) by its own id,
                    // whether or not it was the last one created or loaded.
                    currentSession = message.getJSONObject("params").optString("sessionId", currentSession);
                    promptId = id;
                    if (scenario.name().startsWith("SUBAGENTS") && prompts++ > 0) {
                        // The follow-up prompt: the parent reads the reports and answers.
                        markRead();
                        chunk("Combined after prompt: A=17, B=74%.");
                        update(new JSONObject().put("sessionUpdate", "usage_update").put("used", 3).put("size", 10));
                        send(new JSONObject().put("id", id).put("result", new JSONObject().put("stopReason", "end_turn")));
                        return false;
                    }
                    return prompt();
                case "session/set_config_option": {
                    JSONObject params = message.getJSONObject("params");
                    String value = params.getString("value");
                    JSONObject option = AcpModelCatalog.select(configOptions, params.getString("configId"));
                    if (option == null || refusedConfigValues.contains(value)
                            || !AcpModelCatalog.values(configOptions, params.getString("configId")).contains(value)) {
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32602).put("message", "Unknown model ID: " + value)));
                        return false;
                    }
                    option.put("currentValue", value);
                    send(new JSONObject().put("id", id).put("result", new JSONObject().put("configOptions", new JSONArray(configOptions.toString()))));
                    return false;
                }
                case "session/cancel":
                    if (scenario.name().startsWith("STEER") && promptId != null) {
                        cancelling = true;
                        send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "cancelled")));
                        promptId = null;
                        return false;
                    }
                    if (scenario == Scenario.HANG_UNTIL_CANCEL)
                        send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "cancelled")));
                    return false;
                case "":
                    // A response to one of our requests.
                    if ("perm-1".equals(String.valueOf(id))) finishNormal();
                    return false;
                default:
                    // Like agy: an unknown request is answered at once (the pool's liveness probe).
                    if (id != null && !method.isEmpty())
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32601).put("message", "Method not found: " + method)));
                    return false;
            }
        }

        int prompts;
        int steers;
        boolean cancelling;

        /** A second session/prompt while one runs (see {@link Scenario#STEER}). */
        boolean steer(JSONObject message) throws Exception {
            Object id = message.opt("id");
            String refusal = cancelling ? "the running prompt is being cancelled; wait for it to finish, then send this prompt again" : steerRefusal;
            if (refusal != null) {
                send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32603)
                        .put("message", "Can't add this prompt to session " + currentSession + ": " + refusal)));
                if (!cancelling) {
                    chunk(" Main done.");
                    usage();
                    send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "end_turn")));
                    promptId = null;
                }
                return false;
            }
            StringBuilder text = new StringBuilder();
            JSONArray blocks = message.getJSONObject("params").getJSONArray("prompt");
            for (int i = 0; i < blocks.length(); i++)
                text.append(blocks.getJSONObject(i).optString("type")).append(':').append(blocks.getJSONObject(i).optString("text")).append(';');
            Object previous = promptId;
            promptId = id;
            if (scenario == Scenario.STEER) send(new JSONObject().put("id", previous).put("result", new JSONObject().put("stopReason", "end_turn")));
            chunk("Steered by " + text);
            if (++steers < steersBeforeEnd) return false;
            chunk(" Final answer.");
            usage();
            send(new JSONObject().put("id", id).put("result", new JSONObject().put("stopReason", "end_turn")));
            if (scenario == Scenario.STEER_REVERSED) send(new JSONObject().put("id", previous).put("result", new JSONObject().put("stopReason", "end_turn")));
            promptId = null;
            return false;
        }

        final java.util.List<String> reports = new java.util.ArrayList<>();

        java.io.File storeRoot() {
            List<String> command = launch.command;
            int at = command.indexOf("--app-data-dir");
            String app = at >= 0 && at + 1 < command.size() ? command.get(at + 1) : "taku-acp";
            return AcpSubagents.sessionRoot(launch.env.get("HOME"), app, currentSession);
        }

        void write(java.io.File file, JSONObject value) throws IOException {
            file.getParentFile().mkdirs();
            java.nio.file.Files.writeString(file.toPath(), value.toString());
        }

        void spawn(String toolId, String child, String role) throws Exception {
            JSONObject args = new JSONObject().put("Prompt", "do " + role).put("Role", role).put("TypeName", "self");
            update(new JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", toolId).put("name", "invoke_subagent")
                    .put("title", "Run invoke_subagent").put("kind", "other").put("status", "in_progress").put("rawInput", args));
            if (scenario != Scenario.SUBAGENTS_NO_STORE)
                write(new java.io.File(storeRoot(), "subagents/" + child + ".json"), new JSONObject().put("conversationId", child)
                        .put("subagentDescriptor", new JSONObject().put("typeName", "self").put("role", role)).put("state", "SUBAGENT_STATE_ALIVE"));
            update(new JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", toolId).put("name", "invoke_subagent")
                    .put("title", "Run invoke_subagent").put("kind", "other").put("status", "completed").put("rawInput", args));
        }

        /** The child's own transcript (agy: brain/<child>/.system_generated/logs/transcript.jsonl). */
        void childStep(String child, JSONObject step) throws IOException {
            if (scenario == Scenario.SUBAGENTS_NO_STORE) return;
            java.io.File log = new java.io.File(storeRoot().getParentFile().getParentFile(), child + "/.system_generated/logs/transcript.jsonl");
            log.getParentFile().mkdirs();
            java.nio.file.Files.writeString(log.toPath(), step.toString() + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }

        /** A child's report lands in the store; delivered=true when the parent consumed it. */
        void report(String child, boolean delivered) throws Exception {
            if (scenario == Scenario.SUBAGENTS_NO_STORE) return;
            String id = "m-" + child;
            reports.add(id);
            write(new java.io.File(storeRoot(), "messages/" + id + ".json"), new JSONObject().put("id", id)
                    .put("sender", child).put("recipient", currentSession).put("timestamp", java.time.Instant.now().toString())
                    .put("content", "## Result\n- **" + child + "** done, see /data/local/agentos/acp/home/.gemini/taku-acp/brain/x/out.txt\n\nresult of " + child));
            readFlags.put(id, delivered);
            writeRead();
        }

        final JSONObject readFlags = new JSONObject();

        void markRead() throws Exception {
            if (scenario == Scenario.SUBAGENTS_NO_STORE) return;
            for (String id : reports) readFlags.put(id, true);
            writeRead();
        }

        void writeRead() throws IOException {
            write(new java.io.File(storeRoot(), "messages/read.json"), readFlags);
        }

        void usage() throws IOException {
            update(new JSONObject().put("sessionUpdate", "usage_update").put("used", 1).put("size", 10));
        }

        boolean subagentPrompt() throws Exception {
            spawn("call-a", "child-a", "Version Checker");
            spawn("call-b", "child-b", "Battery Checker");
            childStep("child-a", new JSONObject().put("source", "MODEL").put("type", "PLANNER_RESPONSE").put("model", "gemini-4-neon-high")
                    .put("tool_calls", new org.json.JSONArray().put(new JSONObject().put("name", "mcp_taku_phone_device_info").put("args", new JSONObject()))));
            if (scenario == Scenario.SUBAGENTS_INLINE) {
                report("child-a", true);
                report("child-b", true);
                chunk("Launched both. ");
                chunk("Summary: A=17, B=74%. See [log](file:///data/local/agentos/acp/home/.gemini/taku-acp/brain/x/log.txt).");
                usage();
                send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "end_turn")));
                return false;
            }
            chunk("Both sub-tasks launched ([`child-a`](conversation://child-a)). Waiting for results.");
            usage();
            send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "end_turn")));
            promptStarted.countDown();
            if (scenario == Scenario.SUBAGENTS_HANG) return false;
            Thread later = new Thread(() -> {
                try {
                    boolean wake = scenario != Scenario.SUBAGENTS_SILENT_PARENT;
                    Thread.sleep(subagentDelayMillis);
                    report("child-a", wake);
                    if (wake) {
                        chunk("\n\nSub-task A finished (17). Waiting for B.");
                        usage();
                    }
                    Thread.sleep(subagentDelayMillis);
                    report("child-b", wake);
                    if (wake) {
                        update(new JSONObject().put("sessionUpdate", "agent_thought_chunk")
                                .put("content", new JSONObject().put("type", "text").put("text", "combine")));
                        chunk("\n\nAll done. Summary: Android 17, battery 74%. Screenshot: ../acp/home/.gemini/taku-acp/brain/s/.system_generated/steps/12/media_0.png");
                        usage();
                    }
                    // A PipedInputStream reports "write end dead" once its last writer thread exits.
                    while (!destroyed) Thread.sleep(20);
                } catch (Exception ignored) {
                    // The client went away.
                }
            }, "fake-subagents");
            later.setDaemon(true);
            later.start();
            return false;
        }

        boolean prompt() throws Exception {
            if (scenario.name().startsWith("SUBAGENTS")) return subagentPrompt();
            switch (scenario) {
                case PROMPT_AUTH:
                    send(new JSONObject().put("id", promptId).put("error", new JSONObject().put("code", -32000).put("message", "Authentication required")));
                    return false;
                case HANG_UNTIL_CANCEL:
                case IGNORE_CANCEL:
                case STEER:
                case STEER_REVERSED:
                    chunk("Working on it");
                    promptStarted.countDown();
                    return false;
                case PROMPT_UNAVAILABLE:
                    chunk("Started. ");
                    send(new JSONObject().put("id", promptId).put("error", new JSONObject().put("code", -32603)
                            .put("message", "googleapi: Error 503: The model is overloaded. UNAVAILABLE")));
                    return false;
                case PROMPT_INTERNAL:
                    send(new JSONObject().put("id", promptId).put("error", new JSONObject().put("code", -32603)
                            .put("message", "internal error: nil map")));
                    return false;
                case CRASH:
                case CRASH_NETWORK:
                    chunk("Partial answer before the crash");
                    promptStarted.countDown();
                    Thread.sleep(50);
                    exit = 137;
                    return true;
                default:
                    // Agents may log to stdout; the client must skip non-protocol lines.
                    synchronized (agentWrites) {
                        agentWrites.write("agy: warming up\n".getBytes(StandardCharsets.UTF_8));
                    }
                    update(new JSONObject().put("sessionUpdate", "plan").put("entries", new JSONArray()
                            .put(new JSONObject().put("content", "Open Settings").put("status", "completed").put("priority", "high"))
                            .put(new JSONObject().put("content", "Enable Wi-Fi").put("status", "in_progress").put("priority", "high"))));
                    update(new JSONObject().put("sessionUpdate", "agent_thought_chunk")
                            .put("content", new JSONObject().put("type", "text").put("text", "Let me check")));
                    update(new JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "tc-1")
                            .put("title", "Open Settings").put("kind", "execute").put("status", "in_progress"));
                    // An unsupported client capability; the client must answer, not hang.
                    send(new JSONObject().put("id", "fs-1").put("method", "fs/read_text_file")
                            .put("params", new JSONObject().put("sessionId", "s-1").put("path", "/etc/hosts")));
                    // Another session's noise must be ignored.
                    send(new JSONObject().put("method", "session/update").put("params", new JSONObject().put("sessionId", "other")
                            .put("update", new JSONObject().put("sessionUpdate", "agent_message_chunk")
                                    .put("content", new JSONObject().put("type", "text").put("text", "LEAK")))));
                    send(new JSONObject().put("id", "perm-1").put("method", "session/request_permission").put("params", new JSONObject()
                            .put("sessionId", "s-1")
                            .put("toolCall", new JSONObject().put("toolCallId", "tc-1").put("title", "Open Settings"))
                            .put("options", new JSONArray()
                                    .put(new JSONObject().put("optionId", "deny").put("name", "Reject").put("kind", "reject_once"))
                                    .put(new JSONObject().put("optionId", "yes").put("name", "Allow").put("kind", "allow_once")))));
                    return false;
            }
        }

        void finishNormal() throws Exception {
        if (beforeFinish != null) for (JSONObject extra : beforeFinish.apply(phoneTurn)) update(extra);
            update(new JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "tc-1").put("status", "completed"));
            chunk("Done: ");
            chunk("Wi-Fi is on.\nDetails follow.");
            send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "end_turn")));
        }

        @Override public InputStream stdout() { return clientReads; }
        @Override public OutputStream stdin() { return clientWrites; }
        @Override public InputStream stderr() {
            if (auth()) return errReads;
            String text = scenario == Scenario.CRASH_NETWORK
                    ? "rpc error: code = UNAVAILABLE desc = read tcp: connection reset by peer token=abc123\n"
                    : "token=abc123 secret stuff\n";
            return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        @Override public boolean alive() { return !destroyed && thread.isAlive(); }
        @Override public Integer exitCode() { return alive() ? null : (exit == null ? 0 : exit); }

        @Override
        public void destroy(long graceMillis) {
            if (scenario == Scenario.AUTH_CANCEL_ON_EOF) {
                // Like the process transport: close stdin, give the agent its grace period to answer.
                try { clientWrites.close(); } catch (IOException ignored) {}
                try { thread.join(graceMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            destroyed = true;
            try { clientWrites.close(); } catch (IOException ignored) {}
            thread.interrupt();
            try { agentWrites.close(); } catch (IOException ignored) {}
            try { errWrites.close(); } catch (IOException ignored) {}
        }
    }
}
