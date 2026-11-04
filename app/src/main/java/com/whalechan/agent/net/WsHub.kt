package com.whalechan.agent.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import com.whalechan.agent.core.AgentEvent
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.AttachmentBus
import com.whalechan.agent.core.ConfirmationGate
import com.whalechan.agent.core.Persona
import com.whalechan.agent.core.WhaleLog
import com.whalechan.agent.service.KeepAliveService
import com.whalechan.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * WebSocket 中枢：把 Agent 的状态/事件/日志推给伴侣端，把伴侣端的指令送回 Agent。
 *
 * 生命周期由 KeepAliveService 管理（服务起来就起，服务销毁就停），
 * 设置页也可以手动开关。
 */
@SuppressLint("StaticFieldLeak") // 只持有 application context，且 WsHub 与进程同生命周期
object WsHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: AgentWsServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var appContext: Context? = null
    private val jobs = mutableListOf<Job>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start(context: Context, port: Int): Boolean {
        if (isRunning) {
            if (this.port == port) return true
            stop()
        }
        val app = context.applicationContext
        val token = AgentRuntime.settings.pairingToken()

        val listener = object : AgentWsServer.Listener {
            override fun onMessage(client: WebSocket, json: JSONObject) = onIncoming(app, json)

            override fun onAuthenticated(client: WebSocket) {
                val state = AgentRuntime.engine.state.value
                client.send(
                    Protocol.state(
                        state.taskId,
                        statusOf(state.paused, state.running),
                        state.step,
                        state.maxSteps,
                        state.lastMessage
                    ).toString()
                )
            }

            override fun onClosed(client: WebSocket) = Unit
        }

        return try {
            val srv = AgentWsServer(port, token, listener)
            srv.start()
            server = srv
            appContext = app
            this.port = port
            isRunning = true
            advertiser = NsdAdvertiser(app, "WhaleChan-${Build.MODEL}", port).also { it.start() }
            subscribe()
            WhaleLog.i("WsHub 启动成功，端口 $port")
            true
        } catch (t: Throwable) {
            WhaleLog.e("WsHub 启动失败", t)
            isRunning = false
            false
        }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { advertiser?.stop() }
        advertiser = null
        runCatching { server?.stop() }
        server = null
        isRunning = false
        WhaleLog.i("WsHub 已停止")
    }

    fun clientCount(): Int = server?.clientCount() ?: 0

    fun broadcast(json: JSONObject) {
        server?.broadcast(json)
    }

    private fun subscribe() {
        jobs += scope.launch {
            AgentRuntime.engine.state.collect { state ->
                broadcast(
                    Protocol.state(
                        state.taskId,
                        statusOf(state.paused, state.running),
                        state.step,
                        state.maxSteps,
                        state.lastMessage
                    )
                )
            }
        }

        jobs += scope.launch {
            AgentRuntime.engine.events.collect { event ->
                when (event) {
                    is AgentEvent.AssistantMessage ->
                        broadcast(Protocol.message("assistant", event.text, AgentRuntime.engine.state.value.taskId))

                    is AgentEvent.Notice -> broadcast(Protocol.notice(event.text))

                    is AgentEvent.Stuck -> broadcast(
                        Protocol.notice("${Persona.TIMEOUT_SIGNAL}｜指纹=${event.fingerprint}｜最后动作=${event.lastAction}")
                    )

                    is AgentEvent.Finished -> broadcast(
                        Protocol.taskEnd(
                            AgentRuntime.engine.state.value.taskId,
                            event.ok,
                            event.summary
                        )
                    )

                    is AgentEvent.Error -> broadcast(Protocol.error("AGENT", event.message))

                    else -> Unit
                }
            }
        }

        jobs += scope.launch {
            AgentRuntime.logs.events.collect { log -> broadcast(Protocol.step(log.toJson())) }
        }

        jobs += scope.launch {
            ConfirmationGate.requests.collect { request ->
                broadcast(
                    Protocol.confirmRequest(
                        request.id,
                        request.action,
                        request.reason,
                        request.detail
                    )
                )
            }
        }
    }

    private fun onIncoming(context: Context, json: JSONObject) {
        when (json.optString("type")) {
            Protocol.TYPE_CHAT -> {
                val text = json.optString("text").trim()
                if (text.isEmpty()) return
                val mode = json.optString("mode", Protocol.MODE_TASK)
                if (mode == Protocol.MODE_CHAT_ONLY) {
                    scope.launch {
                        val reply = AgentRuntime.engine.chat(text)
                        broadcast(Protocol.message("assistant", reply, ""))
                    }
                } else {
                    if (AgentRuntime.engine.isRunning()) {
                        broadcast(Protocol.notice("已有任务在跑，先停下再发新的"))
                    } else {
                        val ok = KeepAliveService.startTask(context, text)
                        broadcast(
                            if (ok) Protocol.notice("任务已提交：${text.take(40)}")
                            else Protocol.error("START_FAILED", "任务提交失败（可能被系统限制后台启动）")
                        )
                    }
                }
            }

            Protocol.TYPE_STOP -> AgentRuntime.engine.cancel("伴侣端停止")

            Protocol.TYPE_CONFIRM_RESULT -> {
                val id = json.optString("id")
                if (id.isNotBlank()) {
                    ConfirmationGate.deliver(id, json.optBoolean("approved"))
                    broadcast(Protocol.notice(if (json.optBoolean("approved")) "伴侣端已确认" else "伴侣端已拒绝"))
                }
            }

            Protocol.TYPE_ATTACHMENT -> handleAttachment(context, json)

            else -> Unit
        }
    }

    /**
     * 伴侣端发来的图片/文件：解码 → 存到 files/received → 交给 AttachmentBus。
     * 之后的任务 prompt 会带上附件说明；开启视觉模型时，图片会作为下一轮的视觉输入。
     */
    private fun handleAttachment(context: Context, json: JSONObject) {
        val name = json.optString("name").ifBlank { "attachment" }
        val kind = json.optString("kind", Protocol.KIND_FILE)
        val mime = json.optString("mime", "application/octet-stream")
        val data = json.optString("data")

        if (data.isBlank()) {
            broadcast(Protocol.error("ATTACHMENT_EMPTY", "附件内容为空"))
            return
        }
        if (data.length > 8_000_000) {
            broadcast(Protocol.error("ATTACHMENT_TOO_LARGE", "附件超过 4MB 上限"))
            return
        }
        val bytes = runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.size > Protocol.MAX_ATTACHMENT_BYTES) {
            broadcast(Protocol.error("ATTACHMENT_TOO_LARGE", "附件解码失败或超过大小上限"))
            return
        }

        val safeName = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(80)
        val dir = File(context.filesDir, "received").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}_$safeName")
        val written = runCatching { file.writeBytes(bytes); true }.getOrDefault(false)
        if (!written) {
            broadcast(Protocol.error("ATTACHMENT_SAVE_FAILED", "附件保存失败"))
            return
        }

        AttachmentBus.offer(
            kind = kind,
            name = name,
            mime = mime,
            file = file,
            imageBase64 = if (kind == Protocol.KIND_IMAGE) data else null
        )
        broadcast(
            Protocol.notice(
                "已收到${if (kind == Protocol.KIND_IMAGE) "图片" else "文件"}：$name（已保存）"
            )
        )
    }

    private fun statusOf(paused: Boolean, running: Boolean): String = when {
        paused -> "PAUSED"
        running -> "ACTING"
        else -> "IDLE"
    }

    /** 给设置页展示：“局域网内可连接的地址”。 */
    fun localAddresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses?.toList()?.forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        result.add(address.hostAddress.orEmpty())
                    }
                }
            }
        }
        return result.filter { it.isNotBlank() }
    }
}
