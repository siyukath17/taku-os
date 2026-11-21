package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The local record of chat conversations that ran on the ACP runtime: the binding
 * {@code conversationId ↔ ACP sessionId} and every ACP turn (the user's message, the agent's
 * messages and tool calls, and the turn status).
 *
 * <p>Turns are stored directly in the native (Codex app-server) thread/turn/item shape the chat UI
 * already renders ({@code userMessage}, {@code agentMessage}, {@code mcpToolCall}; turn status
 * {@code inProgress|completed|failed|interrupted}), so {@link #overlay} can merge them into the
 * conversation's display projection without a second presentation model. Codex stays the owner of
 * the conversation's identity and metadata; this store owns only what the ACP agent produced.
 *
 * <p>One file per conversation, {@code <dir>/<conversationId>.json}, replaced atomically. Only the
 * broker process writes it; reads are served from memory after the first load.
 */
final class AcpConversationStore {
    static final String SCHEMA = "taku.acp-conversation/1";
    static final int MAX_TURNS = 80;
    static final int MAX_TEXT = 60_000;
    private static final String ID = "[a-f0-9-]{36}";

    private final File dir;
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Set<String> absent = new HashSet<>();

    AcpConversationStore(File dir) {
        this.dir = dir;
    }

    static boolean validId(String id) {
        return id != null && id.matches(ID);
    }

    File file(String id) {
        return new File(dir, id + ".json");
    }

    /** True when this conversation has at least one ACP turn or a bound ACP session. */
    synchronized boolean owns(String id) {
        return validId(id) && record(id) != null;
    }

    synchronized String sessionId(String id) {
        JSONObject record = record(id);
        return record == null ? "" : record.optString("sessionId", "");
    }

    /** A copy of the stored turns, oldest first; empty when the conversation is not ACP-owned. */
    synchronized JSONArray turns(String id) {
        JSONObject record = record(id);
        if (record == null) return new JSONArray();
        try {
            return new JSONArray(record.getJSONArray("turns").toString());
        } catch (Exception damaged) {
            return new JSONArray();
        }
    }

    /** A copy of the record, or null. */
    synchronized JSONObject read(String id) {
        JSONObject record = record(id);
        try {
            return record == null ? null : new JSONObject(record.toString());
        } catch (Exception impossible) {
            return null;
        }
    }

    synchronized List<String> ids() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".json") && validId(name.substring(0, name.length() - 5))) out.add(name.substring(0, name.length() - 5));
        }
        java.util.Collections.sort(out);
        return out;
    }

    synchronized void bindSession(String id, String sessionId, JSONObject agentInfo) throws Exception {
        JSONObject record = mutable(id);
        if (!sessionId.equals(record.optString("sessionId"))) record.put("sessionStartedAt", System.currentTimeMillis());
        record.put("sessionId", sessionId);
        if (agentInfo != null) record.put("agentInfo", new JSONObject(agentInfo.toString()));
        save(id, record);
    }

    /** Appends an {@code inProgress} turn holding the user's message and returns its turn id. */
    synchronized String beginTurn(String id, String taskId, String prompt) throws Exception {
        JSONObject record = mutable(id);
        String turnId = "acp-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        JSONObject user = new JSONObject()
                .put("type", "userMessage")
                .put("id", turnId + "-user")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", clip(prompt))));
        JSONObject turn = new JSONObject()
                .put("id", turnId)
                .put("taskId", taskId)
                .put("runtime", "acp")
                .put("status", "inProgress")
                .put("startedAt", now)
                .put("items", new JSONArray().put(user));
        JSONArray turns = record.getJSONArray("turns");
        turns.put(turn);
        while (turns.length() > MAX_TURNS) turns.remove(0);
        if (!record.has("preview") || record.optString("preview").isEmpty()) record.put("preview", clip(prompt, 120));
        save(id, record);
        return turnId;
    }

    /**
     * Replaces everything after the user's message with {@code agentItems} and sets the turn status.
     * A terminal status records {@code completedAt}.
     */
    synchronized void updateTurn(String id, String turnId, JSONArray agentItems, String status) throws Exception {
        JSONObject record = mutable(id);
        JSONArray turns = record.getJSONArray("turns");
        for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.getJSONObject(i);
            if (!turnId.equals(turn.optString("id"))) continue;
            JSONArray items = new JSONArray();
            JSONArray old = turn.optJSONArray("items");
            if (old != null && old.length() > 0) items.put(old.getJSONObject(0));
            if (agentItems != null) for (int j = 0; j < agentItems.length(); j++) {
                JSONObject item = new JSONObject(agentItems.getJSONObject(j).toString());
                if (item.has("text")) item.put("text", clip(item.optString("text")));
                items.put(item);
            }
            turn.put("items", items).put("status", status);
            if (!"inProgress".equals(status)) turn.put("completedAt", System.currentTimeMillis());
            save(id, record);
            return;
        }
        throw new IOException("ACP turn not found: " + turnId);
    }

    // ---- projection ------------------------------------------------------------------------

    /**
     * The conversation's display thread with ACP turns merged in by turn id: an ACP turn already in
     * the thread is replaced in place (so streaming updates never reorder history), new ones are
     * appended. Native (Codex) turns and metadata are kept as they are.
     */
    static JSONObject overlay(JSONObject thread, JSONArray acpTurns) throws Exception {
        if (acpTurns == null || acpTurns.length() == 0) return thread;
        JSONObject merged = new JSONObject(thread.toString());
        JSONArray existing = merged.optJSONArray("turns");
        List<JSONObject> ordered = new ArrayList<>();
        if (existing != null) for (int i = 0; i < existing.length(); i++) ordered.add(existing.getJSONObject(i));
        long updated = 0;
        for (int i = 0; i < acpTurns.length(); i++) {
            JSONObject turn = displayTurn(acpTurns.getJSONObject(i));
            updated = Math.max(updated, Math.max(turn.optLong("startedAt"), turn.optLong("completedAt")));
            int at = -1;
            for (int j = 0; j < ordered.size() && at < 0; j++) if (turn.getString("id").equals(ordered.get(j).optString("id"))) at = j;
            if (at >= 0) ordered.set(at, turn);
            else ordered.add(turn);
        }
        JSONArray turns = new JSONArray();
        for (JSONObject turn : ordered) turns.put(turn);
        merged.put("turns", turns);
        // Native updatedAt is in seconds; never move it backwards.
        if (updated > 0) merged.put("updatedAt", Math.max(merged.optLong("updatedAt"), updated / 1000));
        String preview = merged.optString("preview", "");
        if (preview.isEmpty() || "null".equals(preview)) {
            JSONObject first = acpTurns.getJSONObject(0);
            String text = userText(first);
            if (!text.isEmpty()) merged.put("preview", clip(text, 120));
        }
        return merged;
    }

    /** True when {@code agent_control thread/read} for this request is answered from this store. */
    boolean servesRead(JSONObject request) {
        JSONObject params = request.optJSONObject("params");
        return "thread/read".equals(request.optString("method")) && params != null && owns(params.optString("threadId"));
    }

    /**
     * The agent_control thread/read answer for an ACP conversation: the Codex path's shape
     * ({@code threadId}, {@code loaded}, {@code freshEmpty}; the chat page then reads the projection)
     * plus {@code runtime} and the projected {@code thread} for agents and diagnostics.
     */
    static JSONObject readAnswer(String id, JSONObject view) throws Exception {
        if (view == null || !id.equals(view.optString("id"))) throw new IOException("THREAD_UNAVAILABLE");
        JSONArray turns = view.optJSONArray("turns");
        return new JSONObject().put("threadId", id).put("loaded", true)
                .put("freshEmpty", turns == null || turns.length() == 0)
                .put("runtime", "acp").put("thread", view);
    }

    /** Minimal native-shaped metadata for a conversation that has no native thread view yet. */
    static JSONObject seed(String id, JSONObject record) throws Exception {
        long created = record == null ? System.currentTimeMillis() : record.optLong("createdAt", System.currentTimeMillis());
        JSONObject thread = new JSONObject()
                .put("id", id)
                .put("name", JSONObject.NULL)
                .put("preview", record == null ? "" : record.optString("preview", ""))
                .put("createdAt", created / 1000)
                .put("updatedAt", (record == null ? created : record.optLong("updatedAt", created)) / 1000)
                .put("status", new JSONObject().put("type", "idle"))
                .put("turns", new JSONArray());
        return thread;
    }

    /**
     * A bounded plain-text transcript of earlier turns, newest kept, for an agent session that cannot
     * be resumed. Empty when there is nothing to carry over.
     */
    static String transcript(JSONArray turns, int maxChars) {
        if (turns == null) return "";
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            JSONArray items = turn == null ? null : turn.optJSONArray("items");
            if (items == null) continue;
            String user = "", reply = "";
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) continue;
                if ("userMessage".equals(item.optString("type"))) {
                    String text = messageText(item);
                    if (!text.isEmpty()) user = text;
                } else if ("agentMessage".equals(item.optString("type")) && !item.optString("text").isBlank()) {
                    reply = item.optString("text");
                }
            }
            if (!user.isEmpty()) lines.add("User: " + clip(user, 2000));
            if (!reply.isEmpty()) lines.add("Assistant: " + clip(reply, 2000));
        }
        StringBuilder out = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (out.length() + line.length() + 1 > maxChars) break;
            out.insert(0, line + "\n");
        }
        return out.toString().trim();
    }

    private static JSONObject displayTurn(JSONObject stored) throws Exception {
        JSONObject turn = new JSONObject(stored.toString());
        // Bookkeeping that the native turn shape does not carry stays in the store.
        turn.remove("taskId");
        if (!turn.has("items")) turn.put("items", new JSONArray());
        return turn;
    }

    private static String userText(JSONObject turn) {
        JSONArray items = turn.optJSONArray("items");
        if (items == null) return "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "userMessage".equals(item.optString("type"))) return messageText(item);
        }
        return "";
    }

    private static String messageText(JSONObject item) {
        JSONArray content = item.optJSONArray("content");
        StringBuilder text = new StringBuilder();
        if (content != null) for (int j = 0; j < content.length(); j++) {
            JSONObject part = content.optJSONObject(j);
            if (part != null && "text".equals(part.optString("type"))) {
                if (text.length() > 0) text.append('\n');
                text.append(part.optString("text"));
            }
        }
        return text.toString();
    }

    static String clip(String text) {
        return clip(text, MAX_TEXT);
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    // ---- storage -----------------------------------------------------------------------------

    private JSONObject record(String id) {
        if (!validId(id)) return null;
        JSONObject cached = cache.get(id);
        if (cached != null) return cached;
        if (absent.contains(id) && !file(id).isFile()) return null;
        File f = file(id);
        if (!f.isFile()) {
            absent.add(id);
            return null;
        }
        try {
            JSONObject loaded = new JSONObject(readFile(f));
            if (!id.equals(loaded.optString("conversationId")) || loaded.optJSONArray("turns") == null) return null;
            cache.put(id, loaded);
            absent.remove(id);
            return loaded;
        } catch (Exception damaged) {
            return null;
        }
    }

    private JSONObject mutable(String id) throws Exception {
        if (!validId(id)) throw new IOException("Invalid conversation identifier");
        JSONObject record = record(id);
        if (record == null) {
            if (file(id).isFile()) {
                // Never silently replace a record that exists but does not parse.
                File preserved = new File(dir, id + ".invalid.json");
                if (!preserved.exists() && !file(id).renameTo(preserved)) throw new IOException("cannot preserve damaged ACP conversation");
            }
            long now = System.currentTimeMillis();
            record = new JSONObject().put("schema", SCHEMA).put("conversationId", id)
                    .put("sessionId", "").put("createdAt", now).put("updatedAt", now).put("turns", new JSONArray());
        }
        return new JSONObject(record.toString());
    }

    private void save(String id, JSONObject record) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create ACP conversation store");
        dir.setReadable(false, false);
        dir.setReadable(true, true);
        dir.setExecutable(false, false);
        dir.setExecutable(true, true);
        record.put("updatedAt", System.currentTimeMillis());
        TaskRecords.write(file(id), record.toString());
        File f = file(id);
        f.setReadable(false, false);
        f.setReadable(true, true);
        cache.put(id, record);
        absent.remove(id);
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new IOException("ACP conversation record too large");
            }
            return out.toString("UTF-8");
        }
    }
}
