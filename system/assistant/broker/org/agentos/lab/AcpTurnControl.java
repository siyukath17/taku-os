package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Stopping an ACP chat turn from the chat page ({@code interrupt}) or the Agent gateway
 * ({@code liveTask cancel/replace}).
 *
 * <p>ACP turns are not Codex turns: the Codex app server has no active turn for them, so the Codex
 * paths answer {@code TURN_CHANGED} ({@code turn/interrupt}) or {@code RUNTIME_UNAVAILABLE} /
 * fail on {@code thread/goal/get} ({@code liveTask}). The one stop signal {@link AcpRuntime} honours
 * is the task's {@code cancel} marker: it sends {@code session/cancel}, waits
 * {@code cancelGraceMillis} and reclaims the agent process, then closes the turn
 * {@code interrupted} and the task {@code cancelled}. This class only validates the target and
 * writes that marker; it never touches the agent itself.
 */
final class AcpTurnControl {
    private AcpTurnControl() {}

    /** Prefix of every ACP turn id ({@link AcpConversationStore#beginTurn}). */
    static final String TURN_PREFIX = "acp-";
    private static final String TASK_ID = "[0-9]{1,20}";

    /**
     * Requests the stop of ACP turn {@code turnId} of {@code conversationId}. Answers like the
     * Codex queue cancel ({@code cancellationRequested}) plus {@code turnId}, {@code taskId} and
     * {@code runtime}. {@code TURN_CHANGED} when that turn is not (any longer) running.
     */
    static JSONObject interrupt(AcpConversationStore store, File tasks, String conversationId, String turnId) throws Exception {
        if (!AcpConversationStore.validId(conversationId) || !store.owns(conversationId)) throw new IOException("THREAD_UNAVAILABLE");
        if (turnId == null || !turnId.startsWith(TURN_PREFIX)) throw new IOException("TURN_CHANGED");
        String taskId = store.activeTask(conversationId, turnId);
        if (taskId == null) throw new IOException("TURN_CHANGED");
        requestCancel(task(tasks, taskId, conversationId));
        return new JSONObject().put("threadId", conversationId).put("turnId", turnId).put("taskId", taskId)
                .put("runtime", RuntimeRouter.ACP).put("cancellationRequested", true);
    }

    /**
     * liveTask cancel of an ACP task: a task whose turn has not started yet gets the marker directly
     * (TaskBroker.run and AcpRuntime both honour it before starting the agent); a started turn goes
     * through {@link #interrupt}, so it must still be the conversation's running turn.
     */
    static void cancel(AcpConversationStore store, File tasks, String conversationId, String taskId, String turnId) throws Exception {
        if (turnId == null || turnId.isEmpty()) {
            requestCancel(task(tasks, taskId, conversationId));
            return;
        }
        JSONObject answer = interrupt(store, tasks, conversationId, turnId);
        if (!taskId.equals(answer.getString("taskId"))) throw new IOException("LIVE_TASK_MISMATCH");
    }

    /**
     * True when this task runs (or ran) on ACP, so liveTask must not ask the Codex app server:
     * {@code runtime-route.json} says so, or the task is not routed yet and belongs to a
     * conversation the broker started on ACP (Codex has no thread to ask about).
     */
    static boolean acpTask(AcpConversationStore store, File taskDir, String conversationId, String turnId) {
        File route = new File(taskDir, "runtime-route.json");
        if (route.isFile()) {
            try {
                return RuntimeRouter.ACP.equals(new JSONObject(read(route)).optString("runtime"));
            } catch (Exception unreadable) {
                return false;
            }
        }
        if (turnId != null && turnId.startsWith(TURN_PREFIX)) return true;
        return store.standalone(conversationId);
    }

    /** The queued or running task directory of this conversation. */
    static File task(File tasks, String taskId, String conversationId) throws Exception {
        if (taskId == null || !taskId.matches(TASK_ID)) throw new IOException("INVALID_TASK_ID");
        File dir = new File(tasks, taskId);
        if (!new File(dir, "ready").isFile()) throw new IOException("TASK_UNAVAILABLE");
        JSONObject request;
        try {
            request = new JSONObject(read(new File(dir, "request.json")));
        } catch (Exception unreadable) {
            throw new IOException("TASK_UNAVAILABLE");
        }
        if (!conversationId.equals(request.optString("conversationId"))) throw new IOException("TASK_THREAD_MISMATCH");
        if (new File(dir, "done").exists()) throw new IOException("TURN_CHANGED");
        return dir;
    }

