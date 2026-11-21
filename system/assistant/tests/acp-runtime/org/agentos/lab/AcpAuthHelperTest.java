package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** oauth-personal trigger: URL capture, host allow-list, browser hand-off, and no credential leaks. */
public final class AcpAuthHelperTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static AcpRuntime.Options options(File dir) throws Exception {
        return AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy")
                .put("home", new File(dir, "home").getPath())
                .put("appDataDir", new File(dir, "app-data").getPath())
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
        check(launch.command.contains("--app-data-dir") && launch.command.contains(new File(dir, "app-data").getPath())
                && new File(dir, "home").getPath().equals(launch.env.get("HOME")) && "1".equals(launch.env.get("AGY_CLI_DISABLE_AUTO_UPDATE"))
                && "http://127.0.0.1:7890".equals(launch.env.get("HTTPS_PROXY")),
                "sign-in uses the task runtime's private HOME/app-data dir, proxy and auto-update switch");
        check(agent.processes.get(0).destroyed, "the sign-in agent process is reclaimed");

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
        check("cancelled".equals(finalState) || "failed".equals(finalState), "cancel ends the sign-in (" + finalState + ")");
        System.out.println("AcpAuthHelperTest passed " + checks + " checks");
    }
}
