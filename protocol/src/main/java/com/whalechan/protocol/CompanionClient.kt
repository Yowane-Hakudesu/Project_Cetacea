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
 * 伴侣端 WebSocket 客户端（两端共享实现）。
 *
 * 心跳：每 30 秒 ping，服务端回 pong；断线由 onDisconnected 通知 UI。
 * 帧上限通过 Draft_6455 的 maxFrameSize 设置（Java-WebSocket 1.5.x 没有公开的 setMax* 接口）。
 */
class CompanionClient(
    url: String,
    private val token: String,
    private val listener: Listener
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
        send(Protocol.hello(token, "companion").toString())
        listener.onConnected()
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
        listener.onMessage(json)
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        heartbeat?.cancel()
        heartbeat = null
        listener.onDisconnected("code=$code ${reason.take(60)}")
    }

    override fun onError(ex: Exception) {
        listener.onError(ex.message ?: ex.javaClass.simpleName)
    }

    fun sendChat(text: String, mode: String) {
        send(Protocol.chat(text, mode).toString())
    }

    fun sendStop() {
        send(Protocol.stop().toString())
    }

    fun sendConfirm(id: String, approved: Boolean) {
        send(Protocol.confirmResult(id, approved).toString())
    }

    fun sendAttachment(
        msgId: String,
        kind: String,
        name: String,
        mime: String,
        size: Int,
        base64: String
    ) {
        send(Protocol.attachment(msgId, kind, name, mime, size, base64).toString())
    }

    fun shutdown() {
        heartbeat?.cancel()
        heartbeat = null
        scope.cancel()
        runCatching { close() }
    }
}