    // ---- steering (an extra user message into the running turn) ---------------------------------

    /** agy refused the steer: its running prompt waits for a permission or question answer. */
    static final String STEER_WAITING_ANSWER = "STEER_WAITING_ANSWER";
    /** The turn is being stopped (the cancel marker exists, or agy is cancelling its prompt). */
    static final String STEER_CANCELLING = "STEER_CANCELLING";
    /** agy refused the steer for another reason. */
    static final String STEER_REJECTED = "STEER_REJECTED";
    static final int MAX_STEER_CHARS = 16_000;
    static final int MAX_ATTACHMENTS = 8;
    static final long MAX_ATTACHMENT_BYTES = 20_000_000L;
    /** How long {@link #steer} waits for the runtime's outcome before withdrawing the request. */
    static final long STEER_WAIT_MILLIS = 8_000;
    /** After the runtime claimed the request, how much longer its outcome may take. */
    static final long STEER_OUTCOME_GRACE_MILLIS = 3_000;
    private static final String REQUEST_ID = "[A-Za-z0-9_-]{1,96}";
    /** Where composer attachments live (as in ComposerInput); replaced by host tests. */
    static File attachmentRoot = new File("/data/user/0/org.agentos.assistant/files/composer-attachments");

    /**
     * The steer was provably never handed to the agent (the turn ended or never picked it up, and the
     * request was withdrawn): the caller may queue the text as the next turn instead.
     */
    static final class SteerNotSubmitted extends IOException {
        SteerNotSubmitted() { super("TURN_CHANGED"); }
    }

    /** The agent (or the stopping turn) refused the steer; {@code getMessage()} is the error code. */
    static final class SteerRejected extends IOException {
        SteerRejected(String code) { super(code); }
    }

    static boolean validRequestId(String id) {
        return id != null && id.matches(REQUEST_ID);
    }

    /** {@code steer-<requestId><suffix>} in a task directory: .json request, .claimed, .ack, .result, .withdrawn. */
    static File steerFile(File taskDir, String requestId, String suffix) {
        return new File(taskDir, "steer-" + requestId + suffix);
    }

