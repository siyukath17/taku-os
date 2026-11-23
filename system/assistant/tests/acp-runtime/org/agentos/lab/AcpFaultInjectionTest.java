package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fault injection on the ACP turn path ({@link AcpRuntime} over {@link AcpAgentPool.Agent} and
 * {@link AcpProcessTransport}): an agent that writes stdout noise (non-JSON, truncated, invalid UTF-8,
 * unknown / missing ids, malformed updates, other sessions), crashes or closes stdout mid-prompt, sends
 * an oversized line, never answers, floods output past the reply cap or through a cancel, or stops
 * reading its stdin. Each turn must end with a terminal record (done + state + diagnostic) in bounded
 * time, the process must be reclaimed, and the same runtime must serve the next turn. The last cases
 * run real OS processes through {@link AcpProcessTransport#process()}.
 *
 * <p>Every run is bounded ({@link #runBounded}): a hang fails the test instead of hanging the suite.
 */
public final class AcpFaultInjectionTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    // ---- a scripted agent with raw control over its stdio -------------------------------------------

    interface Script {
        void run(Proc agent) throws Exception;
    }

    /** Each start runs the next script on its own thread; an empty queue runs {@link #healthy}. */
    static final class ScriptedAgent implements AcpProcessTransport {
        final Deque<Script> scripts = new ArrayDeque<>();
        final List<Proc> processes = new CopyOnWriteArrayList<>();

        ScriptedAgent(Script... scripts) {
            this.scripts.addAll(Arrays.asList(scripts));
        }

        @Override
        public Connection start(Launch launch) throws IOException {
            Proc proc = new Proc(scripts.isEmpty() ? AcpFaultInjectionTest::healthy : scripts.poll());
            processes.add(proc);
            proc.thread.start();
            return proc;
        }
    }

    static final class Proc implements AcpProcessTransport.Connection {
        final PipedInputStream clientReads = new PipedInputStream(1 << 16);
        final PipedOutputStream agentWrites = new PipedOutputStream();
        /** Complete lines the client wrote, as text. */
        final BlockingQueue<String> fromClient = new LinkedBlockingQueue<>();
        final List<JSONObject> received = new CopyOnWriteArrayList<>();
        final Stdin stdin = new Stdin();
        final Thread thread;
        final CountDownLatch prompted = new CountDownLatch(1);
        volatile boolean killed, stdoutClosed;
        volatile int destroyCalls;
        volatile Integer exit;
        volatile String stderr = "agent log line\n";

        Proc(Script script) throws IOException {
            agentWrites.connect(clientReads);
            thread = new Thread(() -> {
                try {
                    script.run(this);
                } catch (Exception gone) {
                    // Killed, or the client stopped reading: the script just ends.
                } finally {
                    if (exit == null && !killed) awaitKilled();
                }
            }, "scripted-acp");
            thread.setDaemon(true);
        }

        /** The client's stdin: buffered by line; once {@link #stall}ed every write blocks until the kill. */
        final class Stdin extends OutputStream {
            final ByteArrayOutputStream pending = new ByteArrayOutputStream();
            volatile boolean stalled, closed;

            @Override
            public synchronized void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public synchronized void write(byte[] bytes, int off, int len) throws IOException {
                // A full pipe: the writer blocks (uninterruptibly, as a real pipe write does) until the
                // reader goes away; the kill then fails the write.
                while (stalled && !killed) {
                    try {
                        wait(20);
                    } catch (InterruptedException interrupted) {
                        // A real blocked pipe write ignores interrupts too.
                    }
                }
                if (killed || closed) throw new IOException("Broken pipe");
                for (int i = off; i < off + len; i++) {
                    if (bytes[i] == '\n') {
                        fromClient.add(new String(pending.toByteArray(), StandardCharsets.UTF_8));
                        pending.reset();
                    } else pending.write(bytes[i]);
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        // agent side

        void raw(byte[] bytes) throws IOException {
            agentWrites.write(bytes);
            agentWrites.flush();
        }

        void line(String text) throws IOException {
            raw((text + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void send(JSONObject message) throws IOException {
            line(message.put("jsonrpc", "2.0").toString());
        }

        void update(JSONObject update) throws IOException {
            send(new JSONObject().put("method", "session/update")
                    .put("params", new JSONObject().put("sessionId", "s-1").put("update", update)));
        }

        void chunk(String text) throws IOException {
            update(new JSONObject().put("sessionUpdate", "agent_message_chunk")
                    .put("content", new JSONObject().put("type", "text").put("text", text)));
        }

        /** The next client message (a request, notification or response), or an exception once killed. */
        JSONObject next() throws Exception {
            for (; ; ) {
                if (killed) throw new IOException("killed");
                String line = fromClient.poll(20, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (stdin.closed) throw new IOException("stdin closed");
                    continue;
                }
                JSONObject message = new JSONObject(line);
                received.add(message);
                return message;
            }
        }

        /** Reads until a request for {@code method}; other requests are answered as unknown, the rest skipped. */
        JSONObject await(String method) throws Exception {
            for (; ; ) {
                JSONObject message = next();
                if (method.equals(message.optString("method"))) return message;
                if (message.has("id") && message.has("method"))
                    send(new JSONObject().put("id", message.get("id")).put("error", new JSONObject().put("code", -32601).put("message", "unknown")));
            }
        }

        /** initialize and session/new; returns the session/prompt request. */
        JSONObject handshakeToPrompt() throws Exception {
            JSONObject init = await("initialize");
            send(new JSONObject().put("id", init.get("id")).put("result", new JSONObject().put("protocolVersion", 1)
                    .put("agentCapabilities", new JSONObject().put("promptCapabilities", new JSONObject().put("image", true)))
                    .put("agentInfo", new JSONObject().put("name", "scripted").put("version", "0"))));
            JSONObject session = await("session/new");
            send(new JSONObject().put("id", session.get("id")).put("result", new JSONObject().put("sessionId", "s-1")));
            JSONObject prompt = await("session/prompt");
            prompted.countDown();
            return prompt;
        }

        void endTurn(JSONObject prompt) throws IOException {
            send(new JSONObject().put("id", prompt.get("id")).put("result", new JSONObject().put("stopReason", "end_turn")));
        }

        /** The process exits with {@code code}: stdout reaches EOF. */
        void exit(int code) {
            exit = code;
            closeStdout();
        }

        void closeStdout() {
            stdoutClosed = true;
            try { agentWrites.close(); } catch (IOException ignored) {}
        }

        /** Stays "alive" until killed (a dead writer thread would read as a broken pipe). */
        void awaitKilled() {
            while (!killed) {
                try { Thread.sleep(10); } catch (InterruptedException interrupted) { return; }
            }
        }

        // client side (Connection)

        @Override public InputStream stdout() { return clientReads; }
        @Override public OutputStream stdin() { return stdin; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)); }
        @Override public boolean alive() { return !killed && exit == null; }
        @Override public Integer exitCode() { return alive() ? null : exit == null ? 137 : exit; }

        @Override
        public void destroy(long graceMillis) {
            destroyCalls++;
            killed = true;
            stdin.closed = true;
            closeStdout();
            thread.interrupt();
        }
    }

    static void healthy(Proc agent) throws Exception {
        JSONObject prompt = agent.handshakeToPrompt();
        agent.chunk("Healthy answer.");
        agent.endTurn(prompt);
        agent.awaitKilled();
    }

    // ---- harness ------------------------------------------------------------------------------------

    private static File taskDir(String id) throws IOException {
        File dir = Files.createTempDirectory("acp-fault-" + id).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static String read(File dir, String name) throws IOException {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    private static JSONObject json(File dir, String name) throws IOException {
        return new JSONObject(read(dir, name));
    }

    private static JSONObject request(String id) {
        return new JSONObject().put("id", id).put("prompt", "Turn on Wi-Fi").put("permissionMode", "full");
    }

    private static AcpRuntime.Options options(JSONObject overrides) throws Exception {
        File home = Files.createTempDirectory("acp-fault-home").toFile();
        home.deleteOnExit();
        JSONObject config = new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy")
                .put("home", home.getPath())
                .put("cwd", "")
                .put("progressIntervalMillis", 0)
                .put("requestTimeoutMillis", 5000)
                .put("cancelGraceMillis", 1000)
                .put("autoContinueMax", 0);
        for (String key : overrides.keySet()) config.put(key, overrides.get(key));
        return AcpRuntime.Options.fromJson(config);
    }

    private static AcpRuntime runtime(ScriptedAgent agent, JSONObject overrides) throws Exception {
        return new AcpRuntime(options(overrides), agent, taskId -> { }, () -> { });
    }

    /** Runs the task on its own thread; a run still going after {@code limitMillis} is a hang. Returns ms. */
    private static long runBounded(AcpRuntime runtime, File dir, JSONObject request, long limitMillis, String what) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                runtime.run(dir, request);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "acp-fault-run");
        runner.setDaemon(true);
        long begin = System.nanoTime();
        runner.start();
        runner.join(limitMillis);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        check(!runner.isAlive(), what + ": the turn hung (still running after " + limitMillis + "ms)");
        if (failure.get() != null) throw new AssertionError(what + ": run threw", failure.get());
        return millis;
    }

    /** A terminal failure record: done, state and diagnostic agree. */
    private static JSONObject failed(File dir, String what, String diagnosticType) throws Exception {
        check("failed".equals(read(dir, "done")), what + ": done is failed, got " + read(dir, "done"));
        check("failed".equals(json(dir, "state.json").optString("state")), what + ": state is failed: " + read(dir, "state.json"));
        JSONObject diagnostic = json(dir, "diagnostic.json");
        check(diagnosticType.equals(diagnostic.getString("type")), what + ": diagnostic " + diagnosticType + ", got " + diagnostic);
        return diagnostic;
    }

    private static void reclaimed(Proc proc, String what) {
        check(proc.killed && proc.destroyCalls > 0 && !proc.alive(), what + ": the agent process is destroyed");
    }

    /** The same runtime instance serves a healthy turn right after the fault. */
    private static void nextTurnWorks(AcpRuntime runtime, String what) throws Exception {
        File dir = taskDir("next");
        long millis = runBounded(runtime, dir, request("next-" + what), 5000, what + " (next turn)");
        check("completed".equals(read(dir, "done")) && json(dir, "result.txt").getString("message").contains("Healthy answer."),
                what + ": the next turn completes on the same runtime: " + read(dir, "done"));
        check(millis < 5000, what + ": the next turn is not delayed");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    // ---- cases --------------------------------------------------------------------------------------

    /**
     * stdout noise around every request: plain log lines, a truncated message, invalid UTF-8 (alone and
     * inside a JSON string), JSON that is no object, answers to ids nobody asked for (and a null id),
     * malformed updates, another session's chunk and an agent request for an unsupported method. The
     * turn completes with exactly the real text; the unsupported request is refused with -32601.
     */
    static void noiseIsSkipped() throws Exception {
        Script noisy = agent -> {
            byte[] noise = concat(
                    bytes("agy: starting backend\n"),
                    bytes("\n   \n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"method\":\"session/upd\n"),
                    new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, '\n'},
                    bytes("[1,2,3]\nnull\n42\n\"text\"\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"id\":9999,\"result\":{\"stopReason\":\"end_turn\"}}\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"id\":\"not-a-number\",\"result\":{}}\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":\"oops\"}\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\"}\n"),
                    bytes("{\"jsonrpc\":\"2.0\",\"method\":\"$/unknown\",\"params\":{}}\n"));
            agent.raw(noise);
            JSONObject init = agent.await("initialize");
            agent.raw(noise);
            agent.send(new JSONObject().put("id", init.get("id")).put("result", new JSONObject().put("protocolVersion", 1)));
            JSONObject session = agent.await("session/new");
            agent.raw(noise);
            // A second (late, duplicate) answer to initialize: dropped, not taken for session/new.
            agent.send(new JSONObject().put("id", init.get("id")).put("result", new JSONObject().put("protocolVersion", 1)));
            agent.send(new JSONObject().put("id", session.get("id")).put("result", new JSONObject().put("sessionId", "s-1")));
            JSONObject prompt = agent.await("session/prompt");
            agent.raw(noise);
            agent.send(new JSONObject().put("id", "srv-1").put("method", "fs/read_text_file")
                    .put("params", new JSONObject().put("path", "/etc/passwd")));
            agent.update(new JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", "not an object"));
            agent.update(new JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", new JSONObject().put("type", "text")));
            agent.update(new JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", JSONObject.NULL).put("title", 7));
            agent.send(new JSONObject().put("method", "session/update").put("params", new JSONObject().put("sessionId", "s-other")
                    .put("update", new JSONObject().put("sessionUpdate", "agent_message_chunk")
                            .put("content", new JSONObject().put("type", "text").put("text", "LEAKED")))));
            // A chunk whose text carries an invalid UTF-8 byte: decoded with a replacement character.
            agent.raw(concat(bytes("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s-1\",\"update\":"
                    + "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Wi-Fi \"}}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s-1\",\"update\":"
                    + "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"is on"),
                    new byte[]{(byte) 0xc3}, bytes("\"}}}}\n")));
            agent.raw(noise);
            // The refusal of fs/read_text_file arrives before the prompt is answered.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean refused = false;
            while (!refused && System.nanoTime() < until) {
                JSONObject message = agent.next();
                refused = "srv-1".equals(String.valueOf(message.opt("id"))) && message.has("error");
            }
            agent.endTurn(prompt);
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(noisy);
        AcpRuntime runtime = runtime(agent, new JSONObject());
        File dir = taskDir("noise");
        runBounded(runtime, dir, request("f1"), 10_000, "noise");
        check("completed".equals(read(dir, "done")), "noise: the turn completes despite stdout noise: " + read(dir, "done"));
        String message = json(dir, "result.txt").getString("message");
        check(message.startsWith("Wi-Fi is on") && message.contains("�"), "noise: exactly the real text (invalid byte replaced): " + message);
        check(!message.contains("LEAKED"), "noise: another session's chunk is not part of the reply");
        JSONObject refusal = null;
        for (JSONObject sent : agent.processes.get(0).received)
            if ("srv-1".equals(String.valueOf(sent.opt("id"))) && !sent.has("method")) refusal = sent;
        check(refusal != null && refusal.getJSONObject("error").getInt("code") == -32601,
                "noise: the unsupported agent request is refused with -32601: " + refusal);
        reclaimed(agent.processes.get(0), "noise");
        nextTurnWorks(runtime, "noise");
    }

    /** Partial answer, a truncated line, then exit 3 mid-prompt: failed at once, partial reply kept. */
    static void crashMidPrompt() throws Exception {
        Script crash = agent -> {
            agent.handshakeToPrompt();
            agent.chunk("Partial answer");
            agent.raw(bytes("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s-1\",\"upd"));
            agent.stderr = "fatal: segmentation violation\n";
            agent.exit(3);
        };
        ScriptedAgent agent = new ScriptedAgent(crash);
        AcpRuntime runtime = runtime(agent, new JSONObject());
        File dir = taskDir("crash");
        long millis = runBounded(runtime, dir, request("f2"), 10_000, "crash");
        JSONObject diagnostic = failed(dir, "crash", "agent-exited");
        check(diagnostic.getString("detail").contains("exit=3"), "crash: the exit code is recorded: " + diagnostic);
        check(json(dir, "result.txt").getString("message").contains("Partial answer"), "crash: the partial reply is kept");
        check(millis < 3000, "crash: the turn ends promptly, took " + millis + "ms");
        reclaimed(agent.processes.get(0), "crash");
        nextTurnWorks(runtime, "crash");
    }

    /** The agent closes stdout but keeps running and ignores stdin: failed, and the live process is killed. */
    static void stdoutClosedWhileAlive() throws Exception {
        Script half = agent -> {
            agent.handshakeToPrompt();
            agent.chunk("Working");
            agent.closeStdout();
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(half);
        AcpRuntime runtime = runtime(agent, new JSONObject());
        File dir = taskDir("half");
        long millis = runBounded(runtime, dir, request("f3"), 10_000, "stdout closed");
        failed(dir, "stdout closed", "agent-exited");
        check(millis < 3000, "stdout closed: the turn ends promptly, took " + millis + "ms");
        reclaimed(agent.processes.get(0), "stdout closed");
        nextTurnWorks(runtime, "stdout closed");
    }

    /** One line over the reader's 8M-char limit: failed with an oversized diagnostic, the process killed. */
    static void oversizedLine() throws Exception {
        Script huge = agent -> {
            agent.handshakeToPrompt();
            byte[] block = new byte[64 * 1024];
            Arrays.fill(block, (byte) 'x');
            for (long sent = 0; sent <= 8L * 1024 * 1024 + block.length; sent += block.length) agent.raw(block);
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(huge);
        AcpRuntime runtime = runtime(agent, new JSONObject());
        File dir = taskDir("huge");
        runBounded(runtime, dir, request("f4"), 15_000, "oversized");
        JSONObject diagnostic = failed(dir, "oversized", "agent-exited");
        check(diagnostic.getString("detail").contains("oversized"), "oversized: the diagnostic names the oversized message: " + diagnostic);
        reclaimed(agent.processes.get(0), "oversized");
        nextTurnWorks(runtime, "oversized");
    }

    /** initialize is never answered: the request timeout fails the turn and reclaims the process. */
    static void silentAgentTimesOut() throws Exception {
        Script silent = agent -> {
            agent.await("initialize");
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(silent);
        AcpRuntime runtime = runtime(agent, new JSONObject().put("requestTimeoutMillis", 400));
        File dir = taskDir("silent");
        long millis = runBounded(runtime, dir, request("f5"), 10_000, "silent");
        failed(dir, "silent", "no-response");
        check(millis < 3000, "silent: bounded by the request timeout, took " + millis + "ms");
        reclaimed(agent.processes.get(0), "silent");
        nextTurnWorks(runtime, "silent");
    }

    /** 2.5M chars of reply: the turn completes and the stored reply stays at the cap (about 1M chars). */
    static void floodIsCapped() throws Exception {
        String block = "y".repeat(64 * 1024);
        Script flood = agent -> {
            JSONObject prompt = agent.handshakeToPrompt();
            for (int i = 0; i < 40; i++) agent.chunk(block);
            agent.endTurn(prompt);
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(flood);
        AcpRuntime runtime = runtime(agent, new JSONObject());
        File dir = taskDir("flood");
        runBounded(runtime, dir, request("f6"), 20_000, "flood");
        check("completed".equals(read(dir, "done")), "flood: completes");
        int stored = json(dir, "result.txt").getString("message").length();
        check(stored >= 1024 * 1024 && stored <= 1024 * 1024 + block.length(), "flood: the reply is capped near 1M chars, got " + stored);
        reclaimed(agent.processes.get(0), "flood");
    }

    /** A cancel while the agent floods updates and never answers it: unconfirmed stop, bounded, process killed. */
    static void cancelDuringFlood() throws Exception {
        Script flood = agent -> {
            agent.handshakeToPrompt();
            for (; ; ) agent.chunk("more ");
        };
        ScriptedAgent agent = new ScriptedAgent(flood);
        AcpRuntime runtime = runtime(agent, new JSONObject().put("cancelGraceMillis", 500));
        File dir = taskDir("cancel-flood");
        Thread canceller = new Thread(() -> {
            try {
                while (agent.processes.isEmpty()) Thread.sleep(5);
                if (agent.processes.get(0).prompted.await(5, TimeUnit.SECONDS)) {
                    Thread.sleep(200);
                    Files.write(new File(dir, "cancel").toPath(), bytes("1"));
                }
            } catch (Exception ignored) {
                // The run's own checks report it.
            }
        });
        canceller.setDaemon(true);
        canceller.start();
        long millis = runBounded(runtime, dir, request("f7"), 10_000, "cancel during flood");
        failed(dir, "cancel during flood", "stop-unconfirmed");
        check(millis < 5000, "cancel during flood: the stop is bounded by the grace period, took " + millis + "ms");
        boolean cancelSent = false;
        for (String sent : agent.processes.get(0).fromClient) cancelSent |= "session/cancel".equals(new JSONObject(sent).optString("method"));
        check(cancelSent, "cancel during flood: session/cancel reached the agent");
        reclaimed(agent.processes.get(0), "cancel during flood");
        nextTurnWorks(runtime, "cancel during flood");
    }

    /**
     * Regression: the agent stops reading stdin (its pipe is full) as the prompt is written. The write
     * used to block forever, uninterruptibly, holding the turn past cancel and budget; the write
     * watchdog now kills the process and the turn fails promptly.
     */
    static void stdinStallIsKilled() throws Exception {
        Script stall = agent -> {
            JSONObject init = agent.await("initialize");
            agent.send(new JSONObject().put("id", init.get("id")).put("result", new JSONObject().put("protocolVersion", 1)));
            JSONObject session = agent.await("session/new");
            agent.stdin.stalled = true;
            agent.send(new JSONObject().put("id", session.get("id")).put("result", new JSONObject().put("sessionId", "s-1")));
            agent.awaitKilled();
        };
        ScriptedAgent agent = new ScriptedAgent(stall);
        AcpRuntime runtime = runtime(agent, new JSONObject().put("writeTimeoutMillis", 300));
        File dir = taskDir("stall");
        long millis = runBounded(runtime, dir, request("f8"), 8_000, "stdin stall");
        JSONObject diagnostic = failed(dir, "stdin stall", "agent-exited");
        check(diagnostic.getString("detail").contains("stopped reading its input"), "stdin stall: the diagnostic names the stall: " + diagnostic);
        check(millis < 4000, "stdin stall: bounded by the write timeout, took " + millis + "ms");
        reclaimed(agent.processes.get(0), "stdin stall");
        nextTurnWorks(runtime, "stdin stall");
    }

    // ---- long replies: AcpToolLabels.sanitize ------------------------------------------------------

    /** The regex sanitize ran before (quadratic on long delimiter-free runs): the oracle for the scanner. */
    private static final java.util.regex.Pattern REFERENCE_PATH = java.util.regex.Pattern.compile(
            "(?:file://)?(?:(?:\\.\\./)+|/)?(?:data/local/agentos/[^\\s)\\]\"'`<>]*|(?:[^\\s)\\]\"'`<>/]+/)*(?:acp/home|\\.gemini)/[^\\s)\\]\"'`<>]*)");
    private static final java.util.regex.Pattern REFERENCE_LINK = java.util.regex.Pattern.compile(
            "\\[([^\\]\\n]{0,200})\\]\\((?:conversation://[^)\\s]*|(?:file://)?(?:(?:\\.\\./)+|/)?(?:data/local/agentos/|[^)\\s]*(?:acp/home|\\.gemini)/)[^)\\s]*)\\)");

    static String referenceSanitize(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = REFERENCE_LINK.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
        java.util.regex.Matcher m = REFERENCE_PATH.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group();
            while (path.endsWith(".") || path.endsWith(",") || path.endsWith(";") || path.endsWith(":")) path = path.substring(0, path.length() - 1);
            String rest = m.group().substring(path.length());
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            String shown = base.matches("[^/]{1,80}\\.[A-Za-z0-9]{1,8}") ? base : "[internal file]";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(shown + rest));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The linear scanner equals the regex definition on random path-shaped text (fixed seed). */
    static void sanitizeMatchesRegexDefinition() {
        String[] parts = {"a", "b9", "/", "//", ".", "..", "../", "file://", "file:", "file:/", "acp/home/", "acp/", "home/",
                "acp/home", ".gemini/", ".gemini", "gemini/", "data/local/agentos/", "data/local/", "agentos/", "x.txt", "notes.md",
                " ", "\t", "\n", ")", "]", "\"", "'", "`", "<", ">", "(", "[", "[see](", "](", "conversation://c-1", ",", ":", ";",
                "中文", "\u2003", "\u00a0", "-", "_"};
        java.util.Random random = new java.util.Random(20261123L);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int k = random.nextInt(14); k >= 0; k--) text.append(parts[random.nextInt(parts.length)]);
            String input = text.toString(), expected = referenceSanitize(input), actual = AcpToolLabels.sanitize(input);
            if (!expected.equals(actual)) check(false, "sanitize differs from its regex definition for [" + input + "]: expected ["
                    + expected + "] got [" + actual + "]");
        }
        String sample = "See /data/local/agentos/work/report.pdf, file:///x/acp/home/.gemini/tmp/a.png; ../acp/home/y/ and "
                + "[notes](/data/local/agentos/n.md) or [child](conversation://c-2) then .gemini/cache/";
        check(referenceSanitize(sample).equals(AcpToolLabels.sanitize(sample)), "sanitize: the sample agrees: " + AcpToolLabels.sanitize(sample));
        check(AcpToolLabels.sanitize(sample).equals("See report.pdf, a.png; [internal file] and notes or child then [internal file]"),
                "sanitize: sample output " + AcpToolLabels.sanitize(sample));
        checks++;
    }

    /**
     * Regression: 1M characters without a delimiter (the reply cap; CJK prose, base64, minified JSON),
     * with and without an internal path in it. The regex took minutes; each must now take well under a second.
     */
    static void sanitizeIsLinearOnLongReplies() {
        String base64ish = "QUJD/ZGVm+aGlq".repeat(1024 * 1024 / 14);
        String[] inputs = {
                "y".repeat(1024 * 1024),
                "中文段落没有空格".repeat(1024 * 1024 / 8),
                base64ish,
                base64ish + " /data/local/agentos/acp/home/.gemini/a.txt",
                "/data/local/agentos/" + base64ish + "/acp/home/x",
                "z/".repeat(512 * 1024) + "acp/home/",
                "[a](b) ".repeat(1024 * 128) + ".gemini/"};
        for (String input : inputs) {
            long begin = System.nanoTime();
            String out = AcpToolLabels.sanitize(input);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            check(millis < 1500, "sanitize is linear: " + input.length() + " chars took " + millis + "ms");
            check(!out.contains("data/local/agentos/") && !out.contains("acp/home/") && !out.contains(".gemini/"),
                    "sanitize: no internal path survives in a long reply");
        }
        check(AcpToolLabels.sanitize(inputs[3]).endsWith(" a.txt"), "sanitize: a path after a long run still becomes its file name");
    }

    // ---- real OS processes --------------------------------------------------------------------------

    private static AcpProcessTransport.Connection sh(String script) throws IOException {
        return AcpProcessTransport.process().start(new AcpProcessTransport.Launch(List.of("/bin/sh", "-c", script), Map.of(), null));
    }

    private static boolean awaitDead(AcpProcessTransport.Connection connection, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (connection.alive() && System.nanoTime() < until) Thread.sleep(10);
        return !connection.alive();
    }

    /** A real process: noise and invalid UTF-8 before the answer, then a truncated line and exit 3. */
    static void realProcessNoiseThenCrash() throws Exception {
        AcpProcessTransport.Connection connection = sh(
                "IFS= read -r l; "
                        + "printf 'not json\\n\\377\\376 bad utf8\\n{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{}}\\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}\\n{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}\\n'; "
                        + "IFS= read -r l; printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"res'; exit 3");
        AcpAgentPool.Agent agent = new AcpAgentPool.Agent(connection, "", "");
        JSONObject init = agent.call("initialize", new JSONObject(), 5000);
        check(init.optInt("protocolVersion") == 1, "real process: the answer is found among the noise: " + init);
        long begin = System.nanoTime();
        try {
            agent.call("session/new", new JSONObject(), 5000);
            check(false, "real process: a crash mid-call must fail the call");
        } catch (AcpRuntime.AgentExited expected) {
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 3000, "real process: the crash wakes the caller at once");
        }
        check(awaitDead(connection, 3000) && Integer.valueOf(3).equals(connection.exitCode()),
                "real process: the exit code is reported, got " + connection.exitCode());
        agent.destroy(100);
    }

    /** Regression on a real pipe: a process that never reads stdin; a 4MB write is killed, not blocked forever. */
    static void realProcessStdinStall() throws Exception {
        AcpProcessTransport.Connection connection = sh("exec sleep 30");
        AcpAgentPool.Agent agent = new AcpAgentPool.Agent(connection, "", "");
        agent.writeTimeoutMillis = 300;
        JSONArray big = new JSONArray().put("z".repeat(4 * 1024 * 1024));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                agent.send(new JSONObject().put("id", 1).put("method", "session/prompt").put("params", new JSONObject().put("prompt", big)));
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
        }, "acp-fault-writer");
        writer.setDaemon(true);
        long begin = System.nanoTime();
        writer.start();
        writer.join(5000);
        boolean stuck = writer.isAlive();
        if (stuck) connection.destroy(0); // Unblock the stuck writer so the suite ends.
        check(!stuck, "real stdin stall: the write blocked past the watchdog (no write timeout)");
        check(outcome.get() instanceof AcpRuntime.AgentExited && String.valueOf(outcome.get().getMessage()).contains("stopped reading"),
                "real stdin stall: the write fails as a stalled agent: " + outcome.get());
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 4000, "real stdin stall: bounded by the write timeout");
        check(awaitDead(connection, 3000), "real stdin stall: the process is killed");
        agent.destroy(100);
    }

    /** destroy() of a process that ignores SIGTERM still ends it, in bounded time. */
    static void realProcessIgnoringTermIsKilled() throws Exception {
        AcpProcessTransport.Connection connection = sh("trap '' TERM; while :; do sleep 1; done");
        Thread.sleep(100); // the trap is installed
        long begin = System.nanoTime();
        connection.destroy(200);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        check(awaitDead(connection, 2000), "TERM ignored: the process is killed");
        check(millis < 3000, "TERM ignored: destroy is bounded, took " + millis + "ms");
        connection.destroy(200); // idempotent
    }

    public static void main(String[] args) throws Exception {
        PhoneTurnLog.install();
        List<String> ran = new ArrayList<>();
        noiseIsSkipped(); ran.add("noise");
        crashMidPrompt(); ran.add("crash-mid-prompt");
        stdoutClosedWhileAlive(); ran.add("stdout-closed");
        oversizedLine(); ran.add("oversized-line");
        silentAgentTimesOut(); ran.add("silent-timeout");
        floodIsCapped(); ran.add("flood-cap");
        cancelDuringFlood(); ran.add("cancel-flood");
        stdinStallIsKilled(); ran.add("stdin-stall");
        sanitizeMatchesRegexDefinition(); ran.add("sanitize-oracle");
        sanitizeIsLinearOnLongReplies(); ran.add("sanitize-linear");
        realProcessNoiseThenCrash(); ran.add("real-noise-crash");
        realProcessStdinStall(); ran.add("real-stdin-stall");
        realProcessIgnoringTermIsKilled(); ran.add("real-term-ignored");
        System.out.println("AcpFaultInjectionTest passed " + checks + " checks " + ran);
    }
}
