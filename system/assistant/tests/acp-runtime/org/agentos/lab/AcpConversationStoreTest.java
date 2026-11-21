package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** conversationId ↔ ACP session binding, turn records, projection overlay, transcript and thread/read answers. */
public final class AcpConversationStoreTest {
    private static int checks;
    static final String CONV = "01a1fe6b-eaee-7821-b2b6-8302e8130e57";
    static final String OTHER = "01a1fe6b-eaee-7821-b2b6-000000000000";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static File dir() throws Exception {
        File d = Files.createTempDirectory("acp-conv").toFile();
        d.deleteOnExit();
        return new File(d, "acp-conversations");
    }

    static void recordsAndPersistence() throws Exception {
        File dir = dir();
        AcpConversationStore store = new AcpConversationStore(dir);
        check(!store.owns(CONV) && store.sessionId(CONV).isEmpty() && store.turns(CONV).length() == 0, "unknown conversation is not ACP-owned");
        String turn = store.beginTurn(CONV, "100", "hi");
        check(store.owns(CONV) && turn.startsWith("acp-"), "a begun turn makes the conversation ACP-owned");
        JSONObject first = store.turns(CONV).getJSONObject(0);
        check("inProgress".equals(first.getString("status")) && first.getJSONArray("items").length() == 1, "turn starts in progress with the user message");
        JSONObject user = first.getJSONArray("items").getJSONObject(0);
        check("userMessage".equals(user.getString("type")) && "hi".equals(user.getJSONArray("content").getJSONObject(0).getString("text")), "user message in native shape");
        store.bindSession(CONV, "s-1", new JSONObject().put("name", "agy"));
        store.updateTurn(CONV, turn, new JSONArray()
                .put(new JSONObject().put("type", "mcpToolCall").put("id", turn + "-tool-1").put("tool", "Read screen").put("status", "completed"))
                .put(new JSONObject().put("type", "agentMessage").put("id", turn + "-msg-1").put("text", "Hello").put("phase", "final_answer")), "completed");
        // A fresh instance reads the same record from disk.
        AcpConversationStore reopened = new AcpConversationStore(dir);
        check("s-1".equals(reopened.sessionId(CONV)), "session binding persists");
        JSONObject stored = reopened.turns(CONV).getJSONObject(0);
        check("completed".equals(stored.getString("status")) && stored.has("completedAt") && stored.getJSONArray("items").length() == 3,
                "terminal update persists items and status");
        check("Hello".equals(stored.getJSONArray("items").getJSONObject(2).getString("text")), "agent reply persists");
        check("hi".equals(reopened.read(CONV).getString("preview")), "first prompt becomes the preview");
        check(reopened.ids().size() == 1 && reopened.ids().get(0).equals(CONV), "ids lists stored conversations");
        File f = new File(dir, CONV + ".json");
        check(f.isFile(), "record is written");
        try {
            store.beginTurn("../../etc/passwd", "1", "x");
            check(false, "invalid conversation ids are rejected");
        } catch (java.io.IOException expected) {
            check(true, "invalid ids rejected");
        }
        try {
            store.updateTurn(CONV, "acp-missing", new JSONArray(), "completed");
            check(false, "unknown turn must fail");
        } catch (java.io.IOException expected) {
            check(true, "unknown turn rejected");
        }
        // Bounded history.
        for (int i = 0; i < AcpConversationStore.MAX_TURNS + 5; i++) store.beginTurn(OTHER, "t" + i, "m" + i);
        check(store.turns(OTHER).length() == AcpConversationStore.MAX_TURNS, "turns are bounded");
        // A damaged record is preserved, never silently replaced.
        File other = new File(dir, OTHER + ".json");
        Files.write(other.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        AcpConversationStore fresh = new AcpConversationStore(dir);
        check(!fresh.owns(OTHER), "damaged record is not served");
        fresh.beginTurn(OTHER, "x", "again");
        check(new File(dir, OTHER + ".invalid.json").isFile() && fresh.owns(OTHER), "damaged record preserved before a new one starts");
    }

    static void overlayKeepsNativeHistory() throws Exception {
        AcpConversationStore store = new AcpConversationStore(dir());
        String a = store.beginTurn(CONV, "1", "first acp");
        JSONObject codexTurn = new JSONObject().put("id", "codex-turn-1").put("status", "completed").put("items", new JSONArray()
                .put(new JSONObject().put("type", "userMessage").put("id", "u0").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "codex question"))))
                .put(new JSONObject().put("type", "agentMessage").put("id", "a0").put("text", "codex answer").put("phase", "final_answer")));
        JSONObject nativeView = new JSONObject().put("id", CONV).put("name", "My chat").put("preview", "codex question")
                .put("updatedAt", 10).put("turns", new JSONArray().put(codexTurn));
        JSONObject merged = AcpConversationStore.overlay(nativeView, store.turns(CONV));
        JSONArray turns = merged.getJSONArray("turns");
        check(turns.length() == 2 && "codex-turn-1".equals(turns.getJSONObject(0).getString("id")) && a.equals(turns.getJSONObject(1).getString("id")),
                "ACP turns are appended after native turns");
        check(!turns.getJSONObject(1).has("taskId"), "store bookkeeping is not projected");
        check("My chat".equals(merged.getString("name")) && "codex question".equals(merged.getString("preview")), "native metadata kept");
        check(merged.getLong("updatedAt") >= 10, "updatedAt never moves backwards");
        // Streaming update replaces the turn in place; a later native turn stays after it.
        store.updateTurn(CONV, a, new JSONArray().put(new JSONObject().put("type", "agentMessage").put("id", a + "-msg-1").put("text", "partial")), "inProgress");
        JSONObject later = new JSONObject(merged.toString());
        later.getJSONArray("turns").put(new JSONObject().put("id", "codex-turn-2").put("items", new JSONArray()));
        JSONObject again = AcpConversationStore.overlay(later, store.turns(CONV));
        JSONArray t2 = again.getJSONArray("turns");
        check(t2.length() == 3 && a.equals(t2.getJSONObject(1).getString("id")) && "codex-turn-2".equals(t2.getJSONObject(2).getString("id")),
                "re-overlay replaces in place without reordering");
        check("partial".equals(t2.getJSONObject(1).getJSONArray("items").getJSONObject(1).getString("text")), "re-overlay carries the newest items");
        // Idempotent.
        check(AcpConversationStore.overlay(again, store.turns(CONV)).toString().equals(again.toString()), "overlay is idempotent");
        // Seed for a conversation with no native view.
        JSONObject seeded = AcpConversationStore.overlay(AcpConversationStore.seed(CONV, store.read(CONV)), store.turns(CONV));
        check(CONV.equals(seeded.getString("id")) && seeded.getJSONArray("turns").length() == 1 && "first acp".equals(seeded.getString("preview")),
                "seeded projection shows the ACP turn");
        check(!seeded.has("historyNotLoaded"), "an ACP-only conversation is complete");
        // A conversation without ACP turns is untouched.
        check(AcpConversationStore.overlay(nativeView, new JSONArray()) == nativeView, "no ACP turns: no rewrite");
    }

    static void transcriptAndReadAnswer() throws Exception {
        AcpConversationStore store = new AcpConversationStore(dir());
        String t1 = store.beginTurn(CONV, "1", "What is 2+2?");
        store.updateTurn(CONV, t1, new JSONArray().put(new JSONObject().put("type", "agentMessage").put("id", "m").put("text", "4")), "completed");
        String t2 = store.beginTurn(CONV, "2", "And 3+3?");
        store.updateTurn(CONV, t2, new JSONArray().put(new JSONObject().put("type", "agentMessage").put("id", "m2").put("text", "6")), "completed");
        String text = AcpConversationStore.transcript(store.turns(CONV), 10_000);
        check(text.equals("User: What is 2+2?\nAssistant: 4\nUser: And 3+3?\nAssistant: 6"), "transcript in order: " + text);
        String bounded = AcpConversationStore.transcript(store.turns(CONV), 30);
        check(bounded.contains("Assistant: 6") && !bounded.contains("2+2"), "bounded transcript keeps the newest lines: " + bounded);
        check(AcpConversationStore.transcript(new JSONArray(), 100).isEmpty(), "empty history: empty transcript");

        JSONObject read = new JSONObject().put("kind", "agent_control").put("method", "thread/read").put("params", new JSONObject().put("threadId", CONV));
        check(store.servesRead(read), "thread/read of an ACP conversation is served locally");
        check(!store.servesRead(new JSONObject(read.toString()).put("params", new JSONObject().put("threadId", OTHER))), "codex conversations still go to Codex");
        check(!store.servesRead(new JSONObject().put("method", "thread/list")), "thread/list is not intercepted");
        check(!store.servesRead(new JSONObject().put("method", "thread/read")), "missing params is not intercepted");
        JSONObject view = AcpConversationStore.overlay(AcpConversationStore.seed(CONV, store.read(CONV)), store.turns(CONV));
        JSONObject answer = AcpConversationStore.readAnswer(CONV, view);
        check(CONV.equals(answer.getString("threadId")) && answer.getBoolean("loaded") && !answer.getBoolean("freshEmpty")
                && "acp".equals(answer.getString("runtime")) && answer.getJSONObject("thread").getJSONArray("turns").length() == 2,
                "thread/read answer has the Codex shape plus the thread");
        try {
            AcpConversationStore.readAnswer(CONV, null);
            check(false, "no projection: unavailable");
        } catch (java.io.IOException expected) {
            check("THREAD_UNAVAILABLE".equals(expected.getMessage()), "no projection reports THREAD_UNAVAILABLE");
        }
    }

    public static void main(String[] args) throws Exception {
        recordsAndPersistence();
        overlayKeepsNativeHistory();
        transcriptAndReadAnswer();
        System.out.println("AcpConversationStoreTest passed " + checks + " checks");
    }
}
