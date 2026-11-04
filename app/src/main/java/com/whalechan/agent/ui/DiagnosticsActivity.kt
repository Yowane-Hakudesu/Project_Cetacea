package com.whalechan.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.whalechan.agent.R
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.net.WsHub
import com.whalechan.agent.service.KeepAliveService

/**
 * 熄屏操控诊断。
 *
 * 熄屏后“没反应”通常是三件事叠加：
 *  1. CPU 睡了（WakeLock 没拿到或被系统回收）；
 *  2. 锁屏下无障碍手势被拒绝；
 *  3. 厂商 ROM 把无障碍/前台服务杀掉了。
 * 这里只做检查与跳转，不尝试绕过任何系统限制。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var tvDiagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)

        findViewById<Button>(R.id.btnDiagA11y).setOnClickListener {
            PermissionsHelper.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.btnDiagBattery).setOnClickListener {
            PermissionsHelper.requestIgnoreBatteryOptimizations(this)
        }
        findViewById<Button>(R.id.btnDiagNotif).setOnClickListener {
            PermissionsHelper.openNotificationListenerSettings(this)
        }
        findViewById<Button>(R.id.btnDiagOverlay).setOnClickListener {
            PermissionsHelper.openOverlaySettings(this)
        }
        findViewById<Button>(R.id.btnDiagRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnDiagCopy).setOnClickListener { copyReport() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11yAlive = WhaleAccessibilityService.isReady()
        val a11yEnabled = PermissionsHelper.isAccessibilityEnabledInSettings(this)
        val listener = PermissionsHelper.isNotificationListenerEnabled(this)
        val battery = PermissionsHelper.isIgnoringBatteryOptimizations(this)
        val overlay = PermissionsHelper.canDrawOverlays(this)
        val notifPermission = PermissionsHelper.hasNotificationPermission(this)
        val serviceRunning = KeepAliveService.isRunning
        val wakeHeld = KeepAliveService.wakeLockHeldMillis()

        tvDiagnostics.text = buildString {
            appendLine("【熄屏操控诊断】")
            appendLine()
            appendLine(mark(a11yAlive) + "无障碍服务存活：" + yesNo(a11yAlive) +
                if (!a11yAlive && a11yEnabled) "（系统已勾选但服务没连上，试试关掉再开）" else "")
            appendLine(mark(a11yEnabled) + "无障碍系统开关：" + yesNo(a11yEnabled))
            appendLine(mark(listener) + "通知使用权：" + yesNo(listener))
            appendLine(mark(notifPermission) + "通知权限（33+）：" + yesNo(notifPermission))
            appendLine(mark(battery) + "电池优化豁免：" + yesNo(battery))
            appendLine(mark(overlay) + "悬浮窗（后台启动豁免）：" + yesNo(overlay))
            appendLine(mark(serviceRunning) + "常驻前台服务：" + yesNo(serviceRunning))
            appendLine(
                mark(wakeHeld > 0) + "PARTIAL_WAKE_LOCK：" +
                    if (wakeHeld > 0) "持有中（${formatDuration(wakeHeld)}）" else "未持有（空闲时不持有是正常的）"
            )
            appendLine(
                mark(WsHub.isRunning) + "WebSocket 服务端：" +
                    if (WsHub.isRunning) "运行中 · 端口 ${WsHub.port} · 连接 ${WsHub.clientCount()} 台" else "未运行"
            )
            if (WsHub.isRunning) {
                appendLine("   监听地址：" + WsHub.localAddresses().joinToString(" / ").ifBlank { "未知" })
            }
            appendLine()
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine()
            appendLine("【厂商差异提示】")
            appendLine("· 小米 / Redmi：设置 → 应用设置 → 鲸鱼娘 → 权限 → 锁屏显示；电池 → 无限制；自启动 → 允许")
            appendLine("· 华为 / 荣耀：应用启动管理 → 手动管理（允许自启动/关联启动/后台活动）；受保护应用加白")
            appendLine("· OPPO / 一加 / realme：电池 → 应用耗电管理 → 允许后台运行；允许自启动")
            appendLine("· vivo / iQOO：电池 → 后台耗电管理 → 允许后台高耗电；自启动 → 允许")
            appendLine("· 原生 Android：电池优化白名单 + 无障碍常开即可；锁屏时无障碍手势仍可能被系统拒绝")
            appendLine()
            appendLine("说明：锁屏状态下 dispatchGesture 是否可用由系统与厂商决定，应用无法绕过。")
            appendLine("若锁屏诊断全部通过但仍无响应，请保持屏幕点亮后重试。")
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("whale-diagnostics", tvDiagnostics.text))
        Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
    }

    private fun mark(ok: Boolean): String = if (ok) "✅ " else "❌ "
    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes 分 $seconds 秒" else "$seconds 秒"
    }
}
