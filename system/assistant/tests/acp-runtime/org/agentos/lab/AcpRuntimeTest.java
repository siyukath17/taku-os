package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Full ACP client lifecycle against the in-process fake agent; no Android, network or credentials. */
public final class AcpRuntimeTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static String read(File dir, String name) throws IOException {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    private static JSONObject json(File dir, String name) throws IOException {
        return new JSONObject(read(dir, name));
    }

    private static File taskDir(String id) throws IOException {
        File dir = Files.createTempDirectory("acp-task-" + id).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static JSONObject request(String id, String permission, File screenshot) {
        JSONObject r = new JSONObject().put("id", id).put("prompt", "Turn on Wi-Fi").put("permissionMode", permission);
        if (screenshot != null) r.put("screenshot", screenshot.getPath());
        return r;
    }

    private static AcpRuntime.Options options() throws Exception {
        AcpRuntime.Options o = AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy")
                .put("home", "/data/local/agentos/acp/home")
                .put("appDataDir", "taku-acp")
                .put("httpsProxy", "http://127.0.0.1:7890")
                .put("cwd", "")
                .put("progressIntervalMillis", 0)
                .put("requestTimeoutMillis", 5000)
                .put("cancelGraceMillis", 2000));
        return o;
    }

    private static final class Recorder implements AcpRuntime.Notifier {
        final File dir;
        final List<String> messages = new CopyOnWriteArrayList<>();
        final AtomicInteger count = new AtomicInteger();

        Recorder(File dir) { this.dir = dir; }

        @Override
        public void taskUpdated(String taskId) {
            count.incrementAndGet();
            try { messages.add(json(dir, "state.json").getString("message")); } catch (Exception ignored) {}
        }
    }

