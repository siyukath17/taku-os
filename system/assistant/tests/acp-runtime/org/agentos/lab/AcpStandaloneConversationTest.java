package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Conversations the broker runs on ACP without the Codex app server: thread/start creates a
 * standalone conversation (immediately readable and listable, first turn on ACP), and the chat page
 * stop / liveTask cancel stop a running ACP turn through the task's cancel marker. With Codex
 * selected, thread/start and liveTask keep their Codex path.
 */
public final class AcpStandaloneConversationTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static String read(File dir, String name) throws IOException {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File dir, String name, String text) throws IOException {
        Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static File temp(String name) throws IOException {
        File dir = Files.createTempDirectory("acp-standalone-" + name).toFile();
        dir.deleteOnExit();
        return dir;
    }

    /** Stands in for AcpConversations: projection = store overlay on the seed (no native thread). */
    static final class Host implements AcpRuntime.ChatHost {
        final AcpConversationStore store;
        final List<String> changed = new CopyOnWriteArrayList<>();

        Host(File dir) {
            store = new AcpConversationStore(dir);
        }

        JSONObject view(String id) throws Exception {
            return store.project(AcpConversationStore.seed(id, store.read(id)), id);
        }

        @Override public AcpConversationStore store() { return store; }
        @Override public void changed(String conversationId) { changed.add(conversationId); }
        @Override public JSONArray history(String conversationId) throws Exception { return view(conversationId).getJSONArray("turns"); }
    }

