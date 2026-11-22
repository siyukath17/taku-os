package org.agentos.lab;

/**
 * Broker calls that must not cold-start the Codex app server while chat turns run on ACP.
 * Opening the agent app lists conversations ({@code agent_control thread/list}); that list is the
 * broker's own projection index (earlier Codex conversations included), so with ACP selected and
 * the app server not already running it is built without starting Codex. A running app server is
 * still asked, so its conversations stay fresh. Renaming a standalone ACP conversation
 * ({@code thread/name/set}) is answered from {@link AcpConversationStore#rename}: Codex has no
 * thread to rename. Models: {@link AcpModelCatalog#nativeAnswer}.
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
}
