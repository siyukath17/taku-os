package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Chat turns (conversationId) on the ACP runtime: the turn record streams into the store and the
 * projection hook, the agent session is resumed with session/load across turns, and a session that
 * cannot be loaded falls back to session/new with the earlier conversation carried as a transcript.
 */
public final class AcpChatRuntimeTest {
    private static int checks;
    static final String CONV = "01a1fe6b-eaee-7821-b2b6-8302e8130e57";

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
        File dir = Files.createTempDirectory("acp-chat-" + id).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static JSONObject chat(String id, String prompt) {
        return new JSONObject().put("id", id).put("prompt", prompt).put("permissionMode", "full")
                .put("conversationId", CONV).put("screenshot", "").put("includeScreenshot", false);
    }

    private static AcpRuntime.Options options() throws Exception {
        return AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy").put("cwd", "")
                .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000).put("cancelGraceMillis", 2000));
    }

    /** Stands in for AcpConversations: the projection is the store overlay on a native view. */
    static final class Host implements AcpRuntime.ChatHost {
        final AcpConversationStore store;
        final List<JSONObject> published = new CopyOnWriteArrayList<>();
        JSONObject nativeView;

        Host(File dir) {
            store = new AcpConversationStore(dir);
        }

        JSONObject view() throws Exception {
            JSONObject base = nativeView != null ? nativeView : AcpConversationStore.seed(CONV, store.read(CONV));
            return AcpConversationStore.overlay(base, store.turns(CONV));
        }

        @Override public AcpConversationStore store() { return store; }

        @Override
        public void changed(String conversationId) {
            try {
                published.add(view());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public JSONArray history(String conversationId) throws Exception {
            return view().getJSONArray("turns");
        }
    }

    static JSONObject lastTurn(JSONObject view) throws Exception {
        JSONArray turns = view.getJSONArray("turns");
        return turns.getJSONObject(turns.length() - 1);
    }

    static JSONObject item(JSONObject turn, String type, int nth) throws Exception {
        JSONArray items = turn.getJSONArray("items");
        int seen = 0;
        for (int i = 0; i < items.length(); i++)
            if (type.equals(items.getJSONObject(i).getString("type")) && seen++ == nth) return items.getJSONObject(i);
        return null;
    }

    static String promptText(FakeAcpAgent agent, int nth) throws Exception {
        return agent.methods("session/prompt").get(nth).getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text");
    }

    static void multiTurnConversation() throws Exception {
        File storeDir = new File(taskDir("store"), "acp-conversations");
        Host host = new Host(storeDir);

        // Turn 1: a new conversation, new agent session.
        File dir1 = taskDir("t1");
        FakeAcpAgent agent1 = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        agent1.loadSession = true;
        new AcpRuntime(options(), agent1, null, null, host).run(dir1, chat("201", "hi"));
        check("completed".equals(read(dir1, "done")), "turn 1 completes");
        check(agent1.methods("session/new").size() == 1 && agent1.methods("session/load").isEmpty(), "turn 1: new session, nothing to load");
        check("hi".equals(promptText(agent1, 0)), "turn 1: the prompt is the user's message alone");
        check("s-1".equals(host.store.sessionId(CONV)), "turn 1: session bound to the conversation");
        // The user's message was visible before the agent produced anything.
        JSONObject firstPublished = lastTurn(host.published.get(0));
        check("inProgress".equals(firstPublished.getString("status")) && firstPublished.getJSONArray("items").length() == 1
                && "hi".equals(item(firstPublished, "userMessage", 0).getJSONArray("content").getJSONObject(0).getString("text")),
                "turn 1: the user message is published first, turn in progress");
        boolean streamed = false;
        for (JSONObject view : host.published) {
            JSONObject turn = lastTurn(view);
            JSONObject message = item(turn, "agentMessage", 0);
            if ("inProgress".equals(turn.getString("status")) && message != null && message.getString("text").startsWith("Done")) streamed = true;
        }
        check(streamed && host.published.size() >= 4, "turn 1: reply text streams into the projection while in progress");
        JSONObject turn1 = lastTurn(host.view());
        check("completed".equals(turn1.getString("status")), "turn 1: final turn status completed");
        JSONObject tool = item(turn1, "mcpToolCall", 0);
        check(tool != null && "Open Settings".equals(tool.getString("tool")) && "completed".equals(tool.getString("status")),
                "turn 1: tool call projected with its final status " + tool);
        JSONObject reply = item(turn1, "agentMessage", 0);
        check(reply != null && "Done: Wi-Fi is on.\nDetails follow.".equals(reply.getString("text")) && "final_answer".equals(reply.getString("phase")),
                "turn 1: the reply is the final answer " + reply);
        JSONArray items = turn1.getJSONArray("items");
        check("userMessage".equals(items.getJSONObject(0).getString("type")) && "mcpToolCall".equals(items.getJSONObject(1).getString("type"))
                && "agentMessage".equals(items.getJSONObject(2).getString("type")), "turn 1: items in event order");
        check("Done: Wi-Fi is on.\nDetails follow.".equals(read(dir1, "result.txt")), "turn 1: chat result.txt is the plain reply");
        JSONArray env = agent1.methods("session/new").get(0).getJSONObject("params").getJSONArray("mcpServers").getJSONObject(0).getJSONArray("env");
        String phoneThread = "", phoneTurn = "";
        for (int i = 0; i < env.length(); i++) {
            if ("TAKU_MCP_THREAD".equals(env.getJSONObject(i).getString("name"))) phoneThread = env.getJSONObject(i).getString("value");
            if ("TAKU_MCP_TURN".equals(env.getJSONObject(i).getString("name"))) phoneTurn = env.getJSONObject(i).getString("value");
        }
        check(CONV.equals(phoneThread) && turn1.getString("id").equals(phoneTurn), "turn 1: phone tools run as the conversation's thread and this turn");
        JSONObject info = json(dir1, "runtime-info.json");
        check(CONV.equals(info.getString("threadId")) && turn1.getString("id").equals(info.getString("turnId"))
                && info.getBoolean("turnStarted") && !info.getBoolean("sessionResumed"), "turn 1: runtime-info names the thread and turn " + info);

        // Turn 2: the agent can load the session; replayed history must not leak into the reply.
        File dir2 = taskDir("t2");
        FakeAcpAgent agent2 = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        agent2.loadSession = true;
        new AcpRuntime(options(), agent2, null, null, host).run(dir2, chat("202", "and now?"));
        check("completed".equals(read(dir2, "done")), "turn 2 completes");
        List<JSONObject> loads = agent2.methods("session/load");
        check(loads.size() == 1 && "s-1".equals(loads.get(0).getJSONObject("params").getString("sessionId"))
                && loads.get(0).getJSONObject("params").getJSONArray("mcpServers").length() == 1, "turn 2: session/load with the bound session and phone tools");
        check(agent2.methods("session/new").isEmpty(), "turn 2: no new session");
        check("and now?".equals(promptText(agent2, 0)), "turn 2: no transcript when the session resumed");
        check(!read(dir2, "result.txt").contains("REPLAYED"), "turn 2: replayed history is not part of the reply");
        check(json(dir2, "runtime-info.json").getBoolean("sessionResumed"), "turn 2: runtime-info records the resume");
        JSONObject view2 = host.view();
        check(view2.getJSONArray("turns").length() == 2, "turn 2: the conversation has two turns");
        check(!view2.toString().contains("REPLAYED"), "turn 2: replayed history not recorded twice");

        // Turn 3: the session cannot be loaded; fall back to session/new with a transcript.
        File dir3 = taskDir("t3");
        FakeAcpAgent agent3 = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        agent3.loadSession = true;
        agent3.loadFails = true;
        agent3.newSessionIds.add("s-2");
        new AcpRuntime(options(), agent3, null, null, host).run(dir3, chat("203", "third"));
        check("completed".equals(read(dir3, "done")), "turn 3 completes after the fallback");
        check(agent3.methods("session/load").size() == 1 && agent3.methods("session/new").size() == 1, "turn 3: load tried, then new");
        String text = promptText(agent3, 0);
        check(text.contains("User: hi\nAssistant: Done: Wi-Fi is on.") && text.contains("User: and now?") && text.endsWith("Current message:\nthird")
                && !text.contains("User: third"), "turn 3: earlier turns carried as a transcript: " + text);
        check("s-2".equals(host.store.sessionId(CONV)), "turn 3: the conversation is rebound to the new session");
        JSONObject info3 = json(dir3, "runtime-info.json");
        check(!info3.getBoolean("sessionResumed") && info3.getString("sessionLoadFailure").startsWith("-32002"), "turn 3: load failure recorded " + info3);
        check(host.view().getJSONArray("turns").length() == 3, "turn 3: three turns");

        // Turn 4: an agent without loadSession also starts fresh with the transcript.
        File dir4 = taskDir("t4");
        FakeAcpAgent agent4 = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(), agent4, null, null, host).run(dir4, chat("204", "fourth"));
        check(agent4.methods("session/load").isEmpty() && promptText(agent4, 0).contains("User: third"), "turn 4: no loadSession: transcript fallback");
        check("loadSession unsupported".equals(json(dir4, "runtime-info.json").getString("sessionLoadFailure")), "turn 4: reason recorded");
    }

    static void nativeHistoryCarriedIntoFirstAcpTurn() throws Exception {
        Host host = new Host(new File(taskDir("native"), "acp-conversations"));
        host.nativeView = new JSONObject().put("id", CONV).put("preview", "old").put("turns", new JSONArray()
                .put(new JSONObject().put("id", "codex-1").put("status", "completed").put("items", new JSONArray()
                        .put(new JSONObject().put("type", "userMessage").put("id", "u").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "codex era question"))))
                        .put(new JSONObject().put("type", "agentMessage").put("id", "a").put("text", "codex era answer")))));
        File dir = taskDir("native-t1");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(), agent, null, null, host).run(dir, chat("301", "continue"));
        String text = promptText(agent, 0);
        check(text.contains("User: codex era question\nAssistant: codex era answer") && text.endsWith("Current message:\ncontinue"),
                "a Codex conversation moving to ACP keeps its context: " + text);
        JSONArray turns = host.view().getJSONArray("turns");
        check(turns.length() == 2 && "codex-1".equals(turns.getJSONObject(0).getString("id")), "native turn kept before the ACP turn");
    }

    static void failuresCloseTheTurn() throws Exception {
        // Sign-in required.
        Host host = new Host(new File(taskDir("fail"), "acp-conversations"));
        File dir = taskDir("auth");
        new AcpRuntime(options(), new FakeAcpAgent(FakeAcpAgent.Scenario.NEEDS_AUTH), null, null, host).run(dir, chat("401", "hi"));
        check("needs_user".equals(read(dir, "done")), "auth: task needs the user");
        check("failed".equals(lastTurn(host.view()).getString("status")), "auth: turn closed as failed");
        check(!new File(dir, "result.txt").exists(), "auth: no chat reply written");
        check(host.store.sessionId(CONV).isEmpty(), "auth: no session bound");

        // Cancel mid-turn.
        File cancelDir = taskDir("cancel");
        FakeAcpAgent hang = new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AcpRuntime runtime = new AcpRuntime(options(), hang, null, null, host);
        Thread run = new Thread(() -> {
            try { runtime.run(cancelDir, chat("402", "long task")); } catch (Throwable t) { failures.add(t); }
        });
        run.start();
        check(hang.promptStarted.await(5, TimeUnit.SECONDS), "cancel: prompt started");
        Files.write(new File(cancelDir, "cancel").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        run.join(5000);
        check(!run.isAlive() && failures.isEmpty(), "cancel: run returns " + failures);
        JSONObject turn = lastTurn(host.view());
        check("cancelled".equals(read(cancelDir, "done")) && "interrupted".equals(turn.getString("status")), "cancel: turn interrupted");
        check("Working on it".equals(item(turn, "agentMessage", 0).getString("text")), "cancel: partial reply kept");

        // Agent cannot start.
        File startDir = taskDir("start");
        FakeAcpAgent broken = new FakeAcpAgent();
        broken.startFailure = new IOException("no such file");
        new AcpRuntime(options(), broken, null, null, host).run(startDir, chat("403", "hi"));
        check("failed".equals(read(startDir, "done")) && "failed".equals(lastTurn(host.view()).getString("status")), "start failure: turn closed");

        // Background tasks without conversationId are unchanged by a chat host.
        File bg = taskDir("bg");
        new AcpRuntime(options(), new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), null, null, host)
                .run(bg, new JSONObject().put("id", "404").put("prompt", "Turn on Wi-Fi").put("permissionMode", "full"));
        check(json(bg, "result.txt").getString("status").equals("completed"), "background task keeps the JSON result contract");
        check(host.view().getJSONArray("turns").length() == 3, "background task adds no conversation turn");
    }

    public static void main(String[] args) throws Exception {
        multiTurnConversation();
        nativeHistoryCarriedIntoFirstAcpTurn();
        failuresCloseTheTurn();
        System.out.println("AcpChatRuntimeTest passed " + checks + " checks");
    }
}
