package org.agentos.assistant

import android.os.Bundle
import java.io.IOException
import org.json.JSONObject

/**
 * Taku Connect (taku-runtime nodes) for the Agent UI gateway and the open-access provider: one
 * validated path to the broker's NativeBridge op `connect` (see broker `TakuConnect`). A node's
 * token is accepted either as `token` or, from the Agent UI's native credential field, as the
 * `credential` descriptor, and never comes back.
 *
 *     content call --uri content://org.agentos.assistant.open-access --method connect/list
 *     content call --uri content://org.agentos.assistant.open-access --method connect/appearance \
 *       --arg '{"id":"taku_macmini","icon":"builtin:mac_mini","displayName":"书房 Mac mini"}'
 */
internal object TakuConnectGateway {
    @JvmField val ACTIONS: Set<String> = setOf(
        "connect/list", "connect/probe", "connect/test", "connect/add", "connect/remove", "connect/setEnabled", "connect/appearance")
    /** Actions that change runtime.json or presentation (the rest only read or probe). */
    @JvmField val WRITES: Set<String> = setOf("connect/add", "connect/remove", "connect/setEnabled", "connect/appearance")

    private val FIELDS: Map<String, Set<String>> = mapOf(
        "connect/list" to emptySet(),
        "connect/probe" to setOf("id"),
        "connect/test" to setOf("url", "token", "tokenFile"),
        "connect/add" to setOf("name", "url", "token", "tokenFile", "deviceType", "icon"),
        "connect/remove" to setOf("id"),
        "connect/setEnabled" to setOf("id", "enabled"),
        "connect/appearance" to setOf("id", "displayName", "deviceType", "icon"),
    )

    @JvmStatic
    fun execute(method: String, input: JSONObject, extras: Bundle?): JSONObject {
        val fields = FIELDS[method] ?: throw IOException("UNKNOWN_METHOD")
        PanelProtocol.exact(input, fields)
        if ("id" in fields && !input.has("id")) throw IOException("INVALID_ARGUMENT")
        if (method == "connect/setEnabled" && input.opt("enabled") !is Boolean) throw IOException("INVALID_ARGUMENT")
        val request = JSONObject(input.toString())
        if (method == "connect/test" || method == "connect/add") {
            val supplied = AssistantAccounts.credentialText(extras)
            if (supplied != null) {
                if (request.has("token") || request.has("tokenFile")) throw IOException("KEY_SOURCE_AMBIGUOUS")
                request.put("token", supplied.trim())
            }
        }
        request.put("op", "connect").put("action", method.removePrefix("connect/"))
        return NativeClient.exchange(request)
    }
}
