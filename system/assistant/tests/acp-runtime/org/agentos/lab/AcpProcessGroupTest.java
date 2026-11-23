package org.agentos.lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * {@link AcpProcessGroup} on real OS processes: destroy reclaims the agent's whole process group
 * (children it started in the background included) and never signals the broker's own group.
 *
 * <p>The session wrapper is the system's {@code setsid} when it has one (Android, Linux). A macOS host
 * has none: there the same code path runs behind an equivalent test-only wrapper (perl's
 * {@code POSIX::setsid} then exec, PID unchanged), so the {@code -pgid} reclaim is still exercised for
 * real; production never uses an interpreter. The no-wrapper fallback is checked as well.
 */
public final class AcpProcessGroupTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final List<String> PERL_SETSID = List.of("/usr/bin/perl", "-MPOSIX", "-e",
            "POSIX::setsid() > 0 or die \"setsid: $!\"; exec { $ARGV[0] } @ARGV or die \"exec: $!\"");

    /** Every OS call through the real host OS, recording group signals; fails on any that targets the broker. */
    static final class RecordingOs implements AcpProcessGroup.Os {
        final AcpProcessGroup.Os real = new AcpProcessGroup.HostOs();
        final long brokerPid = real.selfPid(), brokerGroup = real.selfGroup();
        final List<String> groupSignals = new CopyOnWriteArrayList<>();
        volatile long reportedSelfGroup = -1, reportedGroupOf = -1;

        @Override public boolean kill(long pid, int signal) {
            if (pid < 0) {
                groupSignals.add(signal + "@" + -pid);
                check(-pid != brokerPid && -pid != brokerGroup && -pid > 1, "never signals the broker's group: " + pid);
            }
            check(pid != brokerPid, "never signals the broker: " + pid);
            return real.kill(pid, signal);
        }

        @Override public long groupOf(long pid) { return reportedGroupOf > 0 ? reportedGroupOf : real.groupOf(pid); }
        @Override public long selfPid() { return brokerPid; }
        @Override public long selfGroup() { return reportedSelfGroup > 0 ? reportedSelfGroup : brokerGroup; }
    }

    static final class Started {
        final AcpProcessTransport.Connection connection;
        final BufferedReader out;

        Started(AcpProcessTransport.Connection connection) {
            this.connection = connection;
            this.out = new BufferedReader(new InputStreamReader(connection.stdout(), StandardCharsets.UTF_8));
        }

        long nextPid() throws IOException {
            String line = out.readLine();
            check(line != null, "the script printed a pid");
            return Long.parseLong(line.trim());
        }
    }

    static List<String> wrapper() {
        List<String> system = AcpProcessGroup.sessionPrefix();
        return system.isEmpty() ? PERL_SETSID : system;
    }

    static Started sh(List<String> wrapper, AcpProcessGroup.Os os, String script) throws IOException {
        return new Started(AcpProcessTransport.process(wrapper, os)
                .start(new AcpProcessTransport.Launch(List.of("/bin/sh", "-c", script), Map.of(), null)));
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static boolean awaitGone(List<Long> pids, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            if (pids.stream().noneMatch(AcpProcessGroupTest::alive)) return true;
            Thread.sleep(10);
        }
        return pids.stream().noneMatch(AcpProcessGroupTest::alive);
    }

    static long millisSince(long begin) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
    }

    /** The wrapper keeps the PID (the shell's $$ is the process Java started) and the group id is that PID. */
    static void pidAndGroupArePreserved() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "echo $$; exec cat");
        long shellPid = agent.nextPid();
        check(agent.connection.pid() == shellPid, "setsid execs in place: pid " + agent.connection.pid() + " == $$ " + shellPid);
        check(os.real.groupOf(shellPid) == shellPid, "the agent leads its own group");
        check(agent.connection.group() == shellPid, "the group destroy signals is the agent's: " + agent.connection.group());
        check(shellPid != os.brokerGroup && os.real.groupOf(shellPid) != os.brokerGroup, "the agent left the broker's group");
        agent.connection.destroy(1000);
    }

    /** An agent that exits on stdin EOF: destroy returns at once, no signal needed, nothing left. */
    static void normalDestroy() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "echo $$; cat >/dev/null; exit 0");
        long pid = agent.nextPid();
        long begin = System.nanoTime();
        agent.connection.destroy(2000);
        check(millisSince(begin) < 1500, "normal destroy: stdin EOF ends it within the grace, took " + millisSince(begin) + "ms");
        check(!agent.connection.alive() && Integer.valueOf(0).equals(agent.connection.exitCode()), "normal destroy: exit 0, got " + agent.connection.exitCode());
        check(os.groupSignals.stream().noneMatch(s -> s.startsWith("15@") || s.startsWith("9@")), "normal destroy: no TERM or KILL: " + os.groupSignals);
        check(!alive(pid), "normal destroy: the agent is gone");
    }

    /** An agent that ignores stdin but exits on TERM: the group TERM after the grace ends it (and its foreground child). */
    static void termWithinGrace() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "trap 'exit 7' TERM; echo $$; while :; do sleep 0.1; done");
        long pid = agent.nextPid();
        long begin = System.nanoTime();
        agent.connection.destroy(200);
        long took = millisSince(begin);
        check(Integer.valueOf(7).equals(agent.connection.exitCode()), "TERM: the agent ran its TERM handler, exit " + agent.connection.exitCode());
        check(took >= 200 && took < 1200, "TERM: after the grace and well before KILL, took " + took + "ms");
        check(os.groupSignals.contains(AcpProcessGroup.SIGTERM + "@" + pid), "TERM went to the whole group: " + os.groupSignals);
        check(!os.groupSignals.contains(AcpProcessGroup.SIGKILL + "@" + pid), "TERM: no KILL needed: " + os.groupSignals);
    }

    /** TERM ignored (inherited by its children too): KILL ends the group in bounded time. */
    static void termIgnoredNeedsKill() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "trap '' TERM; sleep 300 & echo $!; echo $$; while :; do sleep 0.1; done");
        long child = agent.nextPid(), pid = agent.nextPid();
        long begin = System.nanoTime();
        agent.connection.destroy(200);
        long took = millisSince(begin);
        check(awaitGone(List.of(pid), 2000), "TERM ignored: the agent is killed");
        check(took < 200 + 1000 + AcpProcessGroup.GROUP_GRACE_MILLIS + 1500, "TERM ignored: destroy is bounded, took " + took + "ms");
        check(os.groupSignals.contains(AcpProcessGroup.SIGKILL + "@" + pid), "TERM ignored: KILL went to the group: " + os.groupSignals);
        check(awaitGone(List.of(child), 2000), "TERM ignored: its TERM-ignoring background child is KILLed with it");
        agent.connection.destroy(200); // idempotent
    }

    /**
     * The leak this fixes: the agent exits on stdin EOF but leaves background children (an MCP server, a
     * shell tool), one of them ignoring TERM. The sweep TERMs the rest of the group, then KILLs it.
     */
    static void backgroundChildrenAreReclaimed() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os,
                "sleep 300 & echo $!; (trap '' TERM; while :; do sleep 0.1; done) & echo $!; sh -c 'sleep 300' & echo $!; echo $$; exec cat");
        List<Long> children = new ArrayList<>(List.of(agent.nextPid(), agent.nextPid(), agent.nextPid()));
        long pid = agent.nextPid();
        check(children.stream().allMatch(AcpProcessGroupTest::alive), "children: running before destroy");
        check(children.stream().allMatch(c -> os.real.groupOf(c) == pid), "children: in the agent's group");
        long begin = System.nanoTime();
        agent.connection.destroy(1000);
        long took = millisSince(begin);
        check(Integer.valueOf(0).equals(agent.connection.exitCode()), "children: the agent itself exited on EOF, exit " + agent.connection.exitCode());
        check(awaitGone(children, 2000), "children: every background child is reclaimed: " + children);
        check(os.groupSignals.contains(AcpProcessGroup.SIGTERM + "@" + pid) && os.groupSignals.contains(AcpProcessGroup.SIGKILL + "@" + pid),
                "children: TERM, then KILL for the one ignoring it: " + os.groupSignals);
        check(took < 1000 + AcpProcessGroup.GROUP_GRACE_MILLIS + 1500, "children: bounded, took " + took + "ms");
    }

    /** destroy(0) (the stdin write watchdog): KILL the whole group at once, children included. */
    static void immediateKillTakesTheGroup() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "sleep 300 & echo $!; echo $$; exec sleep 300");
        long child = agent.nextPid(), pid = agent.nextPid();
        long begin = System.nanoTime();
        agent.connection.destroy(0);
        check(millisSince(begin) < 1000, "kill now: does not wait, took " + millisSince(begin) + "ms");
        check(awaitGone(List.of(pid, child), 2000), "kill now: the agent and its child are gone");
        check(os.groupSignals.equals(List.of(AcpProcessGroup.SIGKILL + "@" + pid)), "kill now: one group KILL: " + os.groupSignals);
        agent.connection.destroy(100); // the pool's later graceful retire: just a sweep of nothing
    }

    /** Without a wrapper (a host with no setsid) the agent alone is killed: its child outlives it. */
    static void withoutSetsidOnlyTheAgent() throws Exception {
        RecordingOs os = new RecordingOs();
        Started agent = sh(List.of(), os, "sleep 300 & echo $!; echo $$; exec cat");
        long child = agent.nextPid(), pid = agent.nextPid();
        check(agent.connection.group() == 0, "no setsid: no group to signal");
        check(os.real.groupOf(pid) == os.brokerGroup, "no setsid: the agent shares the broker's group");
        agent.connection.destroy(500);
        check(!alive(pid), "no setsid: the agent is gone");
        check(os.groupSignals.isEmpty(), "no setsid: the broker's group is never signalled: " + os.groupSignals);
        check(alive(child), "no setsid: the background child survives (the degraded path this test documents)");
        os.real.kill(child, AcpProcessGroup.SIGKILL);
        check(awaitGone(List.of(child), 2000), "no setsid: cleanup");
    }

    /** The guard: a group that reads as the broker's (or anything but the agent's) is never signalled. */
    static void neverSignalsTheBrokersGroup() throws Exception {
        check(!AcpProcessGroup.safeGroup(0, 100, 200) && !AcpProcessGroup.safeGroup(1, 100, 200) && !AcpProcessGroup.safeGroup(-7, 100, 200),
                "guard: never 0, 1 (init) or negative");
        check(!AcpProcessGroup.safeGroup(100, 100, 200) && !AcpProcessGroup.safeGroup(200, 100, 200), "guard: never the broker's pid or group");
        check(AcpProcessGroup.safeGroup(300, 100, 200), "guard: another group passes");

        // The agent's pid claimed as the broker's own group: refused, the agent killed by pid alone.
        RecordingOs os = new RecordingOs();
        Started agent = sh(wrapper(), os, "trap '' TERM; echo $$; while :; do sleep 0.1; done");
        long pid = agent.nextPid();
        os.reportedSelfGroup = pid;
        check(agent.connection.group() == 0, "guard: a group equal to the broker's is refused");
        agent.connection.destroy(100);
        check(awaitGone(List.of(pid), 2000) && os.groupSignals.isEmpty(), "guard: killed by pid, no group signal: " + os.groupSignals);

        // setsid not effective (the live agent still reads as the broker's group): refused.
        os = new RecordingOs();
        os.reportedGroupOf = os.brokerGroup;
        agent = sh(wrapper(), os, "echo $$; exec cat");
        agent.nextPid();
        check(agent.connection.group() == 0, "guard: an unconfirmed group is refused while the agent lives");
        agent.connection.destroy(0);
        check(os.groupSignals.isEmpty(), "guard: unconfirmed, so no group signal: " + os.groupSignals);
        awaitGone(List.of(agent.connection.pid()), 2000);
    }

    public static void main(String[] args) throws Exception {
        String mode = AcpProcessGroup.sessionPrefix().isEmpty() ? "test-only perl setsid wrapper (host has no setsid)" : "system " + AcpProcessGroup.sessionPrefix();
        List<String> ran = new ArrayList<>();
        pidAndGroupArePreserved(); ran.add("pid-group-preserved");
        normalDestroy(); ran.add("normal");
        termWithinGrace(); ran.add("term-in-grace");
        termIgnoredNeedsKill(); ran.add("term-ignored-kill");
        backgroundChildrenAreReclaimed(); ran.add("background-children");
        immediateKillTakesTheGroup(); ran.add("kill-now");
        withoutSetsidOnlyTheAgent(); ran.add("no-setsid-fallback");
        neverSignalsTheBrokersGroup(); ran.add("broker-guard");
        System.out.println("AcpProcessGroupTest passed " + checks + " checks " + ran + " via " + mode);
    }
}
