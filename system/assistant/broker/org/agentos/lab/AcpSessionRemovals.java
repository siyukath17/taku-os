package org.agentos.lab;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Session removal of a <em>standalone</em> ACP conversation ({@code origin:"acp"}, started on the
 * broker with no Codex thread behind it). The Codex backend answers removal state and family with
 * {@code thread/list} / {@code thread/loaded/list} and applies {@code thread/delete}; asking it about
 * a standalone conversation cold-starts the Codex app server for nothing and never deletes the ACP
 * record, so the next {@code thread/list} republishes the conversation from the store.
 *
 * <p>This backend keeps the durable {@link SessionRemovals} job (receipts, fencing, task cancel,
 * dedicated-screen cleanup) and only reroutes the Codex-facing steps: state comes from the store,
 * family is local, and delete removes the store record (tombstoned) and its projection. A removed
 * standalone conversation stays on this path through retries and broker restarts via the store's
 * tombstone. Every other conversation goes to the Codex backend unchanged.
 */
final class AcpSessionRemovals implements SessionRemovals.Backend {
    interface LocalFamily { Set<String> of(String root) throws Exception; }
    interface Projection { void remove(String id) throws Exception; }

    private final AcpConversationStore store;
    private final SessionRemovals.Backend codex;
    private final LocalFamily local;
    private final Projection projection;

    AcpSessionRemovals(AcpConversationStore store, SessionRemovals.Backend codex, LocalFamily local, Projection projection) {
        this.store = store; this.codex = codex; this.local = local; this.projection = projection;
    }

    /** True when removal of {@code root} never involves Codex. */
    boolean owns(String root) { return store.standalone(root) || store.removed(root); }

    /** Rejects what a standalone conversation cannot do before a job is recorded (no archive state exists). */
    static void checkSubmit(AcpConversationStore store, String root, String target) throws IOException {
        if ("archive".equals(target) && store.standalone(root)) throw new IOException("ACP_ARCHIVE_UNSUPPORTED");
    }

    @Override public Set<String> family(String root) throws Exception {
        if (!owns(root)) return codex.family(root);
        Set<String> result = new LinkedHashSet<>(local.of(root));
        result.add(root);
        return result;
    }
    @Override public JSONArray resources(Set<String> sessions) throws Exception { return codex.resources(sessions); }
    @Override public SessionRemovals.State state(String root) throws Exception {
        if (!owns(root)) return codex.state(root);
        return store.standalone(root) ? SessionRemovals.State.ACTIVE : SessionRemovals.State.ABSENT;
    }
    @Override public void apply(String root, String target) throws Exception {
        if (!owns(root)) { codex.apply(root, target); return; }
        if (!"delete".equals(target)) throw new IOException("ACP_ARCHIVE_UNSUPPORTED");
        store.remove(root);
        projection.remove(root);
    }
    @Override public void cancel(String session) throws Exception { codex.cancel(session); }
    @Override public void cleanup(JSONObject resource) throws Exception { codex.cleanup(resource); }
    @Override public void forget(Set<String> sessions) throws Exception { codex.forget(sessions); }
}
