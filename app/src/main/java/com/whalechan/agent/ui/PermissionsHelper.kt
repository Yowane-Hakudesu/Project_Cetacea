package com.whalechan.agent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.service.KeepAliveService

/**
 * 权限状态的唯一判定入口。
 * 所有“去开启”按钮都只是把用户送到系统页面，真正的授权动作只能由用户在系统 UI 里完成。
 */
object PermissionsHelper {

    // ── 无障碍 ──
    fun isAccessibilityConnected(): Boolean = WhaleAccessibilityService.isReady()

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val expected = "${context.packageName}/${WhaleAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        launchSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ── 通知使用权 ──
    fun isNotificationListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openNotificationListenerSettings(context: Context) {
        launchSafely(context, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    // ── 电池优化 ──
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 直接申请豁免；部分 ROM 没有该页面时退回到列表页。 */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        val launched = runCatching { activity.startActivity(intent) }.isSuccess
        if (!launched) openBatteryOptimizationSettings(activity)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        launchSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    // ── 悬浮窗（后台启动 Activity 的豁免条件） ──
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openOverlaySettings(context: Context) {
        launchSafely(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    // ── 通知权限（API 33+） ──
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isServiceRunning(): Boolean = KeepAliveService.isRunning

    private fun launchSafely(context: Context, intent: Intent) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
