package org.agentos.lab;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One warm {@code agy acp} process kept between ACP turns.
 *
 * <p>agy answers {@code initialize} in ~0.5s but boots its backend lazily on the first session call
 * (~5s on the Pixel 9, agy 1.5.14); afterwards {@code session/new} takes ~0.2s. A cold turn pays that
 * boot before its first token, so the pool keeps a single process that has already run
 * {@code initialize} and a read-only warm-up ({@code session/list}).
 *
 * <ul>
 *   <li><b>One slot, idle TTL.</b> At most one idle process (~240MB); it is destroyed after
 *       {@code idleTtlMillis} without a turn, after {@code maxAgeMillis} or {@code maxTurns}, or once it
 *       has accumulated {@code maxMcpConfigs} distinct phone MCP servers (agy keeps one per distinct
 *       declaration and does not stop it on {@code session/close}).</li>
 *   <li><b>Fingerprint and stamp.</b> A process serves only turns with the same command line,
 *       environment and working directory (fingerprint), and only while its stamp holds: the agy
 *       binary on disk (size, mtime) and the broker state it depends on (runtime.json, the sign-in
 *       status file). A changed stamp retires it; a turn with another command line (another
 *       permission mode) just starts cold and leaves it warm.</li>
 *   <li><b>Probe.</b> Before reuse the process must answer a JSON-RPC request (agy answers an unknown
 *       method with -32601 at once); a dead or silent process is destroyed and the turn starts cold.</li>
 *   <li><b>Release.</b> {@link AcpRuntime} returns a process only after a turn that ended with the
 *       agent idle (its prompt answered, no steer in flight, no subagents); everything else (a stop
 *       agy never confirmed, a crash, a budget timeout, sign-in) destroys it.</li>
 * </ul>
 */
