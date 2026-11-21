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
    enum Scenario { NORMAL, NEEDS_AUTH, PROMPT_AUTH, HANG_UNTIL_CANCEL, IGNORE_CANCEL, CRASH }

    final Deque<Scenario> scenarios = new ArrayDeque<>();
    final List<Launch> launches = new CopyOnWriteArrayList<>();
    final List<JSONObject> received = new CopyOnWriteArrayList<>();
    final List<Process> processes = new CopyOnWriteArrayList<>();
    volatile CountDownLatch promptStarted = new CountDownLatch(1);
    IOException startFailure;

    FakeAcpAgent(Scenario... scenarios) {
        for (Scenario scenario : scenarios) this.scenarios.add(scenario);
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
        Process process = new Process(scenarios.isEmpty() ? Scenario.NORMAL : scenarios.poll());
        processes.add(process);
        process.thread.start();
        return process;
    }

    final class Process implements Connection {
        final Scenario scenario;
        final PipedInputStream clientReads = new PipedInputStream(1 << 20);
        final PipedOutputStream agentWrites = new PipedOutputStream();
        final PipedInputStream agentReads = new PipedInputStream(1 << 20);
        final PipedOutputStream clientWrites = new PipedOutputStream();
        final Thread thread;
        volatile boolean destroyed;
        volatile Integer exit;
        Object promptId;

        Process(Scenario scenario) throws IOException {
            this.scenario = scenario;
            agentWrites.connect(clientReads);
            clientWrites.connect(agentReads);
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
                    .put("params", new JSONObject().put("sessionId", "s-1").put("update", update)));
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
                exit = 0;
            } catch (Exception closed) {
                if (exit == null) exit = destroyed ? 143 : 1;
            } finally {
                try { agentWrites.close(); } catch (IOException ignored) {}
            }
        }

        /** Returns true when the scripted agent "exits". */
        boolean handle(JSONObject message) throws Exception {
            String method = message.optString("method");
            Object id = message.opt("id");
            switch (method) {
                case "initialize":
                    send(new JSONObject().put("id", id).put("result", new JSONObject()
                            .put("protocolVersion", 1)
                            .put("agentCapabilities", new JSONObject().put("loadSession", false)
                                    .put("promptCapabilities", new JSONObject().put("image", true)))
                            .put("authMethods", new JSONArray()
                                    .put(new JSONObject().put("id", "oauth-personal").put("name", "Log in with Google"))
                                    .put(new JSONObject().put("id", "gemini-api-key").put("name", "Use API key")))
                            .put("agentInfo", new JSONObject().put("name", "fake-agy").put("version", "0"))));
                    return false;
                case "session/new":
                    if (scenario == Scenario.NEEDS_AUTH)
                        send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32000).put("message", "Authentication required")));
                    else send(new JSONObject().put("id", id).put("result", new JSONObject().put("sessionId", "s-1")));
                    return false;
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
            update(new JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "tc-1").put("status", "completed"));
            chunk("Done: ");
            chunk("Wi-Fi is on.\nDetails follow.");
            send(new JSONObject().put("id", promptId).put("result", new JSONObject().put("stopReason", "end_turn")));
        }

        @Override public InputStream stdout() { return clientReads; }
        @Override public OutputStream stdin() { return clientWrites; }
        @Override public InputStream stderr() { return new java.io.ByteArrayInputStream("token=abc123 secret stuff\n".getBytes(StandardCharsets.UTF_8)); }
        @Override public boolean alive() { return !destroyed && thread.isAlive(); }
        @Override public Integer exitCode() { return alive() ? null : (exit == null ? 0 : exit); }

        @Override
        public void destroy(long graceMillis) {
            destroyed = true;
            try { clientWrites.close(); } catch (IOException ignored) {}
            thread.interrupt();
            try { agentWrites.close(); } catch (IOException ignored) {}
        }
    }
}
