package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * agy's asynchronous subagents (invoke_subagent) on the ACP runtime, against a fake agent that follows
 * the protocol measured on the Pixel 9 (agy 1.5.14): the parent's session/prompt ends while children
 * run, children's reports land in agy's store and the parent wakes by itself. The runtime keeps the
 * agent alive until every child reported and the parent answered, and still ends on cancel, on the
 * turn budget, and with a follow-up prompt when the parent never picks up the reports. Also: tool
 * display labels and internal-path redaction.
 */
public final class AcpSubagentRuntimeTest {
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

    private static File temp(String name) throws IOException {
        File dir = Files.createTempDirectory("acp-sub-" + name).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static AcpRuntime.Options options(File home, JSONObject extra) throws Exception {
        JSONObject c = new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy").put("cwd", "")
                .put("home", home.getPath()).put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000)
                .put("cancelGraceMillis", 1000).put("subagentSettleMillis", 400).put("subagentNudgeMillis", 900)
                .put("subagentPollMillis", 100).put("subagentIdleMillis", 1500);
        if (extra != null) for (String key : extra.keySet()) c.put(key, extra.get(key));
        return AcpRuntime.Options.fromJson(c);
    }

    private static JSONObject chat(String id) {
        return new JSONObject().put("id", id).put("prompt", "do two things").put("permissionMode", "full")
                .put("conversationId", AcpChatRuntimeTest.CONV);
    }

    private static List<JSONObject> agentMessages(JSONObject turn) {
        List<JSONObject> out = new java.util.ArrayList<>();
        JSONArray items = turn.getJSONArray("items");
        for (int i = 0; i < items.length(); i++)
            if ("agentMessage".equals(items.getJSONObject(i).getString("type"))) out.add(items.getJSONObject(i));
        return out;
    }

    /** Recorder of island-relevant progress at every task update. */
    static final class Progress implements AcpRuntime.Notifier {
        final File dir;
        final List<JSONObject> seen = new CopyOnWriteArrayList<>();

        Progress(File dir) { this.dir = dir; }

        @Override
        public void taskUpdated(String taskId) {
            try { seen.add(json(dir, "progress.json")); } catch (Exception ignored) {}
        }

        boolean phase(String phase) {
            for (JSONObject p : seen) if (phase.equals(p.optString("phase"))) return true;
            return false;
        }
    }

    static void asyncSubagentsKeepTheAgentAlive() throws Exception {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        try {
            File home = temp("home"), dir = temp("t1");
            AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(temp("store"), "acp-conversations"));
            FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS);
            agent.subagentDelayMillis = 400;
            Progress progress = new Progress(dir);
            long started = System.nanoTime();
            new AcpRuntime(options(home, null), agent, progress, null, host).run(dir, chat("501"));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            check("completed".equals(read(dir, "done")), "async: the task completes after the subagents report");
            check(json(dir, "state.json").getString("message").startsWith("All done. Summary"),
                    "async: the task headline is the parent's final summary, not its launch note: " + json(dir, "state.json"));
            check(tookMs >= 800, "async: the runtime waited for both reports, took " + tookMs + "ms");
            check(agent.methods("session/prompt").size() == 1 && agent.methods("session/cancel").isEmpty(),
                    "async: no extra prompt and no cancel when the parent wakes by itself");
            check(agent.processes.get(0).destroyed, "async: the agent process is reclaimed at the end");
            String result = read(dir, "result.txt");
            check(result.contains("All done. Summary: Android 17, battery 74%."), "async: the parent's summary is the reply: " + result);
            check(!result.contains(".gemini") && !result.contains("acp/home") && !result.contains("conversation://"),
                    "async: no internal path or subagent link in the reply: " + result);

