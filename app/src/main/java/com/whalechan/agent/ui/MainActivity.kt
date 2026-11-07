package com.whalechan.agent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whalechan.agent.R
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.LlmClient
import com.whalechan.agent.core.LlmConfig
import com.whalechan.agent.core.StepPolicy
import com.whalechan.agent.data.AppSettings
import com.whalechan.agent.net.WsHub
import com.whalechan.agent.service.KeepAliveService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvA11yStatus: TextView
    private lateinit var btnA11y: Button
    private lateinit var tvNotifStatus: TextView
    private lateinit var btnNotif: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var swKeepAlive: Switch
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnService: Button
    private lateinit var swAggressive: Switch
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModelId: EditText
    private lateinit var etTemperature: EditText
    private lateinit var btnTest: Button
    private lateinit var tvTestResult: TextView
    private lateinit var swAutoSend: Switch
    private lateinit var swVision: Switch
    private lateinit var swJsonMode: Switch
    private lateinit var swCuriosity: Switch
    private lateinit var swAutoReply: Switch
    private lateinit var etAutoReplyWhitelist: EditText
    private lateinit var etAutoReplyCooldown: EditText
    private lateinit var etPersona: EditText

    // v0.2 新增
    private lateinit var rgStepPolicy: RadioGroup
    private lateinit var etMaxSteps: EditText
    private lateinit var etMaxRuntime: EditText
    private lateinit var etStallLimit: EditText
    private lateinit var etStallWindow: EditText
    private lateinit var swUnlimitedConsent: Switch
    private lateinit var swCacheReplay: Switch
    private lateinit var swFullTrust: Switch
    private lateinit var swAllowCredential: Switch
    private lateinit var swWsEnabled: Switch
    private lateinit var etWsPort: EditText
    private lateinit var tvWsStatus: TextView
    private lateinit var tvToken: TextView
    private lateinit var btnCopyToken: Button
    private lateinit var btnRegenerateToken: Button
    private lateinit var swRelayEnabled: Switch
    private lateinit var etRelayUrl: EditText
    private lateinit var etRelayToken: EditText
    private lateinit var tvPairCode: TextView
    private lateinit var btnRegeneratePairCode: Button
    private lateinit var btnCompanion: Button
    private lateinit var btnDiagnostics: Button

    private lateinit var btnSave: Button
    private lateinit var btnChat: Button

    /** 加载设置时避免触发开关的监听器 */
    private var restoringUi = false

    /** 程序化设置 FULL_TRUST 开关时，避免再次弹出确认框 */
    private var ignoreFullTrustChange = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("没有通知权限：常驻通知和确认请求都看不到，任务会因超时而拒绝执行")
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadSettingsIntoViews()
        wireActions()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        runCatching { persist(showToast = false) }
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        btnA11y = findViewById(R.id.btnA11y)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        btnNotif = findViewById(R.id.btnNotif)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBattery = findViewById(R.id.btnBattery)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        swKeepAlive = findViewById(R.id.swKeepAlive)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnService = findViewById(R.id.btnService)
        swAggressive = findViewById(R.id.swAggressive)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModelId = findViewById(R.id.etModelId)
        etTemperature = findViewById(R.id.etTemperature)
        btnTest = findViewById(R.id.btnTest)
        tvTestResult = findViewById(R.id.tvTestResult)
        swAutoSend = findViewById(R.id.swAutoSend)
        swVision = findViewById(R.id.swVision)
        swJsonMode = findViewById(R.id.swJsonMode)
        swCuriosity = findViewById(R.id.swCuriosity)
        swAutoReply = findViewById(R.id.swAutoReply)
        etAutoReplyWhitelist = findViewById(R.id.etAutoReplyWhitelist)
        etAutoReplyCooldown = findViewById(R.id.etAutoReplyCooldown)
        etPersona = findViewById(R.id.etPersona)

        rgStepPolicy = findViewById(R.id.rgStepPolicy)
        etMaxSteps = findViewById(R.id.etMaxSteps)
        etMaxRuntime = findViewById(R.id.etMaxRuntime)
        etStallLimit = findViewById(R.id.etStallLimit)
        etStallWindow = findViewById(R.id.etStallWindow)
        swUnlimitedConsent = findViewById(R.id.swUnlimitedConsent)
        swCacheReplay = findViewById(R.id.swCacheReplay)
        swFullTrust = findViewById(R.id.swFullTrust)
        swAllowCredential = findViewById(R.id.swAllowCredential)
        swWsEnabled = findViewById(R.id.swWsEnabled)
        etWsPort = findViewById(R.id.etWsPort)
        tvWsStatus = findViewById(R.id.tvWsStatus)
        tvToken = findViewById(R.id.tvToken)
        btnCopyToken = findViewById(R.id.btnCopyToken)
        btnRegenerateToken = findViewById(R.id.btnRegenerateToken)
        btnCompanion = findViewById(R.id.btnCompanion)
        btnDiagnostics = findViewById(R.id.btnDiagnostics)
        swRelayEnabled = findViewById(R.id.swRelayEnabled)
        etRelayUrl = findViewById(R.id.etRelayUrl)
        etRelayToken = findViewById(R.id.etRelayToken)
        tvPairCode = findViewById(R.id.tvPairCode)
        btnRegeneratePairCode = findViewById(R.id.btnRegeneratePairCode)

        btnSave = findViewById(R.id.btnSave)
        btnChat = findViewById(R.id.btnChat)
    }

    private fun loadSettingsIntoViews() {
        restoringUi = true
        val s = AgentRuntime.settings.load()

        etBaseUrl.setText(s.baseUrl)
        etModelId.setText(s.modelId)
        etTemperature.setText(s.temperature.toString())
        swKeepAlive.isChecked = s.keepAliveEnabled
        swAggressive.isChecked = s.aggressiveWakeLock
        swAutoSend.isChecked = s.autoSendWithoutConfirm
        swVision.isChecked = s.useVision
        swJsonMode.isChecked = s.jsonMode
        swCuriosity.isChecked = s.allowCuriosity
        swAutoReply.isChecked = s.autoReplyEnabled
        etAutoReplyWhitelist.setText(s.autoReplyWhitelist)
        etAutoReplyCooldown.setText(s.autoReplyCooldownSec.toString())
        etPersona.setText(s.personaExtra)

        etMaxSteps.setText(s.maxSteps.toString())
        etMaxRuntime.setText(s.maxRuntimeMinutes.toString())
        etStallLimit.setText(s.stallLimit.toString())
        etStallWindow.setText(s.stallWindowSeconds.toString())
        swUnlimitedConsent.isChecked = s.unlimitedConsent
        swCacheReplay.isChecked = s.cacheReplayEnabled
        swFullTrust.isChecked = s.fullTrustMode
        swAllowCredential.isChecked = s.allowCredentialEntry
        swWsEnabled.isChecked = s.wsEnabled
        etWsPort.setText(s.wsPort.toString())
        swRelayEnabled.isChecked = s.relayEnabled
        etRelayUrl.setText(s.relayUrl)
        etRelayToken.setText(s.relayToken)
        tvPairCode.text = "中继配对码：${s.pairCode}"

        when (StepPolicy.from(s.stepPolicy)) {
            StepPolicy.LIMITED -> rgStepPolicy.check(R.id.rbLimited)
            StepPolicy.TIME_WINDOW -> rgStepPolicy.check(R.id.rbTimeWindow)
            StepPolicy.UNLIMITED -> rgStepPolicy.check(R.id.rbUnlimited)
        }

        etApiKey.setText("")
        etApiKey.hint = if (AgentRuntime.settings.hasApiKey()) {
            "API Key 已保存（留空则不修改）"
        } else {
            "API Key（用 Android Keystore 加密存储）"
        }
        restoringUi = false
    }

    private fun wireActions() {
        btnA11y.setOnClickListener {
            PermissionsHelper.openAccessibilitySettings(this)
            toast("在「已下载的服务」里找到「鲸鱼娘」并打开")
        }
        btnNotif.setOnClickListener {
            PermissionsHelper.openNotificationListenerSettings(this)
            toast("勾选「鲸鱼娘」以允许读取通知")
        }
        btnBattery.setOnClickListener {
            PermissionsHelper.requestIgnoreBatteryOptimizations(this)
        }
        btnOverlay.setOnClickListener {
            PermissionsHelper.openOverlaySettings(this)
        }
        btnService.setOnClickListener {
            if (KeepAliveService.isRunning) {
                KeepAliveService.stop(this)
                toast("已停止常驻服务")
            } else {
                saveSettings(showToast = false)
                KeepAliveService.start(this)
                toast("正在启动常驻服务…")
            }
            tvServiceStatus.postDelayed({ refreshStatus() }, 800)
        }
        btnTest.setOnClickListener { testConnection() }
        btnSave.setOnClickListener {
            saveSettings(showToast = true)
            refreshStatus()
        }
        btnChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        btnCompanion.setOnClickListener {
            startActivity(Intent(this, CompanionActivity::class.java))
        }
        btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        btnCopyToken.setOnClickListener {
            val token = AgentRuntime.settings.pairingToken()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("whale-token", token))
            toast("配对码已复制")
        }
        btnRegenerateToken.setOnClickListener {
            val token = AgentRuntime.settings.regeneratePairingToken()
            tvToken.text = "配对码：$token"
            toast("已重新生成，旧配对码立即失效")
        }

        // FULL_TRUST 必须二次确认：输入“我理解”才真正开启
        swFullTrust.setOnCheckedChangeListener { button, checked ->
            if (restoringUi || ignoreFullTrustChange) return@setOnCheckedChangeListener
            if (checked) {
                ignoreFullTrustChange = true
                button.isChecked = false
                ignoreFullTrustChange = false
                confirmFullTrust()
            }
        }

        // WebSocket 服务端：改开关立即生效
        swWsEnabled.setOnCheckedChangeListener { _, checked ->
            if (restoringUi) return@setOnCheckedChangeListener
            val port = etWsPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8787
            if (checked) {
                val ok = WsHub.start(this, port)
                if (!ok) {
                    swWsEnabled.isChecked = false
                    toast("启动失败：端口 $port 可能被占用")
                } else {
                    toast("WebSocket 服务端已启动（端口 $port）")
                }
            } else {
                WsHub.stop()
                toast("WebSocket 服务端已停止")
            }
            persist(showToast = false)
            refreshStatus()
        }

        // 公网中继：改开关立即生效
        swRelayEnabled.setOnCheckedChangeListener { _, checked ->
            if (restoringUi) return@setOnCheckedChangeListener
            if (checked) {
                val url = etRelayUrl.text.toString().trim()
                if (url.isBlank()) {
                    toast("先填中继地址，例如 wss://relay.example.com/ws")
                    swRelayEnabled.isChecked = false
                    return@setOnCheckedChangeListener
                }
                persist(showToast = false)
                val port = etWsPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8787
                WsHub.start(this, port)
                toast("正在连接中继…")
            } else {
                persist(showToast = false)
                if (!swWsEnabled.isChecked) WsHub.stop()
                toast("中继已关闭")
            }
            refreshStatus()
        }

        btnRegeneratePairCode.setOnClickListener {
            val code = AgentRuntime.settings.regeneratePairCode()
            tvPairCode.text = "中继配对码：$code"
            toast("新的中继配对码：$code")
        }
    }

    // ────────────────────────── 设置读写 ──────────────────────────

    private fun collectSettings(): AppSettings {
        val current = AgentRuntime.settings.load()
        val policy = when (rgStepPolicy.checkedRadioButtonId) {
            R.id.rbTimeWindow -> StepPolicy.TIME_WINDOW
            R.id.rbUnlimited -> StepPolicy.UNLIMITED
            else -> StepPolicy.LIMITED
        }
        return current.copy(
            baseUrl = etBaseUrl.text.toString().trim().ifBlank { current.baseUrl },
            modelId = etModelId.text.toString().trim().ifBlank { current.modelId },
            temperature = etTemperature.text.toString().toDoubleOrNull() ?: current.temperature,
            stepPolicy = policy.name,
            maxSteps = etMaxSteps.text.toString().toIntOrNull() ?: current.maxSteps,
            maxRuntimeMinutes = etMaxRuntime.text.toString().toIntOrNull() ?: current.maxRuntimeMinutes,
            stallLimit = etStallLimit.text.toString().toIntOrNull() ?: current.stallLimit,
            stallWindowSeconds = etStallWindow.text.toString().toIntOrNull() ?: current.stallWindowSeconds,
            unlimitedConsent = swUnlimitedConsent.isChecked,
            cacheReplayEnabled = swCacheReplay.isChecked,
            fullTrustMode = swFullTrust.isChecked,
            allowCredentialEntry = swAllowCredential.isChecked,
            wsEnabled = swWsEnabled.isChecked,
            wsPort = etWsPort.text.toString().toIntOrNull() ?: current.wsPort,
            relayEnabled = swRelayEnabled.isChecked,
            relayUrl = etRelayUrl.text.toString().trim(),
            relayToken = etRelayToken.text.toString().trim(),
            pairCode = current.pairCode,
            keepAliveEnabled = swKeepAlive.isChecked,
            aggressiveWakeLock = swAggressive.isChecked,
            autoSendWithoutConfirm = swAutoSend.isChecked,
            useVision = swVision.isChecked,
            jsonMode = swJsonMode.isChecked,
            allowCuriosity = swCuriosity.isChecked,
            autoReplyEnabled = swAutoReply.isChecked,
            autoReplyWhitelist = etAutoReplyWhitelist.text.toString(),
            autoReplyCooldownSec = etAutoReplyCooldown.text.toString().toIntOrNull()
                ?: current.autoReplyCooldownSec,
            personaExtra = etPersona.text.toString()
        ).normalized()
    }

    private fun saveSettings(showToast: Boolean) = persist(showToast)

    private fun persist(showToast: Boolean) {
        val settings = collectSettings()
        AgentRuntime.settings.save(settings)

        val rawKey = etApiKey.text.toString().trim()
        if (rawKey.isNotEmpty()) {
            AgentRuntime.settings.saveApiKey(rawKey)
            etApiKey.setText("")
            etApiKey.hint = "API Key 已保存（留空则不修改）"
        }

        if (settings.isEndpointCleartext()) {
            tvTestResult.text = "提示：当前是 http:// 端点，Key 与对话内容在局域网内明文传输。"
        }
        if (settings.stepPolicy == StepPolicy.UNLIMITED.name && !settings.unlimitedConsent) {
            tvTestResult.text = "无限模式还没勾选风险确认，任务启动时会被拦下。"
        }

        if (showToast) toast("已保存")
    }

    private fun confirmFullTrust() {
        val input = EditText(this).apply {
            hint = "在这里输入：我理解"
            setText("")
        }
        AlertDialog.Builder(this)
            .setTitle("开启完全信任模式？")
            .setMessage(
                "开启后，鲸鱼娘将不经确认执行任何 UI 操作，包括但不限于支付、权限授予、数据删除、系统设置修改。\n\n" +
                    "该模式没有操作前拦截，只有事后日志；错误操作可能无法撤销。\n\n" +
                    "如果确认，请输入「我理解」。"
            )
            .setView(input)
            .setPositiveButton("确认开启") { _, _ ->
                if (input.text.toString().trim() == "我理解") {
                    ignoreFullTrustChange = true
                    swFullTrust.isChecked = true
                    ignoreFullTrustChange = false
                    persist(showToast = false)
                    toast("已开启 FULL_TRUST，请你清楚自己在做什么")
                } else {
                    ignoreFullTrustChange = true
                    swFullTrust.isChecked = false
                    ignoreFullTrustChange = false
                    toast("输入不匹配，保持关闭")
                }
                refreshStatus()
            }
            .setNegativeButton("取消") { _, _ ->
                ignoreFullTrustChange = true
                swFullTrust.isChecked = false
                ignoreFullTrustChange = false
            }
            .setOnCancelListener {
                ignoreFullTrustChange = true
                swFullTrust.isChecked = false
                ignoreFullTrustChange = false
            }
            .show()
    }

    // ────────────────────────── 测试连接 ──────────────────────────

    private fun testConnection() {
        val current = AgentRuntime.settings.load()
        val baseUrl = etBaseUrl.text.toString().trim().ifBlank { current.baseUrl }
        val model = etModelId.text.toString().trim().ifBlank { current.modelId }
        val apiKey = etApiKey.text.toString().trim().ifBlank { AgentRuntime.settings.apiKey().orEmpty() }

        if (baseUrl.isBlank() || model.isBlank()) {
            tvTestResult.text = "先把 Base URL 和 Model ID 填上。"
            return
        }

        tvTestResult.text = "测试中…（会真的发一次最小请求）"
        btnTest.isEnabled = false

        lifecycleScope.launch {
            val result = runCatching {
                LlmClient().testConnection(LlmConfig(baseUrl, model, apiKey))
            }
            tvTestResult.text = result.getOrElse { error ->
                "失败：${error.message ?: error.javaClass.simpleName}"
            }
            btnTest.isEnabled = true
        }
    }

    // ────────────────────────── 权限与状态 ──────────────────────────

    private fun refreshStatus() {
        val accessibility = when {
            PermissionsHelper.isAccessibilityConnected() -> "已连接 ✅"
            PermissionsHelper.isAccessibilityEnabledInSettings(this) -> "已在系统里勾选，等待连接…"
            else -> "未开启 ❌"
        }
        tvA11yStatus.text = "无障碍服务：$accessibility"

        val listener = if (PermissionsHelper.isNotificationListenerEnabled(this)) "已授权 ✅" else "未授权 ❌"
        tvNotifStatus.text = "通知使用权：$listener"

        val battery = if (PermissionsHelper.isIgnoringBatteryOptimizations(this)) "已豁免 ✅" else "未豁免 ⚠️"
        tvBatteryStatus.text = "电池优化豁免：$battery"

        val overlay = if (PermissionsHelper.canDrawOverlays(this)) "已允许 ✅" else "未允许（可选）⚠️"
        tvOverlayStatus.text = "悬浮窗：$overlay"

        val notification = if (PermissionsHelper.hasNotificationPermission(this)) "" else "｜通知权限未开 ⚠️"
        tvServiceStatus.text = "前台服务：" + if (KeepAliveService.isRunning) "运行中 ✅" else "未运行 ⚠️"
        btnService.text = if (KeepAliveService.isRunning) "停止常驻服务" else "启动常驻服务"

        val token = AgentRuntime.settings.pairingToken()
        tvToken.text = "配对码：$token"

        tvWsStatus.text = if (WsHub.isRunning || WsHub.relayState != "OFF") {
            val addresses = WsHub.localAddresses().joinToString(" / ").ifBlank { "未知地址" }
            "服务端：${if (WsHub.isRunning) "运行中" else "未运行"} · 端口 ${WsHub.port} · 连接数 ${WsHub.clientCount()}\n" +
                "地址：$addresses\n中继：${WsHub.relayState}"
        } else {
            "服务端：未运行 · 中继：未启用"
        }

        val runState = AgentRuntime.engine.state.value
        tvStatus.text = if (runState.running) {
            val budget = if (runState.maxSteps > 0) runState.maxSteps.toString() else "∞"
            "正在执行：${runState.goal.take(20)}（${runState.step}/$budget）"
        } else {
            val mode = if (KeepAliveService.isRunning) "常驻中" else "未常驻"
            "$mode · 模型 ${AgentRuntime.settings.load().modelId}$notification"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionsHelper.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
