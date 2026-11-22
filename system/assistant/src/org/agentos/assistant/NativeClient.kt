package org.agentos.assistant

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Interactive answers and authorization URLs never enter task files. */
internal object NativeClient {
    private const val SOCKET_NAME = "taku-agent-native"

    @JvmField val io: ExecutorService = Executors.newCachedThreadPool()
    @JvmField val main = Handler(Looper.getMainLooper())

    interface Callback {
        fun ready(value: JSONObject)
        fun failed(message: String)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun exchange(request: JSONObject): JSONObject = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(SOCKET_NAME))
        if (socket.peerCredentials.uid != 0) throw IOException("Invalid native service identity")
        socket.soTimeout = 45_000
        socket.send(request)

        val input = socket.inputStream
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            if (output.size() > 6_000_000) throw IOException("Native result exceeds the limit")
        }
        val answer = JSONObject(output.toString("UTF-8"))
        if (!answer.optBoolean("ok")) {
            if (answer.optString("code") == "STEER_NOT_SUBMITTED") throw AgentGatewaySubmissions.SteerNotSubmitted()
            if (answer.optString("code") in AgentGatewaySubmissions.STEER_REFUSALS) throw AgentGatewaySubmissions.SteerRejected(answer.optString("code"))
            throw IOException(answer.optString("message", "Native service returned no result"))
        }
        answer.getJSONObject("result")
    }

    /** The caller takes ownership of the socket only after the subscription is sent. */
    @JvmStatic
    @Throws(Exception::class)
    fun openSessionStream(request: JSONObject): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(SOCKET_NAME))
            if (socket.peerCredentials.uid != 0) throw IOException("INVALID_RUNTIME_IDENTITY")
            socket.soTimeout = 15_000
            socket.send(JSONObject(request.toString()).put("op", "subscribeSession"))
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun state(id: String?): JSONObject = exchange(JSONObject().put("op", "state").put("threadId", id))

    @JvmStatic
    @Throws(Exception::class)
    fun rpcSync(method: String?, params: JSONObject?): JSONObject = exchange(rpcRequest(method, params))

    @JvmStatic
    fun async(request: JSONObject, callback: Callback) {
        io.execute {
            try {
                val result = exchange(request)
                main.post { callback.ready(result) }
            } catch (error: Exception) {
                val message = error.message ?: "Native service temporarily unavailable"
                main.post { callback.failed(message) }
            }
        }
    }

    @JvmStatic
    fun rpc(method: String?, params: JSONObject?, callback: Callback) {
        try {
            async(rpcRequest(method, params), callback)
        } catch (_: Exception) {
            callback.failed("Invalid native request")
        }
    }

    @JvmStatic
    fun respond(key: String?, response: JSONObject?, callback: Callback) {
        try {
            async(JSONObject().put("op", "respond").put("key", key).put("response", response), callback)
        } catch (_: Exception) {
            callback.failed("Could not send answer")
        }
    }

    private fun rpcRequest(method: String?, params: JSONObject?): JSONObject {
        val request = JSONObject().put("op", "rpc").put("method", method)
        if (params != null) request.put("params", params)
        return request
    }

    private fun LocalSocket.send(request: JSONObject) {
        outputStream.write(request.toString().toByteArray(Charsets.UTF_8))
        shutdownOutput()
    }
}