    /**
     * Adds a user message to ACP turn {@code turnId} while it runs (native {@code turn/steer} for
     * ACP). Validates that the turn is the conversation's running turn and its task is neither done
     * nor stopping, then atomically writes {@code steer-<requestId>.json} into the task directory;
     * {@link AcpRuntime} claims it (atomic rename to {@code .claimed}), sends a second
     * {@code session/prompt} into the same agent session and writes {@code .result}. This waits for
     * that outcome. If the runtime never claimed the request (the turn ended first), it is withdrawn
     * by the same atomic rename, so exactly one side wins and {@link SteerNotSubmitted} proves it
     * was not sent. {@code checkOnly} validates without writing anything.
     */
    static JSONObject steer(AcpConversationStore store, File tasks, String conversationId, String turnId, String requestId,
                            String text, JSONArray attachments, boolean checkOnly, long waitMillis) throws Exception {
        if (!AcpConversationStore.validId(conversationId) || !store.owns(conversationId)) throw new IOException("THREAD_UNAVAILABLE");
        if (!validRequestId(requestId)) throw new IOException("INVALID_ARGUMENT");
        String body = text == null ? "" : text;
        JSONArray files = attachments(attachments);
        if ((body.isBlank() && files.length() == 0) || body.length() > MAX_STEER_CHARS) throw new IOException("INVALID_ARGUMENT");
        if (turnId == null || !turnId.startsWith(TURN_PREFIX)) throw new SteerNotSubmitted();
        String taskId = store.turnTask(conversationId, turnId);
        if (taskId == null || !taskId.matches(TASK_ID)) throw new SteerNotSubmitted();
        JSONObject answer = new JSONObject().put("threadId", conversationId).put("turnId", turnId).put("taskId", taskId)
                .put("requestId", requestId).put("runtime", RuntimeRouter.ACP);
        // A retried request answers from its recorded outcome, even after the turn ended.
        JSONObject prior = outcome(new File(tasks, taskId), requestId);
        if (prior != null) return settled(answer, prior);
        if (store.activeTask(conversationId, turnId) == null) throw new SteerNotSubmitted();
        File dir;
        try {
            dir = task(tasks, taskId, conversationId);
        } catch (IOException ended) {
            if ("TURN_CHANGED".equals(ended.getMessage())) throw new SteerNotSubmitted();
            throw ended;
        }
        boolean inFlight = steerFile(dir, requestId, ".claimed").exists() || steerFile(dir, requestId, ".json").exists();
        if (!inFlight && new File(dir, "cancel").exists()) throw new SteerRejected(STEER_CANCELLING);
        if (checkOnly) return answer.put("checked", true);
        if (!inFlight) {
            if (steerFile(dir, requestId, ".withdrawn").exists()) throw new SteerNotSubmitted();
            TaskRecords.write(steerFile(dir, requestId, ".json"), new JSONObject().put("requestId", requestId)
                    .put("text", body).put("attachments", files).put("createdAt", System.currentTimeMillis()).toString());
        }
        long end = System.currentTimeMillis() + Math.max(0, waitMillis);
        for (; ; ) {
            JSONObject result = outcome(dir, requestId);
            if (result != null) return settled(answer, result);
            boolean ended = new File(dir, "done").exists();
            long now = System.currentTimeMillis();
            if (ended || now >= end) {
                if (steerFile(dir, requestId, ".json").renameTo(steerFile(dir, requestId, ".withdrawn"))) throw new SteerNotSubmitted();
                // The runtime claimed it: agy answers a refusal at once, an acceptance within moments.
                result = outcome(dir, requestId);
                if (result != null) return settled(answer, result);
                if (ended || now >= end + STEER_OUTCOME_GRACE_MILLIS) {
                    if (steerFile(dir, requestId, ".ack").isFile()) return answer.put("state", "sent");
                    throw new IOException("STEER_UNCONFIRMED");
                }
            }
            Thread.sleep(25);
        }
    }

    private static JSONObject outcome(File dir, String requestId) {
        File f = steerFile(dir, requestId, ".result");
        if (!f.isFile()) return null;
        try {
            return new JSONObject(read(f));
        } catch (Exception partial) {
            return null; // written atomically; unreadable means not there yet
        }
    }

    private static JSONObject settled(JSONObject answer, JSONObject result) throws Exception {
        if ("accepted".equals(result.optString("state"))) return answer.put("state", "accepted");
        String code = result.optString("code", STEER_REJECTED);
        if ("TURN_CHANGED".equals(code)) throw new SteerNotSubmitted();
        throw new SteerRejected(code.matches("[A-Z][A-Z_]{2,80}") ? code : STEER_REJECTED);
    }

    /**
     * The composer attachments of a steer ({@code [{path, mime, name}]}), checked like
     * {@code ComposerInput.appendAttachments}: at most 8, each a readable file directly in the
     * composer attachment directory, at most 20 MB.
     */
    static JSONArray attachments(JSONArray attachments) throws Exception {
        JSONArray out = new JSONArray();
        if (attachments == null) return out;
        if (attachments.length() > MAX_ATTACHMENTS) throw new IOException("ATTACHMENT_LIMIT");
        File root = attachmentRoot.getCanonicalFile();
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            if (item == null) throw new IOException("ATTACHMENT_UNAVAILABLE");
            File file = new File(item.optString("path", "")).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.isFile() || !file.canRead() || file.length() > MAX_ATTACHMENT_BYTES)
                throw new IOException("ATTACHMENT_UNAVAILABLE");
            String name = item.optString("name", file.getName());
            out.put(new JSONObject().put("path", file.getPath()).put("mime", item.optString("mime", ""))
                    .put("name", name.isBlank() ? file.getName() : AcpConversationStore.clip(name, 200)));
        }
        return out;
    }

    static void requestCancel(File taskDir) throws Exception {
        TaskRecords.write(new File(taskDir, "cancel"), "1");
    }

    private static String read(File f) throws IOException {
        if (f.length() > 1_000_000) throw new IOException("record too large");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
