package org.agentos.assistant

import java.io.IOException
import org.json.JSONObject

/**
 * The app side of the broker's NativeBridge op `connect` (broker `TakuConnect`), free of Android and
 * socket dependencies so host tests can feed its output straight into the broker.
 *
 * The gateway only rejects what the broker cannot see: unknown fields (so a typo is not silently
 * ignored), a missing/non-string `id`, a non-boolean `enabled`, and an inline token that collides
 * with the native credential field. Everything else — routes, names, icons, `DEMO_NODE`,
 * `NODE_NOT_FOUND`, `NOTHING_TO_UPDATE` — is validated once, by the broker, so open-access callers
 * and the Agent UI see the same error codes the broker documents.
 */
internal object TakuConnectContract {
    /** Broker `TakuConnect.APPEARANCE_FIELDS`; each one is settable and clearable (null or ""). */
    @JvmField val APPEARANCE_FIELDS: List<String> = listOf("displayName", "shortName", "deviceType", "icon")

    @JvmField val FIELDS: Map<String, Set<String>> = mapOf(
        "connect/list" to emptySet(),
        "connect/probe" to setOf("id"),
        "connect/test" to setOf("url", "urls", "token", "tokenFile"),
        "connect/add" to setOf("name", "url", "routes", "token", "tokenFile", "deviceType", "icon"),
        "connect/remove" to setOf("id"),
        "connect/setEnabled" to setOf("id", "enabled"),
        "connect/appearance" to setOf("id") + APPEARANCE_FIELDS,
        "connect/routes" to setOf("id", "routes"),
    )

    @JvmField val ACTIONS: Set<String> = FIELDS.keys

    /**
     * The broker request for [method]: a copy of [input] plus `op`/`action`, with [credential] (the
     * Agent UI's native credential field, already resolved) as `token` for `test`/`add`.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun request(method: String, input: JSONObject, credential: String?): JSONObject {
        val fields = FIELDS[method] ?: throw IOException("UNKNOWN_METHOD")
        for (key in input.keys()) if (key !in fields) throw IOException("INVALID_ARGUMENT")
        if ("id" in fields) {
            val id = input.opt("id")
            if (id !is String || id.isBlank()) throw IOException("INVALID_ARGUMENT")
        }
        if (method == "connect/setEnabled" && input.opt("enabled") !is Boolean) throw IOException("INVALID_ARGUMENT")
        val request = JSONObject(input.toString())
        if (credential != null && (method == "connect/test" || method == "connect/add")) {
            if (request.has("token") || request.has("tokenFile")) throw IOException("KEY_SOURCE_AMBIGUOUS")
            request.put("token", credential.trim())
        }
        return request.put("op", "connect").put("action", method.removePrefix("connect/"))
    }
}
