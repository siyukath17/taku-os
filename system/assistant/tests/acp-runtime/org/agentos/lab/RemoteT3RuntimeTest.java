package org.agentos.lab;

import com.android.systemui.taku.island.IslandPayload;
import com.sun.net.httpserver.HttpServer;
import org.agentos.assistant.AcpIslandProjection;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * The remote T3 lane against an in-memory remote host in virtual time: submit, streamed progress
 * and its island projection, cancel, transient-failure recovery, remote/auth failures (redacted),
 * timeouts; plus the HttpURLConnection binding against a loopback server.
 */
public final class RemoteT3RuntimeTest {
    private static int checks;
    static final String TOKEN = "t3tok_9f8e7d6c5b4a39281706";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    /** Virtual time: sleeping advances the clock instantly. */
    static final class FakeClock implements RemoteT3Runtime.Clock {
        long now = 1_000_000;
        long slept;

        @Override public long millis() { return now; }
        @Override public void sleep(long millis) { now += millis; slept += millis; }
    }

    static RemoteT3Transport.Failure transient_() {
        return new RemoteT3Transport.Failure(RemoteT3Transport.Failure.Kind.TRANSIENT, 0, "SocketTimeoutException");
    }

    /** Scripted remote host. Each queue item is a JSONObject answer or an IOException to throw. */
    static final class FakeRemote implements RemoteT3Transport {
        final Deque<Object> submits = new ArrayDeque<>(), statuses = new ArrayDeque<>(), interrupts = new ArrayDeque<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        JSONObject lastStatus = new JSONObject().put("runId", "run-1").put("status", "running");
        JSONObject body;
        String key, token, endpoint;
        int statusCalls;
        IntConsumer onStatus = n -> {};

        FakeRemote submit(Object answer) { submits.add(answer); return this; }
        FakeRemote status(Object answer) { statuses.add(answer); return this; }
        FakeRemote interruptAnswer(Object answer) { interrupts.add(answer); return this; }

        private static JSONObject answer(Object item) throws IOException {
            if (item instanceof IOException) throw (IOException) item;
            return (JSONObject) item;
        }

        @Override public JSONObject submit(Call call, String idempotencyKey, JSONObject run) throws IOException {
            calls.add("submit");
            body = run;
            key = idempotencyKey;
            token = call.token;
            endpoint = call.endpoint;
            Object next = submits.poll();
            return answer(next == null ? new JSONObject().put("runId", "run-1").put("threadId", "thread-1").put("status", "queued") : next);
        }

        @Override public JSONObject status(Call call, String runId) throws IOException {
            calls.add("status:" + runId);
            onStatus.accept(++statusCalls);
            Object next = statuses.poll();
            if (next == null) return lastStatus;
            if (next instanceof JSONObject) lastStatus = (JSONObject) next;
            return answer(next);
        }

        @Override public JSONObject interrupt(Call call, String runId) throws IOException {
            calls.add("interrupt:" + runId);
            Object next = interrupts.poll();
            return answer(next == null ? new JSONObject().put("runId", runId).put("status", "running") : next);
        }

        long count(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }
    }

    static JSONObject running(String phase, String summary) {
        return new JSONObject().put("runId", "run-1").put("status", "running").put("phase", phase).put("summary", summary);
    }

    static RemoteT3Runtime.Options options() throws Exception {
        return RemoteT3Runtime.Options.fromJson(new JSONObject()
                .put("endpoint", "https://t3.example.ts.net/")
                .put("tokenEnv", "TAKU_REMOTE_T3_TOKEN")
                .put("projectId", "taku")
                .put("environmentId", "vps-1")
                .put("model", "claude-opus-5-5")
                .put("pollIntervalMs", 1000)
                .put("progressIntervalMs", 0)
                .put("turnLimitMs", 600_000)
                .put("unreachableLimitMs", 30_000)
                .put("cancelGraceMs", 10_000));
    }

    static File task(String id, String runtime) throws Exception {
        File dir = Files.createTempDirectory("remote-t3-" + id).toFile();
        Files.writeString(new File(dir, "runtime-route.json").toPath(),
                new JSONObject().put("runtime", runtime).put("requested", runtime).put("reason", "selected-by-request").toString());
        return dir;
    }

