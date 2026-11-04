package com.whalechan.agent.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whalechan.agent.R
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.data.log.ErrorCategory
import com.whalechan.protocol.CompanionClient
import com.whalechan.protocol.NsdDiscovery
import com.whalechan.protocol.Protocol
import org.json.JSONObject

/**
 * 伴侣模式：在另一台设备（同一 Wi-Fi）上连接手机端的 WebSocket 服务端。
 *
 * 三种用法：
 *  1. 聊天即指令：默认消息带 TASK，手机端会交给 Agent 执行；关掉开关就只聊天；
 *  2. 时间线：手机端每一步的日志实时推送过来，可按结果/错误分类查看；
 *  3. 远程确认：手机端需要确认时会推 confirm_request，这里直接点确认/拒绝。
 */
class CompanionActivity : AppCompatActivity() {

    private lateinit var tvCompanionStatus: TextView
    private lateinit var svCompanion: ScrollView
    private lateinit var llCompanionLog: LinearLayout
    private lateinit var etHost: EditText
    private lateinit var etToken: EditText
    private lateinit var etCompanionInput: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnCompanionSend: Button
    private lateinit var btnCompanionStop: Button
    private lateinit var swTaskMode: Switch
    private lateinit var swShowTimeline: Switch

    private var client: CompanionClient? = null
    private var discovery: NsdDiscovery? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_companion)
        bindViews()

        etToken.setText(AgentRuntime.settings.pairingToken())

        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnDiscover.setOnClickListener { discover() }
        btnCompanionSend.setOnClickListener { send() }
        btnCompanionStop.setOnClickListener {
            client?.sendStop()
            addSystem("已请求手机端停止当前任务")
        }

        addSystem("输入手机的 IP:端口和配对码后点「连接」。手机端需先在设置页开启 WebSocket 服务端。")
        addSystem("默认消息会当作任务执行；只想聊天就关掉上面的开关。")
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        client?.shutdown()
        client = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        tvCompanionStatus = findViewById(R.id.tvCompanionStatus)
        svCompanion = findViewById(R.id.svCompanion)
        llCompanionLog = findViewById(R.id.llCompanionLog)
        etHost = findViewById(R.id.etHost)
        etToken = findViewById(R.id.etToken)
        etCompanionInput = findViewById(R.id.etCompanionInput)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnCompanionSend = findViewById(R.id.btnCompanionSend)
        btnCompanionStop = findViewById(R.id.btnCompanionStop)
        swTaskMode = findViewById(R.id.swTaskMode)
        swShowTimeline = findViewById(R.id.swShowTimeline)
    }

    // ────────────────────────── 连接 ──────────────────────────

    private fun connect() {
        val raw = etHost.text.toString().trim()
        if (raw.isEmpty()) {
            toast("先填手机的 IP:端口")
            return
        }
        val token = etToken.text.toString().trim()
        if (token.isEmpty()) {
            toast("配对码不能为空（手机端设置页可查看）")
            return
        }

        val hostPort = if (raw.contains(':')) raw else "$raw:8787"
        val url = "ws://$hostPort"
        disconnect()

        addSystem("正在连接 $url …")
        tvCompanionStatus.text = "连接中…"

        val listener = object : CompanionClient.Listener {
            override fun onConnected() {
                runOnUiThread {
                    tvCompanionStatus.text = "已连接 $hostPort"
                    addSystem("握手已发出，等待服务端确认…")
                }
            }

            override fun onDisconnected(reason: String) {
                runOnUiThread {
                    tvCompanionStatus.text = "已断开"
                    addSystem("连接断开：$reason")
                }
            }

            override fun onMessage(json: JSONObject) {
                runOnUiThread { handleServerMessage(json) }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    tvCompanionStatus.text = "连接错误"
                    addSystem("错误：$message")
                }
            }
        }

        val c = CompanionClient(url, token, listener)
        client = c
        runCatching { c.connect() }.onFailure {
            addSystem("连接失败：${it.message}")
            client = null
        }
    }

    private fun disconnect() {
        client?.shutdown()
        client = null
        tvCompanionStatus.text = "未连接"
    }

    private fun discover() {
        discovery?.stop()
        tvCompanionStatus.text = "搜索中…"
        val d = NsdDiscovery(
            context = this,
            onFound = { name, host, port ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    etHost.setText("$host:$port")
                    tvCompanionStatus.text = "发现 $name → $host:$port"
                    addSystem("发现设备：$name（$host:$port），点「连接」即可")
                }
            },
            onLost = { name ->
                runOnUiThread { if (!isFinishing) addSystem("设备消失：$name") }
            }
        )
        discovery = d
        d.start()
        handler.postDelayed({
            discovery?.stop()
            discovery = null
            if (!isFinishing && tvCompanionStatus.text == "搜索中…") {
                tvCompanionStatus.text = "未发现设备（可手动输入 IP）"
            }
        }, 6000L)
    }

    // ────────────────────────── 消息处理 ──────────────────────────

    private fun handleServerMessage(json: JSONObject) {
        when (json.optString("type")) {
            Protocol.TYPE_HELLO_OK -> {
                val device = json.optString("device")
                tvCompanionStatus.text = "已连接 · $device"
                addSystem("已连接：$device（协议 v${json.optInt("protocol")}）")
            }

            Protocol.TYPE_STATE -> {
                val status = json.optString("status")
                val step = json.optInt("step")
                val max = json.optInt("maxSteps")
                val budget = if (max > 0) max.toString() else "∞"
                tvCompanionStatus.text = "状态：$status · 第 $step/$budget 步"
            }

            Protocol.TYPE_MESSAGE -> {
                val role = json.optString("role")
                addBubble(json.optString("text"), isUser = role == "user")
            }

            Protocol.TYPE_STEP -> {
                if (swShowTimeline.isChecked) {
                    json.optJSONObject("record")?.let { addTimeline(it) }
                }
            }

            Protocol.TYPE_CONFIRM_REQUEST -> showConfirm(json)

            Protocol.TYPE_TASK_END -> {
                val ok = json.optBoolean("ok")
                addSystem("任务结束：${if (ok) "成功" else "未完成"} · ${json.optString("summary")}")
            }

            Protocol.TYPE_NOTICE -> addSystem("· ${json.optString("text")}")

            Protocol.TYPE_ERROR -> addSystem("错误：${json.optString("code")} ${json.optString("message")}")

            Protocol.TYPE_PONG -> Unit

            else -> addSystem("· 收到 ${json.optString("type")}")
        }
    }

    private fun showConfirm(json: JSONObject) {
        if (isFinishing || isDestroyed) return
        val id = json.optString("id")
        AlertDialog.Builder(this)
            .setTitle("🐋 ${json.optString("action")}")
            .setMessage("${json.optString("reason")}\n\n${json.optString("detail").take(600)}")
            .setPositiveButton(R.string.confirm_approve) { _, _ ->
                client?.sendConfirm(id, true)
                addSystem("已在伴侣端确认：id=$id")
            }
            .setNegativeButton(R.string.confirm_deny) { _, _ ->
                client?.sendConfirm(id, false)
                addSystem("已在伴侣端拒绝：id=$id")
            }
            .show()
    }

    private fun send() {
        val text = etCompanionInput.text.toString().trim()
        if (text.isEmpty()) return
        val c = client
        if (c == null) {
            toast("先连接手机端")
            return
        }
        etCompanionInput.setText("")
        addBubble(text, isUser = true)

        val mode = if (swTaskMode.isChecked) Protocol.MODE_TASK else Protocol.MODE_CHAT_ONLY
        runCatching { c.sendChat(text, mode) }.onFailure { addSystem("发送失败：${it.message}") }
    }

    // ────────────────────────── 渲染 ──────────────────────────

    private fun addBubble(text: String, isUser: Boolean) {
        if (text.isBlank()) return
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(
                ContextCompat.getColor(
                    this@CompanionActivity,
                    if (isUser) R.color.whale_user_text else R.color.whale_text_primary
                )
            )
            setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_whale)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(
                bubble,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        llCompanionLog.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scrollToBottom()
    }

    private fun addSystem(text: String) {
        val line = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@CompanionActivity, R.color.whale_text_secondary))
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        llCompanionLog.addView(
            line,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scrollToBottom()
    }

    private fun addTimeline(record: JSONObject) {
        val step = record.optInt("stepIndex")
        val action = record.optString("actionType")
        val result = record.optString("result")
        val category = record.optString("errorCategory")
        val detail = record.optString("errorDetail")
        val categoryLabel = if (category.isBlank()) "" else "［${ErrorCategory.fromName(category).label}］"
        val before = record.optString("screenFingerprintBefore").take(18)
        val after = record.optString("screenFingerprintAfter").take(18)
        addSystem("⏱ #$step $action → $result$categoryLabel\n   指纹 $before → $after\n   ${detail.take(80)}")
    }

    private fun scrollToBottom() {
        svCompanion.post { svCompanion.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
