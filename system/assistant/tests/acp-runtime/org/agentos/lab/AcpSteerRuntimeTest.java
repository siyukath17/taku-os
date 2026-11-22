package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Steering a running ACP chat turn ({@link AcpTurnControl#steer} → {@link AcpRuntime}): the request
 * file is claimed and sent as a second {@code session/prompt} of the same session, agy's answers in
 * either order end the turn only after the last one, refusals map to STEER_* codes, a stopping turn
 * refuses steers, an unclaimed request is withdrawn (SteerNotSubmitted), and the turn shows each
 * added message in order.
 */
public final class AcpSteerRuntimeTest {
    private static int checks;
    static final String CONV = AcpChatRuntimeTest.CONV;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final class Run {
        final File root, tasks, dir;
        final AcpChatRuntimeTest.Host host;
        final FakeAcpAgent agent;
        final Thread thread;
        volatile Throwable failure;

        Run(String taskId, FakeAcpAgent agent) throws Exception {
            root = Files.createTempDirectory("acp-steer").toFile();
            root.deleteOnExit();
            tasks = new File(root, "tasks");
            dir = new File(tasks, taskId);
            dir.mkdirs();
            JSONObject request = new JSONObject().put("id", taskId).put("prompt", "List fruits").put("permissionMode", "full")
                    .put("conversationId", CONV).put("screenshot", "").put("includeScreenshot", false);
            TaskRecords.write(new File(dir, "request.json"), request.toString());
            TaskRecords.write(new File(dir, "ready"), "1");
            host = new AcpChatRuntimeTest.Host(new File(root, "acp-conversations"));
            this.agent = agent;
            AcpRuntime.Options options = AcpRuntime.Options.fromJson(new JSONObject()
                    .put("binary", "/data/local/agentos/acp/bin/agy").put("cwd", "")
                    .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000).put("cancelGraceMillis", 2000));
            AcpRuntime runtime = new AcpRuntime(options, agent, null, null, host);
            thread = new Thread(() -> {
                try { runtime.run(dir, request); } catch (Throwable t) { failure = t; }
            }, "acp-steer-run");
            thread.start();
            check(agent.promptStarted.await(5, TimeUnit.SECONDS), "the prompt started");
        }

        String turnId() {
            JSONArray turns = host.store.turns(CONV);
            return turns.getJSONObject(turns.length() - 1).getString("id");
        }

        JSONObject turn() {
            JSONArray turns = host.store.turns(CONV);
            return turns.getJSONObject(turns.length() - 1);
        }

        JSONObject steer(String requestId, String text, JSONArray attachments) throws Exception {
            return AcpTurnControl.steer(host.store, tasks, CONV, turnId(), requestId, text, attachments, false, 5000);
        }

        void join() throws Exception {
            thread.join(10_000);
            check(!thread.isAlive(), "the turn ended");
            if (failure != null) throw new AssertionError(failure);
        }

        String file(String name) throws Exception {
            return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
        }
    }

    static List<JSONObject> items(JSONObject turn, String type) {
        List<JSONObject> out = new java.util.ArrayList<>();
        JSONArray items = turn.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) if (type.equals(items.getJSONObject(i).optString("type"))) out.add(items.getJSONObject(i));
        return out;
    }

    static String userText(JSONObject item) {
        return item.getJSONArray("content").getJSONObject(0).getString("text");
    }

    static void acceptedSteer(FakeAcpAgent.Scenario scenario) throws Exception {
        File attachments = Files.createTempDirectory("composer-attachments").toFile();
        attachments.deleteOnExit();
        AcpTurnControl.attachmentRoot = attachments;
        File image = new File(attachments, "photo.png");
        Files.write(image.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Run run = new Run("101", new FakeAcpAgent(scenario));
        JSONObject answer = run.steer("req-1", "Also add STEER_OK", new JSONArray()
                .put(new JSONObject().put("path", image.getPath()).put("mime", "image/png").put("name", "photo.png")));
        check("accepted".equals(answer.getString("state")), scenario + ": the steer is accepted: " + answer);
        check("101".equals(answer.getString("taskId")) && "acp".equals(answer.getString("runtime")), "answer names the task");
        run.join();
        List<JSONObject> prompts = run.agent.methods("session/prompt");
        check(prompts.size() == 2, scenario + ": the steer is a second session/prompt: " + prompts.size());
        JSONObject second = prompts.get(1).getJSONObject("params");
        check("s-1".equals(second.getString("sessionId")), "same session");
        JSONArray blocks = second.getJSONArray("prompt");
        check("Also add STEER_OK".equals(blocks.getJSONObject(0).getString("text")), "steer text sent as is");
        check("image".equals(blocks.getJSONObject(1).getString("type")) && "image/png".equals(blocks.getJSONObject(1).getString("mimeType"))
                && !blocks.getJSONObject(1).getString("data").isEmpty(), "image attachment sent inline");
        JSONObject result = new JSONObject(run.file("steer-req-1.result"));
        check("accepted".equals(result.getString("state")), "result recorded accepted");
        check(new File(run.dir, "steer-req-1.claimed").isFile() && new File(run.dir, "steer-req-1.ack").isFile()
                && !new File(run.dir, "steer-req-1.json").exists(), "request claimed and acknowledged");
        check("completed".equals(run.file("done")), scenario + ": the task completes only after the steer: " + run.file("done"));
        check(run.file("result.txt").contains("Final answer."), "the reply includes the steered answer: " + run.file("result.txt"));
        JSONObject turn = run.turn();
        check("completed".equals(turn.getString("status")), "turn completed");
        JSONArray items = turn.getJSONArray("items");
        // user (opening) → agent "Working on it" → user (steer) → agent steered answer (final)
        check("userMessage".equals(items.getJSONObject(0).getString("type")) && "List fruits".equals(userText(items.getJSONObject(0))), "opening message first");
        List<JSONObject> users = items(turn, "userMessage");
        check(users.size() == 2, "two user messages in one turn: " + users.size());
        JSONObject steer = users.get(1);
        check(steer.getString("id").equals(run.turnId() + "-steer-req-1"), "steer message id");
        check(userText(steer).equals("Also add STEER_OK"), "steer message text");
        check(steer.getJSONArray("content").getJSONObject(1).getString("text").startsWith("User-selected attachment: photo.png\nLocal path: "),
                "attachment part in the native composer shape");
        int steerAt = -1, firstAgent = -1, lastAgent = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == steer || item.getString("id").equals(steer.getString("id"))) steerAt = i;
            if ("agentMessage".equals(item.getString("type"))) { if (firstAgent < 0) firstAgent = i; lastAgent = i; }
        }
        check(firstAgent < steerAt && steerAt < lastAgent, "the steer sits between the earlier text and its answer: " + items);
        check(items.getJSONObject(lastAgent).getString("text").contains("Steered by text:Also add STEER_OK"), "answer after the steer");
        check("final_answer".equals(items.getJSONObject(lastAgent).getString("phase")), "the steered answer is the final answer");
        check(items.getJSONObject(firstAgent).getString("text").contains("Working on it")
                && !items.getJSONObject(firstAgent).getString("text").contains("Steered"), "earlier text stays in its own message");
        // The projection the chat page reads shows the same order.
        JSONObject view = run.host.view();
        JSONArray viewTurns = view.getJSONArray("turns");
        check(viewTurns.length() == 1 && items(viewTurns.getJSONObject(0), "userMessage").size() == 2, "projection shows both user messages");
        // A retry of the same request answers from the recorded outcome, even after the turn ended.
        JSONObject replay = AcpTurnControl.steer(run.host.store, run.tasks, CONV, run.turnId(), "req-1", "Also add STEER_OK", null, false, 100);
        check("accepted".equals(replay.getString("state")), "replay answers accepted");
        check(run.agent.methods("session/prompt").size() == 2, "replay sends nothing");
    }

    static void refused(String refusal, String code) throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.STEER);
        agent.steerRefusal = refusal;
        Run run = new Run("102", agent);
        try {
            run.steer("req-2", "One more thing", null);
            check(false, "a refused steer throws");
        } catch (AcpTurnControl.SteerRejected rejected) {
            check(code.equals(rejected.getMessage()), "refusal maps to " + code + ": " + rejected.getMessage());
        }
        run.join();
        check("completed".equals(run.file("done")), "the main prompt still completes");
        JSONObject result = new JSONObject(run.file("steer-req-2.result"));
        check("rejected".equals(result.getString("state")) && code.equals(result.getString("code")), "result recorded rejected");
        check(items(run.turn(), "userMessage").size() == 1, "a refused steer leaves no message in the turn");
        check(run.file("result.txt").contains("Main done."), "the reply is the main prompt's");
    }

    static void cancelledTurnRefusesSteer() throws Exception {
        Run run = new Run("103", new FakeAcpAgent(FakeAcpAgent.Scenario.STEER));
        // The stop and a steer land together: the cancel is seen first, so the steer never reaches agy.
        TaskRecords.write(new File(run.dir, "cancel"), "1");
        TaskRecords.write(AcpTurnControl.steerFile(run.dir, "req-3", ".json"),
                new JSONObject().put("requestId", "req-3").put("text", "too late").toString());
        run.join();
        check("cancelled".equals(run.file("done")), "the task is cancelled");
        JSONObject result = new JSONObject(run.file("steer-req-3.result"));
        check("rejected".equals(result.getString("state")) && AcpTurnControl.STEER_CANCELLING.equals(result.getString("code")),
                "steer after cancel refused STEER_CANCELLING: " + result);
        check(run.agent.methods("session/prompt").size() == 1, "nothing sent to the agent after cancel");
        check("interrupted".equals(run.turn().getString("status")), "turn interrupted");
    }

    static void precheckRefusesStoppingAndEndedTurns() throws Exception {
        Run run = new Run("104", new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL));
        String turnId = run.turnId();
        JSONObject checked = AcpTurnControl.steer(run.host.store, run.tasks, CONV, turnId, "req-4", "check", null, true, 0);
        check(checked.getBoolean("checked") && !AcpTurnControl.steerFile(run.dir, "req-4", ".json").exists(), "checkOnly writes nothing");
        TaskRecords.write(new File(run.dir, "cancel"), "1");
        try {
            AcpTurnControl.steer(run.host.store, run.tasks, CONV, turnId, "req-5", "late", null, false, 1000);
            check(false, "a stopping turn refuses");
        } catch (AcpTurnControl.SteerRejected rejected) {
            check(AcpTurnControl.STEER_CANCELLING.equals(rejected.getMessage()), "STEER_CANCELLING before writing");
        }
        check(!AcpTurnControl.steerFile(run.dir, "req-5", ".json").exists(), "no request written for a stopping turn");
        run.join();
        try {
            AcpTurnControl.steer(run.host.store, run.tasks, CONV, turnId, "req-6", "after", null, false, 1000);
            check(false, "an ended turn refuses");
        } catch (AcpTurnControl.SteerNotSubmitted ended) {
            check("TURN_CHANGED".equals(ended.getMessage()), "ended turn: SteerNotSubmitted (TURN_CHANGED)");
        }
        try {
            AcpTurnControl.steer(run.host.store, run.tasks, CONV, "turn-codex", "req-7", "x", null, true, 0);
            check(false, "a non-ACP turn is refused");
        } catch (AcpTurnControl.SteerNotSubmitted notAcp) {
            checks++;
        }
        try {
            AcpTurnControl.steer(run.host.store, run.tasks, CONV, turnId, "bad id!", "x", null, true, 0);
            check(false, "invalid request id");
        } catch (java.io.IOException invalid) {
            check("INVALID_ARGUMENT".equals(invalid.getMessage()), "INVALID_ARGUMENT");
        }
    }

    /** No runtime picks the request up in time: it is withdrawn by rename, so it can never be sent later. */
    static void unclaimedRequestIsWithdrawn() throws Exception {
        File root = Files.createTempDirectory("acp-steer-unclaimed").toFile();
        root.deleteOnExit();
        AcpConversationStore store = new AcpConversationStore(new File(root, "acp-conversations"));
        String turnId = store.beginTurn(CONV, "105", "hello");
        File dir = new File(new File(root, "tasks"), "105");
        dir.mkdirs();
        TaskRecords.write(new File(dir, "request.json"), new JSONObject().put("id", "105").put("conversationId", CONV).toString());
        TaskRecords.write(new File(dir, "ready"), "1");
        long started = System.currentTimeMillis();
        try {
            AcpTurnControl.steer(store, new File(root, "tasks"), CONV, turnId, "req-8", "nobody listens", null, false, 200);
            check(false, "unclaimed steer is not submitted");
        } catch (AcpTurnControl.SteerNotSubmitted notSubmitted) {
            checks++;
        }
        check(System.currentTimeMillis() - started < 2000, "withdrawal is prompt");
        check(!AcpTurnControl.steerFile(dir, "req-8", ".json").exists() && AcpTurnControl.steerFile(dir, "req-8", ".withdrawn").isFile(),
                "the request was withdrawn atomically");
        try {
            AcpTurnControl.steer(store, new File(root, "tasks"), CONV, turnId, "req-8", "nobody listens", null, false, 200);
            check(false, "a withdrawn request id stays not submitted");
        } catch (AcpTurnControl.SteerNotSubmitted again) {
            checks++;
        }
    }

    static void rejectionMapping() {
        check(AcpTurnControl.STEER_WAITING_ANSWER.equals(AcpRuntime.steerRejection(
                "Can't add this prompt to session s: the running prompt is waiting for a permission or question answer; answer it or send session/cancel, then send this prompt again")),
                "waiting answer");
        check(AcpTurnControl.STEER_CANCELLING.equals(AcpRuntime.steerRejection(
                "Can't add this prompt to session s: the running prompt is being cancelled; wait for it to finish, then send this prompt again")),
                "cancelling");
        check(AcpTurnControl.STEER_REJECTED.equals(AcpRuntime.steerRejection("something else")), "other");
    }

    static void twoSteersInOneTurn() throws Exception {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.STEER);
        agent.steersBeforeEnd = 2;
        Run run = new Run("106", agent);
        check("accepted".equals(run.steer("req-9", "first addition", null).getString("state")), "first steer accepted");
        check("accepted".equals(run.steer("req-10", "second addition", null).getString("state")), "second steer accepted");
        run.join();
        check("completed".equals(run.file("done")), "completed after both");
        List<JSONObject> users = items(run.turn(), "userMessage");
        check(users.size() == 3 && userText(users.get(1)).equals("first addition") && userText(users.get(2)).equals("second addition"),
                "both additions in order: " + users);
        JSONArray items = run.turn().getJSONArray("items");
        check("agentMessage".equals(items.getJSONObject(items.length() - 1).getString("type"))
                && items.getJSONObject(items.length() - 1).getString("text").contains("second addition"), "last answer follows the last addition");
    }

    public static void main(String[] args) throws Exception {
        rejectionMapping();
        acceptedSteer(FakeAcpAgent.Scenario.STEER);
        acceptedSteer(FakeAcpAgent.Scenario.STEER_REVERSED);
        refused("the running prompt is waiting for a permission or question answer; answer it or send session/cancel, then send this prompt again",
                AcpTurnControl.STEER_WAITING_ANSWER);
        cancelledTurnRefusesSteer();
        precheckRefusesStoppingAndEndedTurns();
        unclaimedRequestIsWithdrawn();
        twoSteersInOneTurn();
        System.out.println("AcpSteerRuntimeTest passed (" + checks + " checks)");
    }
}
