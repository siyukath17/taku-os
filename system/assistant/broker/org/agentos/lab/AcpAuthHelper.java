package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the agent's own {@code oauth-personal} sign-in and hands the official authorization URL to
 * the phone's browser. The agent keeps its process alive to receive the loopback redirect and stores
 * the resulting login in its private {@code --app-data-dir} / {@code HOME}.
 *
 * <p>Credential boundary: this class never reads, prints or persists credentials. Agent output is
 * scanned in memory only for an authorization URL on an allowed host; the URL itself is passed to
 * the browser and never written anywhere (the status file records only its host). No agent
 * stdout/stderr text reaches disk or logs.
 */
final class AcpAuthHelper {
    /** Opens a URL in the system browser. Device: {@code am start -a VIEW -d <url>}. */
    interface IntentLauncher {
        void view(String url) throws IOException;

        static IntentLauncher activityManager() {
            return url -> {
                // argv, never a shell string: the URL cannot inject arguments or commands.
                Process process = new ProcessBuilder("/system/bin/am", "start", "--user", "0",
                        "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", url)
                        .redirectErrorStream(true).start();
                try (InputStream in = process.getInputStream()) {
                    byte[] sink = new byte[1024];
                    while (in.read(sink) != -1) { /* drain; output may echo the URL, so it is discarded */ }
                }
                try {
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new IOException("browser launch timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("browser launch interrupted");
                }
                if (process.exitValue() != 0) throw new IOException("browser launch failed");
            };
        }
    }

    static final List<String> DEFAULT_URL_HOSTS = List.of("accounts.google.com");
    private static final Pattern URL = Pattern.compile("https://[^\\s\"'<>`\\\\]+");
    private static final int MAX_URL_CHARS = 8192;

    private final File statusFile;
    private final AcpProcessTransport transport;
    private final IntentLauncher launcher;
    private final long timeoutMillis;
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean cancelled;
    private volatile AcpProcessTransport.Connection active;

    AcpAuthHelper(File statusFile, AcpProcessTransport transport, IntentLauncher launcher, long timeoutMillis) {
        this.statusFile = statusFile;
        this.transport = transport;
        this.launcher = launcher;
        this.timeoutMillis = timeoutMillis;
    }

    /** Starts one sign-in in the background; a second call while one runs returns its status. */
    JSONObject start(AcpRuntime.Options options, JSONArray configuredHosts) throws Exception {
        synchronized (lock) {
            if (worker != null && worker.isAlive()) return status().put("alreadyRunning", true);
            cancelled = false;
            List<String> hosts = hosts(configuredHosts);
            writeStatus("starting", options.loginMethod, null, null);
            worker = new Thread(() -> {
                try {
                    run(options, hosts);
                } catch (Exception unexpected) {
                    try { writeStatus("failed", options.loginMethod, null, "internal"); } catch (Exception ignored) {}
                }
            }, "acp-sign-in");
            worker.setDaemon(true);
            worker.start();
            return status();
        }
    }

