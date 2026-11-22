package org.agentos.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual configure/worker/receipt path with the existing ledger rig; no Android or model. */
public final class WorkspaceRevocationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static JSONObject patch(String... packages) {
        return new JSONObject().put("blockedPackages", new JSONArray(List.of(packages)));
    }
    private static DedicatedScreensTest.Rig rig() throws Exception {
        WorkspacePolicy.configure(patch());
        DedicatedScreensTest.Rig rig = new DedicatedScreensTest.Rig();
        DedicatedScreensOnDevice.registry = rig.screens;
        return rig;
    }
    private static JSONObject settled(String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JSONArray receipts = WorkspaceControl.list().getJSONArray("revocations");
            for (int i = 0; i < receipts.length(); i++) {
                JSONObject receipt = receipts.getJSONObject(i);
                if (receipt.getString("screenKey").equals(key) && !receipt.getString("status").equals("pending"))
                    return receipt;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Revocation did not settle for " + key);
    }
    private static JSONObject screen(DedicatedScreensTest.Rig rig) throws Exception {
        return rig.screens.describe(DedicatedScreensTest.session(1));
    }
    private static void partialAndFailure() throws Exception {
        var rig = rig();
        JSONObject got = rig.request(1, "Clock and Settings", "com.clock", "com.settings");
        int display = got.getInt("displayId");
        rig.tasks.open("com.clock", display);
        int settings = rig.tasks.open("com.settings", display);
        WorkspaceControl.configure(patch("com.clock"));
        JSONObject receipt = settled(got.getString("screenKey"));
        check(receipt.getString("status").equals("released") && receipt.getBoolean("screenRetained"),
                "Spec 2: configure settles without closing the screen");
        check(receipt.getJSONArray("released").toList().equals(List.of("com.clock"))
                        && receipt.getJSONArray("heldApps").toList().equals(List.of("com.settings")),
                "Spec 2: receipt distinguishes released and retained Apps");
        check(DedicatedScreensTest.tasksOn(rig, display).equals(List.of(settings))
                        && screen(rig).getString("controlOwner").equals("agent"),
                "Spec 2: the allowed page and Agent control survive");
        check(CodexAppServer.stopProofChecks == 0 && WorkspaceExecutionRuntime.subAgentLookups == 0,
                "Spec 2: automatic revocation never invokes turn-stop or child-close handling");
        check(NativeBridge.notices.get(NativeBridge.notices.size() - 1).get(5, TimeUnit.SECONDS).contains("Reassess"),
                "Spec 2: completion resolves the reevaluation notice");

        rig = rig();
        got = rig.request(1, "Both", "com.clock", "com.settings");
        rig.tasks.open("com.clock", got.getInt("displayId"));
        rig.tasks.removeFailure = "TASK_REMOVE_FAILED";
        WorkspaceControl.configure(patch("com.clock"));
        receipt = settled(got.getString("screenKey"));
        check(receipt.getString("status").equals("failed") && receipt.getBoolean("screenRetained")
                        && receipt.getJSONArray("released").isEmpty() && receipt.getJSONArray("heldApps").length() == 2,
                "Spec 2: failed cleanup retains ownership and reports no false release");
        check(screen(rig).getString("controlOwner").equals("agent") && rig.host.stopped.isEmpty(),
                "Spec 2: failure does not stop or close the allowed work");
        rig.tasks.removeFailure = "";
        WorkspaceControl.configure(patch("com.clock"));
        check(settled(got.getString("screenKey")).getString("status").equals("released"),
                "Spec 2: saving the policy again retries failed App cleanup");
    }
    private static void policyChangesDuringCleanup() throws Exception {
        var rig = rig();
        JSONObject got = rig.request(1, "Both", "com.clock", "com.settings");
        rig.tasks.open("com.clock", got.getInt("displayId"));
        rig.tasks.open("com.settings", got.getInt("displayId"));
        rig.tasks.removing = new CountDownLatch(1);
        rig.tasks.removeGate = new CountDownLatch(1);
        try {
            WorkspaceControl.configure(patch("com.clock"));
            check(rig.tasks.removing.await(5, TimeUnit.SECONDS), "Spec 2: first cleanup is in flight");
            WorkspaceControl.configure(patch("com.clock", "com.settings"));
        } finally {
            rig.tasks.removeGate.countDown();
        }
        JSONObject receipt = settled(got.getString("screenKey"));
        check(receipt.getString("status").equals("released") && receipt.getJSONArray("released").length() == 2
                        && receipt.getJSONArray("heldApps").isEmpty() && receipt.getBoolean("screenRetained"),
                "Spec 2: coalesced policy changes drain newly blocked Apps before settling");
        check(DedicatedScreensTest.tasksOn(rig, got.getInt("displayId")).isEmpty(),
                "Spec 2: final release cleans the whole screen");

        rig = rig();
        got = rig.request(1, "Both", "com.clock", "com.settings");
        rig.tasks.open("com.clock", got.getInt("displayId"));
        rig.tasks.open("com.settings", got.getInt("displayId"));
        rig.tasks.removing = new CountDownLatch(1);
        rig.tasks.removeGate = new CountDownLatch(1);
        try {
            WorkspaceControl.configure(patch("com.clock"));
            check(rig.tasks.removing.await(5, TimeUnit.SECONDS), "Spec 2: first batch is deleting Clock");
            WorkspaceControl.configure(patch("com.clock", "com.settings"));
            rig.tasks.removeFailure = "SECOND_BATCH_FAILED";
        } finally {
            rig.tasks.removeGate.countDown();
        }
        receipt = settled(got.getString("screenKey"));
        check(receipt.getString("status").equals("failed")
                        && receipt.getJSONArray("released").toList().equals(List.of("com.clock"))
                        && receipt.getJSONArray("heldApps").toList().equals(List.of("com.settings"))
                        && receipt.getJSONArray("packageNames").length() == 2,
                "Spec 2: a later failure reports both affected Apps and the actual partial release");

        rig = rig();
        got = rig.request(1, "Both", "com.clock", "com.settings");
        int task = rig.tasks.open("com.clock", got.getInt("displayId"));
        var lease = rig.screens.exact(DedicatedScreensTest.session(1), got.getString("screenKey"), got.getString("generation"));
        lease.inputLock().lock();
        try {
            WorkspaceControl.configure(patch("com.clock"));
            WorkspaceControl.configure(patch());
            check(DedicatedScreensTest.tasksOn(rig, got.getInt("displayId")).equals(List.of(task)),
                    "Spec 2: revocation waits for the input already in flight");
        } finally {
            lease.inputLock().unlock();
        }
        receipt = settled(got.getString("screenKey"));
        check(receipt.getString("status").equals("superseded") && receipt.getJSONArray("heldApps").length() == 2
                        && DedicatedScreensTest.tasksOn(rig, got.getInt("displayId")).equals(List.of(task)),
                "Spec 2: restored permission supersedes a cleanup that has not started");
    }
    private static void userControlAndGestureFailure() throws Exception {
        for (String owner : new String[]{"user", "paused"}) {
            var rig = rig();
            JSONObject got = rig.request(1, "Both", "com.clock", "com.settings");
            String thread = DedicatedScreensTest.session(1), key = got.getString("screenKey"), generation = got.getString("generation");
            rig.screens.beginPause(thread, key, generation);
            rig.screens.finishControl(thread, key, generation, owner);
            WorkspaceControl.configure(patch("com.clock"));
            JSONObject receipt = settled(key);
            check(receipt.getString("status").equals(owner.equals("user") ? "retained" : "released")
                            && receipt.getBoolean("screenRetained") && screen(rig).getString("controlOwner").equals(owner),
                    "Spec 2: preserve the takeover/pause contract: " + owner);
        }
        var rig = rig();
        JSONObject got = rig.request(1, "Both", "com.clock", "com.settings");
        PhoneGestures.idleFailure = "POINTER_NOT_IDLE";
        try {
            WorkspaceControl.configure(patch("com.clock"));
            JSONObject receipt = settled(got.getString("screenKey"));
            check(receipt.getString("status").equals("failed") && receipt.getJSONArray("heldApps").length() == 2,
                    "Spec 2: unconfirmed gesture cleanup retains App ownership");
        } finally {
            PhoneGestures.idleFailure = "";
        }
    }
    public static void main(String[] unused) throws Exception {
        Path directory = Files.createTempDirectory("workspace-revocation");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            partialAndFailure();
            policyChangesDuringCleanup();
            userControlAndGestureFailure();
            System.out.println("WorkspaceRevocationTest: " + checks + " checks passed (real worker and ledger; fake device)");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }
}