            JSONObject turn = AcpChatRuntimeTest.lastTurn(host.view());
            check("completed".equals(turn.getString("status")), "async: chat turn completed");
            List<JSONObject> messages = agentMessages(turn);
            check(messages.size() == 3, "async: launch note and each wake-up are separate messages " + messages);
            JSONObject last = messages.get(messages.size() - 1);
            check("final_answer".equals(last.getString("phase")) && last.getString("text").contains("All done. Summary")
                    && last.getString("text").contains("media_0.png") && !last.getString("text").contains("../"),
                    "async: the summary is the final answer, the screenshot path reduced to its name: " + last);
            check("commentary".equals(messages.get(0).getString("phase"))
                    && messages.get(0).getString("text").contains("(`child-a`)"), "async: subagent links keep only their text " + messages.get(0));

            JSONObject tool = AcpChatRuntimeTest.item(turn, "mcpToolCall", 0);
            check("invoke_subagent".equals(tool.getString("tool")) && "派发子任务".equals(tool.getString("title"))
                    && "invoke_subagent".equals(tool.getString("toolName")) && "completed".equals(tool.getString("status")),
                    "async: invoke_subagent shows as 派发子任务, internal name kept: " + tool);

            // Each invoke_subagent item carries a structured task card.
            JSONObject cardA = tool.getJSONObject("subagent");
            JSONObject cardB = AcpChatRuntimeTest.item(turn, "mcpToolCall", 1).getJSONObject("subagent");
            check("subagent-1".equals(cardA.getString("id")) && "subagent-2".equals(cardB.getString("id")),
                    "cards: ordinal ids, never agy's conversation ids " + cardA + cardB);
            check("Version Checker".equals(cardA.getString("title")) && "Battery Checker".equals(cardB.getString("title"))
                    && "self".equals(cardA.getString("type")) && "local".equals(cardA.getString("source")),
                    "cards: title from the Role, type and source " + cardA);
            check("completed".equals(cardA.getString("status")) && "completed".equals(cardB.getString("status")),
                    "cards: both completed once reported " + cardA + cardB);
            check(cardA.getLong("startedAt") > 0 && cardA.getLong("completedAt") >= cardA.getLong("startedAt")
                    && cardA.getLong("elapsedMs") == cardA.getLong("completedAt") - cardA.getLong("startedAt")
                    && cardB.getLong("completedAt") >= cardA.getLong("completedAt"), "cards: start, end and elapsed " + cardA + cardB);
            check("Gemini 4 Neon".equals(cardA.getString("model")), "cards: the child's model from its transcript " + cardA);
            check("do Version Checker".equals(cardA.getString("goal")), "cards: the goal is the dispatched prompt " + cardA);
            check("child-a done, see out.txt".equals(cardA.getString("summary")),
                    "cards: summary is the report's first line of substance, plain and without internal paths " + cardA);
            check(cardA.getString("report").contains("result of child-a") && !cardA.getString("report").contains(".gemini")
                    && !cardA.toString().contains("/data/local/agentos"), "cards: report sanitized " + cardA);
            check(cardA.getString("activity").isEmpty(), "cards: no current activity once finished " + cardA);
            boolean runningCard = false, runningStage = false;
            for (JSONObject view : host.published) {
                JSONObject t = AcpChatRuntimeTest.lastTurn(view);
                if (!"inProgress".equals(t.getString("status"))) continue;
                for (int i = 0; i < t.getJSONArray("items").length(); i++) {
                    JSONObject card = t.getJSONArray("items").getJSONObject(i).optJSONObject("subagent");
                    if (card == null || !"running".equals(card.getString("status"))) continue;
                    runningCard = true;
                    if ("Version Checker".equals(card.getString("title")) && "读取设备信息".equals(card.getString("activity"))) runningStage = true;
                }
            }
            check(runningCard && runningStage, "cards: while running, the card streams with the child's current step");