    static void normalCompletionAndStreaming() throws Exception {
        File dir = taskDir("normal");
        File png = new File(dir, "context.png");
        Files.write(png.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        Recorder recorder = new Recorder(dir);
        AtomicInteger idles = new AtomicInteger();
        new AcpRuntime(options(), agent, recorder, idles::incrementAndGet).run(dir, request("101", "full", png));

        check("completed".equals(read(dir, "done")), "normal: done marker is completed");
        JSONObject state = json(dir, "state.json");
        check("completed".equals(state.getString("state")) && "Done: Wi-Fi is on.".equals(state.getString("message")),
                "normal: state.json carries the reply headline, got " + state);
        JSONObject result = json(dir, "result.txt");
        check("completed".equals(result.getString("status")) && result.getString("message").equals("Done: Wi-Fi is on.\nDetails follow.")
                && result.getJSONArray("evidence").length() == 0, "normal: result.txt follows the task-result schema, got " + result);
        check(!result.getString("message").contains("LEAK"), "normal: other sessions' updates are ignored");

        AcpProcessTransport.Launch launch = agent.launches.get(0);
        // A full-mode task gives agy's subagents the same policy (their approvals have no ACP route).
        check(launch.command.equals(List.of("/data/local/agentos/acp/bin/agy", "acp", "--app-data-dir", "taku-acp", "--dangerously-skip-permissions")),
                "launch: binary, acp subcommand, --app-data-dir and the full-mode approval flag, got " + launch.command);
        check("/data/local/agentos/acp/home".equals(launch.env.get("HOME")), "launch: HOME is the agent's own home");
        check("http://127.0.0.1:7890".equals(launch.env.get("HTTPS_PROXY")), "launch: HTTPS_PROXY is forwarded");
        check("1".equals(launch.env.get("AGY_DISABLE_AUTO_UPDATE")) && "1".equals(launch.env.get("DISABLE_AUTO_UPDATE")),
                "launch: auto-update is disabled");
        check("0".equals(launch.env.get("GIT_TERMINAL_PROMPT")), "launch: git never blocks the turn on a terminal prompt");

        JSONObject init = agent.methods("initialize").get(0).getJSONObject("params");
        check(init.getInt("protocolVersion") == 1 && !init.getJSONObject("clientCapabilities").getBoolean("terminal"),
                "initialize: ACP v1 with no terminal capability");
        check(agent.methods("authenticate").isEmpty(), "auth: the client never drives a login on its own");
        JSONArray servers = agent.methods("session/new").get(0).getJSONObject("params").getJSONArray("mcpServers");
        JSONObject phone = servers.getJSONObject(0);
        check(servers.length() == 1 && "taku_phone".equals(phone.getString("name"))
                && phone.getJSONArray("args").toList().contains("org.agentos.lab.PhoneMcpHost"), "session/new: declares the phone MCP relay");
        boolean taskEnv = false, permissionEnv = false;
        for (int i = 0; i < phone.getJSONArray("env").length(); i++) {
            JSONObject e = phone.getJSONArray("env").getJSONObject(i);
            taskEnv |= "TAKU_MCP_TASK".equals(e.getString("name")) && "101".equals(e.getString("value"));
            permissionEnv |= "TAKU_MCP_PERMISSION".equals(e.getString("name")) && "full".equals(e.getString("value"));
        }
        check(taskEnv && permissionEnv, "session/new: the relay is bound to this task and permission mode");
        String thread = "";
        boolean turnEnv = false;
        for (int i = 0; i < phone.getJSONArray("env").length(); i++) {
            JSONObject e = phone.getJSONArray("env").getJSONObject(i);
            if ("TAKU_MCP_THREAD".equals(e.getString("name"))) thread = e.getString("value");
            turnEnv |= "TAKU_MCP_TURN".equals(e.getString("name"));
        }
        // The screen registries reject non-UUID sessions with INVALID_SCREEN_THREAD (Pixel 9, 2026-11-22).
        check(thread.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && thread.equals(AcpRuntime.phoneThread("", "101")) && !thread.equals(AcpRuntime.phoneThread("", "102")),
                "session/new: the relay names a stable UUID phone thread: " + thread);
        // A warm agent keeps one MCP server per distinct declaration: no per-turn value in it.
        check(!turnEnv, "session/new: the relay declaration names no turn (stable across turns)");
        check(PhoneTurnLog.events().contains("start " + thread + " acp-task-101")
                && PhoneTurnLog.events().contains("end " + thread + " acp-task-101"),
                "phone turn: the task's turn is started and explicitly ended: " + PhoneTurnLog.events());
        JSONArray prompt = agent.methods("session/prompt").get(0).getJSONObject("params").getJSONArray("prompt");
        check("s-1".equals(agent.methods("session/prompt").get(0).getJSONObject("params").getString("sessionId"))
                && "Turn on Wi-Fi".equals(prompt.getJSONObject(0).getString("text"))
                && "image".equals(prompt.getJSONObject(1).getString("type")), "session/prompt: text plus screenshot image");
        JSONObject permission = agent.response("perm-1");
        check(permission != null && "yes".equals(permission.getJSONObject("result").getJSONObject("outcome").getString("optionId")),
                "permission: full mode selects allow_once");
        JSONObject fs = agent.response("fs-1");
        check(fs != null && fs.getJSONObject("error").getInt("code") == -32601, "fs/read_text_file: method not found");

        JSONObject progress = json(dir, "progress.json");
        check(progress.getInt("toolCalls") == 1 && progress.getInt("toolCallsCompleted") == 1
                && "1/2".equals(progress.getString("plan")) && progress.getInt("permissionsAllowed") == 1,
                "progress.json: tool, plan and permission counters, got " + progress);
        JSONObject info = json(dir, "runtime-info.json");
        check("acp".equals(info.getString("runtime")) && "s-1".equals(info.getString("sessionId"))
                && "fake-agy".equals(info.getJSONObject("agentInfo").getString("name")), "runtime-info.json records the ACP session");
        check(recorder.messages.contains("Working: Open Settings") && recorder.messages.contains("Writing the result…"),
                "streaming: island-visible progress passes through tool and reply phases, got " + recorder.messages);
        check(recorder.messages.indexOf("Working: Open Settings") < recorder.messages.indexOf("Writing the result…"),
                "streaming: progress is ordered");
        check(agent.processes.get(0).destroyed, "process is reclaimed after the turn");
        check(idles.get() >= 0, "idle hook is safe to call");
    }

    static void readOnlyRejectsPermission() throws Exception {
        File dir = taskDir("readonly");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(), agent, null, null).run(dir, request("102", "readOnly", null));
        JSONObject permission = agent.response("perm-1");
        check("deny".equals(permission.getJSONObject("result").getJSONObject("outcome").getString("optionId")),
                "permission: readOnly selects reject_once");
        JSONArray prompt = agent.methods("session/prompt").get(0).getJSONObject("params").getJSONArray("prompt");
        check(prompt.length() == 1, "no screenshot, no image block");
    }

