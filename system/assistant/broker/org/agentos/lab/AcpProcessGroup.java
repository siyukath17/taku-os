package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reclaims the ACP agent's whole process tree, not just the agent. agy starts MCP servers and shell
 * tools as its children; killing agy alone orphans them (reparented to init, never reclaimed).
 *
 * <p><b>Start.</b> The agent is started through {@code setsid(1)} (toybox on Android, util-linux on a
 * Linux host). Both call {@code setsid(2)} and exec in place when the caller is not already a process
 * group leader, which a freshly forked Java child never is: the agent keeps its PID, parent and stdio,
 * and leads a new session whose process group id equals its PID. The cost is one extra execve of an
 * already resident binary; no interpreter and no extra process (see {@link #SETSID_PATHS}). Without
 * setsid (a macOS host) the agent stays in the broker's group and only the agent itself is signalled,
 * exactly as before.
 *
 * <p><b>Destroy.</b> Close stdin, wait the grace, then {@code TERM} the group, wait, {@code KILL} it.
 * A group left behind by an agent that exited on its own (children still running) gets the same TERM,
 * grace, KILL sweep.
 *
 * <p><b>Never the broker.</b> A group is signalled only when {@link #safeGroup}: {@code pgid > 1}, not
 * the broker's PID, not the broker's own group, and (read from the live agent) equal to the agent's PID.
 * An agent whose group still reads as anything else (setsid not yet effective, or absent) is signalled
 * by PID only.
 */
final class AcpProcessGroup {
    static final int SIGKILL = 9, SIGTERM = 15; // the same numbers on Linux, Android and Darwin
    /** Searched once per broker process; the first executable one wins. */
    static final List<String> SETSID_PATHS = List.of("/system/bin/setsid", "/usr/bin/setsid", "/bin/setsid");
    /** How long children of an exited (or TERMed) agent get to exit before the group is KILLed. */
    static final long GROUP_GRACE_MILLIS = 500;

    /** The operating-system calls group reclaim needs; tests substitute a recorder. */
    interface Os {
        /** {@code kill(2)}; a negative pid is a process group, signal 0 only probes. True if delivered. */
        boolean kill(long pid, int signal);

        /** Process group of a live process, or -1 when unknown (gone or unreadable). */
        long groupOf(long pid);

        /** The broker's PID, or -1 when unknown. */
        long selfPid();

        /** The broker's process group, or -1 when unknown. */
        long selfGroup();
    }

    private static final class Holder {
        static final List<String> SESSION = sessionPrefix(SETSID_PATHS);
        static final Os SYSTEM = system(AndroidOs.create());

        private static Os system(Os android) {
            return android != null ? android : new HostOs();
        }
    }

    /** The prefix that makes the agent a session leader on this system, or empty (none available). */
    static List<String> sessionPrefix() {
        return Holder.SESSION;
    }

    static Os system() {
        return Holder.SYSTEM;
    }

    static List<String> sessionPrefix(List<String> candidates) {
        for (String path : candidates) if (new File(path).canExecute()) return List.of(path);
        System.err.println("acp: no setsid in " + candidates + "; agent child processes are reclaimed only with the agent");
        return List.of();
    }

    /** The one rule every group signal passes: never init, never the broker or its own group. */
    static boolean safeGroup(long pgid, long selfPid, long selfGroup) {
        return pgid > 1 && pgid != selfPid && pgid != selfGroup;
    }

    /** {@link Process#pid()}, else the platform's private field (older Android libcore), else -1. */
    static long pidOf(Process process) {
        try {
            return process.pid();
        } catch (UnsupportedOperationException | LinkageError unsupported) {
            // Android's UNIXProcess predates pid().
        }
        for (Class<?> type = process.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("pid");
                field.setAccessible(true);
                return ((Number) field.get(process)).longValue();
            } catch (ReflectiveOperationException | RuntimeException absent) {
                // Not on this class; try its superclass.
            }
        }
        return -1;
    }

    private final Process process;
    private final long pid;
    private final boolean sessionLeader;
    private final Os os;
    /** -1 unknown, 0 refused, else the confirmed group id (== pid). Decided once, while the agent lives. */
    private volatile long group = -1;

    AcpProcessGroup(Process process, boolean sessionLeader, Os os) {
        this.process = process;
        this.pid = pidOf(process);
        this.sessionLeader = sessionLeader;
        this.os = os;
    }

    long pid() {
        return pid;
    }

    /** The agent's group id when it may be signalled as a whole, else 0. */
    long group() {
        long known = group;
        if (known >= 0) return known;
        long decided = 0;
        if (sessionLeader && pid > 0 && safeGroup(pid, os.selfPid(), os.selfGroup())) {
            // Confirmed on the live agent. Gone already: a group with id == pid can only be the one it
            // led (the id stays reserved while any member lives), so signalling it reaches only its children.
            if (!process.isAlive() || os.groupOf(pid) == pid) decided = pid;
        }
        if (decided == 0 && sessionLeader && process.isAlive()) return 0; // setsid may not have run yet: ask again
        group = decided;
        return decided;
    }

    /** See {@link AcpProcessTransport.Connection#destroy}. */
    void destroy(long graceMillis) {
        long target = group();
        if (graceMillis <= 0) {
            if (target > 0) os.kill(-target, SIGKILL);
            process.destroyForcibly();
            return;
        }
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
        boolean termed = false, interrupted = false;
        try {
            if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                if (target == 0) target = group();
                // The group TERM reaches the agent too. Process.destroy() is only the fallback: after its
                // TERM it closes the agent's stdout and stderr at once, so an agent that logs while it
                // shuts down dies of SIGPIPE instead of exiting cleanly.
                if (target <= 0 || !os.kill(-target, SIGTERM)) process.destroy();
                termed = true;
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    if (target > 0) os.kill(-target, SIGKILL);
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException stop) {
            interrupted = true;
            if (target > 0) os.kill(-target, SIGKILL);
            process.destroyForcibly();
        }
        if (target == 0) target = group();
        if (target > 0) sweep(target, termed, interrupted);
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Whatever is left of the group after the agent: TERM (unless already sent), a grace, KILL. */
    private void sweep(long target, boolean termed, boolean interrupted) {
        if (!os.kill(-target, 0)) return;
        if (!interrupted) {
            if (!termed) os.kill(-target, SIGTERM);
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GROUP_GRACE_MILLIS);
            while (System.nanoTime() < until) {
                if (!os.kill(-target, 0)) return;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        os.kill(-target, SIGKILL);
    }

    // ---- the operating system -----------------------------------------------------------------------

    /** Group of {@code pid} from {@code /proc/<pid>/stat} (Linux, Android), or -1. */
    static long procGroup(String pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.US_ASCII);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" +");
            return Long.parseLong(fields[2]); // state, ppid, pgrp
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
    }

    /** Android: {@code android.system.Os} by reflection (this class also compiles without android.jar). */
    private static final class AndroidOs implements Os {
        private final Method kill;
        private final long self;

        private AndroidOs(Method kill, long self) {
            this.kill = kill;
            this.self = self;
        }

        static AndroidOs create() {
            try {
                Class<?> os = Class.forName("android.system.Os");
                return new AndroidOs(os.getMethod("kill", int.class, int.class), ((Number) os.getMethod("getpid").invoke(null)).longValue());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                return null;
            }
        }

        @Override public boolean kill(long pid, int signal) {
            try {
                kill.invoke(null, Math.toIntExact(pid), signal);
                return true;
            } catch (InvocationTargetException errno) {
                return false; // ErrnoException: ESRCH (nothing left) or EPERM
            } catch (ReflectiveOperationException | RuntimeException failed) {
                return false;
            }
        }

        @Override public long groupOf(long pid) { return procGroup(Long.toString(pid)); }
        @Override public long selfPid() { return self; }
        @Override public long selfGroup() { return procGroup("self"); }
    }

    /** A JVM host (tests, a Linux or macOS development machine): kill(1), and /proc or ps(1). */
    static final class HostOs implements Os {
        private volatile long selfGroup = -2;

        @Override public boolean kill(long pid, int signal) {
            String flag = signal == 0 ? "-0" : signal == SIGKILL ? "-KILL" : signal == SIGTERM ? "-TERM" : "-" + signal;
            return run("kill", flag, "--", Long.toString(pid)) != null;
        }

        @Override public long groupOf(long pid) {
            if (new File("/proc/self/stat").exists()) return procGroup(Long.toString(pid));
            String out = run("ps", "-o", "pgid=", "-p", Long.toString(pid));
            try {
                return out == null ? -1 : Long.parseLong(out.trim());
            } catch (NumberFormatException unreadable) {
                return -1;
            }
        }

        @Override public long selfPid() { return ProcessHandle.current().pid(); }

        @Override public long selfGroup() {
            long known = selfGroup;
            if (known == -2) selfGroup = known = groupOf(selfPid());
            return known;
        }

        /** stdout of a successful command, or null (failed, timed out, or not found). */
        private static String run(String... command) {
            try {
                Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (InputStream out = process.getInputStream()) {
                    process.getOutputStream().close();
                    String text = new String(out.readAllBytes(), StandardCharsets.UTF_8);
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        return null;
                    }
                    return process.exitValue() == 0 ? text : null;
                }
            } catch (IOException failed) {
                return null;
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
