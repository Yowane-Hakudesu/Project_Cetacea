package com.whalechan.agent.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.whalechan.agent.R
import com.whalechan.agent.WhaleApp
import com.whalechan.agent.core.NotificationPermission
import com.whalechan.agent.core.WhaleLog

/**
 * 步数上限暂停时的通知：给用户“继续 / 放弃”两个动作。
 * 10 分钟内没有回应就按放弃处理（由 AgentEngine 的 withTimeoutOrNull 保证）。
 */
object PauseNotifier {

    const val NOTIF_ID = 2001

    @SuppressLint("MissingPermission") // 已用 NotificationPermission.canPost 做运行时检查
    fun notify(context: Context, taskId: String, step: Int) {
        if (!NotificationPermission.canPost(context)) {
            WhaleLog.w("没有通知权限，暂停请求只能靠对话页/伴侣端")
            return
        }

        val continueIntent = Intent(context, TaskActionReceiver::class.java).apply {
            action = TaskActionReceiver.ACTION_CONTINUE
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val abortIntent = Intent(context, TaskActionReceiver::class.java).apply {
            action = TaskActionReceiver.ACTION_ABORT
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
        }

        val continuePi = PendingIntent.getBroadcast(
            context, 21, continueIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val abortPi = PendingIntent.getBroadcast(
            context, 22, abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, WhaleApp.CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(context.getString(R.string.pause_title))
            .setContentText(context.getString(R.string.pause_text, step))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.pause_text, step))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.pause_continue), continuePi)
            .addAction(0, context.getString(R.string.pause_abort), abortPi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        }.onFailure { WhaleLog.w("发送暂停通知失败", it) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }
}
