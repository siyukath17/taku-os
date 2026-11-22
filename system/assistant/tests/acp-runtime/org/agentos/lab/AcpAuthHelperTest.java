package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** oauth-personal trigger: URL capture, host allow-list, browser hand-off, and no credential leaks. */
public final class AcpAuthHelperTest {
    private static int checks;

    /** The main screen's tasks: id to package, in memory. */
    static final class Windows implements AcpAuthHelper.Windows {
        final Map<Integer, String> tasks = new ConcurrentHashMap<>();
        final List<Integer> removed = new CopyOnWriteArrayList<>();
        int next = 100;

        synchronized int open(String pkg) {
            tasks.put(next, pkg);
            return next++;
        }

        public Map<Integer, String> list() { return Map.copyOf(tasks); }

        public void remove(int id) {
            removed.add(id);
            tasks.remove(id);
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static AcpRuntime.Options options(File dir) throws Exception {
        return AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy")
                .put("home", new File(dir, "home").getPath())
                .put("appDataDir", "app-data")
                .put("cwd", "")
                .put("httpsProxy", "http://127.0.0.1:7890"));
    }

    /** A sign-in run; returns the final status. The launcher "visits" the URL by releasing the agent. */
    static JSONObject signIn(File dir, FakeAcpAgent agent, List<String> opened, boolean launchFails, long timeout) throws Exception {
        File status = new File(dir, "acp-login.json");
        AcpAuthHelper helper = new AcpAuthHelper(status, agent, url -> {
            if (launchFails) throw new IOException("no browser");
            opened.add(url);
            agent.browserVisited.countDown();
        }, timeout);
        helper.run(options(dir), AcpAuthHelper.DEFAULT_URL_HOSTS);
        String text = Files.readString(status.toPath());
        // The status file never carries the URL, its query, or anything credential-like.
        check(!text.contains("state=st-1") && !text.contains("client_id") && !text.contains("ya29") && !text.contains("SECRET"),
                "status file holds no URL or credential text: " + text);
        return new JSONObject(text);
    }

    public static void main(String[] args) throws Exception {
        List<String> hosts = AcpAuthHelper.DEFAULT_URL_HOSTS;
        // URL capture and allow-list.
        check(FakeAcpAgent.AUTH_URL.equals(AcpAuthHelper.authorizationUrl("Visit " + FakeAcpAgent.AUTH_URL + ".", hosts)),
                "plain log line: URL captured, trailing punctuation dropped");
        String escaped = new JSONObject().put("m", "go to " + FakeAcpAgent.AUTH_URL).toString()
                .replace("/", "\\/").replace("&", "\\u0026");
        check(FakeAcpAgent.AUTH_URL.equals(AcpAuthHelper.authorizationUrl(escaped, hosts)), "JSON-escaped URL captured");
        check(AcpAuthHelper.authorizationUrl("https://evil.example.com/o/oauth2?x=1", hosts) == null, "other hosts ignored");
        check(AcpAuthHelper.authorizationUrl("https://accounts.google.com.evil.com/x", hosts) == null, "suffix spoof ignored");
        check(AcpAuthHelper.authorizationUrl("https://user@accounts.google.com/x", hosts) == null, "user-info URL ignored");
        check(AcpAuthHelper.authorizationUrl("http://accounts.google.com/x", hosts) == null, "plain http ignored");
        check(AcpAuthHelper.authorizationUrl("listening on http://127.0.0.1:4321/oauth2callback", hosts) == null, "loopback ignored");
        check("https://x.accounts.google.com/a".equals(AcpAuthHelper.authorizationUrl("https://x.accounts.google.com/a", hosts)), "subdomain allowed");
        check(AcpAuthHelper.hosts(new JSONArray().put("Login.Example.com").put("bad host")).equals(List.of("login.example.com")),
                "configured hosts are normalised and filtered");
        check(AcpAuthHelper.hosts(new JSONArray().put("")).equals(hosts), "empty configuration falls back to defaults");

        File dir = Files.createTempDirectory("acp-auth").toFile();

        // URL on stderr → browser → agent confirms → signed_in.
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_STDERR);
        List<String> opened = new CopyOnWriteArrayList<>();
        JSONObject status = signIn(dir, agent, opened, false, 5000);
        check("signed_in".equals(status.getString("state")) && "accounts.google.com".equals(status.getString("urlHost")),
                "stderr URL: signed in, status records the host only (" + status + ")");
        check(opened.equals(List.of(FakeAcpAgent.AUTH_URL)), "exactly the official URL was opened, once");
        JSONObject auth = agent.methods("authenticate").get(0);
        check("oauth-personal".equals(auth.getJSONObject("params").getString("methodId")), "authenticate uses oauth-personal");
        AcpProcessTransport.Launch launch = agent.launches.get(0);
        check(launch.command.contains("--app-data-dir") && launch.command.contains("app-data")
                && new File(dir, "home").getPath().equals(launch.env.get("HOME")) && "1".equals(launch.env.get("AGY_CLI_DISABLE_AUTO_UPDATE"))
                && "http://127.0.0.1:7890".equals(launch.env.get("HTTPS_PROXY")),
                "sign-in uses the task runtime's private HOME/app-data dir, proxy and auto-update switch");
        check(agent.processes.get(0).destroyed, "the sign-in agent process is reclaimed");

