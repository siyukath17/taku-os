package org.agentos.tests;

import android.os.Bundle;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Real DocumentsUI, task queue and upstream errors. No model result is simulated. */
public final class ComposerReliabilityTest extends UiAutomatorTestCase {
    static final String CHAT = "org.agentos.agent";
    static final String DOCS = "com.android.documentsui";
    static final String APP = "/data/user/0/org.agentos.assistant";
    static final String OPEN_ACCESS = "content://org.agentos.assistant.open-access";
    static final String INPUT = "message-input";
    static final String SEND = "send-message";
    static final String STOP = "stop-turn";
    static final String RUNTIME = APP + "/files/runtime/";
    String run, phase, thread = "", ownedTurn = "", imageCode, textCode;
    String unavailableModel = "";
    boolean staleModelInstalled;
    File output, fixtures;
    JSONObject evidence;
    UiAutomation automation;

    void refreshAccessibility() throws Exception {
        if (automation == null) {
            java.lang.reflect.Method bridgeMethod = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
            bridgeMethod.setAccessible(true);
            Object bridge = bridgeMethod.invoke(getUiDevice());
            for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("mUiAutomation");
                    field.setAccessible(true); automation = (UiAutomation) field.get(bridge); break;
                } catch (NoSuchFieldException ignored) {}
            }
            assertNotNull("Use the existing UiAutomator connection", automation);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            try { automation.waitForIdle(100, 1500); } catch (java.util.concurrent.TimeoutException ignored) {}
        }
        UiAutomation.class.getMethod("clearCache").invoke(automation);
    }
    boolean waitAccessible(UiObject item, long millis) throws Exception {
        long until = System.nanoTime() + millis * 1_000_000L;
        do {
            if (item.exists()) return true;
            sleep(150);
        } while (System.nanoTime() < until);
        return false;
    }

    class Entry extends UiObject {
        final String pkg, field, value;
        final boolean regex;
        Entry(String pkg, String field, String value, boolean regex) {
            super(new UiSelector().packageName(pkg));
            this.pkg=pkg; this.field=field; this.value=value; this.regex=regex;
        }
        AccessibilityNodeInfo matching(AccessibilityNodeInfo node, int depth, int[] budget) {
            if (node == null || depth > 50 || --budget[0] < 0) return null;
            if (node.isVisibleToUser() && node.isEnabled()
                    && pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                CharSequence actual = field.equals("description") ? node.getContentDescription() : node.getText();
                String actualText = actual == null ? "" : actual.toString();
                String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
                String id = node.getViewIdResourceName();
                if (field.equals("package") || (field.equals("scrollable") && node.isScrollable())
                        || (field.equals("id") && (value.equals(id) || (pkg + ":id/" + value).equals(id)))
                        || (field.equals("document") && (actualText.equals(value) || description.equals(value)
                                || description.startsWith(value + ",")))
                        || ((field.equals("description") || field.equals("text"))
                                && (regex ? actualText.matches(value) : actualText.equals(value)))) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo found = matching(node.getChild(i), depth+1, budget);
                if (found != null) return found;
            }
            return null;
        }
        @Override protected AccessibilityNodeInfo findAccessibilityNodeInfo(long timeout) {
            long until = System.nanoTime() + timeout * 1_000_000L;
            try {
                do {
                    refreshAccessibility();
                    // A background virtual display can own the active window. Only operate display 0.
                    List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(0);
                    if (windows != null) for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo found = matching(window.getRoot(), 0, new int[]{4096});
                        if (found != null) return found;
                    }
                    if (System.nanoTime() < until) sleep(100);
                } while (System.nanoTime() < until);
                return null;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public boolean click() {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            if (node == null) return false;
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            // Legacy UiObject.click clips coordinates against the active window, which may
            // belong to display 2. Activate the selected display-0 control (or its row) instead.
            while (node != null && pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                node = node.getParent();
            }
            // DocumentsUI's image-grid cells use touch selection without ACTION_CLICK.
            // Use their observed bounds and explicitly target display 0, never the active display.
            // Folder navigation can expose nodes before the toolbar finishes laying out.
            for (int attempt = 0; attempt < 20; attempt++) {
                sleep(200);
                AccessibilityNodeInfo current = findAccessibilityNodeInfo(1000);
                if (current == null) return false;
                android.graphics.Rect next = new android.graphics.Rect();
                current.getBoundsInScreen(next);
                if (next.equals(bounds)) break;
                bounds.set(next);
            }
            if (bounds.isEmpty()) return false;
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80,
                    android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
            try {
                for (android.view.MotionEvent event : new android.view.MotionEvent[]{down, up}) {
                    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    android.view.InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, 0);
                }
                boolean pressed = automation.injectInputEvent(down, true);
                sleep(80);
                return automation.injectInputEvent(up, true) && pressed;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { down.recycle(); up.recycle(); }
        }
    }
    Entry textEntry(String pkg, String value) { return new Entry(pkg, "text", value, false); }
    final class Node extends Entry {
        Node(String id) { super(CHAT, "id", id, false); }
        boolean replace(String value) throws Exception {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        String value() throws Exception {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            if (node == null) throw new AssertionError("Composer is missing");
            return node.isShowingHintText() || node.getText() == null ? "" : node.getText().toString();
        }
        boolean act() { return click(); }
    }

    String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("Command failed: " + args[0] + "\n" + result, 0, process.waitFor());
        return result.trim();
    }
    JSONObject object(String key, Object value) throws Exception { return new JSONObject().put(key, value); }

    JSONObject access(String method, JSONObject args) throws Exception {
        return content(OPEN_ACCESS, method, args);
    }

    JSONObject home(String method, JSONObject args) throws Exception {
        return content("content://org.agentos.agent.home-assistant", "assistant/" + method, args);
    }

    private JSONObject content(String authority, String method, JSONObject args) throws Exception {
        String reply = command("/system/bin/content", "call", "--uri", authority,
                "--user", "0", "--method", method, "--arg", args.toString());
        int start = reply.indexOf("Bundle[{result=");
        int end = reply.lastIndexOf("}]");
        assertTrue("Missing Open Access response for " + method + ": " + reply, start >= 0 && end > start);
        JSONObject envelope = new JSONObject(reply.substring(start + "Bundle[{result=".length(), end));
        assertTrue(method + ": " + envelope, envelope.optBoolean("ok"));
        return envelope.getJSONObject("result");
    }
    JSONObject draft() throws Exception {
        JSONObject value = access("composer/read", object("threadId", thread));
        assertEquals(thread, value.getString("threadId"));
        return value;
    }
    JSONArray attachments() throws Exception {
        return draft().getJSONArray("attachments");
    }
    void expectAttachments(int count) throws Exception {
        long until = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < until) { if (attachments().length() == count) return; sleep(100); }
        assertEquals("Selected attachments must be persisted for this test thread", count, attachments().length());
    }
    void expectDraft(String value) throws Exception {
        long until = System.nanoTime() + 12_000_000_000L;
        while (System.nanoTime() < until) {
            if (value.equals(new Node(INPUT).value()) && value.equals(draft().getString("text"))) return;
            sleep(150);
        }
        assertEquals("Expo composer text", value, new Node(INPUT).value());
        assertEquals("Public composer text", value, draft().getString("text"));
    }
    void openThread() throws Exception {
        command("/system/bin/am", "start", "-W", "--display", "0", "-a", "android.intent.action.MAIN",
                "-n", CHAT + "/.MainActivity", "--es", "threadId", thread);
        assertTrue("Expo composer missing", waitAccessible(new Node(INPUT), 25000));
    }
    void createThread() throws Exception {
        JSONObject options = object("developerInstructions",
                "这是独立、静音的输入框回归。仅按用户要求读取其主动选择的附件或执行 sleep。"
                + "不要调用手机操作，不修改文件，不发通知，不播放声音。图片必须实际读取；无法读取时明确报错。");
        if (phase.equals("failure-async")) {
            unavailableModel = "taku-composer-unavailable-" + run;
            evidence.put("failureTrigger", "unavailable-model-on-isolated-test-thread").put("requestedModel", unavailableModel);
        }
        thread = NativeSocketProbe.rpc("thread/start", options)
                .getJSONObject("thread").getString("id");
        assertTrue(thread.matches("[a-f0-9-]{36}"));
        evidence.put("threadId", thread); saveEvidence();
        if (!unavailableModel.isEmpty()) installStaleModel();
        String marker = "Composer fixture " + thread;
        String revision = draft().getString("revision");
        access("composer/write", object("threadId", thread).put("revision", revision).put("text", marker));
        openThread();
        expectDraft(marker);
        assertTrue(new Node(INPUT).replace(""));
        expectDraft("");
    }
    void installStaleModel() throws Exception {
        // Simulate a saved per-thread model that disappeared from the catalog. The public writer rejects new unknown IDs.
        command("/system/bin/am", "force-stop", "--user", "0", "org.agentos.assistant");
        File settings = new File(APP + "/shared_prefs/agent-settings.xml");
        assertFalse("Assistant preferences have an unfinished Android write", new File(settings + ".bak").exists());
        String xml = read(settings);
        String key = "composer." + thread + ".model";
        int close = xml.lastIndexOf("</map>");
        assertTrue("Expected a fresh thread in an existing preference map", close >= 0 && !xml.contains(key));
        android.system.StructStat metadata = android.system.Os.stat(settings.getPath());
        File replacement = File.createTempFile("composer-model-fixture-", ".xml", settings.getParentFile());
        try {
            write(replacement, xml.substring(0, close) + "<string name=\"" + key + "\">" + unavailableModel
                    + "</string>\n" + xml.substring(close));
            android.system.Os.chown(replacement.getPath(), metadata.st_uid, metadata.st_gid);
            android.system.Os.chmod(replacement.getPath(), metadata.st_mode & 0777);
            command("/system/bin/restorecon", replacement.getPath());
            android.system.Os.rename(replacement.getPath(), settings.getPath());
            staleModelInstalled = true;
        } finally { replacement.delete(); }
        assertEquals("The stale model fixture is scoped to this thread", unavailableModel,
                access("composer/options/read", object("threadId", thread)).getJSONObject("effective").getString("model"));
    }
    void assertUnavailableModelFailure(String task) throws Exception {
        JSONObject state = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
        JSONObject completed = state.optJSONObject("completedTurn");
        if (completed != null && completed.optString("status").equals("failed")) {
            String error = state.optString("error");
            assertTrue("Expected an upstream model error: " + error, error.contains(unavailableModel));
            evidence.put("turnId", completed.getString("id")).put("nativeTurnStatus", "failed");
            return;
        }
        // App Server can reject the turn/start request before creating a turn.
        File diagnostic = new File(RUNTIME + "tasks/" + task + "/diagnostic.json");
        assertTrue("Expected a native turn failure or a turn/start rejection: " + state, diagnostic.isFile());
        String error = new JSONObject(read(diagnostic)).optString("operationError");
        assertTrue("Expected a native turn/start rejection: " + error, error.startsWith("Assistant connection failed: turn/start"));
        evidence.put("nativeRequestRejected", true);
    }
    void createFixtures() throws Exception {
        assertTrue(fixtures.mkdirs() || fixtures.isDirectory());
        imageCode = getParams().getString("imageCode", "");
        assertTrue("Host-generated image token is required", imageCode.matches("PIC-[0-9]{8}"));
        textCode = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Raw app_process has no initialized Android font manager; prepare pixels on the host.
        try (InputStream in = new FileInputStream("/data/local/tmp/taku-composer-" + run + "-image.png");
                FileOutputStream out = new FileOutputStream(new File(fixtures, "image.png"))) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
        }
        write(new File(fixtures, "document.txt"), "Document verification token: " + textCode + "\n");
        evidence.put("fixtureDirectory", fixtures.getPath()).put("imageCode", imageCode).put("textCode", textCode);
    }

    void clickDocumentText(String text) throws Exception {
        Entry item = new Entry(DOCS, "document", text, false);
        for (int attempt=0; attempt<12 && !item.waitForExists(900); attempt++) {
            AccessibilityNodeInfo list = new Entry(DOCS, "scrollable", "", false).findAccessibilityNodeInfo(500);
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            sleep(150);
        }
        assertTrue("DocumentsUI entry missing: " + text, item.waitForExists(2000));
        assertTrue("DocumentsUI entry could not be activated: " + text, item.click());
    }
    void pick(String filename, boolean image, int expectedCount) throws Exception {
        assertTrue(new Node("composer-tools").act());
        UiObject choice = new Node(image ? "attach-image" : "attach-file");
        assertTrue("Attachment menu missing", waitAccessible(choice, 8000)); assertTrue(choice.click());
        assertTrue("Real Android document picker did not open", waitAccessible(new Entry(DOCS, "package", "", false), 25000));
        UiObject roots = new Entry(DOCS, "description", "(?i)(Show roots|Show navigation drawer|显示根目录|显示导航抽屉|显示根路径)", true);
        assertTrue("DocumentsUI navigation button missing", roots.waitForExists(3000)); assertTrue(roots.click());
        refreshAccessibility();
        // The Downloads provider only shows indexed files. Follow the real storage tree instead.
        String model = command("/system/bin/getprop", "ro.product.model");
        UiObject storage = textEntry(DOCS, model);
        if (!storage.waitForExists(1200)) storage = new Entry(DOCS, "text", "(?i)(Internal storage|内部存储设备|内部存储)", true);
        assertTrue("DocumentsUI internal storage root missing: " + model, storage.waitForExists(3000));
        assertTrue(storage.click());
        clickDocumentText("Download"); clickDocumentText(fixtures.getName());
        Entry listView = new Entry(DOCS, "description", "(?i)(List view|列表视图)", true);
        if (listView.waitForExists(1200)) assertTrue(listView.click());
        clickDocumentText(filename);
        // Some modes expose Open; one condition deadline covers either selection flow.
        long returnUntil = System.nanoTime() + 25_000_000_000L;
        boolean returned = false, accepted = false;
        do {
            if (!new Entry(DOCS, "package", "", false).exists() && new Node(INPUT).exists()) {
                returned = true; break;
            }
            UiObject accept = new Entry(DOCS, "text", "(?i)(Open|Select|打开|选择)", true);
            if (!accepted && accept.exists()) { assertTrue(accept.click()); accepted = true; }
            sleep(150);
        } while (System.nanoTime() < returnUntil);
        assertTrue("Picker must return to the assistant", returned);
        expectAttachments(expectedCount);
        assertEquals("Picker selection order", filename, attachments().getJSONObject(expectedCount - 1).getString("name"));
        expectAttachment(filename);
        evidence.put("actualAndroidPickerSelections", evidence.optInt("actualAndroidPickerSelections") + 1);
        saveEvidence();
    }

    String ownTask() throws Exception {
        File[] published = new File(RUNTIME + "tasks").listFiles((dir, name) -> name.matches("[0-9]{1,20}"));
        assertNotNull("Task directory is unavailable", published);
        String owned = "";
        for (File task : published) {
            if (!new File(task, "ready").isFile()) continue;
            File request = new File(task, "request.json");
            if (!request.isFile() || !thread.equals(new JSONObject(read(request)).optString("conversationId"))) continue;
            assertTrue("More than one task published for " + thread, owned.isEmpty());
            owned = task.getName();
        }
        return owned;
    }
    String waitOwnTask() throws Exception {
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until) {
            String id = ownTask();
            if (!id.isEmpty()) return id;
            sleep(100);
        }
        fail("No actual queue task for the isolated test thread"); return "";
    }
    JSONObject waitDone(String id) throws Exception {
        long until = System.nanoTime() + 150_000_000_000L;
        while (System.nanoTime() < until) {
            JSONObject detail = access("readTaskDetail", object("taskId", id));
            if (detail.getString("state").matches("completed|cancelled|failed|needs_user")) return detail;
            sleep(250);
        }
        fail("Actual task did not finish within 150 seconds: " + id); return null;
    }
    void saveEvidence() throws Exception { write(new File(output, "result.json"), evidence.toString(2)); }
    void capture(String name) throws Exception {
        command("/system/bin/screencap", "-p", new File(output, name + ".png").getPath());
        // Legacy UiDevice treats its argument as a filename beneath /data/local/tmp.
        String xmlName = "taku-composer-" + run + "-" + phase + "-" + name + ".xml";
        getUiDevice().dumpWindowHierarchy(xmlName);
        File hierarchy = new File("/data/local/tmp", xmlName);
        if (hierarchy.isFile()) {
            write(new File(output, name + ".xml"), read(hierarchy));
            assertTrue("Clean temporary hierarchy", hierarchy.delete());
        }
    }
    void expectAttachment(String filename) throws Exception {
        assertTrue("Expo attachment chip missing: " + filename,
                new Entry(CHAT, "description", "(Remove attachment |移除附件 )" + java.util.regex.Pattern.quote(filename), true)
                        .waitForExists(8000));
    }
    /**
     * A delivered request that failed is never put back into the draft (nor resent): the failure
     * card offers "Continue" in the same conversation instead.
     */
    void restoreFailedTask(String text, JSONArray selected) throws Exception {
        Node proceed = new Node("continue-failed-task");
        assertTrue("Failed task must offer Continue", proceed.waitForExists(25000));
        capture("failure");
        assertTrue("The draft-restore action is gone", !new Node("restore-failed-task").waitForExists(500));
        expectDraft("");
        expectAttachments(0);
        assertEquals("Showing the failure must not publish another task", evidence.getString("taskId"), ownTask());
        evidence.put("draftRestored", false).put("continueOffered", true).put("failedText", text).put("failedAttachments", selected.length());
    }
    void failureMissing() throws Exception {
        String text = "未发送草稿：附件失效时请保留这段文字。";
        assertTrue(new Node(INPUT).replace(text));
        pick("document.txt", false, 1);
        expectDraft(text);
        JSONArray selected = attachments();
        JSONObject attachment = selected.getJSONObject(0);
        assertEquals("document.txt", attachment.getString("name"));
        String id = attachment.getString("id");
        assertTrue(id.matches("[a-f0-9-]{36}"));
        File copy = new File(APP + "/files/composer-attachments", id);
        assertTrue("Invalidate only the freshly selected fixture copy", !java.nio.file.Files.isSymbolicLink(copy.toPath()) && copy.delete());
        assertTrue(new Node(SEND).act());
        String task = waitOwnTask();
        JSONObject detail = waitDone(task);
        evidence.put("taskId", task).put("taskState", detail); saveEvidence();
        assertEquals("Missing attachment must fail the send", "failed", detail.getString("state"));
        JSONObject nativeState = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
        assertEquals("Missing attachment must not start an upstream turn", "", nativeState.optString("activeTurnId"));
        assertNull("Missing attachment must not finish an upstream turn", nativeState.optJSONObject("completedTurn"));
        restoreFailedTask(text, selected);
    }
    void failureAsync() throws Exception {
        String text = "异步发送失败时保留本草稿及文件附件。";
        assertTrue(new Node(INPUT).replace(text));
        pick("document.txt", false, 1);
        expectDraft(text);
        JSONArray selected = attachments();
        assertTrue(new Node(SEND).act());
        String task = waitOwnTask();
        JSONObject detail = waitDone(task);
        evidence.put("taskId", task).put("taskState", detail); saveEvidence();
        assertEquals("The real unavailable-model request must fail", "failed", detail.getString("state"));
        assertUnavailableModelFailure(task);
        restoreFailedTask(text, selected);
        evidence.put("realUpstreamFailure", true);
    }
    void addBoth() throws Exception {
        pick("image.png", true, 1); pick("document.txt", false, 2);
    }
    String requestPrompt() {
        return "请实际查看所附图片并读取所附文本文件，只回复图片中的 PIC 验证码和文档中的 DOC 验证码。"
                + "不要根据文件名猜测；不要调用手机操作，不写文件，不发通知。";
    }
    void expectCodes(String answer) {
        assertTrue("Actual image code missing from real response: " + answer, answer.contains(imageCode));
        assertTrue("Actual text-file token missing from real response: " + answer, answer.contains(textCode));
    }
    void sendAttachments() throws Exception {
        assertTrue(new Node(INPUT).replace(requestPrompt())); addBoth();
        capture("before-send");
        assertTrue(new Node(SEND).act());
        String task = waitOwnTask();
        JSONObject detail = waitDone(task);
        evidence.put("taskId", task).put("taskState", detail); saveEvidence();
        assertEquals(detail.getString("message"), "completed", detail.getString("state"));
        String answer = access("manageAssistant", object("operation", "taskFiles/read")
                .put("threadId", thread).put("taskId", task).put("path", "result.txt")).getString("text");
        expectCodes(answer);
        expectDraft(""); expectAttachments(0);
        assertTrue("The real reply is visible in Expo",
                new Entry(CHAT, "text", "(?s).*" + imageCode + ".*", true).waitForExists(25000));
        capture("completed");
        evidence.put("reply", answer).put("realImageAndTextUnderstanding", true);
    }

    void sendThroughHome() throws Exception {
        assertTrue(new Node(INPUT).replace(requestPrompt()));
        addBoth();
        expectDraft(requestPrompt());
        home("resume", object("threadId", thread));
        try {
            home("open", object("mode", "text"));
            JSONObject visible = home("get", new JSONObject());
            assertEquals("Home reopened the same conversation", thread, visible.optString("threadId"));
            assertEquals("Home reads the shared text", requestPrompt(), visible.optString("draft"));
            assertEquals(new JSONArray().put("image.png").put("document.txt").toString(),
                    visible.getJSONArray("draftAttachmentNames").toString());
            capture("home-before-send");
            Entry names = new Entry(CHAT, "text", "image.png · document.txt", false);
            assertTrue("Home shows the files picked in Expo", names.waitForExists(12000));
            assertTrue("Home sends its shared draft", new Entry(CHAT, "description", "Send", false).click());
            String task = waitOwnTask();
            JSONObject detail = waitDone(task);
            evidence.put("taskId", task).put("taskState", detail); saveEvidence();
            assertEquals(detail.getString("message"), "completed", detail.getString("state"));
            String answer = access("manageAssistant", object("operation", "taskFiles/read")
                    .put("threadId", thread).put("taskId", task).put("path", "result.txt")).getString("text");
            expectCodes(answer);
            assertEquals("", draft().getString("text"));
            expectAttachments(0);
            evidence.put("reply", answer).put("homeSharedAttachmentsDelivered", true);
        } finally {
            home("reset", new JSONObject());
        }
    }
    JSONArray turns() throws Exception {
        return NativeSocketProbe.rpc("thread/read", object("threadId", thread).put("includeTurns", true))
                .getJSONObject("thread").getJSONArray("turns");
    }
    JSONObject waitNativeTurn(boolean completed) throws Exception {
        long until = System.nanoTime() + 150_000_000_000L;
        while (System.nanoTime() < until) {
            JSONArray turns = turns();
            for (int i = 0; i < turns.length(); i++) {
                JSONObject turn = turns.getJSONObject(i);
                if (ownedTurn.equals(turn.optString("id")) && turn.optString("status").matches("completed|interrupted|failed")) {
                    if (completed) assertEquals(turn.toString(), "completed", turn.getString("status"));
                    return turn;
                }
            }
            sleep(250);
        }
        fail("Actual native turn did not reach a terminal state: " + ownedTurn); return null;
    }
    void startNativeSleep() throws Exception {
        ownedTurn = NativeSocketProbe.rpc("turn/start", object("threadId", thread).put("input", new JSONArray()
                .put(object("type", "text").put("text", "请真实执行 /system/bin/sleep 60，等待完成再回复 ORIGINAL。"
                        + "如果收到追加指令，就在同一轮遵循追加指令。"))))
                .getJSONObject("turn").getString("id");
        evidence.put("turnId", ownedTurn); saveEvidence();
    }
    void steerAttachments() throws Exception {
        addBoth(); assertTrue(new Node(INPUT).replace(requestPrompt()));
        expectDraft(requestPrompt());
        startNativeSleep(); openThread();
        assertTrue("Expo must show Stop before steering", new Node(STOP).waitForExists(25000));
        assertTrue(new Node(SEND).act());
        JSONObject turn = waitNativeTurn(true); StringBuilder answer = new StringBuilder();
        JSONArray items = turn.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optString("type").equals("agentMessage")) answer.append(item.optString("text"));
        }
        expectCodes(answer.toString()); expectDraft(""); expectAttachments(0);
        assertTrue("Expo shows the reply from the steered turn",
                new Entry(CHAT, "text", "(?s).*" + imageCode + ".*", true).waitForExists(15000));
        assertEquals("Steering must produce no fallback task", "", ownTask());
        assertEquals("Steering must not start a second turn", 1, turns().length());
        capture("completed");
        evidence.put("reply", answer.toString()).put("realSteerSameTurn", true).put("steeredAttachmentCount", 2);
    }
    void stopTurn() throws Exception {
        assertTrue(new Node(INPUT).replace(
                "请真实执行 /system/bin/sleep 60，等待命令完成后再回复。不要执行其他操作。"));
        assertTrue(new Node(SEND).act());
        String task = waitOwnTask();
        evidence.put("taskId", task).put("queueCreated", true).put("submittedThroughComposer", true);
        saveEvidence();
        long until = System.nanoTime() + 30_000_000_000L;
        do {
            JSONObject nativeState = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
            ownedTurn = nativeState.optString("activeTurnId");
            if (!ownedTurn.isEmpty()) break;
            sleep(150);
        } while (System.nanoTime() < until);
        assertFalse("The composer task must start a real native turn in its own thread", ownedTurn.isEmpty());
        evidence.put("turnId", ownedTurn); saveEvidence();
        assertTrue(new Node(STOP).waitForExists(8000));
        String text = "尚未提交的追加指令";
        assertTrue(new Node(INPUT).replace(text));
        expectDraft(text);
        assertTrue(new Node(SEND).waitForExists(8000));
        assertTrue("Stop stays available with an unsent draft", new Node(STOP).act());
        JSONObject state = waitDone(task);
        JSONObject turn = waitNativeTurn(false);
        evidence.put("taskState", state).put("nativeTurnStatus", turn.optString("status")); saveEvidence();
        assertEquals("Stop must cancel the actual composer queue task", "cancelled", state.optString("state"));
        assertEquals("Stop must interrupt the matching real upstream turn", "interrupted", turn.optString("status"));
        assertTrue("Stopped composer must still offer Send", waitAccessible(new Node(SEND), 25000));
        assertTrue("Expo must show the interrupted state", new Entry(CHAT, "text",
                "Request stopped|Response stopped|该请求已停止|回复已停止", true).waitForExists(15000));
        assertFalse("Stop must disappear after interruption", new Node(STOP).exists());
        expectDraft(text);
        evidence.put("nativeStoppedStateRendered", true);
        evidence.put("stopWithDraftPreserved", true).put("actualTurnInterrupted", true).put("actualTaskCancelled", true)
                .put("composerReturnedToSend", true); capture("stopped");
    }

    public void testComposerPhase() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        run = getParams().getString("runId", ""); phase = getParams().getString("phase", "");
        assertTrue("A unique host runId is required", run.matches("[a-zA-Z0-9-]{1,80}"));
        assertTrue("Unknown phase", phase.matches("failure-missing|failure-async|attachments|home-attachments|steer|stop"));
        output = new File("/data/local/tmp/taku-composer-tests/" + run + "/" + phase);
        fixtures = new File("/sdcard/Download/taku-composer-" + run + "-" + phase);
        assertTrue(output.mkdirs());
        evidence = object("phase", phase).put("runId", run).put("modelResponsesSimulated", false).put("passed", false);
        try {
            command("/system/bin/input", "-d", "0", "keyevent", "KEYCODE_HOME");
            createFixtures(); createThread();
            switch (phase) {
                case "failure-missing": failureMissing(); break;
                case "failure-async": failureAsync(); break;
                case "attachments": sendAttachments(); break;
                case "home-attachments": sendThroughHome(); break;
                case "steer": steerAttachments(); break;
                case "stop": stopTurn(); break;
                default: throw new AssertionError("Unknown phase");
            }
            evidence.put("passed", true);
        } catch (Throwable failure) {
            evidence.put("failure", failure.toString());
            try { capture("assertion-failure"); } catch (Exception ignored) {}
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        } finally {
            // Only interrupt this test's owned native turn; never inspect or cancel unrelated work.
            if (!ownedTurn.isEmpty()) try {
                JSONObject state = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
                if (ownedTurn.equals(state.optString("activeTurnId")))
                    NativeSocketProbe.rpc("turn/interrupt", object("threadId", thread).put("turnId", ownedTurn));
            } catch (Exception cleanup) { evidence.put("turnCleanupError", cleanup.toString()); }
            try {
                if (staleModelInstalled) access("composer/options/write", object("threadId", thread)
                        .put("options", object("model", JSONObject.NULL)));
            } catch (Exception | AssertionError cleanup) {
                evidence.put("modelFixtureCleanupError", cleanup.toString());
                if (evidence.optBoolean("passed")) {
                    evidence.put("passed", false);
                    throw cleanup;
                }
            } finally { saveEvidence(); }
        }
    }
}
