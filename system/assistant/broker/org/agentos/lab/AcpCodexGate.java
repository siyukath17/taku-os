package org.agentos.lab;

/**
 * Broker calls that must not cold-start the Codex app server while chat turns run on ACP.
 * Opening the agent app lists conversations ({@code agent_control thread/list}); that list is the
 * broker's own projection index (earlier Codex conversations included), so with ACP selected and
 * the app server not already running it is built without starting Codex. A running app server is
 * still asked, so its conversations stay fresh. Renaming a standalone ACP conversation
 * ({@code thread/name/set}) is answered from {@link AcpConversationStore#rename}: Codex has no
 * thread to rename. Models: {@link AcpModelCatalog#nativeAnswer}. A thread-scoped call for a
 * conversation Codex cannot have ({@link #unavailableWithoutCodex}) is refused with
 * {@code THREAD_UNAVAILABLE} before the app server is started.
 */
final class AcpCodexGate {
    private AcpCodexGate() {}

    /** True when {@code method} is answered without starting the Codex app server. */
    static boolean listsWithoutCodex(String method, RuntimeRouter.Route route, boolean codexRunning) {
        return "thread/list".equals(method) && !codexRunning && route != null && RuntimeRouter.ACP.equals(route.runtime);
    }

    /**
     * True when {@code agent_control thread/name/set} targets a conversation the broker started on
     * ACP ({@code origin:"acp"}): the title is stored locally and Codex is neither started nor asked.
     * Every other conversation keeps the Codex path.
     */
    static boolean renamesWithoutCodex(String method, org.json.JSONObject params, AcpConversationStore store) {
        return "thread/name/set".equals(method) && params != null && store.standalone(params.optString("threadId"));
    }

    /** Lazily resolves the new-conversation route (reads runtime.json) only when it decides the answer. */
    interface RouteSource { RuntimeRouter.Route get() throws Exception; }

    /**
     * True when a call that names {@code threadId} must be refused with {@code THREAD_UNAVAILABLE}
     * instead of reaching the Codex app server (which would cold-start it for a thread it never had):
     * <ul>
     *   <li>a conversation {@link AcpConversationStore#remove removed} from the ACP store (tombstone):
     *       always, whether or not Codex is running; it was never a Codex thread and never comes back;</li>
     *   <li>with chat turns on ACP and Codex not running, a conversation that is neither in the ACP
     *       store nor in the broker's projection index of Codex conversations ({@code knownCodex}).</li>
     * </ul>
     * ACP-owned conversations, known Codex conversations, a running app server (asking it costs no
     * start and gives the authoritative answer) and a Codex default keep their path.
     */
    static boolean unavailableWithoutCodex(String threadId, AcpConversationStore store, boolean knownCodex,
                                           boolean codexRunning, RouteSource route) throws Exception {
        if (threadId == null || threadId.isEmpty()) return false;
        if (store.removed(threadId)) return true;
        if (store.owns(threadId) || knownCodex || codexRunning) return false;
        RuntimeRouter.Route selected = route == null ? null : route.get();
        return selected != null && RuntimeRouter.ACP.equals(selected.runtime);
    }
}