    static void needsUser() throws Exception {
        File dir = taskDir("auth");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NEEDS_AUTH);
        new AcpRuntime(options(), agent, null, null).run(dir, request("103", "full", null));
        check("needs_user".equals(read(dir, "done")), "auth: done marker is needs_user");
        check("needs_user".equals(json(dir, "state.json").getString("state"))
                && json(dir, "state.json").getString("message").contains("sign in with Google"), "auth: state guides the user to sign in on the phone");
        JSONObject auth = json(dir, "acp-auth.json");
        check("oauth-personal".equals(auth.getString("loginMethod"))
                && auth.getJSONArray("methods").toList().equals(List.of("oauth-personal", "gemini-api-key")), "auth: acp-auth.json names oauth-personal");
        check("needs_user".equals(json(dir, "result.txt").getString("status")), "auth: result.txt status needs_user");
        check(agent.methods("session/prompt").isEmpty() && agent.methods("authenticate").isEmpty(),
                "auth: no prompt is sent and no login is attempted by the broker");
        check(agent.processes.get(0).destroyed, "auth: process reclaimed");

        File promptDir = taskDir("auth-prompt");
        new AcpRuntime(options(), new FakeAcpAgent(FakeAcpAgent.Scenario.PROMPT_AUTH), null, null).run(promptDir, request("104", "full", null));
        check("needs_user".equals(read(promptDir, "done")), "auth: an auth_required prompt also becomes needs_user");
    }

    private static Thread background(AcpRuntime runtime, File dir, JSONObject request, List<Throwable> failures) {
        Thread thread = new Thread(() -> {
            try { runtime.run(dir, request); } catch (Throwable failure) { failures.add(failure); }
        });
        thread.start();
        return thread;
    }

    static void cancelMidTurn() throws Exception {
        File dir = taskDir("cancel");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread run = background(new AcpRuntime(options(), agent, null, null), dir, request("105", "full", null), failures);
        check(agent.promptStarted.await(5, TimeUnit.SECONDS), "cancel: prompt started");
        check("running".equals(json(dir, "state.json").getString("state")), "cancel: task is running before cancel");
        Files.write(new File(dir, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        run.join(5000);
        check(!run.isAlive() && failures.isEmpty(), "cancel: run returns cleanly " + failures);
        check("cancelled".equals(read(dir, "done")), "cancel: done marker is cancelled");
        check(json(dir, "state.json").getString("message").startsWith("Stopped."), "cancel: state says stopped");
        List<JSONObject> cancels = agent.methods("session/cancel");
        check(cancels.size() == 1 && "s-1".equals(cancels.get(0).getJSONObject("params").getString("sessionId"))
                && !cancels.get(0).has("id"), "cancel: one session/cancel notification for the session");
        check(agent.processes.get(0).destroyed, "cancel: process reclaimed");
        String thread = AcpRuntime.phoneThread("", "105");
        check(PhoneTurnLog.events().stream().filter(("end " + thread + " acp-task-105")::equals).count() == 1
                        && !PhoneTurnLog.events().contains("end-stale " + thread + " acp-task-105")
                        && AcpPhoneTurns.current(thread) == null,
                "cancel: the phone turn is ended exactly once, as the current turn: " + PhoneTurnLog.events());
    }

    static void cancelIgnoredIsReclaimed() throws Exception {
        File dir = taskDir("cancel-ignored");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.IGNORE_CANCEL);
        AcpRuntime.Options o = options();
        o.cancelGraceMillis = 300;
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread run = background(new AcpRuntime(o, agent, null, null), dir, request("106", "full", null), failures);
        check(agent.promptStarted.await(5, TimeUnit.SECONDS), "ignored cancel: prompt started");
        Files.write(new File(dir, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        run.join(5000);
        check(!run.isAlive() && failures.isEmpty(), "ignored cancel: run returns " + failures);
        check("failed".equals(read(dir, "done")) && "stop-unconfirmed".equals(json(dir, "diagnostic.json").getString("type")),
                "ignored cancel: unconfirmed stop fails honestly");
        check(agent.launches.size() == 1, "ignored cancel: an unconfirmed stop is never continued automatically");
        check(agent.processes.get(0).destroyed, "ignored cancel: process force-reclaimed");
    }

    static void cancelBeforeStart() throws Exception {
        File dir = taskDir("cancel-before");
        Files.write(new File(dir, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        FakeAcpAgent agent = new FakeAcpAgent();
        new AcpRuntime(options(), agent, null, null).run(dir, request("107", "full", null));
        check("cancelled".equals(read(dir, "done")) && agent.launches.isEmpty(), "cancel before start: no process is started");
        String thread = AcpRuntime.phoneThread("", "107");
        check(PhoneTurnLog.events().stream().noneMatch(e -> e.endsWith(thread + " acp-task-107")) && AcpPhoneTurns.current(thread) == null,
                "cancel before start: no phone turn is opened: " + PhoneTurnLog.events());
    }

    static void turnBudget() throws Exception {
        File dir = taskDir("timeout");
        AcpRuntime.Options o = options();
        o.turnLimitMillis = 200;
        new AcpRuntime(o, new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL), null, null).run(dir, request("108", "full", null));
        check("failed".equals(read(dir, "done")) && json(dir, "state.json").getString("message").contains("timed out"),
                "timeout: the fixed turn budget cancels and fails the task");
    }

    static void crashThenRecovery() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.CRASH, FakeAcpAgent.Scenario.NORMAL);
        AcpRuntime runtime = new AcpRuntime(options(), agent, null, null);
        File crashed = taskDir("crash");
        runtime.run(crashed, request("109", "full", null));
        check("failed".equals(read(crashed, "done")), "crash: done marker is failed");
        JSONObject diagnostic = json(crashed, "diagnostic.json");
        check("agent-exited".equals(diagnostic.getString("type")) && diagnostic.getString("detail").contains("exit=137"),
                "crash: diagnostic records the exit, got " + diagnostic);
        check(!diagnostic.getString("stderrTail").contains("abc123"), "crash: stderr tail is redacted");
        check(json(crashed, "result.txt").getString("message").contains("Partial answer"), "crash: partial reply is preserved");
        check(agent.processes.get(0).destroyed, "crash: process reclaimed");

        File next = taskDir("recovered");
        runtime.run(next, request("110", "full", null));
        check("completed".equals(read(next, "done")) && agent.launches.size() == 2,
                "recovery: the next task starts a fresh agent and completes");
        check(agent.methods("session/prompt").size() == 2, "crash without a network cause: not continued automatically");
    }

    static void startFailure() throws Exception {
        File dir = taskDir("start");
        FakeAcpAgent agent = new FakeAcpAgent();
        agent.startFailure = new IOException("No such file or directory");
        new AcpRuntime(options(), agent, null, null).run(dir, request("111", "full", null));
        check("failed".equals(read(dir, "done")) && "start-failed".equals(json(dir, "diagnostic.json").getString("type")),
                "start failure: task fails with a diagnostic");
    }

    static void optionsAndRedaction() throws Exception {
        AcpRuntime.Options defaults = AcpRuntime.Options.fromJson(new JSONObject().put("binary", "/x/agy").put("phoneMcp", JSONObject.NULL));
        check(defaults.phoneMcp == null && defaults.args.equals(List.of("acp")) && defaults.turnLimitMillis == TimeUnit.MINUTES.toMillis(15),
                "options: defaults and phoneMcp opt-out");
        String redacted = AcpRuntime.redact("Authorization: Bearer ya29.a0AfH6SMB refresh_token=1//0gAbCdEfGhIjKlMnOpQrStUv \"access_token\": \"zzz\"");
        check(!redacted.contains("ya29.a0") && !redacted.contains("1//0gAb") && !redacted.contains("zzz"), "redaction hides OAuth tokens: " + redacted);
    }

    private static AcpRuntime.Options fastContinue() throws Exception {
        AcpRuntime.Options o = options();
        o.autoContinueBackoffMillis = new ArrayList<>(List.of(10L, 20L));
        return o;
    }

    private static String promptText(FakeAcpAgent agent, int nth) {
        return agent.methods("session/prompt").get(nth).getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text");
    }

    private static JSONObject chatRequest(String id, String prompt) {
        return new JSONObject().put("id", id).put("prompt", prompt).put("permissionMode", "full").put("responseLocale", "en")
                .put("conversationId", AcpChatRuntimeTest.CONV).put("screenshot", "");
    }

    private static JSONObject toolItem(JSONObject turn, String toolCallId) {
        JSONArray items = turn.getJSONArray("items");
        for (int i = 0; i < items.length(); i++)
            if (items.getJSONObject(i).getString("id").endsWith("-tool-" + toolCallId)) return items.getJSONObject(i);
        return null;
    }

    /**
     * Regression (Pixel 9): redacting the serialized rawInput turned {@code TOKEN ===\"} into
     * {@code TOKEN =[hidden]"}, ate the escape and failed the whole turn with JSONException; a value
     * over 4000 chars kept only the document's tail. Values are now redacted one by one.
     */
    static void tokenCommandAndLongInput() throws Exception {
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(taskDir("token-store"), "acp-conversations"));
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        String command = "echo \"=== 1. SETUP GIT_ASKPASS USING GH AUTH TOKEN ===\"";
        StringBuilder longValue = new StringBuilder("HEAD-");
        while (longValue.length() < 6000) longValue.append("abcdefghij");
        longValue.append("-TAIL");
        agent.beforeFinish = turn -> List.of(
                new JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "tc-token").put("title", "Run command")
                        .put("kind", "execute").put("status", "in_progress")
                        .put("rawInput", new JSONObject().put("CommandLine", command)),
                new JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "tc-token").put("status", "completed")
                        .put("rawInput", new JSONObject().put("CommandLine", command).put("access_token", "ya29.zzz").put("Cwd", "/data")),
                new JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "tc-long").put("title", "Write file")
                        .put("kind", "edit").put("status", "in_progress")
                        .put("rawInput", new JSONObject().put("Content", longValue.toString()).put("Lines", new JSONArray().put(1).put(2))),
                new JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "tc-long").put("status", "completed"));
        File dir = taskDir("token");
        new AcpRuntime(options(), agent, null, null, host).run(dir, chatRequest("150", "set up git"));
        check("completed".equals(read(dir, "done")), "token command: the turn completes instead of failing on JSONException");
        check(!new File(dir, "diagnostic.json").exists(), "token command: no failure diagnostic");
        // The persisted conversation is valid JSON end to end.
        JSONObject stored = new JSONObject(new String(Files.readAllBytes(host.store.file(AcpChatRuntimeTest.CONV).toPath()), StandardCharsets.UTF_8));
        check(stored.getJSONArray("turns").length() == 1, "token command: one stored turn");
        JSONObject turn = AcpChatRuntimeTest.lastTurn(host.view());
        check("completed".equals(turn.getString("status")), "token command: turn completed in the projection");
        JSONObject token = toolItem(turn, "tc-token");
        JSONObject args = token == null ? null : token.optJSONObject("arguments");
        check(args != null && args.getString("CommandLine").startsWith("echo \"=== 1. SETUP GIT_ASKPASS USING GH AUTH TOKEN =")
                && args.getString("CommandLine").endsWith("\"") && "/data".equals(args.getString("Cwd")),
                "token command: arguments stay a JSON object with the command string: " + args);
        check("[hidden]".equals(args.getString("access_token")) && !args.toString().contains("ya29.zzz"),
                "token command: a credential-named value is hidden: " + args);
        JSONObject longArgs = toolItem(turn, "tc-long").optJSONObject("arguments");
        String content = longArgs.getString("Content");
        check(content.startsWith("HEAD-") && content.endsWith("…") && content.length() <= AcpRuntime.MAX_REDACTED_CHARS
                && !content.contains("-TAIL") && longArgs.getJSONArray("Lines").length() == 2,
                "long input: one value keeps its head and is clipped, the rest is intact");
        check("completed".equals(toolItem(turn, "tc-long").getString("status")), "long input: tool completed");
    }

    static void redactJsonKeepsStructure() throws Exception {
        JSONObject in = new JSONObject().put("cmd", "curl -H \"Authorization: Bearer ya29.SECRET\" x")
                .put("nested", new JSONObject().put("password", "hunter2").put("list", new JSONArray().put("token=abc123").put(7).put(true)))
                .put("quote", "say \"=== TOKEN ===\" \\ done");
        JSONObject out = (JSONObject) AcpRuntime.redactJson(in, 4000);
        JSONObject reparsed = new JSONObject(out.toString());
        check(!reparsed.toString().contains("ya29.SECRET") && !reparsed.toString().contains("hunter2") && !reparsed.toString().contains("abc123"),
                "redactJson: credentials hidden: " + reparsed);
        check(reparsed.getJSONObject("nested").getJSONArray("list").getInt(1) == 7 && reparsed.getJSONObject("nested").getJSONArray("list").getBoolean(2),
                "redactJson: numbers and booleans kept");
        check(reparsed.getString("quote").startsWith("say \"=== TOKEN =") && reparsed.getString("quote").contains("\\"),
                "redactJson: quotes and backslashes survive as a valid string: " + reparsed.getString("quote"));
        String plain = AcpRuntime.redact("x".repeat(5000));
        check(plain.length() == 5000, "redact: plain text is never truncated (or tail-cut)");
    }

    /** A network crash mid-turn is continued in the same session; the user's text is not resent. */
    static void autoContinuesTransientCrash() throws Exception {
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(taskDir("auto-store"), "acp-conversations"));
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.CRASH_NETWORK, FakeAcpAgent.Scenario.NORMAL);
        agent.loadSession = true;
        File dir = taskDir("auto");
        Recorder recorder = new Recorder(dir);
        new AcpRuntime(fastContinue(), agent, recorder, null, host).run(dir, chatRequest("160", "Turn on Wi-Fi"));
        check("completed".equals(read(dir, "done")), "auto continue: the task completes after a network crash");
        check(agent.launches.size() == 2, "auto continue: one fresh agent process");
        check(agent.methods("session/load").size() == 1
                && "s-1".equals(agent.methods("session/load").get(0).getJSONObject("params").getString("sessionId")),
                "auto continue: the interrupted session is loaded");
        check(AcpRuntime.withChatGuidance("Turn on Wi-Fi").equals(promptText(agent, 0)) && AcpRuntime.CONTINUE_PROMPT.equals(promptText(agent, 1)),
                "auto continue: the second prompt is the continuation instruction alone (no user text, no pacing note)");
        check(recorder.messages.contains("Network hiccup, continuing (1/2)…"), "auto continue: progress names the attempt " + recorder.messages);
        JSONArray turns = host.view().getJSONArray("turns");
        JSONObject turn = turns.getJSONObject(turns.length() - 1);
        check(turns.length() == 1 && "completed".equals(turn.getString("status")), "auto continue: one conversation turn, completed");
        check("Partial answer before the crash".equals(AcpChatRuntimeTest.item(turn, "agentMessage", 0).getString("text"))
                && AcpChatRuntimeTest.item(turn, "agentMessage", 1).getString("text").startsWith("Done"),
                "auto continue: the continuation's reply is its own message after the partial one");
        check(json(dir, "runtime-info.json").getInt("autoContinuation") == 1, "auto continue: runtime-info records the continuation");
    }

    /** A 503 UNAVAILABLE agent error is continued; without a loadable session the original request is carried. */
    static void autoContinuesTransientAgentError() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.PROMPT_UNAVAILABLE, FakeAcpAgent.Scenario.NORMAL);
        File dir = taskDir("auto-503");
        new AcpRuntime(fastContinue(), agent, null, null).run(dir, request("161", "full", null));
        check("completed".equals(read(dir, "done")) && agent.launches.size() == 2, "503: continued once and completed");
        check(promptText(agent, 1).startsWith("Original request:\nTurn on Wi-Fi") && promptText(agent, 1).endsWith(AcpRuntime.CONTINUE_PROMPT),
                "503: a fresh session gets the original request plus the continuation instruction");
    }

    static void nonTransientFailsWithoutContinuing() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.PROMPT_INTERNAL, FakeAcpAgent.Scenario.NORMAL);
        File dir = taskDir("internal");
        new AcpRuntime(fastContinue(), agent, null, null).run(dir, request("162", "full", null));
        check("failed".equals(read(dir, "done")) && agent.launches.size() == 1, "internal agent error: failed, not continued");
        check("agent-error".equals(json(dir, "diagnostic.json").getString("type")), "internal agent error: diagnostic kept");
    }

    static void continuationLimitStopsInFailure() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.PROMPT_UNAVAILABLE, FakeAcpAgent.Scenario.PROMPT_UNAVAILABLE,
                FakeAcpAgent.Scenario.PROMPT_UNAVAILABLE, FakeAcpAgent.Scenario.NORMAL);
        File dir = taskDir("limit");
        new AcpRuntime(fastContinue(), agent, null, null).run(dir, request("163", "full", null));
        check("failed".equals(read(dir, "done")) && agent.launches.size() == 3, "limit: two continuations, then the failure stands");
        check(json(dir, "state.json").getString("message").contains("reported an error"), "limit: the failure message is the agent error");
        check(!AcpRuntime.transientFailure(new AcpRuntime.AcpError(AcpRuntime.AUTH_REQUIRED, "UNAVAILABLE"), "")
                && AcpRuntime.transientFailure(new AcpRuntime.AcpError(-32603, "429 Too Many Requests"), "")
                && AcpRuntime.transientFailure(new AcpRuntime.AcpError(-32603, "rpc: DEADLINE_EXCEEDED"), "")
                && !AcpRuntime.transientFailure(new AcpRuntime.AcpError(-32603, "bad request 400"), "")
                && !AcpRuntime.transientFailure(new IOException("broker internal"), "UNAVAILABLE"),
                "transient classification: auth, internal and broker errors never continue");
    }

    /** The user's "Continue": an empty prompt, the interrupted session loaded, no user bubble stored. */
    static void userContinuation() throws Exception {
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(taskDir("cont-store"), "acp-conversations"));
        FakeAcpAgent first = new FakeAcpAgent(FakeAcpAgent.Scenario.PROMPT_INTERNAL);
        first.loadSession = true;
        File failed = taskDir("cont-failed");
        new AcpRuntime(fastContinue(), first, null, null, host).run(failed, chatRequest("170", "Book the table"));
        check("failed".equals(read(failed, "done")), "continue: the first task failed");

        FakeAcpAgent second = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        second.loadSession = true;
        File dir = taskDir("cont");
        JSONObject request = chatRequest("171", "").put("continuation", new JSONObject().put("ofTaskId", "170").put("reason", "user"));
        new AcpRuntime(fastContinue(), second, null, null, host).run(dir, request);
        check("completed".equals(read(dir, "done")), "continue: completes");
        check(second.methods("session/load").size() == 1 && AcpRuntime.CONTINUE_PROMPT.equals(promptText(second, 0)),
                "continue: session/load then the continuation instruction alone");
        JSONArray turns = host.view().getJSONArray("turns");
        JSONObject last = turns.getJSONObject(turns.length() - 1);
        JSONObject user = AcpChatRuntimeTest.item(last, "userMessage", 0);
        check(turns.length() == 2 && user.getJSONArray("content").getJSONObject(0).getString("text").isEmpty(),
                "continue: the continuation turn stores no user text (no duplicate bubble)");
        check("170".equals(json(dir, "runtime-info.json").getJSONObject("continuation").getString("ofTaskId")), "continue: runtime-info names the task");

        // session/load fails: the transcript fallback carries the interrupted request.
        FakeAcpAgent third = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        third.loadSession = true;
        third.loadFails = true;
        File fallback = taskDir("cont-fallback");
        new AcpRuntime(fastContinue(), third, null, null, host).run(fallback,
                chatRequest("172", "").put("continuation", new JSONObject().put("ofTaskId", "170").put("reason", "user")));
        check("completed".equals(read(fallback, "done")) && promptText(third, 0).contains("User: Book the table")
                && promptText(third, 0).endsWith(AcpRuntime.CONTINUE_PROMPT) && !promptText(third, 0).contains("Current message:"),
                "continue: transcript fallback ends with the continuation instruction: " + promptText(third, 0));
    }

    public static void main(String[] args) throws Exception {
        PhoneTurnLog.install();
        List<String> ran = new ArrayList<>();
        normalCompletionAndStreaming(); ran.add("normal+streaming");
        readOnlyRejectsPermission(); ran.add("readOnly");
        needsUser(); ran.add("needs_user");
        cancelMidTurn(); ran.add("cancel");
        cancelIgnoredIsReclaimed(); ran.add("cancel-ignored");
        cancelBeforeStart(); ran.add("cancel-before");
        turnBudget(); ran.add("timeout");
        crashThenRecovery(); ran.add("crash+recovery");
        startFailure(); ran.add("start-failure");
        optionsAndRedaction(); ran.add("options");
        tokenCommandAndLongInput(); ran.add("token-command+long-input");
        redactJsonKeepsStructure(); ran.add("redact-json");
        autoContinuesTransientCrash(); ran.add("auto-continue-crash");
        autoContinuesTransientAgentError(); ran.add("auto-continue-503");
        nonTransientFailsWithoutContinuing(); ran.add("no-continue-internal");
        continuationLimitStopsInFailure(); ran.add("continue-limit");
        userContinuation(); ran.add("user-continuation");
        System.out.println("AcpRuntimeTest passed " + checks + " checks " + ran);
    }
}
