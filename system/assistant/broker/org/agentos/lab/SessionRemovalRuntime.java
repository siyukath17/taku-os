package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** GUI, content calls and native callers share this removal store and its result semantics. */
final class SessionRemovalRuntime {
    private static volatile SessionRemovals store;

    static synchronized void initialize() throws Exception {
        if (store != null) return;
        DedicatedScreens screens = DedicatedScreensOnDevice.shared();
        SessionRemovals.Backend codex = new SessionRemovals.Backend() {
                    public Set<String> family(String root) throws Exception {
                        Set<String> result = screens.removalFamily(root);
                        result.addAll(PhoneSubAgents.gone(NativeBridge::askCleanupCodex, root));
                        result.addAll(screens.removalFamily(root));
                        return result;
                    }
                    public JSONArray resources(Set<String> sessions) throws Exception { return screens.removalResources(sessions); }
                    public SessionRemovals.State state(String root) throws Exception {
                        SessionRemovals.State result = PhoneSubAgents.removalState(NativeBridge::askCleanupCodex, root);
                        if (result == SessionRemovals.State.ARCHIVED) NativeBridge.threadAvailability(root, "archived");
                        return result;
                    }
                    public void apply(String root, String target) throws Exception {
                        NativeBridge.askCleanupCodex("thread/" + target, new JSONObject().put("threadId", root));
                        NativeBridge.threadAvailability(root, target.equals("delete") ? "deleted" : "archived");
                    }
                    public void cancel(String session) throws Exception {
                        screens.removalCancelled(session); NativeBridge.cancelRemovedSession(session); AgentViews.remove(session);
                    }
                    public void cleanup(JSONObject resource) throws Exception { screens.cleanupRemoval(resource); }
                    public void forget(Set<String> sessions) { screens.forgetRemoved(sessions); }
                };
        // Standalone ACP conversations have no Codex thread: their removal never starts Codex.
        SessionRemovals.Backend backend = new AcpSessionRemovals(AcpConversations.STORE, codex, screens::removalFamily, id -> {
            AgentViews.remove(id);
            File projection = new File(AgentViews.ROOT, id + ".json");
            if (projection.exists() && !projection.delete()) throw new IOException("ACP_PROJECTION_NOT_REMOVED");
            NativeBridge.threadAvailability(id, "deleted");
        });
        SessionRemovals value = new SessionRemovals(new File("/data/local/agentos/session-removals"), backend,
                message -> android.util.Log.w("TakuSessionRemovals", message));
        screens.removalFence(value::pending);
        store = value;
    }
    private static SessionRemovals shared() throws Exception {
        SessionRemovals value = store;
        if (value == null) { initialize(); value = store; }
        return value;
    }
    static boolean pending(String session) { SessionRemovals value = store; return value != null && value.pending(session); }
    static JSONObject submit(String root, String target) throws Exception {
        AcpSessionRemovals.checkSubmit(AcpConversations.STORE, root, target);
        return shared().submit(root, target);
    }
    static JSONObject control(JSONObject input) throws Exception {
        String action = input.optString("action", "list");
        if (action.equals("list")) return shared().list();
        if (action.equals("get")) return shared().query(input.getString("threadId"), input.optString("requestId"));
        if (action.equals("submit")) return submit(input.getString("threadId"), input.getString("target"));
        throw new IOException("UNKNOWN_SESSION_REMOVAL_ACTION");
    }
    static void filter(JSONObject result) throws Exception {
        JSONArray source = result.optJSONArray("data"); if (source == null) return;
        JSONArray visible = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject thread = source.getJSONObject(i);
            if (!pending(thread.getString("id"))) visible.put(thread);
        }
        result.put("data", visible);
    }
}