            JSONObject finalProgress = json(dir, "progress.json");
            check(finalProgress.getInt("subagents") == 2 && finalProgress.getInt("subagentsDone") == 2,
                    "async: progress.json counts the subagents " + finalProgress);
            check(progress.phase("subagents"), "async: the island saw the waiting-for-sub-tasks phase");
            boolean partial = false;
            for (JSONObject p : progress.seen)
                if ("subagents".equals(p.optString("phase")) && p.optInt("subagentsDone") == 1) partial = true;
            check(partial, "async: the island counted 1/2 while waiting");
            // The chat page streamed the turn while waiting.
            boolean streamedWhileWaiting = false;
            for (JSONObject view : host.published) {
                JSONObject t = AcpChatRuntimeTest.lastTurn(view);
                for (JSONObject m : agentMessages(t))
                    if ("inProgress".equals(t.getString("status")) && m.getString("text").contains("Sub-task A finished")) streamedWhileWaiting = true;
            }
            check(streamedWhileWaiting, "async: the first report streamed into the chat while the turn was still running");
        } finally {
            Locale.setDefault(saved);
        }
    }

    static void inlineSubagentsDoNotWaitOrNudge() throws Exception {
        File home = temp("home-inline"), dir = temp("inline");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_INLINE);
        long started = System.nanoTime();
        new AcpRuntime(options(home, null), agent, null, null).run(dir,
                new JSONObject().put("id", "502").put("prompt", "x").put("permissionMode", "full"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("completed".equals(read(dir, "done")) && tookMs < 3000, "inline: completes promptly, took " + tookMs + "ms");
        check(agent.methods("session/prompt").size() == 1, "inline: no follow-up prompt");
        JSONObject result = json(dir, "result.txt");
        check(result.getString("message").contains("Summary: A=17, B=74%. See log.") && !result.getString("message").contains("file://"),
                "inline: background result carries the summary, internal link reduced to its text: " + result);
    }

    static void silentParentGetsOneFollowUpPrompt() throws Exception {
        File home = temp("home-silent"), dir = temp("silent");
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(temp("store-silent"), "acp-conversations"));
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_SILENT_PARENT);
        agent.subagentDelayMillis = 100;
        new AcpRuntime(options(home, null), agent, null, null, host).run(dir, chat("503"));
        check("completed".equals(read(dir, "done")), "silent: completes");
        List<JSONObject> prompts = agent.methods("session/prompt");
        check(prompts.size() == 2 && "s-1".equals(prompts.get(1).getJSONObject("params").getString("sessionId"))
                && prompts.get(1).getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text").contains("reported back"),
                "silent: one follow-up prompt in the same session wakes the parent");
        check(read(dir, "result.txt").contains("Combined after prompt: A=17, B=74%."), "silent: the woken parent's answer is the reply");
        List<JSONObject> messages = agentMessages(AcpChatRuntimeTest.lastTurn(host.view()));
        check("final_answer".equals(messages.get(messages.size() - 1).getString("phase"))
                && messages.get(messages.size() - 1).getString("text").startsWith("Combined after prompt"), "silent: summary is the final answer");
        check(agent.processes.get(0).destroyed, "silent: process reclaimed");
    }

    static void cancelWhileSubagentsRun() throws Exception {
        File home = temp("home-cancel"), dir = temp("cancel");
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(temp("store-cancel"), "acp-conversations"));
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_HANG);
        Thread canceller = new Thread(() -> {
            try {
                agent.promptStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(300);
                Files.writeString(new File(dir, "cancel").toPath(), "user");
            } catch (Exception ignored) {}
        });
        canceller.start();
        long started = System.nanoTime();
        new AcpRuntime(options(home, new JSONObject().put("subagentIdleMillis", 60_000)), agent, null, null, host).run(dir, chat("504"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("cancelled".equals(read(dir, "done")), "cancel: the waiting task ends cancelled");
        check(tookMs < 5000, "cancel: promptly, took " + tookMs + "ms");
        check(agent.methods("session/cancel").size() == 1, "cancel: session/cancel is sent to the agent");
        check(agent.processes.get(0).destroyed, "cancel: the agent process is reclaimed");
        check("interrupted".equals(AcpChatRuntimeTest.lastTurn(host.view()).getString("status")), "cancel: chat turn interrupted");
        JSONObject card = AcpChatRuntimeTest.item(AcpChatRuntimeTest.lastTurn(host.view()), "mcpToolCall", 0).getJSONObject("subagent");
        check("interrupted".equals(card.getString("status")) && card.getLong("completedAt") > 0 && card.getString("activity").isEmpty(),
                "cancel: a child that never reported ends interrupted " + card);
    }

    static void budgetEndsTheWait() throws Exception {
        File home = temp("home-budget"), dir = temp("budget");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_HANG);
        long started = System.nanoTime();
        new AcpRuntime(options(home, new JSONObject().put("turnLimitMillis", 1500).put("subagentIdleMillis", 60_000)), agent, null, null)
                .run(dir, new JSONObject().put("id", "505").put("prompt", "x").put("permissionMode", "full"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("failed".equals(read(dir, "done")) && json(dir, "state.json").getString("message").contains("timed out"),
                "budget: the fixed budget ends the wait as a timeout");
        check(tookMs < 5000, "budget: within the budget plus grace, took " + tookMs + "ms");
        check(agent.methods("session/cancel").size() == 1 && agent.processes.get(0).destroyed, "budget: cancel sent, process reclaimed");
        check(json(dir, "result.txt").getString("message").contains("Waiting for results"), "budget: the partial reply is kept");
    }

    static void unreadableStoreFallsBackToSilence() throws Exception {
        File home = temp("home-nostore"), dir = temp("nostore");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.SUBAGENTS_NO_STORE);
        agent.subagentDelayMillis = 200;
        long started = System.nanoTime();
        new AcpRuntime(options(home, null), agent, null, null)
                .run(dir, new JSONObject().put("id", "506").put("prompt", "x").put("permissionMode", "full"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("completed".equals(read(dir, "done")), "no store: completes");
        check(tookMs >= 1500 + 400, "no store: waited for the parent's silence after its last update, took " + tookMs + "ms");
        check(json(dir, "result.txt").getString("message").contains("All done. Summary"), "no store: the late summary is kept");
    }

    static void approvalFlagOnlyForFullMode() throws Exception {
        File home = temp("home-flag");
        AcpRuntime runtime = new AcpRuntime(options(home, null), new FakeAcpAgent(), null, null);
        check(runtime.launch(new JSONObject().put("permissionMode", "full")).command.contains("--dangerously-skip-permissions"),
                "flag: full mode");
        check(!runtime.launch(new JSONObject().put("permissionMode", "read_only")).command.contains("--dangerously-skip-permissions"),
                "flag: never for a restricted mode");
        check(!runtime.launch().command.contains("--dangerously-skip-permissions"), "flag: never for sign-in or catalog launches");
        AcpRuntime off = new AcpRuntime(options(home, new JSONObject().put("autoApprove", false)), new FakeAcpAgent(), null, null);
        check(!off.launch(new JSONObject().put("permissionMode", "full")).command.contains("--dangerously-skip-permissions"),
                "flag: runtime.json autoApprove=false turns it off");
    }

    static void toolLabels() {
        Locale zh = Locale.SIMPLIFIED_CHINESE, en = Locale.US;
        AcpToolLabels.Tool device = AcpToolLabels.identify("mcp_taku_phone_device_info", "Run mcp_taku_phone_device_info", "other");
        check(device.phone && "device_info".equals(device.key) && "taku_phone__device_info".equals(device.canonical())
                && "mcp_taku_phone_device_info".equals(device.name), "labels: phone tool identified from agy's name");
        check("读取设备信息".equals(AcpToolLabels.label(device, zh)) && "Read device info".equals(AcpToolLabels.label(device, en)), "labels: device_info");
        String[][] expected = {
                {"mcp_taku_phone_request_apps", "申请后台专屏"}, {"mcp_taku_phone_open_app", "打开应用"},
                {"mcp_taku_phone_screenshot", "查看屏幕"}, {"mcp_taku_phone_observe_background", "查看屏幕"},
                {"mcp_taku_phone_tap", "点击屏幕"}, {"mcp_taku_phone_release_apps", "释放后台专屏"},
                {"invoke_subagent", "派发子任务"}, {"manage_subagents", "查看子任务"}, {"run_command", "运行命令"},
                {"taku_phone__swipe", "滑动屏幕"}, {"taku_phone.type_text", "输入文字"}};
        for (String[] pair : expected)
            check(pair[1].equals(AcpToolLabels.label(AcpToolLabels.identify(pair[0], "", "other"), zh)), "labels: " + pair[0]);
        // Without agy's name, the terminal-style title still identifies the tool.
        AcpToolLabels.Tool fromTitle = AcpToolLabels.identify("", "Run mcp_taku_phone_tap", "other");
        check(fromTitle.phone && "tap".equals(fromTitle.key), "labels: identified from a 'Run …' title");
        AcpToolLabels.Tool file = AcpToolLabels.identify("", "File ../acp/home/.gemini/taku-acp/brain/x/.system_generated/steps/12/media_0.png", "read");
        check("read_file".equals(file.key) && "读取文件".equals(AcpToolLabels.label(file, zh)) && file.title.isEmpty(),
                "labels: a 'File <internal path>' title becomes 读取文件, the path is dropped");
        AcpToolLabels.Tool unknownPhone = AcpToolLabels.identify("mcp_taku_phone_future_tool", "", "other");
        check("操作手机".equals(AcpToolLabels.label(unknownPhone, zh)), "labels: an unknown phone tool gets a generic phone label");
        check("调用工具".equals(AcpToolLabels.label(AcpToolLabels.identify("some_new_tool", "Run some_new_tool", "other"), zh)),
                "labels: an unknown agy tool gets a generic label, never its raw name");
        AcpToolLabels.Tool human = AcpToolLabels.identify("", "Open Settings", "execute");
        check("Open Settings".equals(AcpToolLabels.label(human, zh)) && "Open Settings".equals(human.canonical()),
                "labels: another agent's readable title is kept");

        String text = "See [transcript.jsonl](file:///data/local/agentos/acp/home/.gemini/taku-acp/brain/abc/.system_generated/logs/transcript.jsonl)"
                + " and [`b44`](conversation://b442888e-c492) plus ../acp/home/.gemini/taku-acp/brain/s/.system_generated/steps/12/media_0.png,"
                + " dir /data/local/agentos/work/ and https://example.com/a/b.png stays.";
        String clean = AcpToolLabels.sanitize(text);
        check(clean.equals("See transcript.jsonl and `b44` plus media_0.png, dir [internal file] and https://example.com/a/b.png stays."),
                "sanitize: links keep their text, paths become file names: " + clean);
        check(AcpToolLabels.sanitize("plain 中文 text, no paths.").equals("plain 中文 text, no paths."), "sanitize: ordinary text unchanged");
        String args = AcpToolLabels.sanitize(new JSONObject().put("Path", "/data/local/agentos/acp/home/.gemini/taku-acp/mcp/taku_phone/instructions.md").toString());
        check(new JSONObject(args).getString("Path").equals("instructions.md"), "sanitize: JSON arguments stay valid JSON " + args);
    }

    static void subagentCards() throws Exception {
        check("Gemini 4 Neon".equals(AcpSubagentCards.model("gemini-4-neon-high")), "model: effort dropped, words capitalized");
        check("Claude Opus 5.5".equals(AcpSubagentCards.model("claude-opus-5-5")), "model: version digits joined");
        check("Gemini 4 Neon".equals(AcpSubagentCards.model("Gemini 4 Neon")) && "".equals(AcpSubagentCards.model("")), "model: names kept, empty stays empty");
        check("Android 17, battery 74%".equals(AcpSubagentCards.summary("### 1. Device\n\n- **Android 17**, battery `74%`\n- more")),
                "summary: the line after a heading, Markdown removed");
        check("Device".equals(AcpSubagentCards.summary("# Device\n## Details")), "summary: only headings → the first heading");
        check("Battery 74%, Android 17".equals(AcpSubagentCards.summary("Device and battery check results:\n\n- **Battery** 74%, Android 17")),
                "summary: a lead-in ending in a colon is skipped like a heading");
        check("电量 74%".equals(AcpSubagentCards.summary("查询结果如下：\n电量 74%")), "summary: a full-width colon lead-in too");
        check("Taku Agent org.agentos.agent".equals(AcpSubagentCards.summary("| --- | --- |\n| Taku Agent | `org.agentos.agent` |")),
                "summary: table rules skipped, a row reads as words");
        check("".equals(AcpSubagentCards.summary(null)) && "".equals(AcpSubagentCards.summary("  ")), "summary: empty");
        JSONObject card = AcpSubagentCards.fromArguments(null, new JSONObject().put("Role", "Taku App Scanner")
                .put("Prompt", "List apps; notes in /data/local/agentos/acp/home/.gemini/taku-acp/brain/s/notes.md token=abcd1234")
                .put("TypeName", "research").put("Model", "inherit"), 3, 1000);
        check("subagent-3".equals(card.getString("id")) && "Taku App Scanner".equals(card.getString("title")) && "research".equals(card.getString("type"))
                && "running".equals(card.getString("status")) && card.getLong("startedAt") == 1000 && card.getString("model").isEmpty()
                && "agy".equals(card.getString("provider")),
                "card: from arguments; an inherited model is left for the child's transcript " + card);
        check(card.getString("goal").contains("notes.md") && !card.getString("goal").contains("/data/local") && !card.getString("goal").contains("abcd1234"),
                "card: the goal is sanitized and redacted " + card);
        AcpSubagents.Child child = new AcpSubagents.Child();
        child.stage = "tool";
        child.lastTool = "run_command";
        child.model = "gemini-4-neon-high";
        check(AcpSubagentCards.merge(card, child, "", Locale.US) && "Run command".equals(card.getString("activity"))
                && "Gemini 4 Neon".equals(card.getString("model")), "card: running stage and model merged " + card);
        check(!AcpSubagentCards.merge(card, child, "", Locale.US), "card: no change, no rewrite");
        child.reported = true;
        child.reportedMillis = 19_000;
        child.report = "Found **14** apps.";
        check(AcpSubagentCards.merge(card, child, "", Locale.US) && "completed".equals(card.getString("status"))
                && card.getLong("elapsedMs") == 18_000 && "Found 14 apps.".equals(card.getString("summary")), "card: report completes it " + card);
        check(!AcpSubagentCards.end(card, 50_000) && card.getLong("completedAt") == 19_000, "card: the end of the turn keeps a finished card");
        JSONObject failed = AcpSubagentCards.fromArguments(null, new JSONObject().put("Role", "x"), 1, 10);
        AcpSubagentCards.toolStatus(failed, "failed", 20);
        check("failed".equals(failed.getString("status")) && failed.getLong("elapsedMs") == 10, "card: a failed dispatch fails the card");
        AcpSubagents.Child starting = new AcpSubagents.Child();
        check("启动中".equals(AcpSubagentCards.stage(starting, Locale.SIMPLIFIED_CHINESE)), "stage: starting");
        starting.stage = "reporting";
        check("回报结果".equals(AcpSubagentCards.stage(starting, Locale.SIMPLIFIED_CHINESE)), "stage: reporting");
    }

    public static void main(String[] args) throws Exception {
        subagentCards();
        toolLabels();
        approvalFlagOnlyForFullMode();
        asyncSubagentsKeepTheAgentAlive();
        inlineSubagentsDoNotWaitOrNudge();
        silentParentGetsOneFollowUpPrompt();
        cancelWhileSubagentsRun();
        budgetEndsTheWait();
        unreadableStoreFallsBackToSilence();
        System.out.println("AcpSubagentRuntimeTest passed " + checks + " checks");
    }
}
