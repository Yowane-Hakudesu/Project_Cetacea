package com.whalechan.agent.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.whalechan.agent.core.AgentEvent
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.AttachmentBus
import com.whalechan.agent.core.ConfirmationGate
import com.whalechan.agent.core.Persona
import com.whalechan.agent.core.WhaleLog
import com.whalechan.agent.data.AppSettings
import com.whalechan.agent.service.KeepAliveService
import com.whalechan.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 通信中枢：本地 WebSocket 服务端（同 Wi-Fi 直连）+ 公网中继（跨网/校园网）。
 *
 * 两者可以同时开：伴侣端在局域网时直连，不在时走中继，协议是同一套。
 * 生命周期由 KeepAliveService 管理。
 */
@SuppressLint("StaticFieldLeak")
object WsHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: AgentWsServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var relay: RelayClient? = null
    private var relayRetryJob: Job? = null

    /** 用户是否还想要中继；stop() 后为 false，防止 onClose 回调把连接又拉起来。 */
    @Volatile
    private var relayWanted = false
    private var appContext: Context? = null
    private val jobs = mutableListOf<Job>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var relayState: String = "OFF"
        private set

    @Synchronized
    fun start(context: Context, port: Int): Boolean {
        val settings = AgentRuntime.settings.load()
        val needLocal = settings.wsEnabled
        val needRelay = settings.relayEnabled && settings.relayUrl.isNotBlank()

        if (!needLocal && !needRelay) {
            WhaleLog.w("WsHub.start 被调用，但本地服务端与中继都未启用")
            return false
        }

        if (server != null || relay != null) {
            if (this.port == port && !needRelay) return true
            stop()
        }

        val app = context.applicationContext
        appContext = app

        if (needLocal) {
            val ok = startLocal(app, port)
            if (!ok && !needRelay) return false
        }
        if (needRelay) {
            relayWanted = true
            startRelay(app, settings)
        }
        subscribe()
        return true
    }

    private fun startLocal(app: Context, port: Int): Boolean {
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
            this.port = port
            isRunning = true
            runCatching {
                advertiser = NsdAdvertiser(app, "WhaleChan-${Build.MODEL}", port).also { it.start() }
            }
            WhaleLog.i("局域网 WebSocket 已启动，端口 $port")
            true
        } catch (t: Throwable) {
            WhaleLog.e("局域网 WebSocket 启动失败", t)
            false
        }
    }

    /** 手机主动连公网中继；断线 5 秒后自动重连（校园网掉线很常见）。 */
    private fun startRelay(context: Context, settings: AppSettings) {
        if (!relayWanted) return
        if (relay != null) return
        relayState = "CONNECTING"

        val listener = object : RelayClient.Listener {
            override fun onReady() {
                relayState = "ONLINE"
                isRunning = true
                WhaleLog.i("中继已就绪：${settings.relayUrl}")
                broadcast(
                    Protocol.notice("中继已连接，配对码 ${settings.pairCode}")
                )
            }

            override fun onClosed(reason: String) {
                relayState = "RETRYING"
                WhaleLog.w("中继断开：$reason")
                relay = null
                scheduleRelayRetry(context, settings)
            }

            override fun onPayload(json: JSONObject) {
                onIncoming(context, json)
            }

            override fun onError(message: String) {
                WhaleLog.w("中继错误：$message")
                relayState = "ERROR"
            }
        }

        val client = RelayClient(
            url = settings.relayUrl,
            token = settings.relayToken,
            pairCode = settings.pairCode,
            deviceId = deviceId(context),
            listener = listener
        )
        relay = client
        runCatching { client.connect() }.onFailure {
            WhaleLog.e("中继连接失败", it)
            relay = null
            relayState = "ERROR"
            scheduleRelayRetry(context, settings)
        }
    }

    private fun scheduleRelayRetry(context: Context, settings: AppSettings) {
        if (!relayWanted) return
        if (!settings.relayEnabled || settings.relayUrl.isBlank()) return
        if (relayRetryJob?.isActive == true) return
        relayRetryJob = scope.launch {
            delay(5_000L)
            if (!relayWanted) return@launch
            val latest = AgentRuntime.settings.load()
            if (latest.relayEnabled && latest.relayUrl.isNotBlank() && relay == null) {
                startRelay(context, latest)
            }
        }
    }

    @Synchronized
    fun stop() {
        relayWanted = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        relayRetryJob?.cancel()
        relayRetryJob = null
        runCatching { relay?.shutdown() }
        relay = null
        relayState = "OFF"
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
        relay?.sendPayload(json)
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
                relay?.sendPayload(
                    Protocol.relayState(
                        status = when {
                            state.paused -> Protocol.STATUS_WAITING
                            state.running -> Protocol.STATUS_THINKING
                            else -> Protocol.STATUS_IDLE
                        },
                        step = state.step,
                        maxSteps = state.maxSteps,
                        detail = state.lastMessage
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

                    is AgentEvent.Acted -> relay?.sendPayload(
                        Protocol.relayState(
                            status = Protocol.STATUS_EXECUTING,
                            step = event.step,
                            maxSteps = AgentRuntime.engine.state.value.maxSteps,
                            detail = event.detail
                        )
                    )

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
            // 伴侣端明确意图：直接当任务执行，不再经过“聊天/任务”判断
            Protocol.TYPE_COMMAND -> {
                val text = json.optString("text").trim()
                if (text.isEmpty()) return
                if (AgentRuntime.engine.isRunning()) {
                    com.whalechan.agent.notify.PendingInbox.offer(
                        com.whalechan.agent.notify.PendingInbox.Item(goal = text, conversation = "伴侣端指令")
                    )
                    broadcast(Protocol.notice("正在忙，指令已排队：${text.take(30)}"))
                } else {
                    val ok = KeepAliveService.startTask(context, text)
                    broadcast(
                        if (ok) Protocol.notice("任务已提交：${text.take(40)}")
                        else Protocol.error("START_FAILED", "任务提交失败（可能被系统限制后台启动）")
                    )
                }
            }

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

    private fun deviceId(context: Context): String {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return if (!id.isNullOrBlank()) id else AgentRuntime.settings.pairingToken()
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
