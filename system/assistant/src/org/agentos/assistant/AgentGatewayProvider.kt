package org.agentos.assistant

import android.content.ContentProvider
import android.content.Intent
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Signed ordinary-UID UI to system-owned runtime boundary. */
class AgentGatewayProvider : ContentProvider() {
    private val calls = Semaphore(4)
    private val streams = LinkedHashMap<String, SessionStream>()
    private val streamExpiry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agent-stream-expiry").apply { isDaemon = true }
    }
    private val reactionPushes = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-reaction-push").apply { isDaemon = true }
    }

    private class SessionStream(val uid: Int, val request: JSONObject) {
        val created = SystemClock.elapsedRealtime()
        var opened = false
        /** The worker and out-of-band events (reactions) write whole lines, one at a time. */
        val writeLock = Any()
        /**
         * The epoch and sequence of the latest native event already written, which an out-of-band
         * event repeats. Both change only under [writeLock], after the event's line is written.
         */
        var lastEpoch = ""
        var lastSeq = -1L
        @Volatile var closed = false
        var socket: LocalSocket? = null
        var output: ParcelFileDescriptor? = null

        @Synchronized
        fun close() {
            closed = true
            try { socket?.close() } catch (_: IOException) { }
            try { output?.close() } catch (_: IOException) { }
        }
    }

    private val payloads = LinkedHashMap<String, Payload>()
    private val completeHistory = ConcurrentHashMap<String, Set<String>>()
    private val sessionPreviewCatalog = ChatWorkspacePreviewProtocol.VerifiedCatalog()
    private val homePreviewCatalog = ChatWorkspacePreviewProtocol.VerifiedCatalog()
    private lateinit var submissions: AgentGatewaySubmissions
    private lateinit var matters: MatterRuntime
    private lateinit var matterStreams: MatterStreams

    private class Payload(val bytes: ByteArray) {
        val created = SystemClock.elapsedRealtime()
    }

    override fun onCreate(): Boolean {
        submissions = AgentGatewaySubmissions.get(context!!.filesDir)
        matters = MatterRuntime.get(context!!)
        matterStreams = MatterStreams(matters)
        // A reaction decided after its message was shown reaches open chat streams at once. Pushes
        // run on their own thread: a stalled stream must not hold Jev or an Agent's reactions/set.
        MessageReactions.listener = { thread, turn, item, emoji -> reactionPushes.execute { pushReaction(thread, turn, item, emoji) } }
        return true
    }

    /**
     * `session/reaction` on every open stream of [thread]: out of band, it repeats the stream's
     * last epoch and sequence instead of taking a new one, and the chat applies it in place.
     */
    private fun pushReaction(thread: String, turn: String, item: String, emoji: String) {
        val targets = synchronized(this) { streams.values.filter { it.opened && !it.closed && it.request.optString("threadId") == thread } }
        for (stream in targets) try {
            synchronized(stream.writeLock) {
                // Read under the lock, so the repeated sequence is one the chat has already received.
                if (stream.lastSeq < 0 || stream.lastEpoch.isEmpty()) return@synchronized
                val event = JSONObject().put("schema", "taku.session-events/1").put("threadId", thread)
                    .put("brokerEpoch", stream.lastEpoch).put("seq", stream.lastSeq).put("method", "session/reaction")
                    .put("params", JSONObject().put("turnId", turn).put("itemId", item).put("reaction", emoji))
                writeEvent(stream, (event.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: Exception) { }
    }

    private fun enforceCaller() {
        context!!.enforceCallingPermission(AgentGatewayProtocol.PERMISSION, "Signed Agent UI required")
        val manager = context!!.packageManager
        val uid = Binder.getCallingUid()
        val names = manager.getPackagesForUid(uid)
        if (uid == android.os.Process.SYSTEM_UID || names == null || names.size != 1 ||
            AgentGatewayProtocol.CLIENT_PACKAGE != names[0] ||
            manager.checkSignatures(context!!.packageName, names[0]) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Agent client identity mismatch")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        if (method.startsWith("composer/") || method.startsWith("workspaces/") || method.startsWith("capture/") ||
            method.startsWith("accounts/") || method.startsWith("requests/") || method.startsWith("live/") ||
            method in setOf("getWorkspaces", "openWorkspace", "assistCapture", "assistInvocation", "readTaskDetail", "manageSystemAi", "manageAssistant")) {
            ComposerState.userError(context!!, callerUid)?.let { return error(Bundle(), it) }
        }
        if ("nativeMatter" == method || "nativeAudio" == method) {
            val uid = Binder.getCallingUid()
            if (uid != 0 && uid != android.os.Process.SYSTEM_UID && uid != android.os.Process.SHELL_UID)
                throw SecurityException("Native runtime only")
        } else enforceCaller()
        val output = Bundle()
        if (!calls.tryAcquire()) return error(output, "BUSY")
        val callerPid = Binder.getCallingPid()
        val identity = Binder.clearCallingIdentity()
        try {
            if (method == "assistCapture" || method == "assistInvocation") {
                if (arg == null || arg.length > 4096) throw IOException("INVALID_ARGUMENT")
                val result = if (method == "assistCapture") AssistantCaptureBackend.receive(context!!, JSONObject(arg), extras)
                    else AssistantInvocationCapture.receive(context!!, JSONObject(arg), extras)
                output.putString("result", JSONObject().put("ok", true).put("result", result).toString())
                return output
            }
            val request: JSONObject
            if ("nativeMatter" == method || "nativeAudio" == method) {
                if (arg == null || arg.length > AgentGatewayProtocol.MAX_REQUEST) throw IOException("REQUEST_TOO_LARGE")
                request = JSONObject(arg)
            } else request = AgentGatewayProtocol.request(method, arg)
            val result = when {
                method == AgentGatewayProtocol.COMPOSER_ATTACH -> ComposerAttachments.attach(context!!, request, extras, callerUid, callerPid)
                method.startsWith("accounts/") -> AssistantAccounts.command(context!!, method, request, extras, challenge = true)
                method == "manageAssistant" -> AssistantManagement.execute(context!!, request, extras)
                method.startsWith("requests/") -> AssistantRequests.execute(method, request, extras, trusted = true)
                method in TakuConnectGateway.ACTIONS -> TakuConnectGateway.execute(method, request, extras)
                else -> execute(method, request, callerUid)
            }
            val envelope = JSONObject().put("ok", true).put("result", result)
            val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_RESULT_BYTES) return error(output, "RESPONSE_TOO_LARGE")
            if (bytes.size <= INLINE_BYTES) output.putString("result", envelope.toString())
            else {
                val token = retain(bytes)
                output.putString("result", JSONObject().put("ok", true).put("resultUri", uri("result", token).toString()).toString())
            }
        } catch (conflict: ComposerState.RevisionConflict) {
            error(output, "DRAFT_REVISION_CONFLICT", conflict.revision)
        } catch (failure: Exception) {
            val code = failure.message
            error(output, if (code != null && java.util.regex.Pattern.matches("[A-Z][A-Z_]{2,80}", code)) code else "RUNTIME_UNAVAILABLE")
        } finally {
            Binder.restoreCallingIdentity(identity)
            calls.release()
        }
        return output
    }

    private fun error(output: Bundle, code: String, revision: String? = null): Bundle {
        try {
            val envelope = JSONObject().put("ok", false).put("code", code).put("message", code)
            if (revision != null) envelope.put("revision", revision)
            output.putString("result", envelope.toString())
        } catch (_: Exception) { }
        return output
    }

    private fun preferences(): SharedPreferences = context!!.getSharedPreferences("agent-settings", 0)

    private fun execute(action: String, input: JSONObject, callerUid: Int): JSONObject {
        val thread = input.optString("threadId")
        when (action) {
            "nativeMatter" -> return matters.nativeAction(input)
            "panels" -> return matters.panels.call(input.getString("operation"), input.getJSONObject("args"))
            "cardView" -> return matters.panels.cardView(input.getString("operation"), input.getJSONObject("args"))
            "readMatter" -> return matters.read(input.getString("matterId"))
            "subscribeMatter" -> return matterStreams.subscribe(input.getJSONArray("matterIds"), callerUid)
            "unsubscribeMatter" -> return matterStreams.unsubscribe(input.getString("subscriptionId"), callerUid)
            "dispatchMatter" -> return matters.dispatch(input)
            "readTaskDetail" -> return TaskDetails.read(context!!.filesDir, input.getString("taskId"))
            "capture/read", "capture/accept", "capture/dismiss", "capture/request", "capture/openConversation" ->
                return AssistantCaptureBackend.execute(context!!, action, input)
            "workspaces/list", "workspaces/apps", "workspaces/configure", "workspaces/release", "workspaces/preview", "workspaces/open",
            "workspaces/pause", "workspaces/takeControl", "workspaces/input", "workspaces/returnAndResume", "workspaces/addRequirement", "workspaces/executionStatus" ->
                return WorkspaceCommands.call(context!!, action, input, callerUid)
            "analyzeCameraFrame" -> return CameraGuide.analyze(input)
            "audioCapabilities" -> return AudioSessionBridge.get(context!!).capabilities()
            "audioCommand" -> return audioCommand(input)
            "nativeAudio" -> {
                val params = JSONObject(input.toString())
                val operation = params.getString("action")
                params.remove("action")
                val nativeThread = params.optString("threadId")
                params.remove("threadId")
                if (operation == "capabilities") {
                    if (params.length() != 0) throw IOException("UNSUPPORTED_PARAMETER")
                    return AudioSessionBridge.get(context!!).capabilities()
                }
                if (operation != "command") throw IOException("UNSUPPORTED_AUDIO_ACTION")
                val identity = AgentGatewayProtocol.matterIdentity(params.getString("matterId"))
                if (identity[0] != nativeThread) throw IOException("AUDIO_MATTER_MISMATCH")
                return audioCommand(AgentGatewayProtocol.request("audioCommand", params.toString()))
            }
            "readSystemAi", "setSystemAi", "bindSystemAi", "bindSystemAiModel", "reorderSystemAi", "restoreSystemAi",
            "deleteSystemAiGroup", "manageSystemAi" ->
                return SystemAiUiBridge.execute(context!!, action, input)
            "openMagpieManagement" -> {
                AssistantAccounts.openMagpie(context!!)
                return JSONObject().put("opened", true)
            }
            "readAppPermissions", "setAppPermissionMode", "setAppPermissionScope", "resetAppPermission" ->
                return AppPermissionUiBridge.execute(context!!, action, input)
            "readAccount" -> return AccountStatus.readWithDeviceCode()
            "openAssistantConnection" -> {
                if (!AgentFrontendLauncher.openRoute(context!!, "/system-ai?section=connection")) throw IOException("AGENT_UI_UNAVAILABLE")
                return JSONObject().put("opened", true)
            }
            "connectChatGpt" -> return AiConnections.startChatGpt(context!!, "chatgptDeviceCode", false, true, true)
            "cancelChatGpt" -> return AiConnections.cancelChatGpt()
            "readJev" -> return AiConnections.jevStatus(context!!)
            "connectJev" -> return AiConnections.connectJev(context!!, input.getString("key"))
            "enableJev" -> return AiConnections.enableJev(context!!)
            // Foreground warm-up of the on-device agent: the broker answers at once and decides (ACP
            // default, signed in, empty pool, 5s interval); the warm-up runs in its background.
            "prewarmAgent" -> return NativeClient.exchange(JSONObject().put("op", "acpPrewarm").put("surface", input.getString("surface")))
            "listThreads" -> return AgentGatewayProtocol.clean(TaskRequests.control("thread/list", JSONObject()), 0) as JSONObject
            "createThread" -> {
                val result = ComposerDelivery.createThread()
                val created = result.getJSONObject("thread")
                completeHistory[created.getString("id")] = historyIds(created)
                return AgentGatewayProtocol.clean(result, 0) as JSONObject
            }
            "resumeThread" -> {
                sessionPreviewCatalog.invalidate()
                val resumed = TaskRequests.control("thread/read", JSONObject().put("threadId", thread))
                if (resumed.optBoolean("threadUnavailable") || !resumed.optBoolean("loaded") || thread != resumed.optString("threadId"))
                    throw IOException("THREAD_UNAVAILABLE")
                val selected = snapshot(thread, false)
                if (!preferences().edit().putString("conversation", thread).commit()) throw IOException("SELECTION_SAVE_FAILED")
                return selected
            }
            "readSession" -> return snapshot(thread, input.optBoolean("refresh"), input.optBoolean("statusOnly"))
            "subscribeSession" -> return subscribe(input, callerUid)
            "unsubscribeSession" -> return unsubscribe(input.getString("subscriptionId"), callerUid)
            AgentGatewayProtocol.COMPOSER_READ -> return ComposerState.read(preferences(), thread)
            AgentGatewayProtocol.COMPOSER_WRITE -> return ComposerState.write(preferences(), thread, input.getString("revision"), input.getString("text"))
            AgentGatewayProtocol.COMPOSER_IMPORT -> return ComposerState.importLegacy(preferences(), thread, input.getString("text"))
            AgentGatewayProtocol.COMPOSER_MOVE -> return ComposerDelivery.move(context!!, input)
            AgentGatewayProtocol.COMPOSER_RECEIPT, AgentGatewayProtocol.COMPOSER_SUBMIT,
            AgentGatewayProtocol.COMPOSER_SETTLE, AgentGatewayProtocol.COMPOSER_RELEASE -> return ComposerDelivery.call(context!!, action, input)
            AgentGatewayProtocol.COMPOSER_REMOVE -> return ComposerState.removeAttachment(preferences(), thread, input.getString("expectedRevision"), input.getString("attachmentId"))
            AgentGatewayProtocol.COMPOSER_OPTIONS_READ, AgentGatewayProtocol.COMPOSER_OPTIONS_WRITE -> return ComposerOptions.call(context!!, action, input)
            AgentGatewayProtocol.COMPOSER_CREATE_THREAD -> return AgentGatewayProtocol.clean(ComposerDelivery.createThread(), 0) as JSONObject
            AgentGatewayProtocol.COMPOSER_HANDOFF -> return ComposerState.handoff(preferences(), thread, input.getString("handoffId"), input.getString("text"))
            AgentGatewayProtocol.COMPOSER_RESTORE -> return ComposerState.restore(preferences(), thread, input.getString("expectedRevision"), input.getString("recoveryId"))
            AgentGatewayProtocol.COMPOSER_PRESERVE_DRAFT -> return ComposerState.preserveDraft(preferences(), thread, input.getString("recoveryId"), input.getString("text"))
            AgentGatewayProtocol.COMPOSER_RESTORE_TASK -> return ComposerDelivery.restoreTask(context!!, input)
            "sendMessage" -> return submit(input)
            "steer" -> return submissions.steer(input.getString("requestId"), thread, input.getString("expectedTurnId"), input.getString("text"),
                object : AgentGatewaySubmissions.SteerRoute {
                    @Throws(Exception::class)
                    override fun check() {
                        val state = NativeClient.state(thread)
                        if (input.getString("expectedTurnId") != state.optString("activeTurnId")) throw IOException("TURN_CHANGED")
                    }
                    @Throws(Exception::class)
                    override fun steer(): JSONObject = NativeClient.rpcSync("turn/steer", JSONObject().put("threadId", thread)
                        .put("expectedTurnId", input.getString("expectedTurnId")).put("input", textInput(input.getString("text"))))
                },
                // An ACP turn is not a Codex turn: the broker steers it (TaskRequests.acpSteer).
                TaskRequests.acpSteer(thread, input.getString("expectedTurnId"), input.getString("requestId"), input.getString("text"), null),
                object : AgentGatewaySubmissions.Fallback {
                @Throws(Exception::class)
                override fun check() { checkSubmitAfterTurn(thread) }
                @Throws(Exception::class)
                override fun publish(before: TaskRequests.BeforePublish): String = publishSubmit(thread, input.getString("text"), false, before)
                override fun published(taskId: String): Boolean = java.util.regex.Pattern.matches("[0-9]+", taskId) && File(File(TaskRuntime.TASKS, taskId), "ready").isFile
            })
            "cancelQueued" -> return TaskRequests.cancelQueued(thread, input.getString("taskId"))
            "continueTask" -> return TaskRequests.continueTask(thread, input.getString("taskId")) { task ->
                if (!preferences().edit().putString("task-$thread", task).commit()) throw IOException("RECEIPT_SAVE_FAILED")
            }
            "interrupt" -> return TaskRequests.interrupt(thread, input.getString("turnId"))
            AgentGatewayProtocol.LIVE_TASK_READ, AgentGatewayProtocol.LIVE_TASK_REPLACE, AgentGatewayProtocol.LIVE_TASK_CANCEL ->
                return TaskRequests.liveTask(submissions, input, action)
            "answer" -> {
                AgentGatewayProtocol.pending(NativeClient.state(thread), thread, input.getString("key"))
                // The broker validates the current request/turn and consumes the answer atomically.
                return NativeClient.exchange(JSONObject().put("op", "respond").put("key", input.getString("key")).put("response", input.getJSONObject("response")))
            }
            "getWorkspaces" -> return workspaces(if (input.has("scope")) null else thread)
            "openWorkspace" -> {
                val fromHome = input.optString("source") == "home"
                val lease = exactLease(thread, input.getString("screenKey"), input.getString("generation"), fromHome)
                // A background viewer may have another lease. Deliver the selected lease to onNewIntent.
                val intent = Intent(context!!, MirrorActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                for (key in arrayOf("threadId", "screenKey", "generation")) intent.putExtra(key, lease.getString(key))
                if (fromHome) {
                    intent.putExtra(AgentFrontendLauncher.RETURN_WORKSPACE_HOME, true)
                }
                context!!.startActivity(intent)
                return JSONObject().put("opened", true)
            }
            "openSettings" -> {
                val route = when (input.optString("page", "models")) {
                    "workspaces" -> "/workspaces"
                    "system" -> "/system-ai"
                    "account" -> "/system-ai?section=account"
                    else -> "/system-ai?section=connection"
                }
                if (!AgentFrontendLauncher.openRoute(context!!, route)) throw IOException("AGENT_UI_UNAVAILABLE")
                return JSONObject().put("opened", true)
            }
            "pluginManager" -> return PluginManagerBridge.execute(input)
            "decide" -> {
                // The same `jev` scene binding the Jev settings page writes (the old `jevModel` pref is migrated away).
                // No binding: Jev was never set up (e.g. skipped in onboarding), so the card offers its setup page.
                // A binding with the switch off: the user turned Jev off, which the card respects without asking.
                val status = AiConnections.jevStatus(context!!)
                if (status.optString("model").isEmpty()) throw IOException("JEV_NOT_CONFIGURED")
                if (!status.optBoolean("enabled")) throw IOException("JEV_DISABLED")
                val model = AiConnections.jevModel(context!!)
                if (model.isEmpty()) throw IOException("JEV_NOT_CONFIGURED")
                return AgentGatewayProtocol.clean(JevApiClient().decide(model, input.getJSONObject("state"), input.getJSONObject("questions"), true), 0) as JSONObject
            }
            "generateUi" -> return AgentGatewayProtocol.clean(NativeClient.exchange(JSONObject().put("op", "ui")
                .put("instructions", input.getString("instructions")).put("prompt", input.getString("prompt"))), 0) as JSONObject
            "mainReply" -> {
                val params = JSONObject(input.toString())
                params.remove("action")
                return AgentGatewayProtocol.replyRead(params) { request -> NativeClient.exchange(request) }
            }
            else -> throw IOException("UNSUPPORTED_ACTION")
        }
    }

    private fun audioCommand(input: JSONObject): JSONObject = matters.retain(input.getString("matterId")).use {
        retainedAudioCommand(input)
    }

    private fun retainedAudioCommand(input: JSONObject): JSONObject {
        val matterId = input.getString("matterId")
        val requestId = input.getString("requestId")
        val thread = AgentGatewayProtocol.matterIdentity(matterId)[0]
        matters.read(matterId)
        val params = JSONObject(input.toString()).put("threadId", thread)
        params.remove("requestId")
        if (input.getString("operation") == "status") {
            val source = AudioSessionBridge.get(context!!).command(params)
            matters.registerAudio(matterId, source)
            return JSONObject().put("source", source)
        }
        val receipt = submissions.steer(requestId, thread, "local-media-session", input.toString(), {
            matters.read(matterId)
            if (params.optString("operation") == "start" && params.optString("purpose") == "MEDIA_TRANSLATE")
                params.put("targetPackage", AudioPlaybackTarget.resolve(context!!, params.optString("targetPackage")))
        }, { AudioSessionBridge.get(context!!).command(params) })
        receipt.put("effect", "local-media-session")
        val result = JSONObject().put("receipt", receipt)
        var source = receipt.optJSONObject("result")
        if (source != null && source.has("resourceId")) {
            source = AudioSessionBridge.get(context!!).read(JSONObject().put("resourceId", source.getString("resourceId"))
                .put("threadId", thread).put("matterId", matterId))
            matters.registerAudio(matterId, source)
            result.put("source", source)
        }
        return result
    }

    private fun snapshot(threadId: String, refresh: Boolean): JSONObject = snapshot(threadId, refresh, false)

    private fun snapshot(threadId: String, refresh: Boolean, statusOnly: Boolean): JSONObject {
        val projection = File(TaskRuntime.ROOT, "agent-view/" + threadId + ".json")
        val nativeState = try { NativeClient.state(threadId) } catch (_: Exception) { JSONObject().put("ready", false) }
        val result: JSONObject
        if (statusOnly) result = JSONObject().put("thread", JSONObject().put("id", threadId))
            .put("native", AgentGatewayProtocol.nativeView(nativeState, threadId)).put("statusOnly", true)
        else {
            var thread = if (projection.isFile) readObject(projection) else null
            var historyError = ""
            if (refresh && nativeState.optString("activeTurnId").isEmpty()) {
                try {
                    val refreshed = NativeClient.rpcSync("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", true))
                    thread = refreshed.getJSONObject("thread")
                    if (threadId != thread.optString("id")) throw IOException("THREAD_ID_MISMATCH")
                    if (thread.has("turns")) completeHistory[threadId] = historyIds(thread)
                } catch (_: Exception) { historyError = "HISTORY_REFRESH_UNAVAILABLE" }
            }
            if (thread == null) throw IOException("THREAD_UNAVAILABLE")
            if (threadId != thread.optString("id")) throw IOException("THREAD_ID_MISMATCH")
            val proof = completeHistory[threadId]
            var complete = proof != null && historyIds(thread).containsAll(proof)
            val projectionInfo = thread.optJSONObject("_projection")
            if (projectionInfo != null) complete = projectionInfo.optBoolean("historyComplete", complete)
            if (complete) completeHistory[threadId] = historyIds(thread)
            val turns = thread.optJSONArray("turns")
            matters.observeThread(thread)
            MessageReactions.observe(context!!, thread)
            val view = AgentGatewayProtocol.threadView(thread)
            MessageReactions.decorate(context!!, view)
            result = JSONObject().put("thread", view)
                .put("native", AgentGatewayProtocol.nativeView(nativeState, threadId))
                .put("historyComplete", complete).put("historyStatus", if (complete) "complete" else "unknown")
                .put("knownEmpty", complete && turns != null && turns.length() == 0).put("totalTurns", if (turns == null) 0 else turns.length())
            if (historyError.isNotEmpty()) result.put("historyError", historyError)
        }
        val taskId = preferences().getString("task-" + threadId, "")!!
        if (java.util.regex.Pattern.matches("[0-9]+", taskId)) {
            val task = File(TaskRuntime.TASKS, taskId)
            val state = File(task, "state.json")
            val request = File(task, "request.json")
            val runtime = File(task, "runtime-info.json")
            if (!request.isFile || threadId != readObject(request).optString("conversationId")) return result
            if (state.isFile) result.put("task", AgentGatewayProtocol.clean(readObject(state)
                .put("published", File(task, "ready").isFile).put("cancelRequested", File(task, "cancel").isFile).put("turnStarted", false), 0))
            if (request.isFile) {
                val raw = readObject(request)
                if (threadId == raw.optString("conversationId")) result.put("request", JSONObject().put("prompt", raw.optString("prompt")))
            }
            if (runtime.isFile) {
                val raw = readObject(runtime)
                val visible = JSONObject()
                for (key in arrayOf("turnStarted", "turnId", "threadId", "conversationId")) if (raw.has(key)) visible.put(key, raw.get(key))
                result.put("runtime", visible)
                if (result.has("task")) result.getJSONObject("task").put("turnStarted", raw.optBoolean("turnStarted"))
            }
        }
        return result
    }

    @Throws(Exception::class)
    private fun submit(input: JSONObject): JSONObject = matters.services.submit(input)

    private fun checkSubmit(thread: String) { matters.services.checkSubmit(thread) }

    private fun publishSubmit(thread: String, text: String, requireConfirmation: Boolean, before: TaskRequests.BeforePublish): String =
        matters.services.publish(JSONObject().put("threadId", thread).put("text", text).put("requireConfirmation", requireConfirmation), before)

    private fun checkSubmitAfterTurn(thread: String) {
        var attempt = 0
        while (true) {
            try { checkSubmit(thread); return } catch (busy: IOException) {
                if (attempt >= 19 || ("THREAD_BUSY" != busy.message && "THREAD_QUEUED" != busy.message)) throw busy
                Thread.sleep(250)
            }
            attempt++
        }
    }

    private fun workspaceResult(screens: JSONArray, vararg previewPath: String): JSONObject {
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            screen.put("previewUri", uri(*previewPath, screen.getString("threadId"),
                screen.getString("screenKey"), screen.getString("generation")).toString())
        }
        return JSONObject().put("schema", "taku.agent-workspaces/1").put("screens", screens)
    }

    private fun workspaces(thread: String?): JSONObject {
        val previewCatalog = if (thread == null) homePreviewCatalog else sessionPreviewCatalog
        val revision = previewCatalog.beginRead()
        try {
            val request = JSONObject().put("op", "workspaces").put("action", "list")
            if (thread != null) request.put("threadId", thread)
            val catalog = NativeClient.exchange(request)
            val checkedAt = SystemClock.elapsedRealtime()
            val verified = ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog, thread, checkedAt, checkedAt)
            val screens = verified.screens(SystemClock.elapsedRealtime())
            val result = if (thread == null) workspaceResult(screens, "preview", "home")
                else workspaceResult(screens, "preview")
            // A stale read must never publish over the winner.
            previewCatalog.publish(revision, verified)
            return result
        } catch (failure: Exception) {
            previewCatalog.failedRead(revision)
            throw failure
        }
    }

    private fun exactLease(thread: String, key: String, generation: String, fromHome: Boolean): JSONObject {
        val screens = workspaces(if (fromHome) null else thread).getJSONArray("screens")
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            if (thread == screen.optString("threadId") && key == screen.optString("screenKey") &&
                generation == screen.optString("generation")) return screen
        }
        throw IOException("SCREEN_LEASE_CHANGED")
    }

    private fun preview(previewCatalog: ChatWorkspacePreviewProtocol.VerifiedCatalog,
                        thread: String, key: String, generation: String): ByteArray {
        val lease = previewCatalog.lease(thread, key, generation, SystemClock.elapsedRealtime())
        val binding = lease.binding
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("taku-mirror-" + key, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 2500
                val input = DataInputStream(socket.inputStream)
                val meta = JSONObject(String(mirrorCall(socket, input, "status", 65536), StandardCharsets.UTF_8))
                ChatWorkspacePreviewProtocol.validateHost(binding, meta, socket.peerCredentials.uid, socket.peerCredentials.pid)
                val frame = mirrorCall(socket, input, "frame", ChatWorkspacePreviewProtocol.MAX_FRAME_BYTES)
                val size = BitmapFactory.Options()
                size.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(frame, 0, frame.size, size)
                if (frame.size < 3 || (frame[0].toInt() and 255) != 255 || (frame[1].toInt() and 255) != 216 ||
                    size.outWidth != meta.getInt("width") || size.outHeight != meta.getInt("height")) throw IOException("INVALID_FRAME")
                previewCatalog.recheck(lease, SystemClock.elapsedRealtime())
                return frame
            }
        } catch (failure: Exception) {
            previewCatalog.failedLease(lease)
            throw failure
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceCaller()
        if ("r" != mode || AgentGatewayProtocol.AUTHORITY != uri.authority) throw FileNotFoundException("Read only")
        if (!calls.tryAcquire()) throw FileNotFoundException("BUSY")
        val callerUid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        try {
            val parts = uri.pathSegments
            if (parts.size == 2 && parts[0] == "events") return openSession(parts[1], callerUid)
            if (parts.size == 2 && parts[0] == "matter-events") return matterStreams.open(parts[1], callerUid)
            if (parts.size == 2 && (parts[0] == "audio" || parts[0] == "audio-transcript")) {
                val thread = uri.getQueryParameter("threadId")
                AgentGatewayProtocol.requireId(JSONObject().put("threadId", thread), "threadId")
                AgentGatewayProtocol.requireId(JSONObject().put("sessionId", parts[1]), "sessionId")
                if (parts[0] == "audio-transcript") return AudioSessionBridge.get(context!!).openTranscript(parts[1], thread!!)
                return AudioSessionBridge.get(context!!).openRecording(parts[1], uri.getQueryParameter("recordingId"), thread!!)
            }
            val bytes: ByteArray
            if (parts.size == 2 && parts[0] == "result") bytes = take(parts[1])
            else if (parts.firstOrNull() == "preview") {
                val home = parts.getOrNull(1) == "home"
                val offset = if (home) 2 else 1
                if (parts.size != offset + 3) throw IOException("UNKNOWN_RESOURCE")
                val lease = AgentGatewayProtocol.request("openWorkspace", JSONObject().put("threadId", parts[offset])
                    .put("screenKey", parts[offset + 1]).put("generation", parts[offset + 2]).toString())
                val previewCatalog = if (home) homePreviewCatalog else sessionPreviewCatalog
                bytes = preview(previewCatalog, lease.getString("threadId"), lease.getString("screenKey"),
                    lease.getString("generation"))
            } else if (parts.size == 2 && parts[0] == "attachment") {
                bytes = ComposerAttachments.thumbnail(context!!, uri.getQueryParameter("threadId").orEmpty(), parts[1])
            } else throw IOException("UNKNOWN_RESOURCE")
            return openPipeHelper(uri, getType(uri), null, bytes) { output, _, _, _, data ->
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream -> stream.write(data) }
                } catch (_: IOException) { }
            }
        } catch (_: Exception) {
            throw FileNotFoundException("RESOURCE_UNAVAILABLE")
        } finally {
            Binder.restoreCallingIdentity(identity)
            calls.release()
        }
    }

    @Synchronized
    private fun subscribe(request: JSONObject, uid: Int): JSONObject {
        pruneStreams()
        if (streams.size >= 4) throw IOException("STREAM_BUSY")
        val token = UUID.randomUUID().toString()
        streams[token] = SessionStream(uid, JSONObject(request.toString()))
        return JSONObject().put("streamUri", uri("events", token).toString()).put("subscriptionId", token)
    }

    @Synchronized
    private fun unsubscribe(token: String, uid: Int): JSONObject {
        val stream = streams[token]
        if (stream != null) {
            if (stream.uid != uid) throw IOException("STREAM_IDENTITY_MISMATCH")
            streams.remove(token)
            stream.close()
        }
        return JSONObject().put("closed", true)
    }

    private fun pruneStreams() {
        val now = SystemClock.elapsedRealtime()
        streams.values.removeIf { stream ->
            if (stream.closed || now - stream.created > (if (stream.opened) 300_000 else 30_000)) {
                stream.close()
                true
            } else false
        }
    }

    @Synchronized
    private fun openSession(token: String, uid: Int): ParcelFileDescriptor {
        pruneStreams()
        val stream = streams[token]
        if (stream == null || stream.opened || stream.uid != uid) throw IOException("STREAM_EXPIRED")
        stream.opened = true
        val pipe = ParcelFileDescriptor.createReliablePipe()
        stream.output = pipe[1]
        Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        val expiry = streamExpiry.schedule({ stream.close() }, 5, TimeUnit.MINUTES)
        val worker = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    val socket = NativeClient.openSessionStream(stream.request)
                    synchronized(stream) {
                        if (stream.closed) {
                            socket.close()
                            return@Thread
                        }
                        stream.socket = socket
                    }
                    BufferedInputStream(socket.inputStream).use { input ->
                        while (true) {
                            val line = readEventLine(input) ?: break
                            val event = AgentGatewayProtocol.sessionEvent(JSONObject(line), stream.request.getString("threadId"))
                            try {
                                val params = event.getJSONObject("params")
                                when (event.optString("method")) {
                                    "session/snapshot" -> matters.observeThread(params.getJSONObject("thread"))
                                    "session/reply" -> matters.observeReply(stream.request.getString("threadId"), params.getString("turnId"), params.getJSONObject("reply"))
                                    "turn/completed" -> matters.observeThread(JSONObject().put("id", stream.request.getString("threadId"))
                                        .put("turns", JSONArray().put(params.getJSONObject("turn"))))
                                }
                            } catch (failure: Exception) {
                                // The reply still streams to the chat; only its card record (saved edits,
                                // pin) is missing, so the chat retries quietly. Say why, for diagnosis.
                                android.util.Log.w("TakuMatters", "card projection unavailable for ${event.optString("method")}: ${failure.message}", failure)
                            }
                            // Reactions: new user messages get a Jev decision; known reactions ride along.
                            try {
                                val params = event.getJSONObject("params")
                                val threadId = stream.request.getString("threadId")
                                when (event.optString("method")) {
                                    "session/snapshot" -> params.getJSONObject("thread").let {
                                        MessageReactions.observe(context!!, it); MessageReactions.decorate(context!!, it)
                                    }
                                    "item/started", "item/completed" -> params.getJSONObject("item").let {
                                        MessageReactions.observeItem(context!!, threadId, params.getString("turnId"), it)
                                        MessageReactions.decorateItem(context!!, threadId, params.getString("turnId"), it)
                                    }
                                }
                            } catch (failure: Exception) {
                                android.util.Log.w("TakuReactions", "reaction projection skipped: ${failure.message}")
                            }
                            val bytes = (event.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                            if (bytes.size > MAX_RESULT_BYTES) throw IOException("STREAM_EVENT_TOO_LARGE")
                            synchronized(stream.writeLock) {
                                writeEvent(stream, bytes)
                                stream.lastEpoch = event.optString("brokerEpoch")
                                stream.lastSeq = event.optLong("seq", -1L)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // EOF/error requires reconnecting with a new baseline.
            } finally {
                expiry.cancel(false)
                stream.close()
                synchronized(this@AgentGatewayProvider) { streams.remove(token) }
            }
        }, "agent-session-stream")
        worker.isDaemon = true
        worker.start()
        return pipe[0]
    }

    @Synchronized
    private fun retain(bytes: ByteArray): String {
        prune()
        var total = bytes.size
        for (payload in payloads.values) total += payload.bytes.size
        if (payloads.size >= 8 || total > MAX_BUFFERED_BYTES) throw IOException("BUSY")
        val token = UUID.randomUUID().toString()
        payloads[token] = Payload(bytes)
        return token
    }

    @Synchronized
    private fun take(token: String): ByteArray {
        prune()
        val payload = payloads.remove(token) ?: throw IOException("RESULT_EXPIRED")
        return payload.bytes
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        payloads.values.removeIf { now - it.created > 30_000 }
    }

    override fun getType(uri: Uri): String {
        val path = uri.path!!
        return if (path.startsWith("/preview/") || path.startsWith("/attachment/")) "image/jpeg"
            else if (path.startsWith("/audio/")) "audio/mp4"
            else if (path.startsWith("/events/") || path.startsWith("/matter-events/") || path.startsWith("/audio-transcript/")) "application/x-ndjson"
            else "application/json"
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, order: String?): Cursor = throw UnsupportedOperationException()
    override fun insert(u: Uri, v: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private const val INLINE_BYTES = 256 * 1024
        private const val MAX_RESULT_BYTES = 8 * 1024 * 1024
        private const val MAX_BUFFERED_BYTES = 16 * 1024 * 1024

        private fun textInput(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", text))

        private fun mirrorCall(socket: LocalSocket, input: DataInputStream, command: String, limit: Int): ByteArray {
            socket.outputStream.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
            socket.outputStream.flush()
            val size = input.readInt()
            if (size < 0 || size > limit) throw IOException("INVALID_FRAME_SIZE")
            val result = ByteArray(size)
            input.readFully(result)
            return result
        }

        private fun writeEvent(stream: SessionStream, bytes: ByteArray) {
            val descriptor = stream.output!!.fileDescriptor
            val poll = StructPollfd()
            poll.fd = descriptor
            poll.events = OsConstants.POLLOUT.toShort()
            var offset = 0
            while (offset < bytes.size) {
                if (stream.closed) throw IOException("STREAM_CLOSED")
                try { offset += Os.write(descriptor, bytes, offset, bytes.size - offset) } catch (busy: ErrnoException) {
                    if (busy.errno != OsConstants.EAGAIN) throw busy
                    Os.poll(arrayOf(poll), 1000)
                }
            }
        }

        @Throws(IOException::class)
        private fun readEventLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1) break
                if (value == '\n'.code) return bytes.toString("UTF-8")
                bytes.write(value)
                if (bytes.size() > MAX_RESULT_BYTES) throw IOException("STREAM_EVENT_TOO_LARGE")
            }
            if (bytes.size() > 0) throw IOException("INCOMPLETE_STREAM_EVENT")
            return null
        }

        private fun uri(vararg parts: String): Uri {
            val builder = Uri.Builder().scheme("content").authority(AgentGatewayProtocol.AUTHORITY)
            for (part in parts) builder.appendPath(part)
            return builder.build()
        }

        private fun readObject(file: File): JSONObject {
            FileInputStream(file).use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > MAX_RESULT_BYTES) throw IOException("RESPONSE_TOO_LARGE")
                }
                return JSONObject(bytes.toString("UTF-8"))
            }
        }

        private fun historyIds(thread: JSONObject): Set<String> {
            val ids = HashSet<String>()
            val turns = thread.optJSONArray("turns")
            if (turns != null) for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                val id = turn.optString("id")
                ids.add("turn:" + id)
                val items = turn.optJSONArray("items")
                if (items != null) for (j in 0 until items.length()) ids.add("item:" + id + ":" + items.getJSONObject(j).optString("id"))
            }
            return ids
        }
    }
}