    static JSONObject request(String id) {
        return new JSONObject().put("id", id).put("prompt", "Run the test suite on the VPS")
                .put("permissionMode", "full").put("screenshot", "/data/user/0/x/context.png").put("displayId", "7");
    }

    static String read(File dir, String name) throws Exception {
        File f = new File(dir, name);
        return f.isFile() ? Files.readString(f.toPath()) : null;
    }

    static JSONObject json(File dir, String name) throws Exception {
        return new JSONObject(read(dir, name));
    }

    static void run(RemoteT3Runtime.Options options, FakeRemote remote, FakeClock clock, File dir, JSONObject request,
                    RemoteT3Runtime.Notifier notifier, String token) throws Exception {
        new RemoteT3Runtime(options, remote, () -> token, notifier, null, clock).run(dir, request);
    }

    static void noSecrets(File dir, String label) throws Exception {
        for (File f : dir.listFiles()) {
            if (!f.isFile()) continue;
            String text = Files.readString(f.toPath());
            check(!text.contains(TOKEN), label + ": " + f.getName() + " never holds the token");
            check(!text.contains("sk-live-abcdef0123456789"), label + ": " + f.getName() + " never holds a leaked key");
        }
    }

    static JSONObject island(File dir, String id, String label) {
        AcpIslandProjection.Projection projection = AcpIslandProjection.fromTaskDirectory(dir, id);
        check(projection != null, label + ": projected");
        IslandPayload.Result result = IslandPayload.parse(projection.payload.toString(), AcpIslandProjectionTest.PACKAGE, false);
        check(result.isValid(), label + ": SystemUI parser accepts " + result.error + " " + projection.payload);
        return projection.payload;
    }

    public static void main(String[] args) throws Exception {
        validation();
        success();
        throttling();
        cancel();
        retries();
        failures();
        timeouts();
        http();
        System.out.println("RemoteT3RuntimeTest passed " + checks + " checks");
    }

    // ---- options, endpoints and token references ---------------------------------------------------

    static void rejects(JSONObject config, String code) throws Exception {
        try {
            RemoteT3Runtime.Options.fromJson(config);
            throw new AssertionError("accepted " + config);
        } catch (AcpRuntimeSettings.Invalid invalid) {
            check(invalid.getMessage().equals(code), config + " -> " + code + " (got " + invalid.getMessage() + ")");
            check(!invalid.getMessage().contains("sk-"), "error codes never echo values");
        }
    }

