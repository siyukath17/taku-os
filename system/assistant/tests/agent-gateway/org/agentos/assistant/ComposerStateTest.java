package org.agentos.assistant;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ComposerStateTest {
    private static final String THREAD = "00000000-0000-4000-8000-000000000001";
    private static final String TARGET = "00000000-0000-4000-8000-000000000002";
    private static final ComposerState STORE = ComposerState.INSTANCE;

    public static void main(String[] args) throws Exception {
        protocol();
        sharedRevision();
        importRecovery();
        moveAndDiskFailure();
        settlementPreservesLaterEdits();
        screenshotPreferenceIsFrozen();
        powerKeyScreenshotIsOneShot();
        handoffPreservesDisplacedDraft();
        legacyHomeAndPendingDelivery();
        System.out.println("PASS ComposerStateTest");
    }

    private static void protocol() throws Exception {
        check(AgentGatewayProtocol.request("composer/write", "{\"threadId\":\"\",\"revision\":\"\",\"text\":\"\"}").length() == 3,
                "the new slot accepts explicit empty thread, revision and text");
        check(AgentGatewayProtocol.request("composer/import", "{\"threadId\":\"\",\"text\":\" \"}").getString("text").equals(" "),
                "import preserves whitespace");
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":1}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":\"../\"}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":\"\",\"extra\":0}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/write", "{\"threadId\":\"\",\"revision\":null,\"text\":\"\"}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/move", new JSONObject()
                .put("threadId", THREAD).put("revision", "").put("targetThreadId", TARGET).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/move", new JSONObject()
                .put("threadId", "").put("revision", "").put("targetThreadId", "").toString()));
    }

    private static void sharedRevision() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "old Android text").apply();
        JSONObject initial = ComposerState.read(prefs, THREAD);
        check(initial.getString("text").equals("old Android text") && initial.getString("revision").isEmpty(),
                "pre-revision Android text is readable in place");
        JSONObject changed = ComposerState.write(prefs, THREAD, "", "  open access\n");
        String revision = changed.getString("revision");
        check(!revision.isEmpty() && ComposerState.read(prefs, THREAD).getString("text").equals("  open access\n"),
                "a new read returns the exact shared text");
        check(ComposerState.write(prefs, THREAD, revision, "  open access\n").getString("revision").equals(revision),
                "an identical write retains the revision");
        String nativeRevision = ComposerState.write(prefs, THREAD, revision, "native edit").getString("revision");
        conflict(nativeRevision, () -> ComposerState.write(prefs, THREAD, revision, "stale update"));
        check(ComposerState.read(prefs, THREAD).getString("text").equals("native edit"), "stale write leaves native edit intact");
        check(ComposerState.read(prefs, TARGET).getString("revision").isEmpty(), "other thread remains untouched");
    }

    private static void importRecovery() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "native draft")
                .putString("composer." + THREAD + ".attachments", "[{\"path\":\"/fixture/attachment\",\"name\":\"attachment\"}]").apply();
        JSONObject snapshot = STORE.importLegacy(prefs, THREAD, "legacy Expo draft");
        check(snapshot.getString("text").equals("native draft") && !snapshot.toString().contains("/fixture/attachment"),
                "import leaves active text intact and omits private paths");
        check(snapshot.getJSONArray("attachments").getJSONObject(0).getString("name").equals("attachment"),
                "native attachment remains in the editor");
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject imported = STORE.importLegacy(restarted, THREAD, "legacy Expo draft");
        JSONObject entry = imported.getJSONArray("recovery").getJSONObject(0);
        check(entry.getString("status").equals("saved-draft"), "different legacy content is saved for recovery");
        String attachmentId = imported.getJSONArray("attachments").getJSONObject(0).getString("id");
        ComposerState.removeAttachment(restarted, THREAD, imported.getString("revision"), attachmentId);
        String empty = ComposerState.write(restarted, THREAD, ComposerState.read(restarted, THREAD).getString("revision"), "").getString("revision");
        JSONObject restored = ComposerState.restore(restarted, THREAD, empty, entry.getString("id"));
        check(restored.getString("text").equals("legacy Expo draft"), "recovered text is shared");
        check(restored.getJSONArray("recovery").length() == 0, "repeated import creates no duplicate recovery entry");
        JSONObject second = STORE.importLegacy(restarted, THREAD, "later legacy edit");
        JSONObject saved = second.getJSONArray("recovery").getJSONObject(0);
        check(second.getString("text").equals("legacy Expo draft") && saved.getString("text").equals("later legacy edit"),
                "a different legacy edit remains recoverable after an earlier import");
        STORE.importLegacy(restarted, THREAD, "legacy Expo draft");
        JSONArray replayed = STORE.importLegacy(new MemoryPreferences(restarted), THREAD, "later legacy edit").getJSONArray("recovery");
        check(replayed.length() == 1 && replayed.getJSONObject(0).getString("id").equals(saved.getString("id")),
                "replaying either import preserves a single recovery entry");
        STORE.importLegacy(restarted, TARGET, "");
        check(STORE.importLegacy(restarted, TARGET, "first nonempty").getString("text").equals("first nonempty"),
                "an empty import leaves the thread eligible for its legacy draft");
    }

    private static void moveAndDiskFailure() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String sourceRevision = ComposerState.write(prefs, "", "", "new-slot draft").getString("revision");
        STORE.importLegacy(prefs, "", "saved new-slot draft");
        ComposerState.write(prefs, THREAD, "", "occupied");
        conflict(sourceRevision, () -> STORE.move(prefs, "", TARGET));
        rejects("DRAFT_TARGET_OCCUPIED", () -> STORE.move(prefs, sourceRevision, THREAD));
        prefs.failNextCommit = true;
        rejects("DRAFT_SAVE_FAILED", () -> STORE.move(prefs, sourceRevision, TARGET));
        check(ComposerState.read(prefs, "").getString("revision").equals(sourceRevision), "failed move retains its source revision");
        check(ComposerState.read(prefs, TARGET).getString("revision").isEmpty(), "failed move leaves the target untouched");
        JSONObject moved = STORE.move(prefs, sourceRevision, TARGET);
        check(moved.getString("threadId").equals(TARGET) && moved.getString("text").equals("new-slot draft"), "move returns target snapshot");
        JSONObject source = ComposerState.read(prefs, "");
        check(source.getString("text").isEmpty() && !source.getString("revision").equals(sourceRevision), "move clears and revises the new slot");
        check(moved.getJSONArray("recovery").length() == 1 && source.getJSONArray("recovery").length() == 0, "move transfers saved drafts");
    }

    private static void settlementPreservesLaterEdits() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String file = "00000000-0000-4000-8000-000000000003";
        JSONObject attached = ComposerState.addAttachment(prefs, THREAD, "", new JSONObject()
                .put("path", "/fixture/" + file).put("name", "notes.txt").put("mime", "text/plain").put("size", 9));
        String revision = attached.getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000004")
                .put("attachmentIds", new JSONArray().put(file));
        JSONObject frozen = STORE.reserveSubmission(prefs, input, (thread, target) -> kotlin.Unit.INSTANCE).getFirst();
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        ComposerState.write(restarted, THREAD, revision, "newer draft");
        check(!STORE.reserveSubmission(restarted, input, (thread, target) -> { throw new AssertionError("replay resolved live options"); }).getSecond(),
                "replay uses the frozen intent even after editing");
        JSONObject changed = new JSONObject(input.toString()).put("attachmentIds", new JSONArray());
        rejects("REQUEST_ID_REUSED", () -> STORE.reserveSubmission(restarted, changed, (thread, target) -> kotlin.Unit.INSTANCE));
        STORE.finishSubmission(restarted, frozen, true);
        JSONObject current = ComposerState.read(restarted, THREAD);
        check(current.getString("text").equals("newer draft") && current.getJSONArray("attachments").getJSONObject(0).getString("id").equals(file),
                "settlement clears neither newer text nor its attachments");
        check(current.getJSONArray("pendingReceipts").length() == 0, "settlement closes the old pending request");
    }

    private static void screenshotPreferenceIsFrozen() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putBoolean("includeScreenshot", true).commit();
        String revision = ComposerState.write(prefs, THREAD, "", "Use the previous screen").getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000006")
                .put("attachmentIds", new JSONArray());
        JSONObject first = STORE.reserveSubmission(prefs, input, (thread, options) -> {
            check(options.getBoolean("includeScreenshot"), "the legacy screenshot opt-in reaches the capture reservation");
            options.put("invocationScreenshot", input.getString("requestId"));
            return kotlin.Unit.INSTANCE;
        }).getFirst();
        prefs.edit().putBoolean("includeScreenshot", false).commit();
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject replay = STORE.reserveSubmission(restarted, input, (thread, options) -> {
            throw new AssertionError("replay recaptured the screen");
        }).getFirst();
        check(replay.getJSONObject("options").getBoolean("includeScreenshot") &&
                replay.getJSONObject("options").getString("invocationScreenshot").equals(input.getString("requestId")),
                "the pending request retains its opt-in and capture after preference changes and restart");
        STORE.finishSubmission(restarted, first, false);
        check(ComposerState.read(restarted, THREAD).getString("text").equals("Use the previous screen"),
                "a rejected screenshot send preserves the draft");
        JSONObject next = STORE.reserveSubmission(restarted, new JSONObject(input.toString())
                .put("requestId", "00000000-0000-4000-8000-000000000007"), (thread, options) -> kotlin.Unit.INSTANCE).getFirst();
        check(!next.getJSONObject("options").getBoolean("includeScreenshot"), "the next send uses the updated screenshot preference");
    }

    private static void powerKeyScreenshotIsOneShot() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putBoolean("includeScreenshot", false).commit();
        boolean[] grant = {true};
        int[] consulted = {0};
        kotlin.jvm.functions.Function0<Boolean> oneShot = () -> {
            consulted[0]++;
            boolean value = grant[0];
            grant[0] = false;
            return value;
        };
        String revision = ComposerState.write(prefs, THREAD, "", "What is on screen?").getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000016")
                .put("attachmentIds", new JSONArray());
        JSONObject first = STORE.reserveSubmission(prefs, input, (thread, options) -> {
            check(options.getBoolean("includeScreenshot"), "the power-key grant reaches the capture reservation");
            options.put("invocationScreenshot", input.getString("requestId"));
            return kotlin.Unit.INSTANCE;
        }, oneShot).getFirst();
        check(first.getJSONObject("options").getBoolean("includeScreenshot"), "the first message after a power-key invocation carries the screenshot");
        check(!prefs.getBoolean("includeScreenshot", true), "the one-shot grant never writes the global screenshot preference");
        check(!grant[0] && consulted[0] == 1, "the grant is consumed by the first message");
        check(!STORE.reserveSubmission(prefs, input, (thread, options) -> { throw new AssertionError("replay resolved live options"); },
                () -> { throw new AssertionError("replay consumed a grant"); }).getSecond(), "a replay neither re-resolves nor re-consumes");
        STORE.finishSubmission(prefs, first, true);
        String next = ComposerState.write(prefs, THREAD, ComposerState.read(prefs, THREAD).getString("revision"), "And now?").getString("revision");
        JSONObject second = STORE.reserveSubmission(prefs, new JSONObject(input.toString()).put("revision", next)
                .put("requestId", "00000000-0000-4000-8000-000000000017"), (thread, options) -> kotlin.Unit.INSTANCE, oneShot).getFirst();
        check(!second.getJSONObject("options").getBoolean("includeScreenshot"), "the second message falls back to the global preference");
        MemoryPreferences empty = new MemoryPreferences();
        String blank = ComposerState.write(empty, THREAD, "", "").getString("revision");
        boolean[] untouched = {true};
        rejects("INVALID_TEXT", () -> STORE.reserveSubmission(empty, new JSONObject(input.toString()).put("revision", blank)
                .put("requestId", "00000000-0000-4000-8000-000000000018"), (thread, options) -> kotlin.Unit.INSTANCE,
                () -> { untouched[0] = false; return true; }));
        check(untouched[0], "an unsendable draft keeps the grant for the real first message");
    }

    private static void handoffPreservesDisplacedDraft() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerState.write(prefs, THREAD, "", "original");
        String handoff = "00000000-0000-4000-8000-000000000005";
        JSONObject handed = ComposerState.handoff(prefs, THREAD, handoff, "from Home");
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject changed = ComposerState.write(restarted, THREAD, handed.getString("revision"), "edited after handoff");
        JSONObject replayed = ComposerState.handoff(restarted, THREAD, handoff, "from Home");
        check(replayed.getString("text").equals("edited after handoff") && replayed.getJSONArray("recovery").length() == 1,
                "replaying a handoff preserves later editing and a single recovery entry");
        String recovery = replayed.getJSONArray("recovery").getJSONObject(0).getString("id");
        rejects("DRAFT_NOT_EMPTY", () -> ComposerState.restore(restarted, THREAD, changed.getString("revision"), recovery));
        String empty = ComposerState.write(restarted, THREAD, changed.getString("revision"), "").getString("revision");
        check(ComposerState.restore(restarted, THREAD, empty, recovery).getString("text").equals("original"),
                "the displaced draft restores after process restart");
    }

    private static void legacyHomeAndPendingDelivery() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "Earlier draft with two files").commit();
        JSONArray files = new JSONArray().put(new JSONObject().put("path", "/fixture/first").put("name", "first"))
                .put(new JSONObject().put("path", "/fixture/second").put("name", "second"));
        prefs.edit().putString("composer." + THREAD + ".attachments", files.toString()).commit();
        String legacyKey = "composer." + THREAD + ".delivery";
        JSONObject old = new JSONObject().put("token", "old-delivery").put("status", "sending")
                .put("text", "Unconfirmed request").put("attachments", files);
        prefs.edit().putString(legacyKey, old.toString()).commit();
        prefs.edit().putString("home-pending-id", "legacy-home").putString("home-pending-thread", THREAD)
                .putString("home-pending-text", "Pending Home request").commit();
        check(ComposerState.read(prefs, TARGET).getString("text").isEmpty(), "opening a different thread leaves the handoff pending");
        prefs.failNextCommit = true;
        rejects("DRAFT_SAVE_FAILED", () -> ComposerState.read(prefs, THREAD));
        check(prefs.contains(legacyKey) && prefs.contains("home-pending-id"), "failed persistence keeps both source records");
        JSONObject state = ComposerState.read(new MemoryPreferences(prefs), THREAD);
        JSONArray recovered = state.getJSONArray("recovery");
        check(state.getString("text").equals("Pending Home request") && recovered.length() == 2,
                "upgrade keeps the pending Home input editable and both earlier records recoverable");
        check("delivery-unconfirmed".equals(recovered.getJSONObject(0).getString("status")) &&
                recovered.getJSONObject(0).getJSONArray("attachments").getJSONObject(1).getString("id").equals("second"),
                "an uncertain legacy send retains status and ordered attachments");
        check(state.getJSONArray("pendingReceipts").length() == 0, "legacy recovery never restores a submission to the send queue");
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(String code, Checked request) throws Exception {
        try { request.run(); }
        catch (IOException expected) { check(code.equals(expected.getMessage()), "expected " + code); return; }
        throw new AssertionError("expected " + code);
    }
    private static void conflict(String actual, Checked request) throws Exception {
        try { request.run(); }
        catch (ComposerState.RevisionConflict expected) {
            check(expected.getMessage().equals("DRAFT_REVISION_CONFLICT") && expected.getRevision().equals(actual), "conflict returns the current revision");
            return;
        }
        throw new AssertionError("expected a revision conflict");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