    JSONObject status() throws Exception {
        if (!statusFile.isFile()) return new JSONObject().put("state", "idle");
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(statusFile.toPath()), StandardCharsets.UTF_8));
    }

    JSONObject cancel() throws Exception {
        cancelled = true;
        AcpProcessTransport.Connection connection = active;
        if (connection != null) connection.destroy(500);
        return status();
    }

    /** Waits for the background sign-in (tests). */
    void join(long millis) throws InterruptedException {
        Thread t;
        synchronized (lock) { t = worker; }
        if (t != null) t.join(millis);
    }

    // ---- one sign-in ----------------------------------------------------------------------------

    private static final Object EOF = new Object();

    /** Synchronous sign-in: initialize → authenticate, opening the browser for the captured URL. */
    void run(AcpRuntime.Options options, List<String> hosts) throws Exception {
        String method = options.loginMethod;
        // Same command line and environment as task turns: same private HOME and app-data dir.
        AcpProcessTransport.Launch launch = new AcpRuntime(options, transport, null, null).launch();
        for (String path : new String[]{options.home, options.appDataDir})
            if (!path.isEmpty()) new File(path).mkdirs();
        AcpProcessTransport.Connection connection;
        try {
            connection = transport.start(launch);
        } catch (IOException startFailed) {
            writeStatus("failed", method, null, "start-failed");
            return;
        }
        active = connection;
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        reader(connection.stdout(), inbox, "acp-auth-stdout");
        reader(connection.stderr(), inbox, "acp-auth-stderr");
        OutputStream out = connection.stdin();
        String opened = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            send(out, new JSONObject().put("id", 1).put("method", "initialize").put("params", new JSONObject()
                    .put("protocolVersion", AcpRuntime.PROTOCOL_VERSION)
                    .put("clientCapabilities", new JSONObject()
                            .put("fs", new JSONObject().put("readTextFile", false).put("writeTextFile", false))
                            .put("terminal", false))
                    .put("clientInfo", new JSONObject().put("name", "taku-os").put("version", "1"))));
            boolean authenticating = false;
            int closedStreams = 0;
            for (; ; ) {
                if (cancelled) {
                    writeStatus("cancelled", method, host(opened), null);
                    return;
                }
                if (System.nanoTime() > deadline) {
                    writeStatus("timed_out", method, host(opened), null);
                    return;
                }
                Object next = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (next == null) continue;
                if (next == EOF) {
                    // stdout and stderr both closed: the agent is gone. stderr alone may close early.
                    if (++closedStreams < 2) continue;
                    writeStatus("failed", method, host(opened), "agent-exited");
                    return;
                }
                String line = (String) next;
                if (opened == null) {
                    String url = authorizationUrl(line, hosts);
                    if (url != null) {
                        try {
                            launcher.view(url);
                        } catch (IOException launchFailed) {
                            writeStatus("failed", method, host(url), "browser-launch-failed");
                            return;
                        }
                        opened = url;
                        writeStatus("browser_opened", method, host(url), null);
                    }
                }
                JSONObject message;
                try {
                    message = new JSONObject(line);
                } catch (Exception notJson) {
                    continue;
                }
                if (message.has("method") && message.has("id")) {
                    // We declared no client capabilities; refuse agent→client requests.
                    send(out, new JSONObject().put("id", message.get("id")).put("error",
                            new JSONObject().put("code", -32601).put("message", "Method not found")));
                    continue;
                }
                if (!message.has("id")) continue;
                long id = message.optLong("id", -1);
                if (id == 1 && !authenticating) {
                    if (message.has("error")) {
                        writeStatus("failed", method, null, "initialize-error");
                        return;
                    }
                    JSONObject result = message.optJSONObject("result");
                    if (!offers(result == null ? null : result.optJSONArray("authMethods"), method)) {
                        writeStatus("failed", method, null, "method-unavailable");
                        return;
                    }
                    authenticating = true;
                    writeStatus("authenticating", method, null, null);
                    send(out, new JSONObject().put("id", 2).put("method", "authenticate")
                            .put("params", new JSONObject().put("methodId", method)));
                } else if (id == 2) {
                    if (message.has("error")) {
                        writeStatus("failed", method, host(opened), "authenticate-error");
                    } else {
                        writeStatus("signed_in", method, host(opened), null);
                    }
                    return;
                }
            }
        } catch (IOException broken) {
            writeStatus("failed", method, host(opened), "agent-exited");
        } finally {
            active = null;
            connection.destroy(1000);
        }
    }

    private static boolean offers(JSONArray methods, String id) {
        if (methods == null) return false;
        for (int i = 0; i < methods.length(); i++) {
            JSONObject m = methods.optJSONObject(i);
            if (m != null && id.equals(m.optString("id"))) return true;
        }
        return false;
    }

    private static void send(OutputStream out, JSONObject message) throws Exception {
        byte[] line = (message.put("jsonrpc", "2.0").toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(line);
            out.flush();
        }
    }

    /** Lines from one agent stream into the inbox, held in memory only. */
    private static void reader(InputStream in, BlockingQueue<Object> inbox, String name) {
        Thread t = new Thread(() -> {
            if (in == null) {
                inbox.offer(EOF);
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() > 1_000_000) line = line.substring(0, 1_000_000);
                    inbox.put(line);
                }
            } catch (Exception ignored) {
                // A closed stream is the agent going away.
            } finally {
                inbox.offer(EOF);
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    // ---- URL capture ----------------------------------------------------------------------------

    static List<String> hosts(JSONArray configured) {
        if (configured == null || configured.length() == 0) return DEFAULT_URL_HOSTS;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < configured.length(); i++) {
            String h = configured.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (h.matches("[a-z0-9.-]{1,253}") && h.contains(".")) out.add(h);
        }
        return out.isEmpty() ? DEFAULT_URL_HOSTS : out;
    }

    /**
     * The first https URL in {@code text} whose host is (or is a subdomain of) an allowed host, or
     * null. JSON-escaped forms ({@code \/}, {@code &}) are decoded first, so URLs inside
     * protocol messages are found as well as URLs on plain log lines.
     */
    static String authorizationUrl(String text, List<String> hosts) {
        if (text == null || text.isEmpty()) return null;
        String decoded = text.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")
                .replace("\\u003D", "=").replace("\\u003f", "?").replace("\\u003F", "?");
        Matcher m = URL.matcher(decoded);
        while (m.find()) {
            String candidate = m.group();
            while (!candidate.isEmpty() && ".,;:)]}".indexOf(candidate.charAt(candidate.length() - 1)) >= 0)
                candidate = candidate.substring(0, candidate.length() - 1);
            if (candidate.length() > MAX_URL_CHARS) continue;
            String host = host(candidate);
            if (host == null) continue;
            for (String allowed : hosts)
                if (host.equals(allowed) || host.endsWith("." + allowed)) return candidate;
        }
        return null;
    }

    /** Lower-case host of an https URL without user info, else null. */
    static String host(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null)
                return null;
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Status without secrets: state, method, the URL's host only, an error code, a timestamp. */
    private void writeStatus(String state, String method, String urlHost, String error) throws Exception {
        TaskRecords.write(statusFile, new JSONObject()
                .put("state", state)
                .put("method", method)
                .put("urlHost", urlHost == null ? JSONObject.NULL : urlHost)
                .put("error", error == null ? JSONObject.NULL : error)
                .put("updatedAt", System.currentTimeMillis()).toString());
    }
}
