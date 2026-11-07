package com.whalechan.protocol

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
 * 伴侣端 / 手机端共用的 WebSocket 客户端。
 *
 * 两种模式：
 *  - DIRECT：直连手机端的局域网服务端（旧模式，同一 Wi-Fi 可用）；
 *  - RELAY：连公网中继，靠 pairCode 路由（校园网 AP 隔离、蜂窝网可用）。
 *
 * 帧上限通过 Draft_6455 的 maxFrameSize 设置（Java-WebSocket 1.5.x 没有公开的 setMax* 接口）。
 */
class CompanionClient(
    url: String,
    private val token: String,
    private val listener: Listener,
    private val relayMode: Boolean = false,
    private val pairCode: String = "",
    private val deviceId: String = ""
) : WebSocketClient(
    URI(url),
    Draft_6455(emptyList<IExtension>(), Protocol.MAX_WS_TEXT_BYTES)
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(json: JSONObject)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeat: Job? = null

    override fun onOpen(handshakedata: ServerHandshake?) {
        if (relayMode) {
            send(
                Protocol.register(
                    role = Protocol.ROLE_COMPANION,
                    deviceId = deviceId,
                    pairCode = pairCode,
                    token = token
                ).toString()
            )
        } else {
            send(Protocol.hello(token, "companion").toString())
        }

        heartbeat = scope.launch {
            while (isActive) {
                delay(30_000L)
                runCatching { send("""{"type":"ping"}""") }
                    .onFailure { Plog.w("心跳发送失败", it) }
            }
        }
    }

    override fun onMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return

        // 中继信封：{"type":"fromPhone","payload":{...}}
        if (json.has("payload")) {
            val payload = json.optJSONObject("payload")
            if (payload != null) listener.onMessage(payload)
            return
        }

        when (json.optString("type")) {
            Protocol.TYPE_PING -> runCatching { send("""{"type":"pong"}""") }
            Protocol.TYPE_PONG -> Unit
            Protocol.TYPE_REGISTERED -> {
                if (json.optBoolean("ok")) {
                    listener.onConnected()
                } else {
                    listener.onError("中继拒绝：${json.optString("message", "配对码或令牌错误")}")
                }
            }
            Protocol.TYPE_HELLO_OK -> listener.onConnected()
            Protocol.TYPE_ERROR -> listener.onError(
                json.optString("message", json.optString("code", "远端错误"))
            )
            else -> listener.onMessage(json)
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        heartbeat?.cancel()
        heartbeat = null
        listener.onDisconnected("code=$code ${reason.take(60)}")
    }

    override fun onError(ex: Exception) {
        listener.onError(ex.message ?: ex.javaClass.simpleName)
    }

    /** 明确意图：把 text 当任务执行（遥控端的「任务」模式）。 */
    fun sendCommand(text: String, id: String = java.util.UUID.randomUUID().toString()) {
        val payload = Protocol.command(text, id)
        send(if (relayMode) Protocol.toPhone(payload).toString() else payload.toString())
    }

    fun sendChat(text: String, mode: String) {
        val payload = Protocol.chat(text, mode)
        send(if (relayMode) Protocol.toPhone(payload).toString() else payload.toString())
    }

    fun sendStop() {
        val payload = Protocol.stop()
        send(if (relayMode) Protocol.toPhone(payload).toString() else payload.toString())
    }

    fun sendConfirm(id: String, approved: Boolean) {
        val payload = Protocol.confirmResult(id, approved)
        send(if (relayMode) Protocol.toPhone(payload).toString() else payload.toString())
    }

    fun sendAttachment(
        msgId: String,
        kind: String,
        name: String,
        mime: String,
        size: Int,
        base64: String
    ) {
        val payload = Protocol.attachment(msgId, kind, name, mime, size, base64)
        send(if (relayMode) Protocol.toPhone(payload).toString() else payload.toString())
    }

    fun shutdown() {
        heartbeat?.cancel()
        heartbeat = null
        scope.cancel()
        runCatching { close() }
    }
}
