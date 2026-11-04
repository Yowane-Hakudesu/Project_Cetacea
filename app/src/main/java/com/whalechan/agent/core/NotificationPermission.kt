package com.whalechan.agent.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS 的运行时守卫。
 *
 * 从 API 33 起，没有该权限时调用 NotificationManagerCompat.notify() 不会抛异常，
 * 但通知会被系统静默丢弃——对“确认请求”这种靠通知传达的功能是致命的，
 * 所以发通知前必须先问一句，并在没有权限时走别的通道（对话页弹窗）或明确失败。
 */
object NotificationPermission {

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
