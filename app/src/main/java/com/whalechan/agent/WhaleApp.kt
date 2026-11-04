package com.whalechan.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.WhaleLog

class WhaleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        AgentRuntime.init(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val keepAlive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            getString(R.string.channel_keepalive),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_keepalive_desc)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }

        val task = NotificationChannel(
            CHANNEL_TASK,
            getString(R.string.channel_task),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_task_desc)
        }

        val confirm = NotificationChannel(
            CHANNEL_CONFIRM,
            getString(R.string.channel_confirm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_confirm_desc)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        manager.createNotificationChannels(listOf(keepAlive, task, confirm))
        WhaleLog.i("通知渠道已就绪")
    }

    companion object {
        const val CHANNEL_KEEPALIVE = "whale_keepalive"
        const val CHANNEL_TASK = "whale_task"
        const val CHANNEL_CONFIRM = "whale_confirm"
    }
}
