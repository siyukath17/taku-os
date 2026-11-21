package org.agentos.lab;

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

    static void requestCancel(File taskDir) throws Exception {
        TaskRecords.write(new File(taskDir, "cancel"), "1");
    }

    private static String read(File f) throws IOException {
        if (f.length() > 1_000_000) throw new IOException("record too large");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