    private static AcpRuntime.Options options() throws Exception {
        return AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy").put("cwd", "")
                .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000).put("cancelGraceMillis", 2000));
    }

    private static JSONObject acpConfig() {
        return new JSONObject().put("default", "acp").put("acp", new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy"));
    }

    private static RuntimeRouter router(JSONObject config) {
        return new RuntimeRouter(RuntimeRouterTest.CODEX, RuntimeRouterTest.ACP, () -> config, new HashMap<>());
    }

    /** A queued chat task as TaskRequests.submit leaves it, under {@code tasks/<id>}. */
    private static File queue(File tasks, String id, String conversation, String prompt) throws Exception {
        File dir = new File(tasks, id);
        if (!dir.mkdirs()) throw new IOException("mkdir " + dir);
        JSONObject request = chat(id, conversation, prompt);
        write(dir, "request.json", request.toString());
        write(dir, "state.json", new JSONObject().put("id", id).put("state", "queued").put("prompt", prompt).toString());
        write(dir, "ready", "1");
        return dir;
    }

    private static JSONObject chat(String id, String conversation, String prompt) {
        return new JSONObject().put("id", id).put("prompt", prompt).put("permissionMode", "full")
                .put("conversationId", conversation).put("screenshot", "").put("includeScreenshot", false);
    }

    private static String failure(ThrowingRunnable run) {
        try {
            run.run();
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void standaloneStartReadListAndFirstTurn() throws Exception {
        File root = temp("start");
        Host host = new Host(new File(root, "acp-conversations"));
        File tasks = new File(root, "tasks");

        // thread/start decides from the same router as chat tasks.
        RuntimeRouter.Route route = router(acpConfig()).newConversation();
        check("acp".equals(route.runtime) && "selected-by-config".equals(route.reason), "default acp: thread/start starts on ACP " + route.reason);

        String id = host.store.startNew();
        check(id.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"), "standard UUID conversation id: " + id);
        check(host.store.owns(id) && host.store.standalone(id), "the new conversation is ACP-owned and standalone");
        check(host.store.sessionId(id).isEmpty() && host.store.turns(id).length() == 0, "no session, no turns yet");
        check(!id.equals(host.store.startNew()), "each thread/start gets a new id");

        JSONObject started = AcpConversationStore.startAnswer(id, host.view(id));
        JSONObject thread = started.getJSONObject("thread");
        check(id.equals(thread.getString("id")) && thread.getJSONArray("turns").length() == 0
                && "idle".equals(thread.getJSONObject("status").getString("type")) && "acp".equals(started.getString("runtime")),
                "thread/start answer: the projected empty thread " + started);
        check(failure(() -> AcpConversationStore.startAnswer(id, null)).equals("THREAD_UNAVAILABLE"), "no projection: no answer");

        // thread/read right after thread/start: answered from the store (no app server).
        JSONObject readRequest = new JSONObject().put("method", "thread/read").put("params", new JSONObject().put("threadId", id));
        check(host.store.servesRead(readRequest), "thread/read of the new conversation is served by the store");
        JSONObject read = AcpConversationStore.readAnswer(id, host.view(id));
        check(read.getBoolean("loaded") && read.getBoolean("freshEmpty") && id.equals(read.getString("threadId")), "thread/read: loaded and fresh-empty " + read);
        JSONObject nativeRead = AcpConversationStore.nativeReadAnswer(id, host.view(id), false);
        check(id.equals(nativeRead.getJSONObject("thread").getString("id")), "native thread/read answers the standalone thread (submit precheck)");

        // thread/list: AcpConversations.publishAll indexes every stored conversation.
        check(host.store.ids().contains(id), "thread/list sees the new conversation");
        // Persisted: a restarted broker still knows it is standalone.
        AcpConversationStore reloaded = new AcpConversationStore(host.store.file(id).getParentFile());
        check(reloaded.standalone(id) && reloaded.owns(id), "standalone origin persisted");

        // First message: routed to ACP and recorded in this conversation.
        JSONObject first = chat("701", id, "hi");
        check("acp".equals(router(acpConfig()).route(first).runtime), "the first chat turn routes to ACP");
        File dir = queue(tasks, "701", id, "hi");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(), agent, null, null, host).run(dir, first);
        check("completed".equals(read(dir, "done")), "first turn completes");
        check(agent.methods("session/new").size() == 1 && agent.methods("session/load").isEmpty(), "first turn: new ACP session");
        check("hi".equals(agent.methods("session/prompt").get(0).getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text")),
                "first turn: the prompt is the message alone (no transcript of an empty conversation)");
        check("s-1".equals(host.store.sessionId(id)) && host.store.standalone(id), "session bound; still standalone");
        JSONObject after = AcpConversationStore.readAnswer(id, host.view(id));
        JSONArray turns = after.getJSONObject("thread").getJSONArray("turns");
        check(!after.getBoolean("freshEmpty") && turns.length() == 1 && "completed".equals(turns.getJSONObject(0).getString("status"))
                && "hi".equals(after.getJSONObject("thread").getString("preview")), "thread/read after the first turn: one completed turn " + after);
        JSONObject full = AcpConversationStore.nativeReadAnswer(id, host.view(id), true);
        check(full.getJSONObject("thread").getJSONArray("turns").length() == 1
                && AcpConversationStore.nativeReadAnswer(id, host.view(id), false).getJSONObject("thread").getJSONArray("turns").length() == 0,
                "native thread/read honours includeTurns");
        check(host.changed.contains(id), "the chat page projection was republished");
        check(host.store.activeTask(id, turns.getJSONObject(0).getString("id")) == null, "a finished turn has no active task");
    }

    static void codexDefaultKeepsCodex() throws Exception {
        check("codex".equals(router(null).newConversation().runtime) && "default".equals(router(null).newConversation().reason),
                "no runtime.json: thread/start stays on Codex");
        check("codex".equals(router(new JSONObject().put("default", "codex")).newConversation().runtime), "default codex: Codex");
        check("acp-unconfigured".equals(router(new JSONObject().put("default", "acp")).newConversation().reason), "acp without a binary: Codex");
        check("codex".equals(router(acpConfig().put("default", "remote_t3")).newConversation().runtime), "remote_t3 serves no chat: Codex");
        Map<String, String> env = new HashMap<>();
        env.put(RuntimeRouter.ENV, "codex");
        check("codex".equals(new RuntimeRouter(RuntimeRouterTest.CODEX, RuntimeRouterTest.ACP, () -> acpConfig(), env).newConversation().runtime),
                "env TAKU_TASK_RUNTIME=codex wins over config acp");

        // A Codex-born conversation with ACP turns is ACP-owned but not standalone: native reads,
        // readiness and liveTask of unrouted tasks keep asking Codex.
        File root = temp("codex");
        Host host = new Host(new File(root, "acp-conversations"));
        String codexThread = "01a1fe6b-eaee-7821-b2b6-8302e8130e57";
        File tasks = new File(root, "tasks");
        File dir = queue(tasks, "801", codexThread, "hi");
        new AcpRuntime(options(), new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), null, null, host).run(dir, chat("801", codexThread, "hi"));
        check(host.store.owns(codexThread) && !host.store.standalone(codexThread), "Codex-born conversation: owned, not standalone");
        File queued = queue(tasks, "802", codexThread, "next");
        check(!AcpTurnControl.acpTask(host.store, queued, codexThread, ""), "unrouted task of a Codex-born conversation: liveTask asks Codex");
        write(queued, "runtime-route.json", new JSONObject().put("runtime", "codex").toString());
        check(!AcpTurnControl.acpTask(host.store, queued, codexThread, "turn-1"), "routed to Codex: Codex liveTask path");
        write(queued, "runtime-route.json", new JSONObject().put("runtime", "acp").toString());
        check(AcpTurnControl.acpTask(host.store, queued, codexThread, ""), "routed to ACP: ACP liveTask path");
        check(failure(() -> AcpTurnControl.interrupt(host.store, tasks, codexThread, "0d2c-codex-turn")).equals("TURN_CHANGED"),
                "a Codex turn id is never cancelled through ACP");
        check(failure(() -> AcpTurnControl.interrupt(host.store, tasks, "11111111-2222-4333-8444-555555555555", "acp-x")).equals("THREAD_UNAVAILABLE"),
                "a conversation without ACP turns is not ACP's to stop");
    }

    /** Runs one hanging ACP chat turn and stops it with {@code stop} once the prompt is running. */
    private static void stopMidTurn(String label, ThrowingStop stop) throws Exception {
        File root = temp(label);
        Host host = new Host(new File(root, "acp-conversations"));
        File tasks = new File(root, "tasks");
        String id = host.store.startNew();
        File dir = queue(tasks, "901", id, "long task");
        FakeAcpAgent hang = new FakeAcpAgent(FakeAcpAgent.Scenario.HANG_UNTIL_CANCEL);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AcpRuntime runtime = new AcpRuntime(options(), hang, null, null, host);
        Thread run = new Thread(() -> {
            try { runtime.run(dir, chat("901", id, "long task")); } catch (Throwable t) { failures.add(t); }
        });
        run.start();
        check(hang.promptStarted.await(5, TimeUnit.SECONDS), label + ": prompt started");
        JSONArray turns = host.store.turns(id);
        String turnId = turns.getJSONObject(turns.length() - 1).getString("id");
        check(turnId.startsWith(AcpTurnControl.TURN_PREFIX) && "901".equals(host.store.activeTask(id, turnId)), label + ": the running turn maps to its task");
        check(AcpTurnControl.acpTask(host.store, dir, id, turnId), label + ": liveTask takes the ACP path");
        check(failure(() -> AcpTurnControl.interrupt(host.store, tasks, id, "acp-not-this-turn")).equals("TURN_CHANGED"), label + ": another turn id is refused");
        check(!new File(dir, "cancel").exists(), label + ": nothing written by a refused stop");
        stop.stop(host, tasks, id, turnId);
        run.join(5000);
        check(!run.isAlive() && failures.isEmpty(), label + ": run returns " + failures);
        check("cancelled".equals(read(dir, "done")), label + ": task cancelled");
        List<JSONObject> cancels = hang.methods("session/cancel");
        check(cancels.size() == 1 && "s-1".equals(cancels.get(0).getJSONObject("params").getString("sessionId")), label + ": one session/cancel for the session");
        check(hang.processes.get(0).destroyed, label + ": agent process reclaimed");
        JSONObject turn = host.view(id).getJSONArray("turns").getJSONObject(0);
        check("interrupted".equals(turn.getString("status")), label + ": turn interrupted " + turn);
        check(host.store.activeTask(id, turnId) == null, label + ": no active task afterwards");
        check(failure(() -> AcpTurnControl.interrupt(host.store, tasks, id, turnId)).equals("TURN_CHANGED"), label + ": a second stop is TURN_CHANGED");
    }

    interface ThrowingStop {
        void stop(Host host, File tasks, String conversation, String turnId) throws Exception;
    }

    static void chatPageStop() throws Exception {
        stopMidTurn("interrupt", (host, tasks, id, turnId) -> {
            JSONObject answer = AcpTurnControl.interrupt(host.store, tasks, id, turnId);
            check(answer.getBoolean("cancellationRequested") && "901".equals(answer.getString("taskId"))
                    && turnId.equals(answer.getString("turnId")) && "acp".equals(answer.getString("runtime")), "interrupt answer " + answer);
        });
    }

    static void liveTaskCancel() throws Exception {
        stopMidTurn("liveTask", (host, tasks, id, turnId) -> {
            check(failure(() -> AcpTurnControl.cancel(host.store, tasks, id, "902", turnId)) != null, "liveTask: a different task id is refused");
            AcpTurnControl.cancel(host.store, tasks, id, "901", turnId);
        });

        // Queued (no turn yet): the marker is written and the runtime never starts the agent.
        File root = temp("queued");
        Host host = new Host(new File(root, "acp-conversations"));
        File tasks = new File(root, "tasks");
        String id = host.store.startNew();
        File dir = queue(tasks, "903", id, "later");
        check(AcpTurnControl.acpTask(host.store, dir, id, ""), "unrouted task of a standalone conversation: ACP path");
        check(failure(() -> AcpTurnControl.cancel(host.store, tasks, "11111111-2222-4333-8444-555555555555", "903", "")).equals("TASK_THREAD_MISMATCH"),
                "queued cancel checks the conversation");
        AcpTurnControl.cancel(host.store, tasks, id, "903", "");
        check(new File(dir, "cancel").isFile(), "queued: cancel marker written");
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(), agent, null, null, host).run(dir, chat("903", id, "later"));
        check("cancelled".equals(read(dir, "done")) && agent.launches.isEmpty(), "queued: cancelled before the agent starts");
        check(failure(() -> AcpTurnControl.cancel(host.store, tasks, id, "903", "")).equals("TURN_CHANGED"), "finished task: TURN_CHANGED");
        check(failure(() -> AcpTurnControl.cancel(host.store, tasks, id, "../903", "")).equals("INVALID_TASK_ID"), "task id is validated");
    }

    /**
     * agent_control thread/name/set of a standalone conversation is answered by the ACP store (Codex
     * has no thread to rename and is not started); the title reaches thread/read and thread/list
     * through the projection and survives a broker restart. Other conversations keep the Codex path.
     */
    static void renameStandaloneWithoutCodex() throws Exception {
        File root = temp("rename");
        File dir = new File(root, "acp-conversations");
        Host host = new Host(dir);
        String id = host.store.startNew();
        JSONObject params = new JSONObject().put("threadId", id).put("name", "  Weekend trip  ");
        check(AcpCodexGate.renamesWithoutCodex("thread/name/set", params, host.store), "standalone rename: answered without Codex");
        check(!AcpCodexGate.renamesWithoutCodex("thread/read", params, host.store), "only thread/name/set is a rename");
        check(!AcpCodexGate.renamesWithoutCodex("thread/name/set", null, host.store), "missing params keep the Codex path");

        // A Codex-born conversation that ran ACP turns is not standalone: Codex owns its title.
        String codexBorn = "11111111-2222-4333-8444-555555555555";
        host.store.bindSession(codexBorn, "session-1", null);
        check(host.store.owns(codexBorn) && !AcpCodexGate.renamesWithoutCodex("thread/name/set",
                new JSONObject().put("threadId", codexBorn).put("name", "x"), host.store), "Codex-born conversation: Codex path");
        check("THREAD_UNAVAILABLE".equals(failure(() -> host.store.rename(codexBorn, "x"))), "the store never renames a Codex-born conversation");
        check(!AcpCodexGate.renamesWithoutCodex("thread/name/set",
                new JSONObject().put("threadId", "22222222-2222-4333-8444-555555555555").put("name", "x"), host.store), "unknown conversation: Codex path");

        check(host.view(id).isNull("name"), "no title before the rename");
        check("Weekend trip".equals(host.store.rename(id, params.getString("name"))), "title is trimmed");
        JSONObject view = host.view(id);
        check("Weekend trip".equals(view.optString("name")), "thread/read projection carries the new title: " + view);
        JSONObject answer = AcpConversationStore.renameAnswer(id, view);
        check(id.equals(answer.getString("threadId")) && answer.getBoolean("loaded") && "acp".equals(answer.getString("runtime"))
                && "Weekend trip".equals(answer.getJSONObject("thread").getString("name")), "Codex-shaped rename answer: " + answer);
        JSONObject native_ = AcpConversationStore.nativeReadAnswer(id, view, false);
        check("Weekend trip".equals(native_.getJSONObject("thread").getString("name")), "native thread/read (thread/name/get) sees the title");
        check("Weekend trip".equals(AcpConversationStore.seed(id, host.store.read(id)).getString("name")), "thread/list seed carries the title");

        // A stored native view keeps its turns; the title still wins over a stale name.
        JSONObject stale = new JSONObject(view.toString()).put("name", "Old");
        check("Weekend trip".equals(host.store.project(stale, id).getString("name")), "projection overlay applies the stored title");

        AcpConversationStore restarted = new AcpConversationStore(dir);
        check("Weekend trip".equals(restarted.name(id)) && restarted.standalone(id), "title persists across a broker restart");

        check("Invalid conversation title".equals(failure(() -> host.store.rename(id, "   "))), "blank title rejected");
        String longName = "x".repeat(AcpConversationStore.MAX_NAME + 1);
        check("Invalid conversation title".equals(failure(() -> host.store.rename(id, longName))), "over-long title rejected");
        check("Weekend trip".equals(host.store.name(id)), "a rejected rename keeps the title");
    }

    public static void main(String[] args) throws Exception {
        standaloneStartReadListAndFirstTurn();
        renameStandaloneWithoutCodex();
        codexDefaultKeepsCodex();
        chatPageStop();
        liveTaskCancel();
        System.out.println("AcpStandaloneConversationTest passed " + checks + " checks");
    }
}
