package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>How the URL arrives (verified on the Pixel, android_arm64 agy 1.5.14): agy does not print it;
 * it runs {@code xdg-open <url>} from {@code PATH} and discards that child's output. Android has no
 * {@code xdg-open}, so the sign-in fails with "couldn't open a web browser". {@link BrowserBridge}
 * puts a private {@code xdg-open} first on the agent's {@code PATH}; it writes its argument to a FIFO
 * that only this process reads, so the URL passes through memory and the same host allow-list as any
 * printed URL. Output scanning stays as a fallback for agents that print the URL.
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

    /**
     * The main screen's tasks, so the browser window the sign-in opened can be closed when it ends.
     * Device: {@code am stack list} / {@code am stack remove} (wired in TaskBroker through PhoneTasks).
     */
    interface Windows {
        /** Task id to the package of its root activity, main screen only. */
        Map<Integer, String> list() throws Exception;

        void remove(int id) throws Exception;

        Windows NONE = new Windows() {
            public Map<Integer, String> list() { return Map.of(); }

            public void remove(int id) {}
        };
    }

    /** Never closed by the sign-in cleanup: the home screen and Taku's own chat. */
    static final java.util.Set<String> KEEP_PACKAGES = java.util.Set.of(
            "org.agentos.assistant", "org.agentos.agent",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher");

    static final List<String> DEFAULT_URL_HOSTS = List.of("accounts.google.com");
    /** ACP "request cancelled": agy's authenticate answer when its stdin closes mid sign-in. */
    static final int SIGN_IN_CANCELLED = -32800;
    private static final Pattern URL = Pattern.compile("https://[^\\s\"'<>`\\\\]+");
    private static final int MAX_URL_CHARS = 8192;

    private final File statusFile;
    private final AcpProcessTransport transport;
    private final IntentLauncher launcher;
    private final long timeoutMillis;
    private final Windows windows;
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean cancelled;
    private volatile AcpProcessTransport.Connection active;

    AcpAuthHelper(File statusFile, AcpProcessTransport transport, IntentLauncher launcher, long timeoutMillis) {
        this(statusFile, transport, launcher, timeoutMillis, Windows.NONE);
    }

    AcpAuthHelper(File statusFile, AcpProcessTransport transport, IntentLauncher launcher, long timeoutMillis,
            Windows windows) {
        this.statusFile = statusFile;
        this.transport = transport;
        this.launcher = launcher;
        this.timeoutMillis = timeoutMillis;
        this.windows = windows;
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

    /** A URL handed over by the agent's browser opener (the xdg-open bridge), not agent output. */
    private static final class OpenUrl {
        final String url;

        OpenUrl(String url) {
            this.url = url;
        }
    }

    /** Synchronous sign-in: initialize → authenticate, opening the browser for the captured URL. */
    void run(AcpRuntime.Options options, List<String> hosts) throws Exception {
        String method = options.loginMethod;
        // Same command line and environment as task turns: same private HOME and app-data dir.
        AcpProcessTransport.Launch launch = new AcpRuntime(options, transport, null, null).launch();
        // appDataDir is a name under $HOME/.gemini that the agent creates itself.
        if (!options.home.isEmpty()) new File(options.home).mkdirs();
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        BrowserBridge bridge = null;
        if (!options.home.isEmpty()) {
            try {
                bridge = BrowserBridge.open(new File(options.home), inbox);
                launch = bridge.wrap(launch);
            } catch (IOException noBridge) {
                bridge = null; // Fall back to scanning agent output.
            }
        }
        AcpProcessTransport.Connection connection;
        try {
            connection = transport.start(launch);
        } catch (IOException startFailed) {
            if (bridge != null) bridge.close();
            writeStatus("failed", method, null, "start-failed");
            return;
        }
        active = connection;
        reader(connection.stdout(), inbox, "acp-auth-stdout");
        reader(connection.stderr(), inbox, "acp-auth-stderr");
        OutputStream out = connection.stdin();
        String opened = null;
        List<Integer> browserTasks = new ArrayList<>();
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
                    // cancel() closes stdin, so a cancelled agent exits too: report the cancel.
                    if (cancelled) writeStatus("cancelled", method, host(opened), null);
                    else writeStatus("failed", method, host(opened), "agent-exited");
                    return;
                }
                boolean handedOver = next instanceof OpenUrl;
                String line = handedOver ? ((OpenUrl) next).url : (String) next;
                if (opened == null) {
                    String url = authorizationUrl(line, hosts);
                    if (url != null) {
                        Map<Integer, String> beforeView = tasks();
                        try {
                            launcher.view(url);
                            browserTasks.addAll(opened(beforeView));
                        } catch (IOException launchFailed) {
                            writeStatus("failed", method, host(url), "browser-launch-failed");
                            return;
                        }
                        opened = url;
                        writeStatus("browser_opened", method, host(url), null);
                    }
                }
                if (handedOver) continue;
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
                        // cancel() closes the agent's stdin and agy answers -32800 "Sign-in was cancelled."
                        // (seen on the Pixel); that reply can arrive before the loop sees the flag.
                        if (cancelled) {
                            writeStatus("cancelled", method, host(opened), null);
                            return;
                        }
                        JSONObject error = message.optJSONObject("error");
                        boolean agentCancelled = error != null && error.optInt("code") == SIGN_IN_CANCELLED;
                        writeStatus("failed", method, host(opened), agentCancelled ? "agent-cancelled" : "authenticate-error");
                    } else {
                        writeStatus("signed_in", method, host(opened), null);
                    }
                    return;
                }
            }
        } catch (IOException broken) {
            if (cancelled) writeStatus("cancelled", method, host(opened), null);
            else writeStatus("failed", method, host(opened), "agent-exited");
        } finally {
            active = null;
            connection.destroy(1000);
            if (bridge != null) bridge.close();
            closeWindows(browserTasks);
        }
    }

    // ---- the browser window -----------------------------------------------------------------------

    private Map<Integer, String> tasks() {
        try {
            return windows.list();
        } catch (Exception unavailable) {
            return Map.of();
        }
    }

    /**
     * Tasks the browser launch added to the main screen. A browser that reused a window the user
     * already had adds none, and that window is left alone. Android creates the task before
     * {@code am start} returns; a short re-check covers a slow trampoline.
     */
    private List<Integer> opened(Map<Integer, String> before) {
        List<Integer> added = new ArrayList<>();
        for (int i = 0; i < 4 && added.isEmpty(); i++) {
            if (i > 0) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Map.Entry<Integer, String> t : tasks().entrySet())
                if (!before.containsKey(t.getKey()) && !KEEP_PACKAGES.contains(t.getValue())) added.add(t.getKey());
            if (windows == Windows.NONE) break;
        }
        return added;
    }

    /** Signed in, cancelled, timed out or failed: the window opened for the sign-in goes. */
    private void closeWindows(List<Integer> ids) {
        for (int id : ids) {
            try {
                windows.remove(id);
            } catch (Exception ignored) {
                // Already gone (the user closed it), or Android unavailable: nothing left to do.
            }
        }
    }

    // ---- browser bridge -------------------------------------------------------------------------

    static final String OPEN_URL_FIFO_ENV = "TAKU_ACP_OPEN_URL_FIFO";
    static final String BRIDGE_DIR = ".taku-sign-in";
    private static final String STOP_LINE = "taku-bridge-stop";
    private static final int MAX_BRIDGE_LINE = 16 * 1024;

    /**
     * A private {@code xdg-open} on the agent's {@code PATH} that forwards its argument to a FIFO this
     * process reads. Lives in {@code <home>/.taku-sign-in} (owner-only; HOME is root-only on device)
     * for one sign-in and is removed afterwards. A FIFO holds no data on disk.
     */
    static final class BrowserBridge implements AutoCloseable {
        final File dir;
        final File shim;
        final File fifo;
        private final RandomAccessFile reader;
        private final Thread thread;

        private BrowserBridge(File dir, File shim, File fifo, RandomAccessFile reader, BlockingQueue<Object> inbox) {
            this.dir = dir;
            this.shim = shim;
            this.fifo = fifo;
            this.reader = reader;
            this.thread = new Thread(() -> {
                try {
                    String line;
                    while ((line = readLine(reader)) != null) {
                        if (STOP_LINE.equals(line)) return;
                        if (!line.isEmpty()) inbox.put(new OpenUrl(line));
                    }
                } catch (Exception ignored) {
                    // Closed: the sign-in is over.
                }
            }, "acp-auth-browser-bridge");
            thread.setDaemon(true);
            thread.start();
        }

        static BrowserBridge open(File home, BlockingQueue<Object> inbox) throws IOException {
            File dir = new File(home, BRIDGE_DIR);
            deleteTree(dir);
            if (!dir.mkdirs()) throw new IOException("bridge directory");
            ownerOnly(dir, true);
            File fifo = new File(dir, "open-url");
            Process mkfifo = new ProcessBuilder("mkfifo", "-m", "600", fifo.getPath()).redirectErrorStream(true).start();
            try {
                try (InputStream in = mkfifo.getInputStream()) { while (in.read() != -1) { /* drain */ } }
                if (!mkfifo.waitFor(5, TimeUnit.SECONDS) || mkfifo.exitValue() != 0) throw new IOException("mkfifo failed");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("mkfifo interrupted");
            }
            File shim = new File(dir, "xdg-open");
            String sh = new File("/system/bin/sh").canExecute() ? "/system/bin/sh" : "/bin/sh";
            // The URL is only ever an argument (never evaluated) and goes only to the FIFO.
            java.nio.file.Files.write(shim.toPath(), ("#!" + sh + "\n"
                    + "# Taku: hands the agent's sign-in URL to the broker through a FIFO; never stores it.\n"
                    + "f=\"$" + OPEN_URL_FIFO_ENV + "\"\n"
                    + "[ -p \"$f\" ] || exit 1\n"
                    + "[ $# -ge 1 ] || exit 2\n"
                    + "printf '%s\\n' \"$1\" > \"$f\"\n").getBytes(StandardCharsets.UTF_8));
            ownerOnly(shim, true);
            // O_RDWR: opening never blocks and the read side never sees EOF between writers.
            RandomAccessFile reader = new RandomAccessFile(fifo, "rw");
            return new BrowserBridge(dir, shim, fifo, reader, inbox);
        }

        /** The same launch with the bridge first on {@code PATH}. */
        AcpProcessTransport.Launch wrap(AcpProcessTransport.Launch launch) {
            Map<String, String> env = new LinkedHashMap<>(launch.env);
            String path = env.get("PATH");
            if (path == null || path.isEmpty()) path = System.getenv("PATH");
            if (path == null || path.isEmpty()) path = "/system/bin";
            env.put("PATH", dir.getPath() + ":" + path);
            env.put(OPEN_URL_FIFO_ENV, fifo.getPath());
            return new AcpProcessTransport.Launch(launch.command, env, launch.cwd);
        }

        @Override
        public void close() {
            try (RandomAccessFile stopper = new RandomAccessFile(fifo, "rw")) {
                stopper.write((STOP_LINE + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Reader already gone.
            }
            try { thread.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { reader.close(); } catch (IOException ignored) {}
            deleteTree(dir);
        }

        private static String readLine(RandomAccessFile in) throws IOException {
            StringBuilder line = new StringBuilder();
            for (; ; ) {
                int b = in.read();
                if (b < 0) return line.length() == 0 ? null : line.toString();
                if (b == '\n') return line.toString();
                if (line.length() < MAX_BRIDGE_LINE) line.append((char) b);
            }
        }

        private static void ownerOnly(File file, boolean executable) {
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
            if (executable) file.setExecutable(true, true);
        }

        private static void deleteTree(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
            file.delete();
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
