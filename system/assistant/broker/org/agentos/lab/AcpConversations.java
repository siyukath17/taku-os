package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Device wiring between {@link AcpConversationStore} and the conversation display projection
 * ({@link AgentViews}, read by the chat page from {@code agent-view/<threadId>.json} and the
 * session stream).
 *
 * <ul>
 *   <li>{@link #overlay} is installed as {@code AgentViews.overlay}: every projection save of an
 *       ACP-owned conversation merges its ACP turns, so a native refresh (thread/read,
 *       thread/turns/list, thread/resume) can never drop them.</li>
 *   <li>{@link #HOST} is the {@link AcpRuntime.ChatHost}: each streamed chat update republishes the
 *       projection.</li>
 *   <li>{@link #read} answers {@code agent_control thread/read} for ACP-owned conversations without
 *       asking the Codex app server.</li>
 *   <li>{@link #startsOnAcp}/{@link #start}: while chat turns route to ACP, {@code agent_control
 *       thread/start} creates the conversation here instead of in the Codex app server
 *       (a <em>standalone</em> conversation); {@link #nativeRead} answers native-RPC thread/read for
 *       it, since no Codex thread exists behind it.</li>
 *   <li>{@link #interrupt} stops a running ACP chat turn (chat page stop button) through the
 *       task's cancel marker ({@link AcpTurnControl}).</li>
 * </ul>
 */
final class AcpConversations {
    private AcpConversations() {}

    static final AcpConversationStore STORE = new AcpConversationStore(new File(TaskBroker.ROOT, "acp-conversations"));

    static JSONObject overlay(JSONObject thread) throws Exception {
        String id = thread.optString("id");
        if (!STORE.owns(id)) return thread;
        return AcpConversationStore.overlay(thread, STORE.turns(id));
    }

    /** Republish the conversation's projection (native view + ACP turns). */
    static void changed(String id) {
        try {
            AgentViews.refresh(id, AcpConversationStore.seed(id, STORE.read(id)));
        } catch (Exception failure) {
            // The store is the record; a projection failure must not fail the agent turn.
            System.err.println("acp conversation projection: " + failure);
        }
    }

    /** Earlier display turns of any runtime: the in-memory projection, else its saved file. */
    static JSONArray history(String id) throws Exception {
        JSONObject view = AgentViews.snapshot(id);
        if (view == null) {
            File saved = new File(AgentViews.ROOT, id + ".json");
            if (saved.isFile()) view = new JSONObject(TaskBroker.read(saved));
        }
        JSONArray turns = view == null ? null : view.optJSONArray("turns");
        return turns == null ? new JSONArray() : turns;
    }

    static final AcpRuntime.ChatHost HOST = new AcpRuntime.ChatHost() {
        @Override
        public AcpConversationStore store() {
            return STORE;
        }

        @Override
        public void changed(String conversationId) {
            AcpConversations.changed(conversationId);
        }

        @Override
        public JSONArray history(String conversationId) throws Exception {
            return AcpConversations.history(conversationId);
        }
    };

    static boolean servesRead(JSONObject request) {
        return STORE.servesRead(request);
    }

    /** Republishes the projection, then answers like the Codex path (see {@link AcpConversationStore#readAnswer}). */
    static JSONObject read(String id) throws Exception {
        changed(id);
        return AcpConversationStore.readAnswer(id, AgentViews.snapshot(id));
    }

    /** True when a new conversation's chat turns route to ACP (same router and config as tasks). */
    static boolean startsOnAcp() throws Exception {
        return RuntimeRouter.ACP.equals(TaskBroker.router().newConversation().runtime);
    }

    /** agent_control thread/start on ACP: a standalone conversation, projected and listable at once. */
    static JSONObject start() throws Exception {
        String id = STORE.startNew();
        changed(id);
        JSONObject view = AgentViews.snapshot(id);
        return AcpConversationStore.startAnswer(id, view != null ? view : AcpConversationStore.seed(id, STORE.read(id)));
    }

    /** Native-RPC thread/read for a standalone ACP conversation (see {@link AcpConversationStore#nativeReadAnswer}). */
    static JSONObject nativeRead(JSONObject params) throws Exception {
        String id = params.getString("threadId");
        changed(id);
        JSONObject view = AgentViews.snapshot(id);
        return AcpConversationStore.nativeReadAnswer(id, view != null ? view : AcpConversationStore.seed(id, STORE.read(id)),
                params.optBoolean("includeTurns", false));
    }

    /** True for a native-RPC thread/read that this store answers instead of the Codex app server. */
    static boolean servesNativeRead(String method, JSONObject params) {
        return "thread/read".equals(method) && params != null && STORE.standalone(params.optString("threadId"));
    }

    /** Chat page stop of a running ACP turn ({@code turnId} {@code acp-...}). */
    static JSONObject interrupt(String threadId, String turnId) throws Exception {
        return AcpTurnControl.interrupt(STORE, TaskBroker.TASKS, threadId, turnId);
    }

    /** Phone-tool sessions of ACP turns seen by this broker process (chat or background). */
    private static final java.util.Set<String> PHONE_SESSIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void phoneSessionStarted(String thread) {
        PHONE_SESSIONS.add(thread);
    }

    /** True for a phone session that belongs to the ACP runtime rather than to a Codex thread. */
    static boolean phoneSession(String thread) {
        return thread != null && (PHONE_SESSIONS.contains(thread) || STORE.owns(thread));
    }

    /** ACP conversations missing from the projection index are added before thread/list indexes it. */
    static void publishAll() {
        for (String id : STORE.ids()) if (!AgentViews.threads.containsKey(id)) changed(id);
    }
}