        check(launch.env.get("PATH").startsWith(new File(dir, "home/" + AcpAuthHelper.BRIDGE_DIR).getPath() + ":")
                && launch.env.containsKey(AcpAuthHelper.OPEN_URL_FIFO_ENV), "sign-in puts the private xdg-open bridge first on PATH");
        check(!new File(dir, "home/" + AcpAuthHelper.BRIDGE_DIR).exists(), "bridge directory (shim + FIFO) removed after sign-in");

        // Real agy behaviour: the URL only reaches xdg-open (argv), never any output stream.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_XDG_OPEN);
        opened.clear();
        status = signIn(dir, agent, opened, false, 5000);
        check("signed_in".equals(status.getString("state")) && opened.equals(List.of(FakeAcpAgent.AUTH_URL))
                && "accounts.google.com".equals(status.getString("urlHost")),
                "xdg-open bridge: URL handed over through the FIFO and opened once (" + status + ")");
        check(!new File(dir, "home/" + AcpAuthHelper.BRIDGE_DIR).exists(), "bridge removed after the xdg-open sign-in");

        // App-data dir: agy resolves it under $HOME/.gemini and rejects absolute paths.
        check("taku-acp".equals(AcpRuntime.Options.fromJson(new JSONObject().put("appDataDir", "/data/local/agentos/acp/app-data")).appDataDir)
                && "taku-acp".equals(AcpRuntime.Options.fromJson(new JSONObject().put("appDataDir", "..")).appDataDir)
                && "taku-acp".equals(AcpRuntime.Options.fromJson(new JSONObject().put("appDataDir", "a/b")).appDataDir)
                && "taku-acp".equals(AcpRuntime.Options.fromJson(new JSONObject()).appDataDir)
                && "my-agy".equals(AcpRuntime.Options.fromJson(new JSONObject().put("appDataDir", "my-agy")).appDataDir),
                "appDataDir: plain names kept, absolute/traversal values fall back to taku-acp");

        // URL inside a JSON-RPC notification.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_JSON);
        opened.clear();
        status = signIn(dir, agent, opened, false, 5000);
        check("signed_in".equals(status.getString("state")) && opened.equals(List.of(FakeAcpAgent.AUTH_URL)), "notification URL: signed in");

        // Agent rejects the login after the browser step.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_ERROR);
        opened.clear();
        status = signIn(dir, agent, opened, false, 5000);
        check("failed".equals(status.getString("state")) && "authenticate-error".equals(status.getString("error")), "authenticate error reported");

        // No browser available.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_STDERR);
        status = signIn(dir, agent, opened, true, 5000);
        check("failed".equals(status.getString("state")) && "browser-launch-failed".equals(status.getString("error")), "browser launch failure reported");

        // Agent never answers: bounded.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_SILENT);
        opened.clear();
        status = signIn(dir, agent, opened, false, 600);
        check("timed_out".equals(status.getString("state")) && opened.isEmpty(), "silent agent times out without opening anything");

        // oauth-personal not offered.
        agent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_NO_METHOD);
        status = signIn(dir, agent, opened, false, 5000);
        check("failed".equals(status.getString("state")) && "method-unavailable".equals(status.getString("error")), "missing method reported");
        check(agent.methods("authenticate").isEmpty(), "no authenticate without the method");

        // Agent cannot start.
        agent = new FakeAcpAgent();
        agent.startFailure = new IOException("ENOENT");
        status = signIn(dir, agent, opened, false, 5000);
        check("failed".equals(status.getString("state")) && "start-failed".equals(status.getString("error")), "start failure reported");