final class DedicatedScreensOnDevice {
    static DedicatedScreens registry;
    static DedicatedScreens shared() { return registry; }
}
final class NativeBridge {
    private static final java.util.concurrent.locks.ReentrantLock bridgeLock = new java.util.concurrent.locks.ReentrantLock();
    static CleanupWork.Held lock(){return CleanupWork.lock(bridgeLock);}
    static final List<CompletableFuture<String>> notices = new CopyOnWriteArrayList<>();
    static CompletableFuture<String> phonePolicyChanged(String thread) {
        CompletableFuture<String> notice = new CompletableFuture<>(); notices.add(notice); return notice;
    }
    static String publicError(Throwable error) { return error.getMessage(); }
    static JSONObject workspaceRuntime(String thread) { return new JSONObject(); }
    static void tellRunningTurn(String thread, String text) {}
    static boolean enqueue(Call call) { call.result.complete(new JSONObject()); return true; }
    static final class Call {
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        Call(String method, JSONObject params) {}
    }
}
final class WorkspaceExecutionRuntime {
    static int subAgentLookups;
    static JSONObject control(JSONObject request) { return new JSONObject(); }
    static JSONObject subAgent(String thread, boolean required) { subAgentLookups++; return null; }
}
final class CodexAppServer {
    static int stopProofChecks;
    static JSONObject workspaceProcessProof(String thread) {
        stopProofChecks++; return new JSONObject().put("verified", true).put("complete", true);
    }
}
final class PhoneGestures {
    static String idleFailure = "";
    static void cancelThread(String thread) {}
    static void prepare(PhoneExecutionScreens.Screen screen) {}
    static void requireIdle(int display) throws IOException {
        if (!idleFailure.isEmpty()) throw new IOException(idleFailure);
    }
}
