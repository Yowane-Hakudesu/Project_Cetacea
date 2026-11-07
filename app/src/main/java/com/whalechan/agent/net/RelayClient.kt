package com.whalechan.agent.net

import com.whalechan.agent.core.WhaleLog
import com.whalechan.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * 手机端 → 公网中继的主动连接。
 *
 * 为什么是“主动连出去”：校园网/蜂窝网没有公网 IP，也无法被外部拨入；
 * 只有手机主动建立一条出站 WebSocket，伴侣端再连同一个中继，才能跨网通信。
 */
class RelayClient(
    url: String,
    private val token: String,
    private val pairCode: String,
    private val deviceId: String,
    private val listener: Listener
) : WebSocketClient(
    URI(url),
    Draft_6455(emptyList<IExtension>(), Protocol.MAX_WS_TEXT_BYTES)
) {

    interface Listener {
        fun onReady()
        fun onClosed(reason: String)
        fun onPayload(json: JSONObject)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeat: Job? = null

    override fun onOpen(handshakedata: ServerHandshake?) {
        send(
            Protocol.register(
                role = Protocol.ROLE_PHONE,
                deviceId = deviceId,
                pairCode = pairCode,
                token = token
            ).toString()
        )
        heartbeat = scope.launch {
            while (isActive) {
                delay(30_000L)
                runCatching { send("""{"type":"ping"}""") }
                    .onFailure { WhaleLog.w("中继心跳失败：${it.message}") }
            }
        }
    }

    override fun onMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            Protocol.TYPE_PING -> runCatching { send("""{"type":"pong"}""") }
            Protocol.TYPE_PONG -> Unit
            Protocol.TYPE_REGISTERED -> {
                if (json.optBoolean("ok")) {
                    WhaleLog.i("中继已注册，房间 ${json.optString("pairCode")}")
                    listener.onReady()
                } else {
                    listener.onError("中继拒绝：配对码或令牌错误")
                }
            }
            Protocol.TYPE_FROM_COMPANION -> {
                json.optJSONObject("payload")?.let(listener::onPayload)
            }
            Protocol.TYPE_ERROR -> listener.onError(
                json.optString("message", json.optString("code", "中继错误"))
            )
            else -> Unit
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        heartbeat?.cancel()
        heartbeat = null
        listener.onClosed("code=$code ${reason.take(60)}")
    }

    override fun onError(ex: Exception) {
        listener.onError(ex.message ?: ex.javaClass.simpleName)
    }

    /** 把一条协议消息发给房间里的所有伴侣端。 */
    fun sendPayload(payload: JSONObject) {
        runCatching { send(Protocol.toCompanion(payload).toString()) }
            .onFailure { WhaleLog.w("中继发送失败：${it.message}") }
    }

    fun shutdown() {
        heartbeat?.cancel()
        heartbeat = null
        scope.cancel()
        runCatching { close() }
    }
}
