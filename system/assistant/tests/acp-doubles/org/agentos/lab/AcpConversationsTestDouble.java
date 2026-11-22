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

    static JSONObject read(String id) { throw outside("thread/read"); }
    static JSONObject rename(JSONObject params) { throw outside("thread/name/set"); }
    static JSONObject start() { throw outside("thread/start"); }
    static JSONObject nativeRead(JSONObject params) { throw outside("native thread/read"); }
    static JSONObject interrupt(String threadId, String turnId) { throw outside("interrupt"); }
    static void cancelTask(String threadId, String taskId, String turnId) { throw outside("task cancel"); }
    static JSONObject runtimeControl(JSONObject input) { throw outside("runtime control"); }
}
