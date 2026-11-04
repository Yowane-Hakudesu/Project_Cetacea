package com.whalechan.agent.net

import android.os.Build
import com.whalechan.agent.core.WhaleLog
import com.whalechan.protocol.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Collections

/**
 * 手机端 WebSocket 服务端（被操控端）。
 *
 * 安全模型：
 *  - 只监听一个端口，但**第一条消息必须是 hello + 正确 token**，否则立刻断开；
 *  - token 在手机端首次启动时生成，显示在设置页，伴侣端手输或扫码带过来；
 *  - 不做 TLS：局域网侧载场景下收益低、复杂度高，但 token 校验不能省；
 *  - 帧上限通过 Draft_6455(maxFrameSize) 放到 8MB，否则带 base64 的附件会被框架拒绝。
 */
class AgentWsServer(
    private val port: Int,
    private val token: String,
    private val listener: Listener
) : WebSocketServer(
    InetSocketAddress(port),
    listOf<Draft>(Draft_6455(emptyList<IExtension>(), Protocol.MAX_WS_TEXT_BYTES))
) {

    interface Listener {
        fun onMessage(client: WebSocket, json: JSONObject)
        fun onAuthenticated(client: WebSocket)
        fun onClosed(client: WebSocket)
    }

    private val authenticated: MutableSet<WebSocket> = Collections.synchronizedSet(mutableSetOf())

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        WhaleLog.i("WS 客户端接入：${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        authenticated.remove(conn)
        listener.onClosed(conn)
        WhaleLog.i("WS 客户端断开：$code $reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            Protocol.TYPE_HELLO -> {
                if (json.optString("token") == token) {
                    authenticated.add(conn)
                    conn.send(Protocol.helloOk("${Build.MANUFACTURER} ${Build.MODEL}").toString())
                    listener.onAuthenticated(conn)
                } else {
                    conn.send(Protocol.error("BAD_TOKEN", "配对 token 不正确").toString())
                    conn.close(4001, "bad token")
                }
            }

            Protocol.TYPE_PING -> conn.send(Protocol.pong().toString())

            else -> {
                if (authenticated.contains(conn)) listener.onMessage(conn, json)
                else conn.close(4001, "unauthorized")
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        WhaleLog.w("WS 错误", ex)
    }

    override fun onStart() {
        WhaleLog.i("WS 服务端已启动，端口 $port")
    }

    fun broadcast(json: JSONObject) {
        val text = json.toString()
        val snapshot = synchronized(authenticated) { authenticated.toList() }
        snapshot.forEach { socket ->
            runCatching { if (socket.isOpen) socket.send(text) }
                .onFailure { WhaleLog.w("WS 发送失败", it) }
        }
    }

    fun clientCount(): Int = authenticated.size
}