final class AcpAgentPool {
    static final long DEFAULT_IDLE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(13);
    static final long DEFAULT_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(2);
    static final int DEFAULT_MAX_TURNS = 40;
    static final int DEFAULT_MAX_MCP_CONFIGS = 8;
    static final long PROBE_TIMEOUT_MILLIS = 1500;
    static final long WARMUP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45);
    /** How long a turn waits for a warm-up already in flight before it starts cold instead. */
    static final long WARMING_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final int MAX_LINE_CHARS = 8 * 1024 * 1024;

    /** End of the agent's stdout in {@link Agent#inbox}. */
    static final Object EOF = new Object();

    // ---- one live agent process -------------------------------------------------------------------

    /**
     * A started agent process. Its stdio readers, inbox and JSON-RPC id space belong to the process,
     * not to one turn, so a warm process can serve turn after turn.
     */
    static final class Agent {
        final AcpProcessTransport.Connection connection;
        final String fingerprint, stamp, binary;
        final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        final long startedAt = System.nanoTime();
        /** Distinct phone MCP declarations sessions of this process were given. */
        final Set<String> mcpConfigs = ConcurrentHashMap.newKeySet();
        private final StringBuilder stderrTail = new StringBuilder();
        private final AtomicLong nextId = new AtomicLong(1);
        private final OutputStream out;
        /** The {@code initialize} result once the handshake is done (warm processes arrive with it). */
        volatile JSONObject init;
        volatile boolean eof, warmedUp;
        volatile long idleSince;
        int turns;

        Agent(AcpProcessTransport.Connection connection, String fingerprint, String stamp) {
            this(connection, fingerprint, stamp, "");
        }

        Agent(AcpProcessTransport.Connection connection, String fingerprint, String stamp, String binary) {
            this.connection = connection;
            this.fingerprint = fingerprint;
            this.stamp = stamp;
            this.binary = binary;
            this.out = connection.stdin();
            startReaders();
        }

        long nextId() {
            return nextId.getAndIncrement();
        }

        boolean alive() {
            return !eof && connection.alive();
        }

        String stderr() {
            synchronized (stderrTail) {
                return stderrTail.toString();
            }
        }

        void destroy(long graceMillis) {
            connection.destroy(graceMillis);
        }

        void send(JSONObject message) throws IOException {
            byte[] line;
            try {
                line = (message.put("jsonrpc", "2.0").toString() + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (Exception impossible) {
                throw new IOException("unserializable message");
            }
            try {
                synchronized (out) {
                    out.write(line);
                    out.flush();
                }
            } catch (IOException broken) {
                throw new AcpRuntime.AgentExited("agent stdin closed");
            }
        }

        /**
         * One request outside a turn (warm-up, probe): other lines are dropped, agent requests refused.
         * Throws {@link AcpRuntime.AcpError} for an error answer.
         */
        JSONObject call(String method, JSONObject params, long timeoutMillis) throws Exception {
            long id = nextId();
            send(new JSONObject().put("id", id).put("method", method).put("params", params));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            for (; ; ) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new AcpRuntime.NoResponse(method);
                Object next = inbox.poll(Math.min(TimeUnit.NANOSECONDS.toMillis(left) + 1, 50), TimeUnit.MILLISECONDS);
                if (next == null) continue;
                if (next == EOF) throw new AcpRuntime.AgentExited("agent closed stdout during " + method);
                if (next instanceof IOException) throw (IOException) next;
                JSONObject message;
                try {
                    message = new JSONObject((String) next);
                } catch (Exception notJson) {
                    continue;
                }
                if (message.has("id") && !message.has("method")) {
                    if (message.optLong("id", -1) != id) continue;
                    if (message.has("error")) {
                        JSONObject error = message.optJSONObject("error");
                        throw new AcpRuntime.AcpError(error == null ? -32603 : error.optInt("code", -32603),
                                error == null ? "error" : error.optString("message", "error"));
                    }
                    JSONObject result = message.optJSONObject("result");
                    return result == null ? new JSONObject() : result;
                }
                if (message.has("id")) refuse(message);
            }
        }

        /** An agent request nobody owns (no turn is running): a permission is cancelled, the rest unsupported. */
        void refuse(JSONObject request) throws Exception {
            Object id = request.get("id");
            if ("session/request_permission".equals(request.optString("method")))
                send(new JSONObject().put("id", id).put("result", new JSONObject().put("outcome", new JSONObject().put("outcome", "cancelled"))));
            else
                send(new JSONObject().put("id", id).put("error", new JSONObject().put("code", -32601).put("message", "Method not found: " + request.optString("method"))));
        }

        private void startReaders() {
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
                            inbox.put(new AcpRuntime.AgentExited("agent sent an oversized message"));
                            return;
                        }
                    }
                    if (line.length() > 0) inbox.put(line.toString());
                } catch (Exception ignored) {
                    // A broken pipe is the agent going away: report EOF below.
                } finally {
                    eof = true;
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
    }

    // ---- the pool ---------------------------------------------------------------------------------

    private final AcpProcessTransport transport;
    /** Broker state a warm process depends on (runtime.json, sign-in status); "" when none. */
    private final Supplier<String> stamp;
    final long idleTtlMillis, maxAgeMillis, warmingWaitMillis;
    final int maxTurns, maxMcpConfigs;

    private Agent idle;
    private boolean warming;
    private String warmingFingerprint = "";
    private int checkedOut;
    private Thread reaper;

    /** Counters for diagnostics and tests. */
    final AtomicLong started = new AtomicLong(), reused = new AtomicLong(), warmed = new AtomicLong(),
            retired = new AtomicLong(), probeFailures = new AtomicLong();
    volatile String lastRetireReason = "";

    AcpAgentPool(AcpProcessTransport transport, Supplier<String> stamp) {
        this(transport, stamp, DEFAULT_IDLE_TTL_MILLIS, DEFAULT_MAX_AGE_MILLIS, DEFAULT_MAX_TURNS, DEFAULT_MAX_MCP_CONFIGS, WARMING_WAIT_MILLIS);
    }

    AcpAgentPool(AcpProcessTransport transport, Supplier<String> stamp, long idleTtlMillis, long maxAgeMillis,
                 int maxTurns, int maxMcpConfigs, long warmingWaitMillis) {
        this.transport = transport;
        this.stamp = stamp == null ? () -> "" : stamp;
        this.idleTtlMillis = idleTtlMillis;
        this.maxAgeMillis = maxAgeMillis;
        this.maxTurns = maxTurns;
        this.maxMcpConfigs = maxMcpConfigs;
        this.warmingWaitMillis = warmingWaitMillis;
    }

    /** The launch identity: command line, environment and working directory. */
    static String fingerprint(AcpProcessTransport.Launch launch) {
        StringBuilder key = new StringBuilder();
        key.append(String.join("\u0000", launch.command)).append('\u0001');
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(launch.env).entrySet())
            key.append(e.getKey()).append('=').append(e.getValue()).append('\u0000');
        key.append('\u0001').append(launch.cwd == null ? "" : launch.cwd.getPath());
        return key.toString();
    }

    /** What a process of {@code launch} stays valid for: its binary on disk and the broker's stamp. */
    String stamp(AcpProcessTransport.Launch launch) {
        String binary = launch.command.isEmpty() ? "" : launch.command.get(0);
        return stamp(binary);
    }

    String stamp(String binaryPath) {
        File binary = new File(binaryPath);
        String broker;
        try {
            broker = stamp.get();
        } catch (RuntimeException unreadable) {
            broker = "unreadable";
        }
        return binaryPath + ":" + binary.length() + ":" + binary.lastModified() + "|" + (broker == null ? "" : broker);
    }

    /** A new process for a cold turn; counted as checked out until {@link #release}. */
    Agent startCold(AcpProcessTransport.Launch launch) throws IOException {
        Agent agent = new Agent(transport.start(launch), fingerprint(launch), stamp(launch), binaryOf(launch));
        started.incrementAndGet();
        synchronized (this) {
            checkedOut++;
        }
        return agent;
    }

    /**
     * The warm process for {@code launch}, probed and checked out, or null (start cold). Waits for a
     * warm-up of the same launch that is already in flight (it finishes sooner than a cold start).
     */
    Agent take(AcpProcessTransport.Launch launch) {
        return take(launch, null);
    }

    /**
     * As {@link #take(AcpProcessTransport.Launch)}; while it waits for a warm-up in flight it runs
     * {@code idle} every 100ms (outside the pool lock), so the broker keeps serving native RPC.
     */
    Agent take(AcpProcessTransport.Launch launch, Runnable idleHook) {
        String fingerprint = fingerprint(launch), stampNow = stamp(launch);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(warmingWaitMillis);
        for (; ; ) {
            synchronized (this) {
                if (!(warming && warmingFingerprint.equals(fingerprint) && idle == null) || System.nanoTime() >= until) break;
                try {
                    wait(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (idleHook != null) try {
                idleHook.run();
            } catch (RuntimeException failure) {
                System.err.println("acp pool idle hook: " + failure);
            }
        }
        Agent candidate;
        synchronized (this) {
            candidate = idle;
            if (candidate == null) return null;
            if (!candidate.stamp.equals(stampNow)) {
                idle = null;
                retire(candidate, "stamp-changed");
                return null;
            }
            // Another command line (another permission mode) runs cold; the warm process stays.
            if (!candidate.fingerprint.equals(fingerprint)) return null;
            idle = null;
            checkedOut++;
        }
        if (!probe(candidate)) {
            probeFailures.incrementAndGet();
            synchronized (this) {
                checkedOut--;
            }
            retire(candidate, "probe-failed");
            return null;
        }
        reused.incrementAndGet();
        return candidate;
    }

    /** Whether the process is alive and answers a request (agy: -32601 for an unknown method, at once). */
    static boolean probe(Agent agent) {
        if (!agent.alive()) return false;
        try {
            agent.call("$/ping", new JSONObject(), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (AcpRuntime.AcpError answered) {
            return true;
        } catch (Exception silentOrDead) {
            return false;
        }
    }

    /** Back from a turn. Kept warm only when {@code reusable} and still within its limits. */
    void release(Agent agent, boolean reusable) {
        if (agent == null) return;
        String reason = null;
        synchronized (this) {
            checkedOut = Math.max(0, checkedOut - 1);
            agent.turns++;
            if (!reusable) reason = "turn-not-reusable";
            else if (!agent.alive()) reason = "exited";
            else if (idleTtlMillis <= 0) reason = "pool-disabled";
            else if (agent.turns >= maxTurns) reason = "max-turns";
            else if (System.nanoTime() - agent.startedAt >= TimeUnit.MILLISECONDS.toNanos(maxAgeMillis)) reason = "max-age";
            else if (agent.mcpConfigs.size() >= maxMcpConfigs) reason = "mcp-servers";
            else if (!agent.stamp.equals(stamp(agent.binary))) reason = "stamp-changed";
            else if (idle != null) reason = "slot-taken";
            else {
                agent.idleSince = System.nanoTime();
                idle = agent;
                scheduleReaper();
                notifyAll();
            }
        }
        if (reason != null) retire(agent, reason);
    }

    /** Destroys the idle process (a stop agy never confirmed, a configuration change). */
    void invalidate(String reason) {
        Agent victim;
        synchronized (this) {
            victim = idle;
            idle = null;
        }
        if (victim != null) retire(victim, reason);
    }

    /**
     * Starts a warm process for {@code launch} in the background: {@code initialize} plus a read-only
     * {@code session/list} that boots agy's backend. A no-op while one is idle or warming for the same
     * launch, or while a turn has one checked out (it comes back when the turn ends).
     */
    void prewarm(AcpProcessTransport.Launch launch, String reason) {
        String fingerprint = fingerprint(launch), stampNow = stamp(launch);
        Agent stale = null;
        synchronized (this) {
            if (idleTtlMillis <= 0 || warming || checkedOut > 0) return;
            if (idle != null) {
                if (idle.fingerprint.equals(fingerprint) && idle.stamp.equals(stampNow) && idle.alive()) return;
                stale = idle;
                idle = null;
            }
            warming = true;
            warmingFingerprint = fingerprint;
        }
        if (stale != null) retire(stale, "replaced-by-prewarm");
        Thread worker = new Thread(() -> warm(launch, fingerprint, stampNow, reason), "acp-prewarm");
        worker.setDaemon(true);
        worker.start();
    }

    private void warm(AcpProcessTransport.Launch launch, String fingerprint, String stampNow, String reason) {
        Agent agent = null;
        long begin = System.nanoTime();
        try {
            agent = new Agent(transport.start(launch), fingerprint, stampNow, binaryOf(launch));
            started.incrementAndGet();
            JSONObject init = agent.call("initialize", AcpRuntime.initializeParams(), TimeUnit.SECONDS.toMillis(60));
            if (init.optInt("protocolVersion", -1) != AcpRuntime.PROTOCOL_VERSION) throw new IOException("protocol " + init.opt("protocolVersion"));
            agent.init = init;
            try {
                agent.call("session/list", new JSONObject(), WARMUP_TIMEOUT_MILLIS);
            } catch (AcpRuntime.AcpError answered) {
                // Any answer means the backend is up (an agent without session/list answers -32601).
            }
            agent.warmedUp = true;
            warmed.incrementAndGet();
            System.err.println("acp prewarm (" + reason + ") ready in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) + "ms");
        } catch (Exception failed) {
            System.err.println("acp prewarm (" + reason + ") failed: " + failed);
            if (agent != null) retire(agent, "prewarm-failed");
            agent = null;
        } finally {
            Agent keep = agent, extra = null;
            synchronized (this) {
                warming = false;
                warmingFingerprint = "";
                if (keep != null) {
                    if (idle == null) {
                        keep.idleSince = System.nanoTime();
                        idle = keep;
                        scheduleReaper();
                    } else extra = keep;
                }
                notifyAll();
            }
            if (extra != null) retire(extra, "slot-taken");
        }
    }

    /** Destroys the idle process once its TTL ran out. Called by the reaper thread; tests call it directly. */
    void reap() {
        Agent victim = null;
        synchronized (this) {
            if (idle != null && System.nanoTime() - idle.idleSince >= TimeUnit.MILLISECONDS.toNanos(idleTtlMillis)) {
                victim = idle;
                idle = null;
            } else if (idle != null && !idle.alive()) {
                victim = idle;
                idle = null;
            }
        }
        if (victim != null) retire(victim, victim.alive() ? "idle-ttl" : "exited");
    }

    synchronized boolean hasIdle() {
        return idle != null;
    }

    synchronized boolean isWarming() {
        return warming;
    }

    synchronized int checkedOut() {
        return checkedOut;
    }

    /** Waits until no warm-up is in flight (tests, and the broker's own diagnostics). */
    synchronized boolean awaitWarm(long timeoutMillis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (warming) {
            long left = until - System.nanoTime();
            if (left <= 0) return false;
            wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
        }
        return idle != null;
    }

    private static String binaryOf(AcpProcessTransport.Launch launch) {
        return launch.command.isEmpty() ? "" : launch.command.get(0);
    }

    private void retire(Agent agent, String reason) {
        retired.incrementAndGet();
        lastRetireReason = reason;
        try {
            agent.destroy(1000);
        } catch (RuntimeException ignored) {
            // Best effort: the process transport kills forcibly after the grace period.
        }
    }

    private void scheduleReaper() {
        if (reaper != null && reaper.isAlive()) return;
        long interval = Math.max(50, Math.min(TimeUnit.SECONDS.toMillis(30), idleTtlMillis / 4));
        reaper = new Thread(() -> {
            for (; ; ) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException interrupted) {
                    return;
                }
                reap();
                synchronized (this) {
                    if (idle == null && !warming) {
                        reaper = null;
                        return;
                    }
                }
            }
        }, "acp-pool-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }
}
