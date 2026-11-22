package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The warm agent pool ({@link AcpAgentPool}) under {@link AcpRuntime}: reuse across turns, prewarm,
 * fingerprint and stamp invalidation, idle TTL, the liveness probe, explicit phone-turn release with a
 * process that outlives turns, cancel that keeps the process vs. an unconfirmed stop that kills it,
 * steer / continuation / subagents on warm processes, the native phone MCP relay declaration, and the
 * broker helpers of the same change (route-dependent display preparation, coalesced notifications,
 * the bridge's per-call turn).
 */
public final class AcpWarmPoolTest {
    private static int checks;
    static final String CONV = AcpChatRuntimeTest.CONV;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static File taskDir(String id) throws Exception {
        File dir = Files.createTempDirectory("acp-pool-" + id).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static String read(File dir, String name) throws Exception {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    private static JSONObject chat(String id, String prompt) {
        return new JSONObject().put("id", id).put("prompt", prompt).put("permissionMode", "full")
                .put("conversationId", CONV).put("screenshot", "").put("includeScreenshot", false);
    }

    private static AcpRuntime.Options options(File binary) throws Exception {
        return AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", binary.getPath()).put("cwd", "").put("home", binary.getParent())
                .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000).put("cancelGraceMillis", 1000)
                .put("autoContinueBackoffMillis", new JSONArray().put(10)));
    }

    private static File binary() throws Exception {
        File dir = Files.createTempDirectory("acp-pool-bin").toFile();
        dir.deleteOnExit();
        File agy = new File(dir, "agy");
        Files.write(agy.toPath(), "v1".getBytes(StandardCharsets.UTF_8));
        return agy;
    }

    static final class Env {
        final File binary;
        final AtomicReference<String> stamp = new AtomicReference<>("runtime-1");
        final FakeAcpAgent agent;
        final AcpAgentPool pool;
        final AcpChatRuntimeTest.Host host;

        Env(FakeAcpAgent agent, long ttlMillis) throws Exception {
            this.binary = binary();
            this.agent = agent;
            agent.loadSession = true;
            this.pool = new AcpAgentPool(agent, stamp::get, ttlMillis, TimeUnit.HOURS.toMillis(1), 40, 8, 5000);
            this.host = new AcpChatRuntimeTest.Host(new File(taskDir("store"), "acp-conversations"));
        }

        AcpRuntime runtime() throws Exception {
            return new AcpRuntime(options(binary), agent, null, null, host, pool);
        }

        File run(String id, String prompt) throws Exception {
            File dir = taskDir(id);
            // Like TaskBroker's queue: the request is submitted by its ready mark (claimDelayMs).
            Files.write(new File(dir, "ready").toPath(), new byte[0]);
            runtime().run(dir, chat(id, prompt));
            return dir;
        }
    }

    private static JSONObject info(File dir) throws Exception {
        return new JSONObject(read(dir, "runtime-info.json"));
    }

    private static final String[] TIMING_KEYS = {"poolState", "warmWaitMs", "claimDelayMs", "sessionPrepareMs",
            "configOptionMs", "totalPrepMs", "promptToFirstChunkMs", "promptToFirstTextMs", "promptToFirstToolMs", "turnTotalMs"};

    /** runtime-info.json's timings: every key present, the milliseconds non-negative and consistent. */
    private static JSONObject timings(File dir, String poolState) throws Exception {
        JSONObject t = info(dir).getJSONObject("timings");
        for (String key : TIMING_KEYS) check(t.has(key), "timings." + key + " present: " + t);
        check(poolState.equals(t.getString("poolState")), "timings.poolState " + poolState + ": " + t);
        for (String key : TIMING_KEYS)
            if (key.endsWith("Ms") && !t.isNull(key)) check(t.getLong(key) >= 0, "timings." + key + " >= 0: " + t);
        check(t.getLong("totalPrepMs") >= t.getLong("sessionPrepareMs") && t.getLong("turnTotalMs") >= t.getLong("totalPrepMs"),
                "timings are ordered (session <= prep <= total): " + t);
        check(t.getLong("promptToFirstTextMs") >= t.getLong("promptToFirstChunkMs"), "first chunk precedes first text: " + t);
        return t;
    }

    private static JSONObject phoneServer(JSONObject sessionCall) {
        JSONArray servers = sessionCall.getJSONObject("params").getJSONArray("mcpServers");
        return servers.getJSONObject(servers.length() - 1);
    }

    /** Turn 2 reuses turn 1's process: one launch, one initialize, a warm runtime-info, identical MCP declaration. */
    static void reuseAcrossTurns() throws Exception {
        PhoneTurnLog.clear();
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        File t1 = env.run("701", "first");
        check("completed".equals(read(t1, "done")) && "cold".equals(info(t1).getString("agentProcess")), "turn 1: cold start completes");
        JSONObject cold = timings(t1, "cold_start");
        check(cold.has("initializeMs") && !cold.has("pingMs"), "cold start: a handshake time, no warm probe: " + cold);
        check(!cold.isNull("promptToFirstToolMs") && cold.getLong("promptToFirstToolMs") >= cold.getLong("promptToFirstChunkMs"),
                "cold start: the first tool call is timed after the first update: " + cold);
        check(env.pool.hasIdle() && env.pool.checkedOut() == 0 && !env.pool.empty(), "turn 1: the idle agent is kept warm (pool not empty)");
        File t2 = env.run("702", "second");
        check("completed".equals(read(t2, "done")), "turn 2: completes on the warm process");
        check(env.agent.launches.size() == 1 && env.agent.methods("initialize").size() == 1,
                "turn 2: no new process and no second initialize: launches=" + env.agent.launches.size());
        check("warm".equals(info(t2).getString("agentProcess")), "turn 2: runtime-info says warm");
        JSONObject hit = timings(t2, "warm_hit");
        check(hit.has("pingMs") && !hit.has("initializeMs"), "warm hit: the probe is timed, no handshake: " + hit);
        check(env.agent.methods("$/ping").size() == 1, "turn 2: the warm process was probed before reuse");
        List<JSONObject> loads = env.agent.methods("session/load");
        check(loads.size() == 1 && "s-1".equals(loads.get(0).getJSONObject("params").getString("sessionId")),
                "turn 2: the conversation's session is loaded in the warm process");
        String first = phoneServer(env.agent.methods("session/new").get(0)).toString();
        String second = phoneServer(loads.get(0)).toString();
        check(first.equals(second), "turn 2: the phone MCP declaration is identical (agy keeps one relay): " + first + " vs " + second);
        // Explicit, per-turn release while the MCP connection (the process) lives on.
        String turn1 = env.host.store.turns(CONV).getJSONObject(0).getString("id");
        String turn2 = env.host.store.turns(CONV).getJSONObject(1).getString("id");
        check(PhoneTurnLog.events().equals(List.of("start " + CONV + " " + turn1, "end " + CONV + " " + turn1,
                "start " + CONV + " " + turn2, "end " + CONV + " " + turn2)),
                "phone turns: each turn is started and ended explicitly, in order: " + PhoneTurnLog.events());
        check(AcpPhoneTurns.current(CONV) == null, "phone turns: no turn stays current after the task ends");
        check(env.agent.processes.get(0).alive(), "the warm process outlives both turns");
        env.pool.invalidate("test");
        check(!env.agent.processes.get(0).alive(), "invalidate destroys the idle process");
    }

    /** prewarm: spawn + initialize + session/list in the background; the first turn then starts warm. */
    static void prewarmThenFirstTurn() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        long begin = System.nanoTime();
        env.runtime().prewarm("test");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 500, "prewarm never blocks its caller");
        check(env.pool.awaitWarm(5000), "prewarm: a warm process is ready");
        check(env.agent.methods("initialize").size() == 1 && env.agent.methods("session/list").size() == 1,
                "prewarm: initialize plus the session/list warm-up");
        env.runtime().prewarm("again");
        check(env.agent.launches.size() == 1, "prewarm: a second prewarm with one idle is a no-op");
        File t1 = env.run("711", "hello");
        check("completed".equals(read(t1, "done")) && "warm".equals(info(t1).getString("agentProcess")),
                "first turn after prewarm runs warm");
        check(timings(t1, "warm_hit").getLong("warmWaitMs") < 1000, "a ready warm process is taken without waiting");
        check(env.agent.launches.size() == 1 && env.agent.methods("initialize").size() == 1, "first turn: no cold start");
        List<String> command = env.agent.launches.get(0).command;
        check(command.contains("--dangerously-skip-permissions"), "prewarm uses the full-access command line of chat turns");
        env.pool.invalidate("test");
    }

    /** A take waits for a warm-up already in flight instead of starting a second process. */
    static void takeWaitsForWarming() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        env.agent.sessionListDelayMillis = 400;
        env.runtime().prewarm("test");
        File t1 = env.run("715", "right away");
        check("completed".equals(read(t1, "done")) && env.agent.launches.size() == 1,
                "a turn during the warm-up uses the warming process: launches=" + env.agent.launches.size());
        JSONObject waited = timings(t1, "waited_prewarm");
        check(waited.getLong("warmWaitMs") >= 200, "waited_prewarm: the wait for the warm-up is counted: " + waited);
        env.pool.invalidate("test");
    }

    /** A replaced binary or a changed runtime.json/sign-in stamp retires the warm process; another permission mode runs cold. */
    static void fingerprintAndStamp() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        env.run("721", "one");
        check(env.pool.hasIdle(), "warm after turn 1");
        Files.write(env.binary.toPath(), "v2-longer".getBytes(StandardCharsets.UTF_8));
        env.run("722", "two");
        check(env.agent.launches.size() == 2, "binary change: the turn starts cold: launches=" + env.agent.launches.size());
        check(!env.agent.processes.get(0).alive(), "binary change: the old process is destroyed");
        check(env.pool.hasIdle(), "binary change: the new process is warm afterwards");
        env.stamp.set("runtime-2");
        env.run("723", "three");
        check(env.agent.launches.size() == 3 && !env.agent.processes.get(1).alive(), "runtime.json/sign-in change: rebuilt");
        // Another permission mode: its own command line starts cold, the warm full-access process stays.
        File ro = taskDir("724");
        env.runtime().run(ro, chat("724", "read only").put("permissionMode", "readOnly"));
        check(env.agent.launches.size() == 4 && env.agent.processes.get(2).alive() && !env.agent.processes.get(3).alive(),
                "another command line runs cold and leaves the warm process alone");
        env.run("725", "full again");
        check(env.agent.launches.size() == 4, "the full-access turn is warm again");
        env.pool.invalidate("test");
    }

    /** The idle TTL destroys an unused warm process. */
    static void idleTtl() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), 200);
        env.run("731", "one");
        check(env.pool.hasIdle(), "warm right after the turn");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (env.pool.hasIdle() && System.nanoTime() < until) Thread.sleep(20);
        check(!env.pool.hasIdle() && !env.agent.processes.get(0).alive() && "idle-ttl".equals(env.pool.lastRetireReason),
                "idle TTL: the process is reclaimed: " + env.pool.lastRetireReason);
        env.run("732", "two");
        check(env.agent.launches.size() == 2, "after the TTL a turn starts cold again");
        env.pool.invalidate("test");
    }

    /** A process that died while idle (or no longer answers) is not reused: the turn starts cold. */
    static void probeFailureFallsBackCold() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        env.run("741", "one");
        env.agent.processes.get(0).destroy(0);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!env.pool.empty() && System.nanoTime() < until) Thread.sleep(10);
        check(env.pool.empty(), "a dead idle process counts as an empty pool (a reopen may warm again)");
        File t2 = env.run("742", "two");
        check("completed".equals(read(t2, "done")) && "cold".equals(info(t2).getString("agentProcess")) && env.agent.launches.size() == 2,
                "a dead warm process is replaced by a cold start");
        env.pool.invalidate("test");
    }

    /** Cancel: a confirmed session/cancel keeps the warm process; an unconfirmed stop kills it and empties the pool. */
    static void cancelKeepsOrKills() throws Exception {
        PhoneTurnLog.clear();
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL), TimeUnit.MINUTES.toMillis(5));
        File dir = taskDir("751");
        Thread run = new Thread(() -> {
            try { env.runtime().run(dir, chat("751", "hang")); } catch (Exception e) { throw new RuntimeException(e); }
        });
        run.start();
        check(env.agent.promptStarted.await(5, TimeUnit.SECONDS), "cancel: the prompt runs");
        Files.write(new File(dir, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        run.join(10_000);
        check("cancelled".equals(read(dir, "done")), "cancel: the turn is cancelled");
        check(env.agent.methods("session/cancel").size() == 1, "cancel: session/cancel was sent");
        check(env.pool.hasIdle() && env.agent.processes.get(0).alive(), "cancel confirmed: the warm process is kept");
        String turn = env.host.store.turns(CONV).getJSONObject(0).getString("id");
        check(PhoneTurnLog.events().contains("end " + CONV + " " + turn), "cancel: the turn's screen is released explicitly");
        env.pool.invalidate("test");

        Env stuck = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL, FakeAcpAgent.Scenario.IGNORE_CANCEL), TimeUnit.MINUTES.toMillis(5));
        stuck.run("752", "warm up");
        check(stuck.pool.hasIdle(), "unconfirmed: a warm process exists first");
        // The next turn needs another command line to get the IGNORE_CANCEL process; the warm one stays idle meanwhile.
        File hung = taskDir("753");
        Thread second = new Thread(() -> {
            try { stuck.runtime().run(hung, chat("753", "ignore").put("permissionMode", "ask")); } catch (Exception e) { throw new RuntimeException(e); }
        });
        second.start();
        check(stuck.agent.promptStarted.await(5, TimeUnit.SECONDS), "unconfirmed: the prompt runs");
        Files.write(new File(hung, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        second.join(10_000);
        check("failed".equals(read(hung, "done")), "unconfirmed: the turn fails (stop unconfirmed)");
        check(!stuck.agent.processes.get(1).alive(), "unconfirmed: the agent that ignored the cancel is killed");
        check(!stuck.pool.hasIdle() && !stuck.agent.processes.get(0).alive(), "unconfirmed: the pool is invalidated too");
    }

    /** Native steer works on a warm process, and the pool keeps it afterwards. */
    static void steerOnWarmProcess() throws Exception {
        FakeAcpAgent fake = new FakeAcpAgent(FakeAcpAgent.Scenario.STEER);
        Env env = new Env(fake, TimeUnit.MINUTES.toMillis(5));
        for (int round = 1; round <= 2; round++) {
            fake.promptStarted = new CountDownLatch(1);
            File root = taskDir("steer" + round);
            File tasks = new File(root, "tasks");
            String id = "76" + round;
            File dir = new File(tasks, id);
            dir.mkdirs();
            JSONObject request = chat(id, "list fruits " + round);
            TaskRecords.write(new File(dir, "request.json"), request.toString());
            TaskRecords.write(new File(dir, "ready"), "1");
            AcpRuntime runtime = env.runtime();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread run = new Thread(() -> {
                try { runtime.run(dir, request); } catch (Throwable t) { failure.set(t); }
            });
            run.start();
            check(fake.promptStarted.await(5, TimeUnit.SECONDS), "steer round " + round + ": the prompt runs");
            JSONArray turns = env.host.store.turns(CONV);
            String turnId = turns.getJSONObject(turns.length() - 1).getString("id");
            JSONObject answer = AcpTurnControl.steer(env.host.store, tasks, CONV, turnId, "req-" + round, "also STEER_" + round, new JSONArray(), false, 5000);
            check("accepted".equals(answer.getString("state")), "steer round " + round + ": accepted: " + answer);
            run.join(10_000);
            check(failure.get() == null && "completed".equals(read(dir, "done")) && read(dir, "result.txt").contains("Final answer."),
                    "steer round " + round + ": the steered turn completes");
            check(env.pool.hasIdle(), "steer round " + round + ": the process goes back to the pool");
        }
        check(fake.launches.size() == 1, "steer: both rounds on one warm process");
        env.pool.invalidate("test");
    }

    /** A transient crash: the crashed process is never pooled; the continuation runs cold and is pooled after. */
    static void autoContinueWithPool() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.CRASH_NETWORK, FakeAcpAgent.Scenario.NORMAL), TimeUnit.MINUTES.toMillis(5));
        File dir = env.run("771", "flaky");
        check("completed".equals(read(dir, "done")) && env.agent.launches.size() == 2, "auto-continue: completes on a fresh process");
        check(!env.agent.processes.get(0).alive() && env.pool.hasIdle(), "auto-continue: crashed process gone, the healthy one is warm");
        // User "Continue" of a failed turn in place reuses the warm process.
        File cont = taskDir("772");
        env.runtime().run(cont, AcpRuntime.continuationRequest(chat("772", ""), "user", 0));
        check("completed".equals(read(cont, "done")) && env.agent.launches.size() == 2, "continueTask: runs on the warm process");
        env.pool.invalidate("test");
    }

    /** Subagents may still run after the parent's turn: that process is never pooled. */
    static void subagentsAreNotPooled() throws Exception {
        FakeAcpAgent fake = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_INLINE);
        Env env = new Env(fake, TimeUnit.MINUTES.toMillis(5));
        File dir = taskDir("781");
        AcpRuntime.Options o = options(env.binary);
        o.subagentSettleMillis = 100;
        o.subagentPollMillis = 50;
        new AcpRuntime(o, fake, null, null, env.host, env.pool).run(dir, chat("781", "split"));
        check(new File(dir, "done").exists(), "subagents: the task ends");
        check(!env.pool.hasIdle() && !fake.processes.get(0).alive(), "subagents: the process is not kept warm");
    }

    /** The native relay is declared when the broker's socket exists; the Java relay otherwise. */
    static void nativePhoneMcp() throws Exception {
        Env env = new Env(new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), 0);
        File socket = new File(taskDir("sock"), "phone-mcp.sock");
        Files.write(socket.toPath(), new byte[0]);
        AcpRuntime.Options o = options(env.binary);
        o.phoneMcpSocket = socket;
        o.toybox = "/bin/sh";
        o.shell = "/bin/sh";
        File dir = taskDir("791");
        new AcpRuntime(o, env.agent, null, null, env.host, env.pool).run(dir, chat("791", "native"));
        JSONObject server = phoneServer(env.agent.methods("session/new").get(0));
        String script = server.getJSONArray("args").getString(1);
        check("/bin/sh".equals(server.getString("command")) && "-c".equals(server.getJSONArray("args").getString(0))
                && script.contains("/bin/sh nc -U \"$TAKU_MCP_SOCKET\"") && script.contains("printf '%s\\n' \"$TAKU_MCP_HELLO\""),
                "native relay: sh | toybox nc -U to the socket: " + server);
        String hello = null, path = null;
        JSONArray serverEnv = server.getJSONArray("env");
        for (int i = 0; i < serverEnv.length(); i++) {
            if ("TAKU_MCP_HELLO".equals(serverEnv.getJSONObject(i).getString("name"))) hello = serverEnv.getJSONObject(i).getString("value");
            if ("TAKU_MCP_SOCKET".equals(serverEnv.getJSONObject(i).getString("name"))) path = serverEnv.getJSONObject(i).getString("value");
        }
        JSONObject handshake = new JSONObject(hello);
        check(CONV.equals(handshake.getString("thread")) && CONV.equals(handshake.getString("task"))
                && "full".equals(handshake.getString("permissionMode")) && socket.getPath().equals(path),
                "native relay: the handshake names the conversation thread and the socket: " + hello);
        // The script really relays: run it with a stand-in nc that echoes its stdin.
        File fakeBin = taskDir("fakebin");
        File toy = new File(fakeBin, "toybox");
        Files.write(toy.toPath(), "#!/bin/sh\nshift 2\necho \"socket=$1\"\nexec cat\n".getBytes(StandardCharsets.UTF_8));
        check(toy.setExecutable(true), "stand-in toybox executable");
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", AcpRuntime.NATIVE_RELAY_SCRIPT.replace("@TOYBOX@", toy.getPath()));
        builder.environment().put("TAKU_MCP_HELLO", hello);
        builder.environment().put("TAKU_MCP_SOCKET", path);
        Process relay = builder.start();
        relay.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8));
        relay.getOutputStream().close();
        String relayed = new String(relay.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(relay.waitFor(5, TimeUnit.SECONDS) && relayed.equals("socket=" + path + "\n" + hello + "\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"),
                "native relay: the handshake line first, then stdin unchanged: " + relayed);
        // Socket gone (broker could not bind it): the Java relay is declared.
        socket.delete();
        FakeAcpAgent cold = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(o, cold, null, null, env.host, null).run(taskDir("792"), chat("792", "java"));
        JSONObject java = phoneServer(cold.methods("session/load").isEmpty() ? cold.methods("session/new").get(0) : cold.methods("session/load").get(0));
        check(java.getJSONArray("args").toList().contains("org.agentos.lab.PhoneMcpHost"), "fallback: the Java relay without the socket");
        // runtime.json's own phoneMcp turns the native relay off.
        AcpRuntime.Options custom = AcpRuntime.Options.fromJson(new JSONObject().put("binary", "/x/agy")
                .put("phoneMcp", new JSONObject().put("command", "/x/relay")));
        check(!custom.phoneMcpNative && AcpRuntime.Options.fromJson(new JSONObject().put("binary", "/x/agy")).phoneMcpNative,
                "a configured phoneMcp disables the native relay");
    }

    /** The bridge's turn for each call follows the thread's current ACP turn, not the turn its relay started in. */
    static void bridgeTurnFollowsCurrentTurn() throws Exception {
        AcpPhoneTurns.Hooks saved = AcpPhoneTurns.hooks;
        AcpPhoneTurns.hooks = null;
        String thread = "00000000-0000-4000-8000-0000000000ab";
        PhoneMcpBridge.Session session = new PhoneMcpBridge.Session(thread, "full", "relay-turn", () -> AcpPhoneTurns.current(thread));
        check("relay-turn".equals(session.turnId()), "between turns: the relay's own turn");
        AcpPhoneTurns.begin(thread, "turn-2");
        check("turn-2".equals(session.turnId()), "during a turn: the current turn");
        AcpPhoneTurns.begin(thread, "turn-3");
        AcpPhoneTurns.end(thread, "turn-2");
        check("turn-3".equals(session.turnId()), "a late end of an older turn leaves the newer one current");
        AcpPhoneTurns.end(thread, "turn-3");
        check("relay-turn".equals(session.turnId()), "after the end: back to the relay's turn");
        AcpPhoneTurns.hooks = saved;
    }

    /**
     * A warm agy process serving several conversations holds several taku_phone servers and names their
     * tools with an 8-hex disambiguator (Pixel 9, agy 1.5.14: mcp_taku_phone_3013b55d_apps). They are
     * still phone tools with the canonical id, label and failure lookup.
     */
    static void disambiguatedPhoneToolNames() throws Exception {
        AcpToolLabels.Tool tool = AcpToolLabels.identify("mcp_taku_phone_3013b55d_apps", "", "other");
        check(tool.phone && "apps".equals(tool.key) && "taku_phone__apps".equals(tool.canonical()),
                "disambiguated name: a phone tool with the canonical id: " + tool.canonical());
        AcpToolLabels.Tool lettered = AcpToolLabels.identify("mcp_taku_phone_ab12cd34_request_apps", "", "other");
        check(lettered.phone && "request_apps".equals(lettered.key), "a disambiguator starting with a letter: " + lettered.key);
        check(AcpToolLabels.identify("mcp_taku_phone_device_info", "", "other").key.equals("device_info"), "the plain name still works");
        check("open_app".equals(AcpRuntime.Chat.phoneToolName("mcp_taku_phone_3013b55d_open_app")), "failure lookup: tool part found");
        String prose = AcpToolLabels.humanizePhoneTools("called `mcp_taku_phone_3013b55d_device_info` first", java.util.Locale.ENGLISH);
        check(!prose.contains("3013b55d") && !prose.contains("mcp_taku_phone"), "prose: the disambiguated identifier reads as the tool: " + prose);
    }

    /** P0: only the ACP route skips the execution display; background tasks still capture context. */
    static void displayPreparationByRoute() throws Exception {
        check(!RuntimeRouter.preparesExecutionDisplay(new RuntimeRouter.Route(RuntimeRouter.ACP, "acp", "selected-by-config", new JSONObject())),
                "ACP: no execution display preparation");
        check(RuntimeRouter.preparesExecutionDisplay(new RuntimeRouter.Route(RuntimeRouter.CODEX, "", "default", null)),
                "Codex: the execution display is prepared as before");
        check(RuntimeRouter.preparesExecutionDisplay(new RuntimeRouter.Route(RuntimeRouter.REMOTE_T3, "remote_t3", "selected-by-config", null, new JSONObject())),
                "remote T3: unchanged");
        check(RuntimeRouter.capturesContext(new JSONObject().put("id", "1")) && !RuntimeRouter.capturesContext(chat("2", "x")),
                "a background task captures its context screenshot, a chat turn does not");
    }

    /** Notifications are sent off the caller's thread, in order, merged per task while one is pending. */
    static void coalescedNotifications() throws Exception {
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inFirst = new CountDownLatch(1);
        CoalescingNotifier notifier = new CoalescingNotifier(id -> {
            if (sent.isEmpty()) {
                inFirst.countDown();
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            sent.add(id);
        });
        long begin = System.nanoTime();
        notifier.notify("a");
        check(inFirst.await(5, TimeUnit.SECONDS), "the first broadcast is being sent");
        notifier.notify("b");
        notifier.notify("b");
        notifier.notify("a");
        notifier.notify("b");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1000, "notify never waits for the broadcast");
        gate.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sent.size() < 3 && System.nanoTime() < until) Thread.sleep(10);
        Thread.sleep(100);
        check(sent.equals(List.of("a", "b", "a")), "in order, one pending update per task: " + sent);
    }

    public static void main(String[] args) throws Exception {
        PhoneTurnLog.install();
        reuseAcrossTurns();
        prewarmThenFirstTurn();
        takeWaitsForWarming();
        fingerprintAndStamp();
        idleTtl();
        probeFailureFallsBackCold();
        cancelKeepsOrKills();
        steerOnWarmProcess();
        autoContinueWithPool();
        subagentsAreNotPooled();
        nativePhoneMcp();
        bridgeTurnFollowsCurrentTurn();
        disambiguatedPhoneToolNames();
        displayPreparationByRoute();
        coalescedNotifications();
        System.out.println("AcpWarmPoolTest passed " + checks + " checks");
    }
}
