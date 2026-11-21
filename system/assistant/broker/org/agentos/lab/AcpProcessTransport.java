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
import java.util.concurrent.TimeUnit;

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

        /** Close stdin, wait up to {@code graceMillis}, then kill. Idempotent. */
        void destroy(long graceMillis);
    }

    static AcpProcessTransport process() {
        return launch -> {
            ProcessBuilder builder = new ProcessBuilder(launch.command);
            builder.environment().putAll(launch.env);
            if (launch.cwd != null) builder.directory(launch.cwd);
            Process process = builder.start();
            return new Connection() {
                @Override public InputStream stdout() { return process.getInputStream(); }
                @Override public OutputStream stdin() { return process.getOutputStream(); }
                @Override public InputStream stderr() { return process.getErrorStream(); }
                @Override public boolean alive() { return process.isAlive(); }
                @Override public Integer exitCode() { return process.isAlive() ? null : process.exitValue(); }
                @Override public void destroy(long graceMillis) {
                    try { process.getOutputStream().close(); } catch (IOException ignored) {}
                    try {
                        if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) return;
                        process.destroy();
                        if (process.waitFor(1, TimeUnit.SECONDS)) return;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    process.destroyForcibly();
                }
            };
        };
    }
}