    static void validation() throws Exception {
        RemoteT3Runtime.Options o = options();
        check("https://t3.example.ts.net".equals(o.endpoint) && o.configured() && "env".equals(o.tokenSource()), "trailing slash trimmed, env reference");
        for (String ok : new String[]{"http://127.0.0.1:3773", "http://100.64.0.1:8080", "http://100.127.255.254", "https://mac.tail1234.ts.net/t3"})
            check(RemoteT3Runtime.endpoint(ok).equals(ok), "allowed endpoint " + ok);
        String[][] bad = {
                {"http://example.com", "INSECURE_ENDPOINT"}, {"http://100.128.0.1", "INSECURE_ENDPOINT"},
                {"http://100.63.255.255", "INSECURE_ENDPOINT"}, {"http://10.0.2.2:80", "INSECURE_ENDPOINT"},
                {"http://localhost.evil.com", "INSECURE_ENDPOINT"}, {"http://127.0.0.1.nip.io", "INSECURE_ENDPOINT"},
                {"ftp://t3.example", "INVALID_ENDPOINT"}, {"t3.example", "INVALID_ENDPOINT"},
                {"https://u:p@t3.example", "ENDPOINT_CREDENTIALS_NOT_ALLOWED"}, {"https://t3.example/?token=x", "INVALID_ENDPOINT"},
                {"https://t3.example/#f", "INVALID_ENDPOINT"}, {"https://t3.example/a/../b", "INVALID_ENDPOINT"},
                {"https://t3.ex\nample", "INVALID_ENDPOINT"}};
        for (String[] b : bad) rejects(new JSONObject().put("endpoint", b[0]), b[1]);
        rejects(new JSONObject().put("endpoint", 5), "INVALID_ENDPOINT");
        rejects(new JSONObject().put("token", "sk-live-abcdef0123456789"), "PLAINTEXT_TOKEN_NOT_ALLOWED");
        rejects(new JSONObject().put("apiKey", "x"), "PLAINTEXT_TOKEN_NOT_ALLOWED");
        rejects(new JSONObject().put("tokenEnv", "lower"), "INVALID_TOKEN_ENV");
        rejects(new JSONObject().put("tokenFile", "/data/local/tmp/t3.token"), "TOKEN_FILE_LOCATION_NOT_ALLOWED");
        rejects(new JSONObject().put("tokenFile", "/data/local/agentos/../tmp/t"), "INVALID_TOKEN_FILE");
        rejects(new JSONObject().put("tokenEnv", "A").put("tokenFile", "/data/local/agentos/t3.token"), "AMBIGUOUS_TOKEN_REFERENCE");
        rejects(new JSONObject().put("projectId", "a b"), "INVALID_PROJECT_ID");
        rejects(new JSONObject().put("model", "m;rm"), "INVALID_MODEL");
        rejects(new JSONObject().put("pollIntervalMs", 10), "INVALID_POLL_INTERVAL");
        rejects(new JSONObject().put("pollIntervalMs", 1.5), "INVALID_POLL_INTERVAL");
        check(!RemoteT3Runtime.Options.fromJson(new JSONObject().put("endpoint", "https://t3.example")).configured(), "endpoint without a token reference is unconfigured");

        JSONObject stored = new JSONObject().put("endpoint", "https://t3.example").put("tokenEnv", "T").put("model", "a");
        JSONObject merged = RemoteT3Runtime.Options.merge(stored, new JSONObject().put("model", "b").put("projectId", "p"));
        check("b".equals(merged.getString("model")) && "p".equals(merged.getString("projectId")) && "a".equals(stored.getString("model")),
                "request overrides are applied to a copy");
        for (String key : new String[]{"endpoint", "tokenEnv", "tokenFile", "token", "pollIntervalMs"}) {
            try {
                RemoteT3Runtime.Options.merge(stored, new JSONObject().put(key, "https://attacker.example"));
                throw new AssertionError("request override accepted: " + key);
            } catch (AcpRuntimeSettings.Invalid invalid) {
                check(("UNKNOWN_FIELD:remoteT3." + key).equals(invalid.getMessage()), "a request cannot override " + key);
            }
        }

        // Token sources: environment and a bounded file; the view reports presence, never the value.
        check(TOKEN.equals(RemoteT3Runtime.tokenSource(o, Map.of("TAKU_REMOTE_T3_TOKEN", TOKEN + "\n")).token()), "env token trimmed");
        check(RemoteT3Runtime.tokenSource(o, Map.of()).token() == null, "absent env variable: no token");
        JSONObject view = o.view(Map.of("TAKU_REMOTE_T3_TOKEN", TOKEN));
        check(view.getBoolean("tokenPresent") && !view.toString().contains(TOKEN), "view: presence only");
        File tokenFile = new File(Files.createTempDirectory("t3-token").toFile(), "t3.token");
        Files.writeString(tokenFile.toPath(), TOKEN + "\n");
        RemoteT3Runtime.Options fileOptions = new RemoteT3Runtime.Options();
        fileOptions.endpoint = "https://t3.example";
        fileOptions.tokenFile = tokenFile.getPath();
        check(TOKEN.equals(RemoteT3Runtime.tokenSource(fileOptions, null).token()) && "file".equals(fileOptions.tokenSource()), "file token");
        Files.writeString(tokenFile.toPath(), "two words");
        try {
            RemoteT3Runtime.tokenSource(fileOptions, null).token();
            throw new AssertionError("multi-token file accepted");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("words"), "token file errors never echo contents");
        }
    }

    // ---- dispatch, streamed progress, island -------------------------------------------------------

    static void success() throws Exception {
        File dir = task("401", "remote_t3");
        FakeRemote remote = new FakeRemote()
                .status(new JSONObject().put("runId", "run-1").put("status", "queued"))
                .status(running("thinking", "Reading the repository"))
                .status(running("tool", "Running tests").put("tool", "pnpm test").put("progress", new JSONObject().put("done", 1).put("total", 4)))
                .status(running("replying", "Summarising"))
                .status(new JSONObject().put("runId", "run-1").put("status", "completed")
                        .put("result", new JSONObject().put("message", "All 128 tests pass.\nNo regressions found.")));
        List<String> titles = new ArrayList<>();
        List<String> runtimes = new ArrayList<>();
        FakeClock clock = new FakeClock();
        run(options(), remote, clock, dir, request("401"), id -> {
            check("401".equals(id), "notifier gets the task id");
            JSONObject payload = island(dir, id, "live update");
            titles.add(payload.getJSONObject("expanded").getString("title"));
            try {
                if (new File(dir, "progress.json").isFile()) runtimes.add(json(dir, "progress.json").getString("runtime"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }, TOKEN);
        check("completed".equals(json(dir, "state.json").getString("state")), "terminal state completed");
        check("All 128 tests pass.".equals(json(dir, "state.json").getString("message")), "state message is the headline");
        check("completed".equals(read(dir, "done")), "done marker");
        JSONObject result = json(dir, "result.txt");
        check("completed".equals(result.getString("status")) && result.getString("message").contains("No regressions found."), "full reply in result.txt");
        check(titles.contains("Sending to remote T3") && titles.contains("Waiting for remote T3") && titles.contains("Taku is thinking")
                && titles.contains("Using pnpm test") && titles.contains("Taku is writing the result"), "island followed the remote phases " + titles);
        check("Taku finished".equals(titles.get(titles.size() - 1)), "final island card is finished " + titles);
        check(runtimes.stream().allMatch("remote_t3"::equals), "progress.json is tagged remote_t3");
        // Body and identity of the run.
        check(TOKEN.equals(remote.token) && "https://t3.example.ts.net".equals(remote.endpoint), "token and endpoint reach the transport only");
        check("taku-task-401".equals(remote.key), "stable idempotency key per task");
        JSONObject body = remote.body;
        check("Run the test suite on the VPS".equals(body.getString("prompt")) && "taku".equals(body.getString("projectId"))
                && "vps-1".equals(body.getString("environmentId")) && "claude-opus-5-5".equals(body.getString("model"))
                && "401".equals(body.getString("clientTaskId")), "run body carries prompt and routing fields");
        check(!body.has("screenshot") && !body.has("displayId") && !body.toString().contains("/data/"), "no local screenshot or paths leave the phone");
        JSONObject info = json(dir, "runtime-info.json");
        check("run-1".equals(info.getString("runId")) && "thread-1".equals(info.getString("threadId"))
                && "t3.example.ts.net".equals(info.getString("endpointHost")) && "remote_t3".equals(info.getString("runtime")), "runtime-info records the run");
        check(!new File(dir, "diagnostic.json").exists(), "no diagnostic on success");
        check(remote.count("status:run-1") == 5 && remote.count("interrupt") == 0, "polled until terminal, never interrupted");
        noSecrets(dir, "success");
    }

    static void throttling() throws Exception {
        File dir = task("402", "remote_t3");
        FakeRemote remote = new FakeRemote();
        for (int i = 0; i < 20; i++) remote.status(running("working", "Step " + i));
        remote.status(new JSONObject().put("runId", "run-1").put("status", "completed").put("result", new JSONObject().put("message", "ok")));
        RemoteT3Runtime.Options o = options();
        o.progressIntervalMillis = 5000;
        int[] updates = {0};
        run(o, remote, new FakeClock(), dir, request("402"), id -> updates[0]++, TOKEN);
        // 20 changing summaries at 1 s polls with a 5 s interval: about one write per 5 s, not 20.
        check(updates[0] < 12, "progress writes are throttled (" + updates[0] + " updates for 21 snapshots)");
        check("completed".equals(read(dir, "done")), "throttled run still completes");

        // Heartbeat: 5 minutes of an unchanged summary still refreshes progress.json so the island
        // (stale after 120 s) keeps showing a live run.
        File quiet = task("403", "remote_t3");
        FakeRemote steady = new FakeRemote();
        for (int i = 0; i < 300; i++) steady.status(running("working", "Compiling"));
        steady.status(new JSONObject().put("runId", "run-1").put("status", "completed").put("result", new JSONObject().put("message", "ok")));
        FakeClock clock = new FakeClock();
        List<Long> writes = new ArrayList<>();
        run(options(), steady, clock, quiet, request("403"), id -> {
            try {
                if (new File(quiet, "progress.json").isFile()) writes.add(json(quiet, "progress.json").getLong("updatedAt"));
            } catch (Exception e) { throw new AssertionError(e); }
        }, TOKEN);
        long maxGap = 0;
        for (int i = 1; i < writes.size(); i++) maxGap = Math.max(maxGap, writes.get(i) - writes.get(i - 1));
        check(writes.size() >= 5 && maxGap <= RemoteT3Runtime.HEARTBEAT_MILLIS + 2000,
                "heartbeat keeps progress fresh (" + writes.size() + " writes, max gap " + maxGap + " ms)");
    }

    // ---- cancel ------------------------------------------------------------------------------

    static void cancel() throws Exception {
        File early = task("410", "remote_t3");
        Files.writeString(new File(early, "cancel").toPath(), "");
        FakeRemote untouched = new FakeRemote();
        run(options(), untouched, new FakeClock(), early, request("410"), null, TOKEN);
        check("cancelled".equals(read(early, "done")) && untouched.calls.isEmpty() && !new File(early, "result.txt").exists(),
                "cancel before start: nothing is sent");

        File dir = task("411", "remote_t3");
        FakeRemote remote = new FakeRemote().status(running("working", "Building")).status(running("working", "Still building"));
        remote.onStatus = n -> {
            if (n == 2) try { Files.writeString(new File(dir, "cancel").toPath(), ""); } catch (IOException e) { throw new AssertionError(e); }
        };
        remote.interruptAnswer(new JSONObject().put("runId", "run-1").put("status", "running"));
        remote.status(new JSONObject().put("runId", "run-1").put("status", "cancelled"));
        List<String> titles = new ArrayList<>();
        run(options(), remote, new FakeClock(), dir, request("411"), id -> titles.add(island(dir, id, "cancel").getJSONObject("expanded").getString("title")), TOKEN);
        check(remote.count("interrupt:run-1") == 1, "cancel marker sends exactly one interrupt");
        check("cancelled".equals(json(dir, "state.json").getString("state")) && "cancelled".equals(read(dir, "done")), "remote confirmed: cancelled");
        check(!new File(dir, "result.txt").exists(), "cancelled without a reply writes no result.txt (same contract as ACP)");
        check(titles.contains("Taku is stopping") && "Taku stopped".equals(titles.get(titles.size() - 1)), "island showed stopping then stopped " + titles);

        File unconfirmed = task("412", "remote_t3");
        FakeRemote stubborn = new FakeRemote();
        stubborn.onStatus = n -> {
            if (n == 1) try { Files.writeString(new File(unconfirmed, "cancel").toPath(), ""); } catch (IOException e) { throw new AssertionError(e); }
        };
        FakeClock clock = new FakeClock();
        run(options(), stubborn, clock, unconfirmed, request("412"), null, TOKEN);
        check("failed".equals(read(unconfirmed, "done")) && "stop-unconfirmed".equals(json(unconfirmed, "diagnostic.json").getString("type")),
                "remote ignores the interrupt: failed after the grace period");
        check(clock.slept <= 15_000, "bounded by cancelGraceMs (slept " + clock.slept + ")");

        File retried = task("413", "remote_t3");
        FakeRemote flaky = new FakeRemote().interruptAnswer(transient_()).interruptAnswer(new JSONObject().put("status", "cancelled"));
        Files.writeString(new File(retried, "cancel").toPath(), "");
        // Cancel appears after submit: emulate by creating it from the first status poll.
        new File(retried, "cancel").delete();
        flaky.onStatus = n -> {
            if (n == 1) try { Files.writeString(new File(retried, "cancel").toPath(), ""); } catch (IOException e) { throw new AssertionError(e); }
        };
        run(options(), flaky, new FakeClock(), retried, request("413"), null, TOKEN);
        check(flaky.count("interrupt") == 2 && "cancelled".equals(read(retried, "done")), "a transient interrupt failure is retried");
    }

    // ---- network jitter ---------------------------------------------------------------------------

    static void retries() throws Exception {
        File dir = task("420", "remote_t3");
        FakeRemote remote = new FakeRemote().submit(transient_()).submit(transient_())
                .status(transient_()).status(transient_()).status(running("working", "Back online"))
                .status(new JSONObject().put("runId", "run-1").put("status", "completed").put("result", new JSONObject().put("message", "done")));
        List<String> summaries = new ArrayList<>();
        run(options(), remote, new FakeClock(), dir, request("420"), id -> {
            try { summaries.add(json(dir, "state.json").getString("message")); } catch (Exception e) { throw new AssertionError(e); }
        }, TOKEN);
        check(remote.count("submit") == 3 && "taku-task-420".equals(remote.key), "submit retried with the same idempotency key");
        check("completed".equals(read(dir, "done")), "recovers after transient submit and poll failures");
        check(summaries.contains("Remote T3 is not responding; retrying…"), "retrying is visible to the user");

        File down = task("421", "remote_t3");
        FakeRemote unreachable = new FakeRemote();
        for (int i = 0; i < 10; i++) unreachable.submit(transient_());
        run(options(), unreachable, new FakeClock(), down, request("421"), null, TOKEN);
        check(unreachable.count("submit") == 5 && "failed".equals(read(down, "done"))
                && "unreachable".equals(json(down, "diagnostic.json").getString("type")), "submit gives up after maxSubmitAttempts");

        File lost = task("422", "remote_t3");
        FakeRemote silent = new FakeRemote();
        for (int i = 0; i < 100; i++) silent.status(transient_());
        FakeClock clock = new FakeClock();
        run(options(), silent, clock, lost, request("422"), null, TOKEN);
        check("failed".equals(read(lost, "done")) && json(lost, "state.json").getString("message").startsWith("Lost contact"),
                "poll failures beyond unreachableLimitMs: failed, remote may still run");
        check(silent.count("status") >= 25 && silent.count("status") <= 35, "kept polling through the jitter window (" + silent.count("status") + ")");
    }

    // ---- failures, credentials, redaction --------------------------------------------------------

    static void failures() throws Exception {
        File dir = task("430", "remote_t3");
        FakeRemote remote = new FakeRemote().status(new JSONObject().put("runId", "run-1").put("status", "failed")
                .put("error", new JSONObject().put("code", "E_BUILD").put("message",
                        "build failed; Authorization: Bearer " + TOKEN + " api_key=sk-live-abcdef0123456789 echoed token " + TOKEN)));
        run(options(), remote, new FakeClock(), dir, request("430"), null, TOKEN);
        JSONObject diag = json(dir, "diagnostic.json");
        check("failed".equals(read(dir, "done")) && "remote-failed".equals(diag.getString("type")) && "remote_t3".equals(diag.getString("runtime")),
                "remote failure: failed with a remote-failed diagnostic");
        check(diag.getString("detail").startsWith("E_BUILD: build failed") && diag.getString("detail").contains("[hidden]"), "diagnostic keeps the code, hides secrets");
        check(json(dir, "state.json").getString("message").startsWith("Remote T3 could not finish the task: build failed"), "user sees the redacted reason");
        noSecrets(dir, "remote failure");
        JSONObject failedCard = island(dir, "430", "failed card");
        check("Taku couldn’t finish".equals(failedCard.getJSONObject("expanded").getString("title")), "island shows the failure");

        File auth = task("431", "remote_t3");
        FakeRemote denied = new FakeRemote().submit(RemoteT3Transport.Failure.fromStatus(401, "invalid token " + TOKEN));
        run(options(), denied, new FakeClock(), auth, request("431"), null, TOKEN);
        check("needs_user".equals(read(auth, "done")) && "auth-failed".equals(json(auth, "diagnostic.json").getString("type"))
                && "http 401".equals(json(auth, "diagnostic.json").getString("detail")), "401 on submit: needs_user, status code only");
        check(denied.count("submit") == 1, "auth failures are never retried");
        noSecrets(auth, "auth failure");
        AcpIslandProjection.Projection card = AcpIslandProjection.fromTaskDirectory(auth, "431");
        check(card != null && !card.signIn && "Taku needs your attention".equals(card.payload.getJSONObject("expanded").getString("title")),
                "remote needs_user never offers the on-device sign-in action");

        File revoked = task("432", "remote_t3");
        FakeRemote mid = new FakeRemote().status(running("working", "x")).status(RemoteT3Transport.Failure.fromStatus(403, ""));
        run(options(), mid, new FakeClock(), revoked, request("432"), null, TOKEN);
        check("needs_user".equals(read(revoked, "done")), "403 while polling: needs_user");

        File missing = task("433", "remote_t3");
        FakeRemote never = new FakeRemote();
        run(options(), never, new FakeClock(), missing, request("433"), null, null);
        check("needs_user".equals(read(missing, "done")) && never.calls.isEmpty()
                && "token-missing".equals(json(missing, "diagnostic.json").getString("type")), "missing token: needs_user, nothing sent");
        File unreadable = task("434", "remote_t3");
        new RemoteT3Runtime(options(), never, () -> { throw new IOException("token file too large"); }, null, null, new FakeClock())
                .run(unreadable, request("434"));
        check("needs_user".equals(read(unreadable, "done")) && never.calls.isEmpty(), "unreadable token: needs_user");

        File rejected = task("435", "remote_t3");
        run(options(), new FakeRemote().submit(RemoteT3Transport.Failure.fromStatus(422, "unknown project")), new FakeClock(), rejected, request("435"), null, TOKEN);
        check("failed".equals(read(rejected, "done")) && "submit-rejected".equals(json(rejected, "diagnostic.json").getString("type")), "4xx submit: failed");

        File gone = task("436", "remote_t3");
        run(options(), new FakeRemote().status(RemoteT3Transport.Failure.fromStatus(404, "")), new FakeClock(), gone, request("436"), null, TOKEN);
        check("failed".equals(read(gone, "done")) && "run-lost".equals(json(gone, "diagnostic.json").getString("type")), "404 while polling: run lost");

        File odd = task("437", "remote_t3");
        run(options(), new FakeRemote().submit(new JSONObject().put("status", "queued")), new FakeClock(), odd, request("437"), null, TOKEN);
        check("failed".equals(read(odd, "done")) && "protocol".equals(json(odd, "diagnostic.json").getString("type")), "submit without runId: protocol failure");

        File remoteCancel = task("438", "remote_t3");
        run(options(), new FakeRemote().status(new JSONObject().put("runId", "run-1").put("status", "cancelled")), new FakeClock(), remoteCancel, request("438"), null, TOKEN);
        check("cancelled".equals(read(remoteCancel, "done")) && json(remoteCancel, "state.json").getString("message").contains("remote host"),
                "cancelled on the remote side is reported as such");

        check(RemoteT3Transport.Failure.fromStatus(503, "").kind == RemoteT3Transport.Failure.Kind.TRANSIENT
                && RemoteT3Transport.Failure.fromStatus(429, "").kind == RemoteT3Transport.Failure.Kind.TRANSIENT
                && RemoteT3Transport.Failure.fromStatus(401, "").kind == RemoteT3Transport.Failure.Kind.AUTH
                && RemoteT3Transport.Failure.fromStatus(403, "").kind == RemoteT3Transport.Failure.Kind.AUTH
                && RemoteT3Transport.Failure.fromStatus(404, "").kind == RemoteT3Transport.Failure.Kind.NOT_FOUND
                && RemoteT3Transport.Failure.fromStatus(400, "").kind == RemoteT3Transport.Failure.Kind.REJECTED, "HTTP status classification");
    }

    // ---- timeouts ---------------------------------------------------------------------------------

    static void timeouts() throws Exception {
        RemoteT3Runtime.Options o = options();
        o.turnLimitMillis = 10_000;
        File dir = task("440", "remote_t3");
        FakeRemote endless = new FakeRemote();
        endless.interruptAnswer(new JSONObject().put("runId", "run-1").put("status", "running"));
        for (int i = 0; i < 11; i++) endless.status(running("working", "Working " + i));
        endless.status(new JSONObject().put("runId", "run-1").put("status", "cancelled"));
        run(o, endless, new FakeClock(), dir, request("440"), null, TOKEN);
        check(endless.count("interrupt") == 1, "turn limit interrupts the remote run");
        check("failed".equals(read(dir, "done")) && json(dir, "state.json").getString("message").startsWith("Execution timed out"),
                "timed out and confirmed stopped: failed (same as ACP)");

        File stuck = task("441", "remote_t3");
        FakeClock clock = new FakeClock();
        run(o, new FakeRemote(), clock, stuck, request("441"), null, TOKEN);
        check("failed".equals(read(stuck, "done")) && "timeout".equals(json(stuck, "diagnostic.json").getString("type")),
                "timeout without confirmation: failed with a timeout diagnostic");
        check(clock.slept <= o.turnLimitMillis + o.cancelGraceMillis + 2000, "bounded by turn limit + grace (slept " + clock.slept + ")");
    }

    // ---- the HttpURLConnection binding against a loopback server -----------------------------------

    static void http() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/api/taku/v1/runs", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + path + " key=" + exchange.getRequestHeaders().getFirst("Idempotency-Key") + " body=" + body);
            int status;
            String answer;
            if (!("Bearer " + TOKEN).equals(auth)) { status = 401; answer = "{\"error\":\"bad token\"}"; }
            else if (path.endsWith("/redirect")) { exchange.getResponseHeaders().add("Location", "https://attacker.example/"); status = 302; answer = ""; }
            else if (path.endsWith("/busy")) { status = 503; answer = "busy"; }
            else if (path.endsWith("/interrupt")) { status = 200; answer = "{\"status\":\"cancelled\"}"; }
            else if ("POST".equals(exchange.getRequestMethod())) { status = 201; answer = "{\"runId\":\"r 1\",\"status\":\"queued\"}"; }
            else { status = 200; answer = "{\"runId\":\"r 1\",\"status\":\"running\"}"; }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String endpoint = RemoteT3Runtime.endpoint("http://127.0.0.1:" + server.getAddress().getPort());
            RemoteT3Transport http = RemoteT3Transport.http();
            RemoteT3Transport.Call call = new RemoteT3Transport.Call(endpoint, TOKEN, 5000);
            JSONObject created = http.submit(call, "taku-task-9", new JSONObject().put("prompt", "x"));
            check("r 1".equals(created.getString("runId")), "http submit parses the run");
            check(seen.get(0).equals("POST /api/taku/v1/runs key=taku-task-9 body={\"prompt\":\"x\"}"), "http submit request shape " + seen);
            check("running".equals(http.status(call, "r 1").getString("status")) && seen.get(1).startsWith("GET /api/taku/v1/runs/r%201 "),
                    "http status escapes the run id " + seen);
            check("cancelled".equals(http.interrupt(call, "r 1").getString("status")) && seen.get(2).startsWith("POST /api/taku/v1/runs/r%201/interrupt"),
                    "http interrupt");
            expectKind(() -> http.status(new RemoteT3Transport.Call(endpoint, "wrong", 5000), "r"), RemoteT3Transport.Failure.Kind.AUTH, "http 401");
            expectKind(() -> http.status(call, "busy"), RemoteT3Transport.Failure.Kind.TRANSIENT, "http 503");
            expectKind(() -> http.status(call, "redirect"), RemoteT3Transport.Failure.Kind.REJECTED, "redirects are not followed");
        } finally {
            server.stop(0);
        }
        expectKind(() -> RemoteT3Transport.http().status(new RemoteT3Transport.Call("http://127.0.0.1:1", TOKEN, 2000), "r"),
                RemoteT3Transport.Failure.Kind.TRANSIENT, "connection refused is transient");
    }

    interface Exchange {
        JSONObject run() throws IOException;
    }

    static void expectKind(Exchange exchange, RemoteT3Transport.Failure.Kind kind, String label) {
        try {
            exchange.run();
            throw new AssertionError(label + ": no failure");
        } catch (RemoteT3Transport.Failure failure) {
            check(failure.kind == kind, label + ": " + failure.kind + " " + failure.getMessage());
            check(failure.getMessage() == null || !failure.getMessage().contains(TOKEN), label + ": no token in the failure");
        } catch (IOException other) {
            throw new AssertionError(label + ": unclassified " + other);
        }
    }
}
