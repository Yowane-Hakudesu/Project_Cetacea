package com.whalechan.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.whalechan.agent.core.WhaleLog
import com.whalechan.agent.data.SettingsRepository

/**
 * 开机 / 应用更新 / 被划掉后重启 的恢复入口。
 *
 * 注意：Android 12+ 对“从后台启动前台服务”有严格限制，BOOT_COMPLETED 属于允许的豁免场景；
 * 但部分 OEM（MIUI / EMUI / ColorOS）还会额外要求“允许自启动”，这只能由用户手动打开。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val settings = SettingsRepository(context)
        if (!settings.load().keepAliveEnabled) {
            WhaleLog.i("开机恢复：用户已关闭常驻，跳过")
            return
        }

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_START)
            )
            WhaleLog.i("已恢复常驻前台服务（action=$action）")
        }.onFailure { WhaleLog.w("恢复常驻服务失败（可能被系统或 OEM 拦截）", it) }
    }

    companion object {
        const val ACTION_RESTART = "com.whalechan.agent.action.RESTART"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            ACTION_RESTART
        )
    }
}
