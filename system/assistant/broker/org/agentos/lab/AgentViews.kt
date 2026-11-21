package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap

/** A disposable projection of native thread/turn/item objects, never a conversation database. */
internal class AgentViews {
    /** Rewrites a native thread before projection (ACP chat turns are merged here). */
    fun interface Overlay {
        @Throws(Exception::class)
        fun apply(thread: JSONObject): JSONObject
    }

    companion object {
        @JvmField @Volatile var overlay: Overlay? = null

        private fun overlaid(thread: JSONObject): JSONObject {
            val hook = overlay ?: return thread
            return try { hook.apply(thread) } catch (failure: Exception) {
                // A broken overlay must never fail a native projection save.
                System.err.println("agent view overlay: $failure")
                thread
            }
        }

        private var directoryReady = false
        @JvmField val ROOT = File(TaskBroker.ROOT, "agent-view")
        @JvmField val threads: MutableMap<String, JSONObject> = Collections.synchronizedMap(LinkedHashMap())
        @JvmField val events = SessionEvents()
        private val checkpoints = HashMap<String, Long>()
        private val rawThreads = HashMap<String, JSONObject>()
        private val replies = HashMap<String, MainReplySession>()
        private val liveThreads = HashSet<String>()

        @JvmStatic
        @Throws(Exception::class)
        fun save(thread: JSONObject) {
            synchronized(AgentViews::class.java) {
                val id = thread.getString("id")
                val raw = project(overlaid(JSONObject(thread.toString())))
                if (id in liveThreads) AgentSessionReducer.retainActiveTurns(raw, rawThreads[id])
                var previous = threads[id]
                if (previous == null) {
                    val saved = File(ROOT, "$id.json")
                    if (saved.isFile) try { previous = MainReplySession.restored(JSONObject(TaskBroker.read(saved))) } catch (_: Exception) { }
                }
                rawThreads[id] = raw
                val session = replies.getOrPut(id) { MainReplySession(id, null) }
                val view = session.project(raw, previous, true)
                if (!session.hasContract() && previous != null) retainReplies(view, previous)
                threads[id] = view
                events.publish(id, "session/snapshot", JSONObject().put("thread", view))
                checkpoint(view)
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun saveMetadata(metadata: JSONObject) {
            synchronized(AgentViews::class.java) {
                val id = metadata.getString("id")
                val previous = existing(id)
                save(AgentViewProjection.mergeMetadata(previous, metadata))
            }
        }

        /** Re-save the conversation (native view, else [seed]) so [overlay] merges its current turns. */
        @JvmStatic
        @Throws(Exception::class)
        fun refresh(id: String, seed: JSONObject) {
            synchronized(AgentViews::class.java) {
                save(existing(id) ?: seed)
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun mergeTurnPage(
            id: String,
            page: JSONArray,
            descending: Boolean,
            prepend: Boolean,
            fullItems: Boolean
        ) {
            synchronized(AgentViews::class.java) {
                val previous = existing(id)
                save(AgentViewProjection.mergeTurnPage(previous, page, descending, prepend, fullItems))
            }
        }

        private fun existing(id: String): JSONObject? {
            if (!id.matches(Regex("[a-f0-9-]{36}"))) throw IOException("Invalid conversation identifier")
            rawThreads[id]?.let { return JSONObject(it.toString()) }
            threads[id]?.let { return JSONObject(it.toString()) }
            val file = File(ROOT, "$id.json")
            if (!file.isFile) return null
            val saved = JSONObject(TaskBroker.read(file))
            if (!id.equals(saved.optString("id"))) throw IOException("Existing conversation history identifiers do not match; the original record was preserved")
            return saved
        }

        private fun project(thread: JSONObject): JSONObject {
            val view = JSONObject()
            for (key in arrayOf("id", "name", "preview", "createdAt", "updatedAt", "status", "model", "modelProvider", "source", "turns")) {
                if (thread.has(key)) view.put(key, thread.get(key))
            }
            for (key in arrayOf(
                "historyNotLoaded",
                "historyOmissionCountUnknown",
                "historyOmittedTurns",
                "historyOmittedItems",
                "historyOmittedText",
                "historyOmittedPayloads",
                "_agentViewVersion"
            )) {
                if (thread.has(key)) view.put(key, thread.get(key))
            }
            if (!view.has("turns")) view.put("turns", JSONArray())
            return view
        }

        private fun checkpoint(view: JSONObject) {
            if (!directoryReady) {
                ROOT.mkdirs()
                // init starts the broker as root; the system-UID UI must read its projection.
                android.system.Os.chown(ROOT.path, 1000, 1000)
                android.system.Os.chmod(ROOT.path, 448)
                directoryReady = true
            }
            TaskBroker.write(File(ROOT, view.getString("id") + ".json"), view.toString())
            checkpoints[view.getString("id")] = System.nanoTime()
            index()
        }

        @JvmStatic
        @Throws(Exception::class)
        fun subscribe(request: JSONObject): SessionEvents.Subscription {
            synchronized(AgentViews::class.java) {
                val id = request.getString("threadId")
                val thread = threads[id] ?: throw IOException("THREAD_UNAVAILABLE")
                // Reducer and registration share this monitor: the snapshot covers every seq <= S.
                return events.subscribe(id, thread, request.optString("brokerEpoch"), request.optLong("afterSeq", -1))
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun snapshot(id: String): JSONObject? {
            synchronized(AgentViews::class.java) {
                val thread = threads[id]
                return if (thread == null) null else JSONObject(thread.toString())
            }
        }

        /**
         * Archived and permanently deleted threads leave the projection the same way. The last
         * native observation of each thread is kept beside it so a panel source can tell them apart,
         * and a permanent deletion is never overwritten by a later observation.
         */
        @JvmStatic
        fun threadAvailability(thread: String?, state: String) {
            if (thread == null || !thread.matches(Regex("[a-f0-9-]{36}"))) return
            synchronized(AgentViews::class.java) {
                try {
                    val directory = File(ROOT.parentFile, "thread-availability")
                    if (!directory.isDirectory && !directory.mkdirs()) return
                    val file = File(directory, "$thread.json")
                    if (file.isFile) {
                        val value = JSONObject(TaskBroker.read(file))
                        if ("deleted" == value.optString("state") || state == value.optString("state")) return
                    }
                    TaskBroker.write(file, JSONObject().put("schema", "taku.thread-availability/1")
                        .put("threadId", thread).put("state", state)
                        .put("observedAt", System.currentTimeMillis()).toString())
                } catch (_: Exception) {
                    // A missing marker must not fail the native call that observed the thread.
                }
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun replaceTurns(id: String, turns: JSONArray?) {
            synchronized(AgentViews::class.java) {
                val thread = rawThreads[id]?.let { JSONObject(it.toString()) }
                if (thread != null) save(thread.put("turns", turns))
            }
        }

        @JvmStatic
        fun prepareReply(id: String, contract: JSONObject?, enabled: Boolean) {
            synchronized(AgentViews::class.java) {
                if (contract != null) {
                    val session = replies.getOrPut(id) { MainReplySession(id, contract) }
                    session.install(contract)
                    session.prepare(enabled)
                    val raw = rawThreads[id]
                    if (raw != null) {
                        val previous = threads[id]
                        val view = session.project(raw, previous, true)
                        threads[id] = view
                        if (view.toString() != previous?.toString()) {
                            events.publish(id, "session/snapshot", JSONObject().put("thread", view))
                            checkpoint(view)
                        }
                    }
                } else replies[id]?.prepare(enabled)
            }
        }

        @JvmStatic
        fun interruptReply(id: String, turnId: String) {
            synchronized(AgentViews::class.java) {
                val session = replies[id] ?: return
                session.interrupt(turnId, "TURN_INTERRUPTED")
                refreshReplies(id, session)
            }
        }

        private fun refreshReplies(id: String, session: MainReplySession) {
            val view = threads[id] ?: return
            for (change in session.refresh()) events.publish(id, "session/reply", change)
            checkpoint(view)
        }

        private fun retainReplies(view: JSONObject, previous: JSONObject) {
            val turns = view.getJSONArray("turns")
            val old = previous.optJSONArray("turns") ?: return
            for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                val retained = MainReplyStream.find(old, turn.getString("id"))?.optJSONObject("mainReplies") ?: continue
                turn.put("mainReplies", retained)
                val items = turn.getJSONArray("items")
                for (id in MainReplyStream.keys(retained)) {
                    val reply = retained.getJSONObject(id)
                    val sourceIds = reply.getJSONObject("provenance").getJSONArray("sourceItemIds")
                    for (j in 0 until sourceIds.length()) MainReplyStream.find(items, sourceIds.getString(j))?.let {
                        it.put("text", if (j == 0) reply.getString("readableText") else "").put("replyId", id)
                        it.remove("content")
                    }
                }
            }
        }


        @JvmStatic
        fun mainReplyRead(input: JSONObject): JSONObject {
            synchronized(AgentViews::class.java) {
                fun error(code: String) = JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", code))
                val id = input.optString("threadId")
                val turnId = input.optString("turnId")
                if (input.optString("action") != "read" || id.isEmpty() || turnId.isEmpty() || input.has("replyId") && input.optString("replyId").isEmpty()) return error("INVALID_ARGUMENT")
                var thread = threads[id]
                if (thread == null && id.matches(Regex("[a-f0-9-]{36}"))) {
                    val file = File(ROOT, "$id.json")
                    if (file.isFile) try { thread = MainReplySession.restored(JSONObject(TaskBroker.read(file))) } catch (_: Exception) { }
                }
                if (thread == null) return error("THREAD_UNAVAILABLE")
                val turn = MainReplyStream.find(thread.optJSONArray("turns") ?: JSONArray(), turnId) ?: return error("TURN_UNAVAILABLE")
                val records = turn.optJSONObject("mainReplies") ?: JSONObject()
                val result = JSONObject().put("ok", true).put("threadId", id).put("turnId", turnId)
                if (input.has("replyId")) {
                    val reply = records.optJSONObject(input.getString("replyId")) ?: return error("REPLY_UNAVAILABLE")
                    return result.put("reply", JSONObject(reply.toString()))
                }
                val summaries = JSONArray()
                for (replyId in MainReplyStream.keys(records).takeLast(32)) {
                    val reply = records.getJSONObject(replyId)
                    summaries.put(JSONObject().put("id", replyId).put("key", reply.getString("key")).put("revision", reply.getInt("revision"))
                        .put("readableText", reply.getString("readableText").take(4000)).put("lifecycle", reply.getJSONObject("lifecycle"))
                        .put("provenance", reply.getJSONObject("provenance")).put("hasCard", reply.optJSONObject("card") != null))
                }
                return result.put("replies", summaries)
            }
        }

        /**
         * Every completed primary reply that carries a card for this task's turn and its last user
         * anchor. Unlike the public read it never truncates, because the caller must see ambiguity.
         */
        @JvmStatic
        fun panelCompletion(threadId: String, turnId: String): JSONObject {
            synchronized(AgentViews::class.java) {
                fun error(code: String) = JSONObject().put("ok", false).put("code", code)
                if (!threadId.matches(Regex("[a-f0-9-]{36}")) || turnId.isEmpty()) return error("INVALID_ARGUMENT")
                var thread = threads[threadId]
                if (thread == null) {
                    val file = File(ROOT, "$threadId.json")
                    if (file.isFile) try { thread = MainReplySession.restored(JSONObject(TaskBroker.read(file))) } catch (_: Exception) { }
                }
                if (thread == null) return error("THREAD_UNAVAILABLE")
                val turn = MainReplyStream.find(thread.optJSONArray("turns") ?: JSONArray(), turnId) ?: return error("TURN_UNAVAILABLE")
                val items = turn.optJSONArray("items") ?: JSONArray()
                var anchor = -1
                for (i in 0 until items.length()) if (items.getJSONObject(i).optString("type") == "userMessage") anchor++
                val records = turn.optJSONObject("mainReplies") ?: JSONObject()
                val found = JSONArray()
                for (replyId in MainReplyStream.keys(records)) {
                    val reply = records.getJSONObject(replyId)
                    if (reply.optJSONObject("lifecycle")?.optString("phase") != "completed") continue
                    if (reply.optJSONObject("card") == null) continue
                    if (reply.optJSONObject("provenance")?.optInt("anchorOrdinal", -1) != anchor) continue
                    found.put(JSONObject(reply.toString()))
                }
                return JSONObject().put("ok", true).put("threadId", threadId).put("turnId", turnId)
                    .put("anchorOrdinal", anchor).put("replies", found)
            }
        }

        @JvmStatic
        fun readableTurn(id: String, turnId: String): String? {
            synchronized(AgentViews::class.java) {
                val turn = MainReplyStream.find(threads[id]?.optJSONArray("turns") ?: return null, turnId) ?: return null
                val items = turn.optJSONArray("items") ?: return ""
                val anchor = (0 until items.length()).count { items.getJSONObject(it).optString("type") == "userMessage" } - 1
                val mainReplies = turn.optJSONObject("mainReplies")
                if (mainReplies != null && mainReplies.length() > 0) {
                    val latest = MainReplyStream.keys(mainReplies).map { mainReplies.getJSONObject(it) }
                        .firstOrNull { it.getJSONObject("provenance").getInt("anchorOrdinal") == anchor }
                    if (latest != null) return latest.getString("readableText")
                }
                val text = ArrayList<String>()
                var lastUser = -1
                for (i in 0 until items.length()) if (items.getJSONObject(i).optString("type") == "userMessage") lastUser = i
                var lastTool = lastUser
                for (i in lastUser + 1 until items.length()) if (items.getJSONObject(i).optString("type") != "agentMessage") lastTool = i
                for (i in lastUser + 1 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("type") != "agentMessage") continue
                    val phase = if (item.isNull("phase")) "" else item.optString("phase")
                    if ((phase == "final_answer" || phase.isEmpty() && i > lastTool) && item.optString("text").isNotBlank()) text.add(item.getString("text"))
                }
                return text.joinToString("\n\n")
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun remove(id: String) {
            synchronized(AgentViews::class.java) {
                threads.remove(id)
                checkpoints.remove(id)
                rawThreads.remove(id)
                replies.remove(id)
                liveThreads.remove(id)
                events.publish(id, "session/resync", JSONObject().put("reason", "thread-unavailable"))
                index()
            }
        }

        @JvmStatic
        fun runtimeClosed() {
            synchronized(AgentViews::class.java) {
                for ((id, session) in replies) {
                    val turns = rawThreads[id]?.optJSONArray("turns") ?: continue
                    for (i in 0 until turns.length()) session.interrupt(turns.getJSONObject(i).getString("id"), "RUNTIME_LOST")
                    refreshReplies(id, session)
                }
                try { events.reset() } catch (ignored: Exception) { }
                replies.clear()
                rawThreads.clear()
                liveThreads.clear()
                threads.clear()
                checkpoints.clear()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun index() {
            synchronized(AgentViews::class.java) {
                ROOT.mkdirs()
                val rows = JSONArray()
                for (view in threads.values) {
                    val row = JSONObject()
                    for (key in arrayOf("id", "name", "preview", "createdAt", "updatedAt", "status")) {
                        if (view.has(key)) row.put(key, view.get(key))
                    }
                    rows.put(row)
                }
                TaskBroker.write(File(ROOT, "threads.json"), JSONObject().put("data", rows).put("source", "app-server").toString())
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun event(event: JSONObject) {
            synchronized(AgentViews::class.java) {
                val method = event.optString("method")
                val params = event.optJSONObject("params") ?: return
                if ("thread/started" == method) {
                    save(params.getJSONObject("thread"))
                    return
                }
                val id = params.optString("threadId")
                val raw = rawThreads[id] ?: return
                val view = threads[id] ?: return
                val session = replies[id] ?: return
                if (method == "turn/started" || method == "item/agentMessage/delta") liveThreads.add(id)
                if (method == "turn/completed") liveThreads.remove(id)
                val update = session.update(raw, view, event) ?: return
                for (change in update.replies) events.publish(id, "session/reply", change)
                val terminalReply = update.replies.any { it.getJSONObject("reply").getJSONObject("lifecycle").getString("phase") != "streaming" }
                val item = update.item
                val turnId = params.optJSONObject("turn")?.optString("id") ?: params.optString("turnId")
                val hidden = item != null && session.hidden(turnId, item.getString("id"))
                if (!hidden) {
                    var publicMethod = method
                    var publicParams = params
                    if (params.has("turn") || params.has("item") || method == "item/agentMessage/delta" && item != null && (update.oldText?.length ?: 0) + params.optString("delta").length != item.optString("text").length) {
                        publicParams = JSONObject()
                        for (key in MainReplyStream.keys(params)) if (key != "turn" && key != "item") publicParams.put(key, params.get(key))
                        if (params.has("turn")) publicParams.put("turn", update.turn)
                        if (params.has("item")) publicParams.put("item", item)
                        else if (method == "item/agentMessage/delta") {
                            publicMethod = "item/started"
                            publicParams.remove("delta")
                            publicParams.put("item", item)
                        }
                    }
                    events.publish(id, publicMethod, publicParams)
                }
                val elapsed = System.nanoTime() - checkpoints.getOrDefault(id, 0L)
                if (method != "item/agentMessage/delta" || terminalReply || elapsed >= 250_000_000L) checkpoint(view)
            }
        }
    }
}
