package org.agentos.assistant

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object TaskRequests {
    private val conversationId = Regex("[a-f0-9-]{36}")
    private val optionalFields = arrayOf("model", "effort", "multiAgentMode", "permissionMode", "serviceTier")
    private val panelUpdateFields = setOf("panelId", "originThreadId", "matterId", "sourceVersion", "requestId")

    fun interface BeforePublish {
        @Throws(Exception::class)
        fun persist(taskId: String)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun write(file: File, value: String) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Save failed")
    }

    @JvmStatic
    @Throws(Exception::class)
    fun cancelQueued(thread: String, taskId: String): JSONObject {
        val task = File(TaskRuntime.TASKS, taskId)
        val original = SourceAdapters.readObject(File(task, "request.json"))
        if (thread != original.optString("conversationId")) throw IOException("TASK_THREAD_MISMATCH")
        if (File(task, "done").isFile) throw IOException("TASK_FINISHED")
        if (NativeClient.state(thread).optString("activeTurnId").isNotEmpty()) throw IOException("TURN_STARTED")
        write(File(task, "cancel"), "1")
        return JSONObject().put("threadId", thread).put("taskId", taskId).put("cancellationRequested", true)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun interrupt(thread: String, turnId: String): JSONObject {
        // An ACP chat turn (AcpConversationStore id "acp-…") is not a Codex turn: Codex has no active
        // turn for it, so the broker stops it through the task's cancel marker instead.
        if (turnId.startsWith("acp-")) {
            return NativeClient.exchange(JSONObject().put("op", "acpInterrupt").put("threadId", thread).put("turnId", turnId))
        }
        val state = NativeClient.state(thread)
        if (turnId != state.optString("activeTurnId")) throw IOException("TURN_CHANGED")
        if (state.optJSONObject("goal")?.optString("status") == "active") {
            NativeClient.rpcSync("thread/goal/set", JSONObject().put("threadId", thread).put("status", "paused").put("origin", "user"))
        }
        return NativeClient.rpcSync("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", turnId))
    }

    @JvmStatic
    @Throws(Exception::class)
    fun liveTask(submissions: AgentGatewaySubmissions, input: JSONObject, action: String): JSONObject {
        val operation = when (action) {
            AgentGatewayProtocol.LIVE_TASK_READ -> "read"
            AgentGatewayProtocol.LIVE_TASK_REPLACE -> "replace"
            AgentGatewayProtocol.LIVE_TASK_CANCEL -> "cancel"
            else -> throw IOException("INVALID_ARGUMENT")
        }
        submissions.requireLiveTask(input.getString("requestId"), input.getString("threadId"), input.getString("text"), input.getString("taskId"))
        return NativeClient.exchange(JSONObject(input.toString()).put("op", "liveTask").put("action", operation))
    }

    @JvmStatic
    @Throws(Exception::class)
    fun submit(prompt: String, foreground: String?, screen: Bitmap?): String =
        submit(prompt, foreground, screen, null)

    @JvmStatic
    @Throws(Exception::class)
    fun submit(prompt: String, foreground: String?, screen: Bitmap?, conversation: String?): String =
        submit(prompt, foreground, screen, conversation, null)

    @JvmStatic
    @Throws(Exception::class)
    fun submit(prompt: String, foreground: String?, screen: Bitmap?, conversation: String?, options: JSONObject?): String =
        submit(prompt, foreground, screen, conversation, options, null)

    @JvmStatic
    @Throws(Exception::class)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun submit(
        prompt: String,
        foreground: String?,
        screen: Bitmap?,
        conversation: String?,
        options: JSONObject?,
        beforePublish: BeforePublish?,
    ): String {
        val attachments = options?.optJSONArray("attachments")
        if (prompt.length > 16_000 || (prompt as java.lang.String).isBlank() &&
            (conversation == null || attachments == null || attachments.length() == 0)) {
            throw IOException("INVALID_TEXT")
        }
        if (conversation != null && !conversationId.matches(conversation)) {
            throw IOException("Invalid conversation identifier")
        }
        return withNewTask("Task directory unavailable") { directory, id, createdAt ->
            val screenshot = if (screen == null) "" else {
                val image = File(directory, "context.png")
                FileOutputStream(image).use { output ->
                    screen.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.fd.sync()
                }
                image.absolutePath
            }
            val request = JSONObject()
                .put("id", id)
                .put("prompt", prompt)
                .put("foreground", foreground)
                .put("screenshot", screenshot)
                .put("createdAt", createdAt)
            if (conversation != null) {
                request.put("conversationId", conversation)
                if (options != null) appendOptions(request, options, conversation)
            }
            write(File(directory, "request.json"), request.toString())
            write(File(directory, "state.json"), JSONObject()
                .put("id", id)
                .put("state", "queued")
                .put("prompt", prompt)
                .put("message", "Queued for background execution")
                .toString())
            write(File(TaskRuntime.ROOT, "latest.id"), id)
            beforePublish?.persist(id)
            write(File(directory, "ready"), "1")
            id
        }
    }

    private fun appendOptions(request: JSONObject, options: JSONObject, conversation: String) {
        request.put("includeScreenshot", options.optBoolean("includeScreenshot", false))
        val style = options.optString("responseStyle", "concise")
        if (style != "concise" && style != "detailed") throw IOException("Invalid response style")
        request.put("responseStyle", style)
        for (key in optionalFields) {
            if (options.has(key)) request.put(key, options.opt(key))
        }
        if (options.has("attachments")) request.put("attachments", options.getJSONArray("attachments"))
        if (options.has("composerRequestId")) {
            request.put("composerRequestId", PanelProtocol.uuid(options, "composerRequestId"))
        }
        if (options.has("panelUpdate")) {
            val target = options.getJSONObject("panelUpdate")
            PanelProtocol.exact(target, panelUpdateFields)
            PanelProtocol.uuid(target, "panelId")
            val origin = PanelProtocol.uuid(target, "originThreadId")
            PanelProtocol.uuid(target, "requestId")
            val matterId = target.getString("matterId")
            AgentGatewayProtocol.matterIdentity(matterId)
            PanelProtocol.number(target, "sourceVersion")
            if (conversation != origin || !matterId.startsWith("$conversation/")) throw IOException("INVALID_ARGUMENT")
            request.put("panelUpdate", JSONObject(target.toString()))
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun control(method: String, params: JSONObject?): JSONObject =
        withNewTask("Conversation service unavailable") { directory, id, _ ->
            write(File(directory, "request.json"), JSONObject()
                .put("id", id)
                .put("kind", "agent_control")
                .put("prompt", "")
                .put("method", method)
                .put("params", params)
                .toString())
            write(File(directory, "state.json"), JSONObject()
                .put("id", id)
                .put("state", "queued")
                .put("prompt", "")
                .put("message", "")
                .toString())
            write(File(directory, "ready"), "1")

            val response = File(directory, "response.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (method.startsWith("skills/community/")) 120 else 40)
            while (!response.exists()) {
                if (System.nanoTime() > deadline) throw IOException("Conversation service connection timed out")
                Thread.sleep(60)
            }
            FileInputStream(response).use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > 2_097_152) {
                        throw IOException("Conversation service response exceeds the safety limit; native conversation data was not changed")
                    }
                }
                val answer = JSONObject(bytes.toString("UTF-8"))
                if (!answer.optBoolean("ok")) {
                    throw IOException(answer.optString("message", "Conversation service unavailable"))
                }
                answer.getJSONObject("result")
            }
        }

    private inline fun <T> withNewTask(error: String, block: (File, String, Long) -> T): T {
        TaskRuntime.TASKS.mkdirs()
        val now = System.currentTimeMillis()
        for (attempt in 0 until 100) {
            val id = (now + attempt).toString()
            val directory = File(TaskRuntime.TASKS, id)
            if (directory.mkdir()) return block(directory, id, now)
        }
        throw IOException(error)
    }
}
