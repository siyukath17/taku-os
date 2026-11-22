package org.agentos.assistant

import android.content.Context
import java.io.File
import java.io.IOException
import org.agentos.lab.ComposerInput
import org.json.JSONObject

internal object ComposerDelivery {
    @JvmStatic
    fun createThread(): JSONObject = TaskRequests.control("thread/start", JSONObject())

    @JvmStatic
    fun move(context: Context, input: JSONObject): JSONObject {
        val target = input.getString("targetThreadId")
        val loaded = TaskRequests.control("thread/read", JSONObject().put("threadId", target))
        if (loaded.optBoolean("threadUnavailable") || !loaded.optBoolean("loaded") || target != loaded.optString("threadId"))
            throw IOException("THREAD_UNAVAILABLE")
        val native = NativeClient.state(target)
        if (!native.optBoolean("ready")) throw IOException("THREAD_UNAVAILABLE")
        val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
        if (!loaded.optBoolean("freshEmpty") || native.optString("activeTurnId").isNotEmpty() || !prefs.getString("task-$target", "").isNullOrEmpty())
            throw IOException("DRAFT_TARGET_OCCUPIED")
        return ComposerState.move(prefs, input.getString("revision"), target)
    }

    @JvmStatic
    fun restoreTask(context: Context, input: JSONObject): JSONObject {
        val thread = input.getString("threadId")
        val directory = File(TaskRuntime.TASKS, input.getString("taskId"))
        val request = try { SourceAdapters.readObject(File(directory, "request.json")) }
            catch (_: Exception) { throw IOException("TASK_FAILURE_CHANGED") }
        if (request.optString("conversationId") != thread || thread.isEmpty()) throw IOException("TASK_FAILURE_CHANGED")
        val state = try { SourceAdapters.readObject(File(directory, "state.json")) }
            catch (_: Exception) { throw IOException("TASK_FAILURE_CHANGED") }
        val status = state.optString("state")
        if (status != "failed" && status != "needs_user") throw IOException("TASK_FAILURE_CHANGED")
        return ComposerState.restoreTask(context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE), thread, input.getString("expectedRevision"), request)
    }

    @JvmStatic
    fun call(context: Context, method: String, input: JSONObject): JSONObject {
        val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
        val runtime = MatterRuntime.get(context)
        val id = input.getString("requestId")
        val thread = input.getString("threadId")
        if (method == AgentGatewayProtocol.COMPOSER_SUBMIT) {
            val existing = ComposerState.submission(prefs, id)
            val options = if (existing == null) ComposerOptions.prepareSubmission() else null
            val (request, created) = try {
                ComposerState.reserveSubmission(prefs, input,
                    optionsFor = { target, output ->
                        checkNotNull(options).apply(prefs, target, output)
                        AssistantInvocationCapture.reserve(context, id, output)
                    },
                    autoScreenshot = { AssistantInvocationCapture.consumeAutoScreenshot(context) })
            } catch (failure: Exception) {
                synchronized(ComposerPreferences::class.java) {
                    if (ComposerState.submission(prefs, id) == null) AssistantInvocationCapture.discard(context, id)
                }
                throw failure
            }
            if (!created) return runtime.panels.composerReceipt(request)
            val services = runtime.services
            val receipt = when (request.getString("action")) {
                "continue" -> runtime.panels.continueComposer(request)
                "steer" -> steer(services, request)
                else -> services.submit(request)
            }
            finish(context, prefs, request, receipt)
            return receipt
        }
        val request = ComposerState.submission(prefs, id)
        if (request == null) {
            if (method == AgentGatewayProtocol.COMPOSER_RECEIPT && input.has("action")) return runtime.panels.composerReceipt(input)
            if (method == AgentGatewayProtocol.COMPOSER_RECEIPT) return JSONObject().put("threadId", thread).put("requestId", id).put("state", "missing")
            throw IOException("REQUEST_UNAVAILABLE")
        }
        if (request.getString("threadId") != thread) throw IOException("REQUEST_ID_REUSED")
        val receipt = runtime.panels.composerReceipt(request)
        if (method == AgentGatewayProtocol.COMPOSER_SETTLE) finish(context, prefs, request, receipt)
        if (method == AgentGatewayProtocol.COMPOSER_RELEASE) {
            if (receipt.optString("state") in setOf("queued", "accepted", "notSent")) finish(context, prefs, request, receipt)
            else ComposerState.releaseSubmission(prefs, thread, id)
            return ComposerState.read(prefs, thread)
        }
        return receipt
    }

    private fun finish(context: Context, prefs: android.content.SharedPreferences, request: JSONObject, receipt: JSONObject) {
        val state = receipt.optString("state")
        if (state == "queued" || state == "accepted") ComposerState.finishSubmission(prefs, request, true)
        else if (state == "notSent") ComposerState.finishSubmission(prefs, request, false)
        AssistantInvocationCapture.settle(context, request, receipt)
        try { ComposerAttachments.sweep(context) } catch (_: Exception) { }
    }

    private fun steer(services: PanelNativeServices, request: JSONObject): JSONObject {
        val thread = request.getString("threadId")
        val turn = request.getString("expectedTurnId")
        val text = request.getString("text")
        val input = ComposerInput.text(text)
        ComposerInput.appendAttachments(input, request.getJSONObject("options").getJSONArray("attachments"))
        return services.submissions.steer(request.getString("requestId"), thread, turn, text, {
            if (turn != NativeClient.state(thread).optString("activeTurnId")) throw IOException("TURN_CHANGED")
            services.appendScreenshot(request, input)
        }, {
            NativeClient.rpcSync("turn/steer", JSONObject().put("threadId", thread).put("expectedTurnId", turn).put("input", input))
        }, object : AgentGatewaySubmissions.Fallback {
            override fun check() {
                services.checkScreenshot(request)
                repeat(20) { attempt ->
                    try { services.checkSubmit(thread); return }
                    catch (busy: IOException) {
                        if (attempt == 19 || busy.message !in setOf("THREAD_BUSY", "THREAD_QUEUED")) throw busy
                        Thread.sleep(250)
                    }
                }
            }
            override fun publish(before: TaskRequests.BeforePublish): String = services.publish(request, before)
            override fun published(taskId: String): Boolean = taskId.matches(Regex("[0-9]{1,20}")) && File(File(TaskRuntime.TASKS, taskId), "ready").isFile
        })
    }
}
