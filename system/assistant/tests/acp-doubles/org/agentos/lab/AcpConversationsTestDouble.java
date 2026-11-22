package org.agentos.lab;

import java.io.File;
import org.json.JSONObject;

/**
 * The ACP seam of {@link CodexAppServer} and {@link NativeBridge} for host tests of their Codex path:
 * chat turns never route to ACP here, so every question answers "not ACP" and every ACP-only
 * operation is a test failure. The ACP path itself is covered by test-acp-runtime-host.sh.
 */
final class AcpConversations {
    private AcpConversations() {}

    private static AssertionError outside(String what) {
        return new AssertionError("ACP " + what + " is outside this Codex-path host test");
    }

    /** An always-empty store: SessionRemovalRuntime asks it, and it owns no conversation here. */
    static final AcpConversationStore STORE = new AcpConversationStore(empty());

    private static File empty() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("acp-double-store").toFile();
            dir.deleteOnExit();
            return dir;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static boolean servesRead(JSONObject request) { return false; }
    static boolean servesRename(JSONObject request) { return false; }
    static boolean servesNativeRead(String method, JSONObject params) { return false; }
    static boolean startsOnAcp() { return false; }
    static boolean standalone(String id) { return false; }
    static boolean any() { return false; }
    static boolean listsWithoutCodex(String method, boolean codexRunning) { return false; }
    static boolean ownsTask(File taskDir, String threadId, String turnId) { return false; }
    static boolean phoneSession(String thread) { return false; }
    static JSONObject nativeModels(String method) { return null; }
    static void publishAll() {}

    /** Conversations a test marks as removed ACP conversations (tombstoned in production). */
    static final java.util.Set<String> REMOVED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean removed(String id) { return REMOVED.contains(id); }
    static void requireThreadAvailable(JSONObject params, boolean codexRunning) throws java.io.IOException {
        if (params != null && REMOVED.contains(params.optString("threadId"))) throw new java.io.IOException("THREAD_UNAVAILABLE");
    }

    static JSONObject read(String id) { throw outside("thread/read"); }
    static JSONObject rename(JSONObject params) { throw outside("thread/name/set"); }
    static JSONObject start() { throw outside("thread/start"); }
    static JSONObject nativeRead(JSONObject params) { throw outside("native thread/read"); }
    static JSONObject interrupt(String threadId, String turnId) { throw outside("interrupt"); }
    static JSONObject steer(JSONObject input) { throw outside("steer"); }
    static String steerErrorCode(Throwable failure) { return null; }
    static void cancelTask(String threadId, String taskId, String turnId) { throw outside("task cancel"); }
    static JSONObject runtimeControl(JSONObject input) { throw outside("runtime control"); }
}
