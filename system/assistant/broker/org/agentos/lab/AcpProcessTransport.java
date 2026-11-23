package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts the ACP agent and exposes its stdio. The only seam between {@link AcpRuntime} and the
 * operating system, so host tests can run the full protocol against an in-process fake agent.
 */
interface AcpProcessTransport {
    Connection start(Launch launch) throws IOException;

    /** What to start. Immutable; {@code env} entries override the inherited environment. */
    final class Launch {
        final List<String> command;
        final Map<String, String> env;
        final File cwd;

        Launch(List<String> command, Map<String, String> env, File cwd) {
            this.command = Collections.unmodifiableList(new ArrayList<>(command));
            this.env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
            this.cwd = cwd;
        }
    }

    interface Connection {
        /** Agent → client, newline-delimited JSON-RPC. */
        InputStream stdout();

        /** Client → agent, newline-delimited JSON-RPC. */
        OutputStream stdin();

        /** Diagnostics only; never parsed as protocol. */
        InputStream stderr();

        boolean alive();

        /** Exit code once the agent has exited, else null. */
        Integer exitCode();

        /** OS process id, or -1 (not a real process, or unknown). Diagnostics and tests. */
        default long pid() { return -1; }

        /** The process group {@link #destroy} signals as a whole, or 0 (the process alone). Tests. */
        default long group() { return 0; }

        /**
         * Close stdin, wait up to {@code graceMillis}, then kill: the agent and, where it leads its own
         * process group, every process still in that group (TERM, a grace, KILL). Idempotent. With {@code graceMillis <= 0}
         * it kills at once and never touches stdin first, so it is safe while another thread is blocked
         * writing to it (closing that stream would wait for the blocked write).
         */
        void destroy(long graceMillis);
    }

    /**
     * The real agent process, started as the leader of its own session where {@code setsid} exists so
     * that {@link Connection#destroy} reclaims the agent's children with it ({@link AcpProcessGroup}).
     */
    static AcpProcessTransport process() {
        return process(AcpProcessGroup.sessionPrefix(), AcpProcessGroup.system());
    }

    /**
     * {@link #process()} with an explicit session wrapper (empty: none; the command it prefixes must make
     * the agent a group leader without changing its PID) and operating system, for tests.
     */
    static AcpProcessTransport process(List<String> sessionPrefix, AcpProcessGroup.Os os) {
        List<String> prefix = List.copyOf(sessionPrefix);
        return launch -> {
            List<String> command = new ArrayList<>(prefix);
            command.addAll(launch.command);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(launch.env);
            if (launch.cwd != null) builder.directory(launch.cwd);
            Process process = builder.start();
            AcpProcessGroup tree = new AcpProcessGroup(process, !prefix.isEmpty(), os);
            return new Connection() {
                @Override public InputStream stdout() { return process.getInputStream(); }
                @Override public OutputStream stdin() { return process.getOutputStream(); }
                @Override public InputStream stderr() { return process.getErrorStream(); }
                @Override public boolean alive() { return process.isAlive(); }
                @Override public Integer exitCode() { return process.isAlive() ? null : process.exitValue(); }
                @Override public long pid() { return tree.pid(); }
                @Override public long group() { return tree.group(); }
                // With graceMillis <= 0 it kills first: stdin is closed by the next graceful destroy (the
                // pool's retire), once the write it may be blocking has failed.
                @Override public void destroy(long graceMillis) { tree.destroy(graceMillis); }
            };
        };
    }
}
