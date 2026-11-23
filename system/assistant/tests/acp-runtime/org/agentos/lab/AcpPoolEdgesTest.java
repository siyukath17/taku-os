package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AcpAgentPool} failure edges, driven directly (no runtime): a warm-up whose process dies or
 * cannot start leaves the pool empty and ready for the next warm-up, a wedged warm process fails its
 * probe and is never handed out, a process that hit EOF during a turn is not parked, concurrent takes
 * hand the one idle process to exactly one turn, and a repeated release is a no-op (it neither
 * destroys the process it parked nor steals another turn's checkout).
 */
public final class AcpPoolEdgesTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static AcpProcessTransport.Launch launch() throws Exception {
        File dir = Files.createTempDirectory("acp-pool-edges").toFile();
        dir.deleteOnExit();
        File agy = new File(dir, "agy");
        Files.write(agy.toPath(), "v1".getBytes(StandardCharsets.UTF_8));
        return new AcpProcessTransport.Launch(List.of(agy.getPath(), "acp"), Map.of(), null);
    }

    private static AcpAgentPool pool(FakeAcpAgent agent) {
        return new AcpAgentPool(agent, () -> "stamp", TimeUnit.MINUTES.toMillis(5), TimeUnit.HOURS.toMillis(1), 40, 8, 5000);
    }

    private static void awaitEof(AcpAgentPool.Agent agent) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (agent.alive() && System.nanoTime() < until) Thread.sleep(5);
    }

    /** The warm-up's process exits on initialize: no idle, not warming, a take does not wait, the next warm-up works. */
    static void prewarmCrashRecovers() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.EXIT_ON_INITIALIZE, FakeAcpAgent.Scenario.NORMAL);
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        pool.prewarm(launch, "crash");
        check(!pool.awaitWarm(5000) && !pool.isWarming(), "a crashed warm-up ends (warming cleared) without an idle process");
        check(pool.empty() && pool.checkedOut() == 0 && "prewarm-failed".equals(pool.lastRetireReason),
                "a crashed warm-up leaves an empty pool: " + pool.lastRetireReason);
        check(!agent.processes.get(0).alive(), "the crashed warm-up's process is destroyed");
        long begin = System.nanoTime();
        check(pool.take(launch) == null, "nothing to take after a crashed warm-up");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1000, "a take never waits on a warm-up that already failed");
        pool.prewarm(launch, "retry");
        check(pool.awaitWarm(5000) && agent.launches.size() == 2, "the next warm-up starts a fresh process and succeeds");
        AcpAgentPool.Agent warm = pool.take(launch);
        check(warm != null && warm.init != null && pool.checkedOut() == 1, "the recovered warm process is handed out initialized");
        pool.release(warm, true);
        check(pool.hasIdle() && pool.checkedOut() == 0, "and parked again after its turn");
        pool.invalidate("test");
    }

    /** The agent binary cannot start at all: the warm-up fails cleanly; once it starts again, warming works. */
    static void prewarmStartFailure() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent();
        agent.startFailure = new IOException("agy: not found");
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        pool.prewarm(launch, "missing");
        check(!pool.awaitWarm(5000) && !pool.isWarming() && pool.empty(), "a start failure clears warming and leaves the pool empty");
        check(agent.launches.isEmpty() && pool.started.get() == 0, "nothing was started");
        agent.startFailure = null;
        pool.prewarm(launch, "installed");
        check(pool.awaitWarm(5000) && pool.hasIdle(), "a later warm-up succeeds");
        pool.invalidate("test");
    }

    /** A warm process that is alive but no longer answers: the probe times out, it is retired, the turn starts cold. */
    static void wedgedProcessFailsProbe() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent();
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        pool.prewarm(launch, "test");
        check(pool.awaitWarm(5000), "warm");
        agent.silentPing = true;
        long begin = System.nanoTime();
        check(pool.take(launch) == null, "a wedged warm process is not handed out");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        check(took >= AcpAgentPool.PROBE_TIMEOUT_MILLIS - 50 && took < AcpAgentPool.PROBE_TIMEOUT_MILLIS + 2000,
                "the probe gives up after its timeout: " + took + "ms");
        check(pool.probeFailures.get() == 1 && "probe-failed".equals(pool.lastRetireReason) && pool.checkedOut() == 0 && pool.empty(),
                "probe failure: retired, nothing checked out, pool empty: " + pool.lastRetireReason);
        check(!agent.processes.get(0).alive(), "the wedged process is destroyed");
    }

    /** The process hit EOF (crash) during a turn: even a "reusable" release does not park it. */
    static void eofDuringTurnIsNotParked() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent();
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        AcpAgentPool.Agent cold = pool.startCold(launch);
        check(pool.checkedOut() == 1 && !pool.empty(), "a cold process is checked out");
        agent.processes.get(0).destroy(0);
        awaitEof(cold);
        check(!cold.alive(), "the process is gone (stdout EOF)");
        pool.release(cold, true);
        check(!pool.hasIdle() && pool.checkedOut() == 0 && pool.empty() && "exited".equals(pool.lastRetireReason),
                "a dead process is retired, not parked: " + pool.lastRetireReason);
    }

    /** Eight turns race for one idle process: exactly one gets it, the others start cold, the count stays exact. */
    static void concurrentTakes() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent();
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        pool.prewarm(launch, "test");
        check(pool.awaitWarm(5000), "warm");
        int n = 8;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        AtomicReference<AcpAgentPool.Agent> winner = new AtomicReference<>();
        AtomicReference<String> problem = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            Thread racer = new Thread(() -> {
                try {
                    go.await();
                    AcpAgentPool.Agent got = pool.take(launch);
                    if (got != null && !winner.compareAndSet(null, got)) problem.set("two turns got the warm process");
                } catch (Throwable failed) {
                    problem.set(String.valueOf(failed));
                } finally {
                    done.countDown();
                }
            });
            racer.setDaemon(true);
            racer.start();
        }
        go.countDown();
        check(done.await(10, TimeUnit.SECONDS), "every racer finished");
        check(problem.get() == null, "no double hand-out: " + problem.get());
        check(winner.get() != null && pool.checkedOut() == 1 && !pool.hasIdle() && pool.reused.get() == 1,
                "exactly one turn took the warm process: checkedOut=" + pool.checkedOut());
        check(agent.methods("$/ping").size() == 1 && agent.launches.size() == 1, "probed once, no process started by the race");
        pool.release(winner.get(), true);
        check(pool.hasIdle() && pool.checkedOut() == 0, "parked again after its turn");
        pool.invalidate("test");
    }

    /** A repeated release changes nothing: the parked process stays alive and another turn's checkout is kept. */
    static void repeatedReleaseIsNoOp() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent();
        AcpAgentPool pool = pool(agent);
        AcpProcessTransport.Launch launch = launch();
        AcpAgentPool.Agent first = pool.startCold(launch);
        first.call("initialize", AcpRuntime.initializeParams(), 5000);
        pool.release(first, true);
        long retired = pool.retired.get();
        pool.release(first, true);
        check(pool.hasIdle() && first.alive() && pool.retired.get() == retired && first.turns == 1,
                "a second release keeps the parked process alive (no slot-taken retire): " + pool.lastRetireReason);
        // A stale release while the same process serves another turn must not count that turn out.
        AcpAgentPool.Agent again = pool.take(launch);
        check(again == first && pool.checkedOut() == 1, "the parked process serves the next turn");
        AcpAgentPool.Agent other = pool.startCold(launch);
        pool.release(other, false);
        long afterOther = pool.retired.get();
        pool.release(other, false);
        check(pool.retired.get() == afterOther && pool.checkedOut() == 1,
                "releasing a retired process again neither retires twice nor drops the live checkout: " + pool.checkedOut());
        pool.prewarm(launch, "during-turn");
        check(!pool.isWarming() && agent.launches.size() == 2, "with a turn still checked out a prewarm stays a no-op (in-turn)");
        pool.release(again, true);
        check(pool.hasIdle() && pool.checkedOut() == 0 && first.alive(), "the turn's own release parks it");
        pool.release(null, true);
        check(pool.hasIdle() && pool.checkedOut() == 0, "a null release is ignored");
        pool.invalidate("test");
    }

    public static void main(String[] args) throws Exception {
        prewarmCrashRecovers();
        prewarmStartFailure();
        wedgedProcessFailsProbe();
        eofDuringTurnIsNotParked();
        concurrentTakes();
        repeatedReleaseIsNoOp();
        System.out.println("AcpPoolEdgesTest passed " + checks + " checks");
    }
}
