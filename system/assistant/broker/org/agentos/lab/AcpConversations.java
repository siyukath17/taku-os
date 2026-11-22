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
 *   <li>{@link #servesRename}/{@link #rename}: {@code agent_control thread/name/set} of a standalone
 *       conversation stores the title here ({@link AcpConversationStore#rename}); Codex is not
 *       started or asked.</li>
 *   <li>{@link #interrupt} stops a running ACP chat turn (chat page stop button) through the
 *       task's cancel marker ({@link AcpTurnControl}); {@link #steer} adds a user message to it.</li>
 * </ul>
 *
 * <p>This class is the only ACP seam of {@link CodexAppServer} and {@link NativeBridge}: they ask it,
 * never the store, router or catalog directly, so their host tests replace ACP with one double that
 * answers "not ACP".
 */
final class AcpConversations {
    private AcpConversations() {}

    static final AcpConversationStore STORE = new AcpConversationStore(new File(TaskBroker.ROOT, "acp-conversations"));

    static JSONObject overlay(JSONObject thread) throws Exception {
        String id = thread.optString("id");
        if (!STORE.owns(id)) return thread;
        return STORE.project(thread, id);
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
        // Reopening an existing conversation: warm the agent for its next message (background only).
        TaskBroker.ACP_READ_WARMUP.onRead(id, STORE);
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
        // The user is about to type the first message: warm the agent now (background, never blocks).
        TaskBroker.prewarmAcp("thread_start");
        changed(id);
        JSONObject view = AgentViews.snapshot(id);
        return AcpConversationStore.startAnswer(id, view != null ? view : AcpConversationStore.seed(id, STORE.read(id)));
    }

    /** Native-RPC thread/read for a standalone ACP conversation (see {@link AcpConversationStore#nativeReadAnswer}). */
    static JSONObject nativeRead(JSONObject params) throws Exception {
        String id = params.getString("threadId");
        TaskBroker.ACP_READ_WARMUP.onRead(id, STORE);
        changed(id);
        JSONObject view = AgentViews.snapshot(id);
        return AcpConversationStore.nativeReadAnswer(id, view != null ? view : AcpConversationStore.seed(id, STORE.read(id)),
                params.optBoolean("includeTurns", false));
    }

    /** True for a native-RPC thread/read that this store answers instead of the Codex app server. */
    static boolean servesNativeRead(String method, JSONObject params) {
        return "thread/read".equals(method) && params != null && STORE.standalone(params.optString("threadId"));
    }

    /** True when {@code agent_control thread/name/set} is answered here ({@link AcpCodexGate#renamesWithoutCodex}). */
    static boolean servesRename(JSONObject request) {
        return AcpCodexGate.renamesWithoutCodex(request.optString("method"), request.optJSONObject("params"), STORE);
    }

    /** Stores a standalone conversation's title and republishes its projection (thread/list, thread/read). */
    static JSONObject rename(JSONObject params) throws Exception {
        String id = params.getString("threadId");
        STORE.rename(id, params.optString("name"));
        changed(id);
        JSONObject view = AgentViews.snapshot(id);
        return AcpConversationStore.renameAnswer(id, view != null ? view : AcpConversationStore.seed(id, STORE.read(id)));
    }

    /** Chat page stop of a running ACP turn ({@code turnId} {@code acp-...}). */
    static JSONObject interrupt(String threadId, String turnId) throws Exception {
        return AcpTurnControl.interrupt(STORE, TaskBroker.TASKS, threadId, turnId);
    }

    /**
     * Native {@code acpSteer}: adds a user message to the running ACP turn ({@link AcpTurnControl#steer});
     * {@code checkOnly} validates without writing.
     */
    static JSONObject steer(JSONObject input) throws Exception {
        return AcpTurnControl.steer(STORE, TaskBroker.TASKS, input.getString("threadId"), input.getString("turnId"),
                input.getString("requestId"), input.optString("text", ""), input.optJSONArray("attachments"),
                input.optBoolean("checkOnly", false), AcpTurnControl.STEER_WAIT_MILLIS);
    }

    /**
     * The native error code of a failed {@link #steer}: {@code STEER_NOT_SUBMITTED} when it provably
     * never reached the agent (the gateway may queue it as the next turn), the {@code STEER_*} code of
     * a refusal; null for any other failure.
     */
    static String steerErrorCode(Throwable failure) {
        if (failure instanceof AcpTurnControl.SteerNotSubmitted) return "STEER_NOT_SUBMITTED";
        if (failure instanceof AcpTurnControl.SteerRejected) return failure.getMessage();
        return null;
    }

    /**
     * Refuses a thread-scoped call ({@code params.threadId}) with {@code THREAD_UNAVAILABLE} before
     * it can start the Codex app server ({@link AcpCodexGate#unavailableWithoutCodex}): a removed ACP
     * conversation, or, with chat turns on ACP and Codex not running, a thread that neither the ACP
     * store nor the projection index (in memory or saved) knows. Durable removal of a Codex
     * conversation (cleanup calls) passes {@code codexRunning=true}: its projection may already be
     * gone, and only the tombstone rule applies to it.
     */
    static void requireThreadAvailable(JSONObject params, boolean codexRunning) throws Exception {
        String id = params == null ? "" : params.optString("threadId", "");
        if (id.isEmpty()) return;
        boolean known = AgentViews.threads.containsKey(id)
                || AcpConversationStore.validId(id) && new File(AgentViews.ROOT, id + ".json").isFile();
        if (AcpCodexGate.unavailableWithoutCodex(id, STORE, known, codexRunning, () -> TaskBroker.router().newConversation()))
            throw new java.io.IOException("THREAD_UNAVAILABLE");
    }

    /** True for an ACP conversation that session removal deleted (tombstoned); it never comes back. */
    static boolean removed(String id) {
        return id != null && !id.isEmpty() && STORE.removed(id);
    }

    /** True for a conversation the broker started on ACP (no Codex thread behind it). */
    static boolean standalone(String id) {
        return STORE.standalone(id);
    }

    /** True when any ACP conversation exists (thread/list stays answerable without Codex). */
    static boolean any() {
        return !STORE.ids().isEmpty();
    }

    /** True when {@code method} is answered without starting Codex ({@link AcpCodexGate#listsWithoutCodex}). */
    static boolean listsWithoutCodex(String method, boolean codexRunning) throws Exception {
        return AcpCodexGate.listsWithoutCodex(method, codexRunning ? null : TaskBroker.router().newConversation(), codexRunning);
    }

    /** True when the live task (chat page, liveTask) is an ACP turn of this conversation ({@link AcpTurnControl#acpTask}). */
    static boolean ownsTask(File taskDir, String threadId, String turnId) {
        return AcpTurnControl.acpTask(STORE, taskDir, threadId, turnId);
    }

    /** Cancels a queued or running ACP task of this conversation ({@link AcpTurnControl#cancel}). */
    static void cancelTask(String threadId, String taskId, String turnId) throws Exception {
        AcpTurnControl.cancel(STORE, TaskBroker.TASKS, threadId, taskId, turnId);
    }

    /** Native-RPC model/list on ACP without Codex, else null ({@link AcpModelCatalog#nativeAnswer}). */
    static JSONObject nativeModels(String method) throws Exception {
        return AcpModelCatalog.nativeAnswer(method, TaskBroker.router().newConversation(), TaskBroker.ACP_MODELS);
    }

    /**
     * Native {@code acpPrewarm}: a client surface came to the front ({@code surface}: onResume,
     * overlay, focus). Answers at once with {@code started}; the warm-up itself runs in the
     * background and only when the pool is empty, ACP is the new-conversation runtime and the agent is
     * signed in (see {@link AcpReadWarmup#onForeground}). Never starts Codex.
     */
    static JSONObject prewarm(JSONObject input) throws Exception {
        boolean started = TaskBroker.ACP_READ_WARMUP.onForeground(input.optString("surface"));
        return new JSONObject().put("started", started);
    }

    /** Native {@code acpRuntime} control: ACP runtime settings and sign-in ({@link TaskBroker#acpRuntimeControl}). */
    static JSONObject runtimeControl(JSONObject input) throws Exception {
        return TaskBroker.acpRuntimeControl(input);
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
