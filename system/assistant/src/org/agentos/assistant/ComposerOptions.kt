package org.agentos.assistant

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object ComposerOptions {
    private val threadId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val monitor = ComposerPreferences::class.java
    private val connectionLock = Any()
    private val modelOptionKey = Regex("(?:composer\\.(?:${threadId.pattern})?\\.)?(?:model|effort|serviceTier)")

    private enum class Field(val key: String, val fallback: String? = null, val choices: Set<String>? = null) {
        MODEL("model"),
        EFFORT("effort"),
        SERVICE_TIER("serviceTier"),
        MULTI_AGENT("multiAgentMode", "explicitRequestOnly", setOf("explicitRequestOnly", "proactive")),
        PERMISSION("permissionMode", "full", setOf("full", "ask", "readOnly")),
        RESPONSE_STYLE("responseStyle", "concise", setOf("concise", "detailed"));

        fun storageKey(thread: String?): String = if (thread == null) key else ComposerPreferences.key(thread, key)
    }

    private val fields = Field.entries.associateBy(Field::key)

    private class Model(val json: JSONObject) {
        val id = json.string("model") ?: throw IOException("MODEL_CATALOG_INVALID")
        val efforts = json.ids("supportedReasoningEfforts", "reasoningEffort")
        val defaultEffort = json.string("defaultReasoningEffort")?.takeIf(efforts::contains) ?: efforts.firstOrNull()
        val defaultTier = json.string("defaultServiceTier")
        val tiers = json.ids("serviceTiers", "id").ifEmpty { json.ids("additionalSpeedTiers") }
        fun supportsTier(value: String): Boolean = value == defaultTier || value in tiers
    }

    private class Catalog(val models: JSONArray) {
        private val byId = (0 until models.length()).associate { index ->
            val model = Model(models.optJSONObject(index) ?: throw IOException("MODEL_CATALOG_INVALID"))
            model.id to model
        }
        val default = byId.values.firstOrNull { it.json.optBoolean("isDefault") } ?: byId.values.firstOrNull()
        /**
         * The broker answers `model/list` from the ACP agent's catalog while chat turns run on ACP
         * (entries carry `runtime: "acp"`). A model saved under Codex is not in it: such a send uses
         * the ACP default instead of handing the agent a Codex model id. Codex catalogs are unchanged.
         */
        private val acp = byId.values.any { it.json.optString("runtime") == "acp" }
        fun contains(id: String): Boolean = id in byId
        fun resolve(id: String?): Model? = if (id == null) default else byId[id] ?: default.takeIf { acp }
    }

    class SubmissionOptions internal constructor(private val useCatalog: (JSONObject) -> Unit) {
        @Throws(Exception::class)
        fun apply(prefs: SharedPreferences, thread: String, request: JSONObject) {
            captureSubmission(prefs, thread, request)
            resolve(request)
        }

        @Throws(Exception::class)
        fun resolve(request: JSONObject) = useCatalog(request)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun prepareSubmission(): SubmissionOptions {
        val catalog = loadCatalog()
        return SubmissionOptions { request ->
            val model = catalog.resolve(request.string("model"))
            for (field in Field.entries) {
                val value = effectiveValue(field, request.string(field.key), model)
                if (value != null) request.put(field.key, value)
                else if (field == Field.SERVICE_TIER) request.put(field.key, JSONObject.NULL)
                else request.remove(field.key)
            }
        }
    }

    /** The response style a send in [thread] uses: its own override, else the global setting. */
    @JvmStatic
    fun responseStyle(prefs: SharedPreferences, thread: String): String = synchronized(monitor) {
        resolved(prefs, thread, Field.RESPONSE_STYLE) ?: "concise"
    }

    @JvmStatic
    @Throws(Exception::class)
    fun captureSubmission(prefs: SharedPreferences, thread: String, request: JSONObject) {
        synchronized(monitor) {
            for (field in Field.entries) {
                val value = resolved(prefs, thread, field)
                if (value != null) request.put(field.key, value)
                else if (field == Field.SERVICE_TIER) request.put(field.key, JSONObject.NULL)
                else request.remove(field.key)
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun call(context: Context, method: String, input: JSONObject): JSONObject {
        val thread = input.opt("threadId") as? String ?: invalid()
        if (thread.isNotEmpty() && !threadId.matches(thread)) invalid()
        val patch = when (method) {
            AgentGatewayProtocol.COMPOSER_OPTIONS_READ -> {
                if (input.length() != 1) invalid()
                null
            }
            AgentGatewayProtocol.COMPOSER_OPTIONS_WRITE -> {
                if (input.length() != 2) invalid()
                input.optJSONObject("options") ?: invalid()
            }
            else -> throw IOException("UNSUPPORTED_ACTION")
        }
        return access(context, thread, patch)
    }

    @JvmStatic
    fun callAsync(context: Context, thread: String?, options: JSONObject?, callback: NativeClient.Callback) {
        NativeClient.io.execute {
            try {
                val result = access(context, thread, options)
                NativeClient.main.post { callback.ready(result) }
            } catch (error: Exception) {
                val message = error.message ?: "RUNTIME_UNAVAILABLE"
                NativeClient.main.post { callback.failed(message) }
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun switchConnection(context: Context, mode: String, model: String): JSONObject = synchronized(connectionLock) {
        if (mode !in setOf("openai", "magpie") || (mode == "magpie") != model.isNotBlank() || model.length > 256)
            invalid()
        val selected = NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "select")
            .put("mode", mode).put("model", model))
        cachedCatalog = null
        syncConnection(context, selected, resetModel = true)
        selected
    }

    @JvmStatic
    @Throws(Exception::class)
    fun readConnection(context: Context): JSONObject = synchronized(connectionLock) {
        val state = NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "read"))
        syncConnection(context, state, resetModel = false)
        state
    }

    private fun syncConnection(context: Context, state: JSONObject, resetModel: Boolean) = synchronized(monitor) {
        val mode = state.getString("mode")
        val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
        val previous = prefs.getString("modelConnectionMode", "openai") ?: "openai"
        val changed = mode != previous
        if (changed) cachedCatalog = null
        if (!changed && !resetModel) return@synchronized
        val editor = prefs.edit()
        if (changed) {
            val saved = JSONObject()
            for ((key, value) in prefs.all) if (modelOptionKey.matches(key)) {
                if (value is String) saved.put(key, value)
                editor.remove(key)
            }
            val stored = prefs.getString("modelSettings.$mode", "{}") ?: "{}"
            val restored = try { JSONObject(stored) } catch (_: org.json.JSONException) { JSONObject() }
            for (key in restored.keys()) if (modelOptionKey.matches(key)) {
                val value = restored.opt(key)
                if (value is String) editor.putString(key, value)
            }
            editor.putString("modelSettings.$previous", saved.toString())
        }
        if (mode == "magpie") {
            editor.putString("model", state.getString("model").takeUnless(String::isEmpty))
                .remove("effort").remove("serviceTier")
        }
        if (!editor.putString("modelConnectionMode", mode).commit()) throw IOException("COMPOSER_OPTIONS_WRITE_FAILED")
    }

    private fun access(context: Context, thread: String?, options: JSONObject?): JSONObject = synchronized(connectionLock) {
        val patch = options?.let(::parsePatch)
        val catalog = loadCatalog()
        val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
        synchronized(monitor) {
            if (patch != null) write(prefs, thread, patch, catalog)
            snapshot(prefs, thread, catalog)
        }
    }

    private class CachedCatalog(val models: String, val readAt: Long)
    @Volatile private var cachedCatalog: CachedCatalog? = null
    private const val CATALOG_FRESH_MS = 30_000L
    private const val CATALOG_USABLE_MS = 10 * 60_000L

    /**
     * The runtime's model catalog. Options are read, written and resolved on every send, so the
     * catalog is reused for a short while instead of paging through `model/list` each time, and
     * a recent copy still serves when the runtime briefly cannot answer, so a transient failure
     * does not stop a message from being sent. Changing the connection drops the copy.
     */
    private fun loadCatalog(): Catalog {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedCatalog
        if (cached != null && now - cached.readAt < CATALOG_FRESH_MS) return Catalog(JSONArray(cached.models))
        return try {
            fetchCatalog().also { cachedCatalog = CachedCatalog(it.models.toString(), now) }
        } catch (failure: Exception) {
            if (cached == null || now - cached.readAt > CATALOG_USABLE_MS) throw failure
            Catalog(JSONArray(cached.models))
        }
    }

    private fun fetchCatalog(): Catalog {
        val params = JSONObject().put("limit", 100)
        var result = NativeClient.rpcSync("model/list", params)
        val models = result.optJSONArray("data") ?: throw IOException("MODEL_CATALOG_INVALID")
        var cursor = result.string("nextCursor") ?: return Catalog(models)
        val seen = HashSet<String>()
        while (seen.add(cursor)) {
            result = NativeClient.rpcSync("model/list", params.put("cursor", cursor))
            val page = result.optJSONArray("data") ?: throw IOException("MODEL_CATALOG_INVALID")
            for (index in 0 until page.length()) models.put(page.get(index))
            cursor = result.string("nextCursor") ?: return Catalog(models)
        }
        throw IOException("MODEL_CATALOG_INVALID")
    }

    private fun parsePatch(options: JSONObject): MutableMap<Field, String?> {
        if (options.length() == 0) invalid()
        val patch = LinkedHashMap<Field, String?>(options.length())
        for (key in options.keys()) {
            val field = fields[key] ?: invalid()
            val raw = options.opt(key)
            val value = if (raw === JSONObject.NULL) null else raw as? String ?: invalid()
            if (value != null && (value.isBlank() || field.choices?.contains(value) == false)) invalid()
            patch[field] = value
        }
        return patch
    }

    private fun write(prefs: SharedPreferences, thread: String?, patch: MutableMap<Field, String?>, catalog: Catalog) {
        val modelId = next(prefs, thread, Field.MODEL, patch)
        val model = catalog.resolve(modelId)
        val selectsModel = Field.MODEL in patch && patch[Field.MODEL] != null
        if (selectsModel && (model == null || !catalog.contains(modelId!!))) throw IOException("MODEL_UNAVAILABLE")
        val modelChanged = Field.MODEL in patch && modelId != resolved(prefs, thread, Field.MODEL)
        val requestedEffort = patch[Field.EFFORT]
        if (requestedEffort != null && model?.efforts?.contains(requestedEffort) != true) {
            throw IOException(if (model == null) "MODEL_UNAVAILABLE" else "EFFORT_UNSUPPORTED")
        }
        if (modelChanged && Field.EFFORT !in patch) {
            val inherited = inherited(prefs, thread, Field.EFFORT)
            val effort = if (!selectsModel && inherited != null && model?.efforts?.contains(inherited) == true) {
                inherited
            } else model?.defaultEffort
            patch[Field.EFFORT] = effort.takeUnless { it == inherited }
        }
        val requestedTier = patch[Field.SERVICE_TIER]
        if (requestedTier != null && model?.supportsTier(requestedTier) != true) {
            throw IOException(if (model == null) "MODEL_UNAVAILABLE" else "SERVICE_TIER_UNSUPPORTED")
        }
        val currentTier = resolved(prefs, thread, Field.SERVICE_TIER)
        if (modelChanged && Field.SERVICE_TIER !in patch && currentTier != null && model?.supportsTier(currentTier) != true) {
            patch[Field.SERVICE_TIER] = null
        }
        val previous = patch.keys.associateWith { prefs.getString(it.storageKey(thread), null) }
        val editor = prefs.edit()
        for ((field, value) in patch) editor.putString(field.storageKey(thread), value)
        if (editor.commit()) return
        val rollback = prefs.edit()
        for ((field, value) in previous) rollback.putString(field.storageKey(thread), value)
        rollback.apply()
        throw IOException("COMPOSER_OPTIONS_WRITE_FAILED")
    }

    private fun snapshot(prefs: SharedPreferences, thread: String?, catalog: Catalog): JSONObject {
        val overrides = JSONObject()
        val effective = JSONObject()
        val modelId = resolved(prefs, thread, Field.MODEL)
        val model = catalog.resolve(modelId)
        for (field in Field.entries) {
            val override = prefs.getString(field.storageKey(thread), null)?.takeUnless(String::isEmpty)
            overrides.put(field.key, override ?: JSONObject.NULL)
            val value = override ?: inherited(prefs, thread, field)
            effective.put(field.key, effectiveValue(field, value, model) ?: JSONObject.NULL)
        }
        return JSONObject().put("threadId", thread ?: JSONObject.NULL).put("options", overrides)
            .put("effective", effective).put("models", catalog.models)
    }

    private fun effectiveValue(field: Field, value: String?, model: Model?): String? {
        if (model == null) return value
        return when (field) {
            Field.MODEL -> model.id
            Field.EFFORT -> value?.takeIf(model.efforts::contains) ?: model.defaultEffort
            Field.SERVICE_TIER -> value?.takeIf(model::supportsTier) ?: model.defaultTier
            else -> value
        }
    }

    private fun resolved(prefs: SharedPreferences, thread: String?, field: Field): String? =
        prefs.getString(field.storageKey(thread), null)?.takeUnless(String::isEmpty) ?: inherited(prefs, thread, field)

    private fun inherited(prefs: SharedPreferences, thread: String?, field: Field): String? =
        (if (thread == null) null else prefs.getString(field.key, null)?.takeUnless(String::isEmpty)) ?: field.fallback

    private fun next(prefs: SharedPreferences, thread: String?, field: Field, patch: Map<Field, String?>): String? =
        if (field in patch) patch[field] ?: inherited(prefs, thread, field) else resolved(prefs, thread, field)

    private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeUnless(String::isEmpty)

    private fun JSONObject.ids(key: String, itemKey: String? = null): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                val id = if (itemKey == null) array.opt(index) as? String else array.optJSONObject(index)?.string(itemKey)
                if (!id.isNullOrEmpty()) add(id)
            }
        }
    }

    private fun invalid(): Nothing = throw IOException("INVALID_ARGUMENT")
}