        // Background start: single flight, then completion.
        FakeAcpAgent background = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_STDERR);
        AcpAuthHelper helper = new AcpAuthHelper(new File(dir, "acp-login-bg.json"), background, url -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                throw new IOException(interrupted);
            }
            background.browserVisited.countDown();
        }, 5000);
        JSONObject first = helper.start(options(dir), null);
        JSONObject second = helper.start(options(dir), null);
        check(second.optBoolean("alreadyRunning") && background.launches.size() <= 1, "concurrent sign-in is single-flight (" + first + ")");
        helper.join(5000);
        check("signed_in".equals(helper.status().getString("state")) && background.launches.size() == 1, "background sign-in completes");

        // Cancel.
        FakeAcpAgent silent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_SILENT);
        AcpAuthHelper cancellable = new AcpAuthHelper(new File(dir, "acp-login-cancel.json"), silent, url -> {}, 10000);
        cancellable.start(options(dir), null);
        for (int i = 0; i < 100 && !"authenticating".equals(cancellable.status().getString("state")); i++) Thread.sleep(20);
        cancellable.cancel();
        cancellable.join(5000);
        String finalState = cancellable.status().getString("state");
        check("cancelled".equals(finalState), "cancel ends the sign-in as cancelled (" + finalState + ")");
        // Cancel after the browser opened: agy answers the closed stdin with -32800 before the loop
        // notices the flag (seen on the Pixel). The status must still say cancelled.
        FakeAcpAgent eof = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_CANCEL_ON_EOF);
        AcpAuthHelper eofHelper = new AcpAuthHelper(new File(dir, "acp-login-eof.json"), eof, url -> {}, 10000);
        eofHelper.start(options(dir), null);
        for (int i = 0; i < 200 && !"browser_opened".equals(eofHelper.status().getString("state")); i++) Thread.sleep(20);
        check("browser_opened".equals(eofHelper.status().getString("state")), "browser opened before cancel");
        eofHelper.cancel();
        eofHelper.join(5000);
        check("cancelled".equals(eofHelper.status().getString("state")) && eofHelper.status().isNull("error"),
                "cancel answered by -32800 is reported as cancelled (" + eofHelper.status() + ")");

        // The browser window the sign-in opened is closed when it ends; the user's own stay.
        Windows windows = new Windows();
        int home = windows.open("org.agentos.assistant");
        int usersBrowser = windows.open("org.lineageos.jelly");
        FakeAcpAgent windowed = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_STDERR);
        int[] signInWindow = {-1};
        AcpAuthHelper closing = new AcpAuthHelper(new File(dir, "acp-login-window.json"), windowed, url -> {
            signInWindow[0] = windows.open("org.lineageos.jelly");
            windows.open("org.agentos.agent"); // never closed, even if it shows up meanwhile
            windowed.browserVisited.countDown();
        }, 5000, windows);
        closing.run(options(dir), AcpAuthHelper.DEFAULT_URL_HOSTS);
        check("signed_in".equals(closing.status().getString("state")), "windowed sign-in completes");
        check(windows.removed.equals(List.of(signInWindow[0])) && windows.tasks.containsKey(usersBrowser)
                        && windows.tasks.containsKey(home) && windows.tasks.containsValue("org.agentos.agent"),
                "signed in: only the sign-in's own browser task is removed (" + windows.removed + ")");

        Windows cancelWindows = new Windows();
        FakeAcpAgent eofWindow = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_CANCEL_ON_EOF);
        AcpAuthHelper cancelClosing = new AcpAuthHelper(new File(dir, "acp-login-window-cancel.json"), eofWindow,
                url -> cancelWindows.open("org.lineageos.jelly"), 10000, cancelWindows);
        cancelClosing.start(options(dir), null);
        for (int i = 0; i < 200 && !"browser_opened".equals(cancelClosing.status().getString("state")); i++) Thread.sleep(20);
        check(cancelWindows.tasks.size() == 1, "cancel: browser window open while signing in");
        cancelClosing.cancel();
        cancelClosing.join(5000);
        check("cancelled".equals(cancelClosing.status().getString("state")) && cancelWindows.tasks.isEmpty()
                && cancelWindows.removed.size() == 1, "cancelled: the sign-in's browser window is removed");

        Windows timeoutWindows = new Windows();
        int reused = timeoutWindows.open("org.lineageos.jelly");
        FakeAcpAgent timeoutAgent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_CANCEL_ON_EOF);
        AcpAuthHelper timing = new AcpAuthHelper(new File(dir, "acp-login-window-timeout.json"), timeoutAgent,
                url -> timeoutWindows.open("org.lineageos.jelly"), 800, timeoutWindows);
        timing.run(options(dir), AcpAuthHelper.DEFAULT_URL_HOSTS);
        check("timed_out".equals(timing.status().getString("state")) && timeoutWindows.removed.size() == 1
                && timeoutWindows.tasks.keySet().equals(java.util.Set.of(reused)),
                "timed out: the sign-in's browser window is removed, the user's kept (" + timing.status() + ")");

        Windows reuseWindows = new Windows();
        int only = reuseWindows.open("org.lineageos.jelly");
        FakeAcpAgent reuseAgent = new FakeAcpAgent(FakeAcpAgent.Scenario.AUTH_URL_STDERR);
        AcpAuthHelper reusing = new AcpAuthHelper(new File(dir, "acp-login-window-reuse.json"), reuseAgent,
                url -> reuseAgent.browserVisited.countDown(), 5000, reuseWindows);
        reusing.run(options(dir), AcpAuthHelper.DEFAULT_URL_HOSTS);
        check(reuseWindows.removed.isEmpty() && reuseWindows.tasks.containsKey(only),
                "a browser that reused the user's window adds no task, so nothing is closed");
        System.out.println("AcpAuthHelperTest passed " + checks + " checks");
    }
}
