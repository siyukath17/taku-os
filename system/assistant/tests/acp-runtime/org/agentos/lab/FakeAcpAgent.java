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
        AUTH_NO_METHOD
    }

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

    FakeAcpAgent(Scenario... scenarios) {
        for (Scenario scenario : scenarios) this.scenarios.add(scenario);
    }

    static String phoneTurnOf(JSONObject message) {
        JSONArray servers = message.getJSONObject("params").optJSONArray("mcpServers");
        if (servers != null) for (int i = 0; i < servers.length(); i++) {
            JSONArray env = servers.getJSONObject(i).optJSONArray("env");
            if (env != null) for (int j = 0; j < env.length(); j++)
                if ("TAKU_MCP_TURN".equals(env.getJSONObject(j).optString("name"))) return env.getJSONObject(j).getString("value");
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
                        send(new JSONObject().put("id", id).put("result", new JSONObject().put("sessionId", currentSession)));
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
                    promptId = id;
                    return prompt();
                case "session/cancel":
                    if (scenario == Scenario.HANG_UNTIL_CANCEL)
                        send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "cancelled")));
                    return false;
                case "":
                    // A response to one of our requests.
                    if ("perm-1".equals(String.valueOf(id))) finishNormal();
                    return false;
                default:
                    return false;
            }
        }

        boolean prompt() throws Exception {
            switch (scenario) {
                case PROMPT_AUTH:
                    send(new JSONObject().put("id", promptId).put("error", new JSONObject().put("code", -32000).put("message", "Authentication required")));
                    return false;
                case HANG_UNTIL_CANCEL:
                case IGNORE_CANCEL:
                    chunk("Working on it");
                    promptStarted.countDown();
                    return false;
                case CRASH:
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
        @Override public InputStream stderr() { return auth() ? errReads : new java.io.ByteArrayInputStream("token=abc123 secret stuff\n".getBytes(StandardCharsets.UTF_8)); }
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
