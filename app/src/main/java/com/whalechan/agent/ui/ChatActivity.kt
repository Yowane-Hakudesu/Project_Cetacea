package com.whalechan.agent.ui

import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.whalechan.agent.R
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.core.AgentEngine
import com.whalechan.agent.core.AgentEvent
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.ConfirmRequest
import com.whalechan.agent.core.ConfirmationGate
import com.whalechan.agent.core.Persona
import com.whalechan.agent.service.KeepAliveService
import kotlinx.coroutines.launch

/**
 * 对话页：既是聊天窗口，也是任务的“驾驶舱”。
 * 打开「允许我动手」后，下一条消息会被当成任务交给 AgentEngine；
 * 任务过程中的每一步都会以灰色小字回显，敏感动作会弹出确认框。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var tvChatStatus: TextView
    private lateinit var svChat: ScrollView
    private lateinit var llMessages: LinearLayout
    private lateinit var etChatInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnStop: Button
    private lateinit var swOperate: Switch

    private var confirmDialog: AlertDialog? = null
    private var lastConfirmId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        bindViews()

        addBubble(
            "我是鲸鱼娘，一只住在你手机里的蓝色大肥鱼。「允许我动手」默认开着：" +
                "你发的话会当成任务去执行；关掉它就只聊天。",
            isUser = false
        )

        val prefs = getSharedPreferences("chat_ui", MODE_PRIVATE)
        swOperate.isChecked = prefs.getBoolean("operate", true) // 默认允许她动手
        swOperate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("operate", checked).apply()
        }

        btnSend.setOnClickListener { sendMessage() }
        btnStop.setOnClickListener {
            AgentRuntime.engine.cancel("你按了停止")
            addSystem("已请求停止当前任务")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AgentRuntime.engine.state.collect { renderState(it) } }
                launch { AgentRuntime.engine.events.collect { onAgentEvent(it) } }
                launch { ConfirmationGate.requests.collect { showConfirmDialog(it) } }
            }
        }
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    private fun bindViews() {
        tvChatStatus = findViewById(R.id.tvChatStatus)
        svChat = findViewById(R.id.svChat)
        llMessages = findViewById(R.id.llMessages)
        etChatInput = findViewById(R.id.etChatInput)
        btnSend = findViewById(R.id.btnSend)
        btnStop = findViewById(R.id.btnStop)
        swOperate = findViewById(R.id.swOperate)
    }

    // ────────────────────────── 发送 ──────────────────────────

    private fun sendMessage() {
        val text = etChatInput.text.toString().trim()
        if (text.isEmpty()) return
        etChatInput.setText("")
        addBubble(text, isUser = true)

        if (swOperate.isChecked) {
            startTask(text)
        } else {
            lifecycleScope.launch {
                val reply = AgentRuntime.engine.chat(text)
                addBubble(reply, isUser = false)
            }
        }
    }

    private fun startTask(goal: String) {
        if (!WhaleAccessibilityService.isReady()) {
            addSystem("无障碍没开，我看不见屏幕。先去设置页打开「鲸鱼娘」的无障碍服务。")
            return
        }
        if (AgentRuntime.engine.isRunning()) {
            addSystem("上一件事还没做完，先等我一下，或者按「停」。")
            return
        }
        val started = KeepAliveService.startTask(this, goal)
        if (!started) addSystem("任务没能启动（可能被系统拦住了前台服务）。")
    }

    // ────────────────────────── 事件渲染 ──────────────────────────

    private fun renderState(state: AgentEngine.RunState) {
        tvChatStatus.text = if (state.running) {
            val budget = if (state.maxSteps > 0) state.maxSteps.toString() else "∞"
            val paused = if (state.paused) "（已暂停）" else ""
            "正在执行 · 第 ${state.step}/$budget 步 · ${state.lastMessage}$paused"
        } else {
            "待命中" + if (state.lastMessage.isNotBlank()) " · ${state.lastMessage}" else ""
        }
    }

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> {
                val budget = if (event.maxSteps > 0) event.maxSteps.toString() else "∞"
                tvChatStatus.text = "思考中…（第 ${event.step}/$budget 步）"
            }

            is AgentEvent.AssistantMessage -> addBubble(event.text, isUser = false)

            is AgentEvent.Notice -> addSystem("⚠️ ${event.text}")

            is AgentEvent.Planned -> addSystem("· 计划：${event.action}")

            is AgentEvent.Acted -> addSystem(
                if (event.ok) "✅ ${event.action}｜${event.detail}" else "❌ ${event.action}｜${event.detail}"
            )

            is AgentEvent.Paused -> addSystem(
                "⏸ 已暂停在第 ${event.step} 步：去通知栏点「继续」或「放弃」，10 分钟内不回应按放弃处理。"
            )

            AgentEvent.Resumed -> addSystem("▶ 继续执行")

            is AgentEvent.Stuck -> addSystem(
                "⚠️ ${Persona.TIMEOUT_SIGNAL}（指纹 ${event.fingerprint.take(28)}）"
            )

            is AgentEvent.Finished -> {
                addBubble(if (event.ok) event.summary else "没做成：${event.summary}", isUser = false)
                addSystem(if (event.ok) "任务结束：成功" else "任务结束：未完成")
            }

            is AgentEvent.Error -> addSystem("错误：${event.message}")
        }
    }

    private fun showConfirmDialog(request: ConfirmRequest) {
        if (isFinishing || isDestroyed) return
        if (confirmDialog?.isShowing == true) return
        if (lastConfirmId == request.id) return
        lastConfirmId = request.id

        confirmDialog = AlertDialog.Builder(this)
            .setTitle("🐋 ${request.action}")
            .setMessage("${request.reason}\n\n${request.detail.take(700)}")
            .setPositiveButton(R.string.confirm_approve) { dialog, _ ->
                ConfirmationGate.deliver(request.id, true)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.confirm_deny) { dialog, _ ->
                ConfirmationGate.deliver(request.id, false)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    // ────────────────────────── 气泡 ──────────────────────────

    private fun addBubble(text: String, isUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(
                ContextCompat.getColor(
                    this@ChatActivity,
                    if (isUser) R.color.whale_user_text else R.color.whale_text_primary
                )
            )
            setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_whale)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
            addView(
                bubble,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        llMessages.addView(
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
            setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.whale_text_secondary))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        llMessages.addView(
            line,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scrollToBottom()
    }

    private fun scrollToBottom() {
        svChat.post { svChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("unused")
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
