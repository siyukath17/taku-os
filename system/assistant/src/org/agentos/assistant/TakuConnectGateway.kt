package org.agentos.assistant

import android.os.Bundle
import org.json.JSONObject

/**
 * Taku Connect (taku-runtime nodes) for the Agent UI gateway and the open-access provider: one
 * validated path to the broker's NativeBridge op `connect` (see broker `TakuConnect`). A node's
 * token is accepted either as `token` or, from the Agent UI's native credential field, as the
 * `credential` descriptor, and never comes back. Field and argument rules: [TakuConnectContract].
 *
 *     content call --uri content://org.agentos.assistant.open-access --method connect/list
 *     content call --uri content://org.agentos.assistant.open-access --method connect/appearance \
 *       --arg '{"id":"taku_macmini","icon":"builtin:mac_mini","displayName":"书房 Mac mini","shortName":"Mac mini"}'
 */
internal object TakuConnectGateway {
    @JvmField val ACTIONS: Set<String> = TakuConnectContract.ACTIONS

    @JvmStatic
    fun execute(method: String, input: JSONObject, extras: Bundle?): JSONObject {
        val credential = if (method == "connect/test" || method == "connect/add") AssistantAccounts.credentialText(extras) else null
        return NativeClient.exchange(TakuConnectContract.request(method, input, credential))
    }
}
