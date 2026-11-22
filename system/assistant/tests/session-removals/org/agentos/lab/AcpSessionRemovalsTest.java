package org.agentos.lab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;

/** Removal of a standalone ACP conversation never reaches Codex; a Codex conversation still does. */
public final class AcpSessionRemovalsTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** The device Codex backend: family/state/apply ask the app server; the rest is local. */
    static final class Codex implements SessionRemovals.Backend {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final Map<String, SessionRemovals.State> states = new HashMap<>();
        public Set<String> family(String root) { asked.add("family:" + root); return new LinkedHashSet<>(Set.of(root)); }
        public JSONArray resources(Set<String> sessions) { return new JSONArray(); }
        public SessionRemovals.State state(String root) {
            asked.add("state:" + root); return states.getOrDefault(root, SessionRemovals.State.ABSENT);
        }
        public void apply(String root, String target) { asked.add("apply:" + target + ":" + root); states.put(root, SessionRemovals.State.ABSENT); }
        public void cancel(String session) {}
        public void cleanup(JSONObject resource) {}
        public void forget(Set<String> sessions) {}
    }

    static JSONObject receipt(SessionRemovals removals, String root) throws Exception {
        JSONArray rows = removals.list().getJSONArray("data");
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("threadId").equals(root)) return rows.getJSONObject(i);
        return null;
    }

    static void standaloneDeleteSkipsCodex() throws Exception {
        Path root = Files.createTempDirectory("acp-removal");
        AcpConversationStore store = new AcpConversationStore(root.resolve("acp").toFile());
        String id = store.startNew();
        String kept = store.startNew();
        Codex codex = new Codex();
        List<String> projections = new ArrayList<>();
        AcpSessionRemovals backend = new AcpSessionRemovals(store, codex, r -> new LinkedHashSet<>(), projections::add);
        try (SessionRemovals removals = new SessionRemovals(root.resolve("jobs").toFile(), backend, m -> {}, System::currentTimeMillis, 2000, 30000, false)) {
            removals.submit(id, "delete");
            SessionRemovalsRoundRobinTest.drain(removals);
            JSONObject done = receipt(removals, id);
            check(done.getBoolean("completed") && done.getBoolean("targetReached"), "standalone ACP delete completes: " + done);
        }
        check(codex.asked.isEmpty(), "Codex is never asked (no thread/list, thread/loaded/list or thread/delete): " + codex.asked);
        check(projections.equals(List.of(id)), "the projection of the removed conversation is removed");
        check(!store.owns(id) && !store.standalone(id) && store.read(id) == null, "the store record is gone");
        check(store.ids().equals(List.of(kept)), "thread/list republishing (store ids) no longer includes it");
        check(store.removed(id) && backend.owns(id), "the tombstone keeps retries on the ACP path");
        boolean refused = false;
        try { store.rename(id, "resurrected"); } catch (Exception expected) { refused = true; }
        check(refused && store.ids().equals(List.of(kept)), "a late write cannot recreate the removed conversation");
        AcpConversationStore restarted = new AcpConversationStore(root.resolve("acp").toFile());
        check(restarted.read(id) == null && restarted.ids().equals(List.of(kept)) && restarted.removed(id), "removal survives a broker restart");
        // Retrying the removal after it completed is still answered without Codex.
        check(backend.state(id) == SessionRemovals.State.ABSENT && codex.asked.isEmpty(), "a retried state check stays local");
    }

    static void codexDeleteKeepsItsPath() throws Exception {
        Path root = Files.createTempDirectory("acp-removal-codex");
        AcpConversationStore store = new AcpConversationStore(root.resolve("acp").toFile());
        String codexThread = "11111111-2222-4333-8444-555555555555";
        Codex codex = new Codex();
        codex.states.put(codexThread, SessionRemovals.State.ACTIVE);
        List<String> projections = new ArrayList<>();
        AcpSessionRemovals backend = new AcpSessionRemovals(store, codex, r -> new LinkedHashSet<>(), projections::add);
        try (SessionRemovals removals = new SessionRemovals(root.resolve("jobs").toFile(), backend, m -> {}, System::currentTimeMillis, 2000, 30000, false)) {
            removals.submit(codexThread, "delete");
            SessionRemovalsRoundRobinTest.drain(removals);
            check(receipt(removals, codexThread).getBoolean("targetReached"), "Codex delete completes");
        }
        check(codex.asked.contains("apply:delete:" + codexThread) && codex.asked.contains("family:" + codexThread)
                && codex.asked.contains("state:" + codexThread), "a Codex conversation is still removed through Codex: " + codex.asked);
        check(projections.isEmpty() && !store.removed(codexThread), "the ACP store is not touched for a Codex conversation");
    }

    static void standaloneArchiveIsRejectedUpFront() throws Exception {
        AcpConversationStore store = new AcpConversationStore(Files.createTempDirectory("acp-archive").toFile());
        String id = store.startNew();
        boolean rejected = false;
        try { AcpSessionRemovals.checkSubmit(store, id, "archive"); } catch (java.io.IOException expected) {
            rejected = "ACP_ARCHIVE_UNSUPPORTED".equals(expected.getMessage());
        }
        check(rejected, "archiving a standalone ACP conversation fails without a Codex round trip");
        AcpSessionRemovals.checkSubmit(store, id, "delete");
        AcpSessionRemovals.checkSubmit(store, "11111111-2222-4333-8444-555555555555", "archive");
        check(store.standalone(id), "the rejected archive leaves the conversation intact");
    }

    public static void main(String[] arguments) throws Exception {
        standaloneDeleteSkipsCodex();
        codexDeleteKeepsItsPath();
        standaloneArchiveIsRejectedUpFront();
        System.out.println("AcpSessionRemovalsTest: " + checks + " checks passed");
    }
}
