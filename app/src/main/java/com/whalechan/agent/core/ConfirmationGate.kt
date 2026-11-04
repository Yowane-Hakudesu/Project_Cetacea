package com.whalechan.agent.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.whalechan.agent.R
import com.whalechan.agent.WhaleApp
import com.whalechan.agent.service.ConfirmActionReceiver
import com.whalechan.agent.ui.ConfirmActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConfirmRequest(
    val id: String,
    val action: String,
    val reason: String,
    val detail: String,
    val notificationId: Int
)

/**
 * 敏感操作确认闸门。
 *
 * 两条呈现通道：
 *  1) 高优先级通知（带“确认执行 / 不要”两个按钮）——应用在后台也能用；
 *  2) 对话页里的弹窗（通过 requests 流）——应用在前台时更快。
 *
 * 任何一条通道先回应都算数（CompletableDeferred 只完成一次）。
 * 超时（默认 90 秒）一律按“拒绝”处理，宁可不动也不误操作。
 */
object ConfirmationGate {

    const val DEFAULT_TIMEOUT_MS = 90_000L

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _requests = MutableSharedFlow<ConfirmRequest>(replay = 0, extraBufferCapacity = 8)
    val requests: SharedFlow<ConfirmRequest> = _requests

    suspend fun ask(
        context: Context,
        decision: AgentDecision,
        verdict: SafetyPolicy.Verdict,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred

        val request = ConfirmRequest(
            id = id,
            action = decision.oneLine(),
            reason = verdict.reason.ifBlank { "这个动作需要你确认" },
            detail = decision.explain(),
            notificationId = id.hashCode() and 0x7fffffff
        )

        _requests.tryEmit(request)
        postNotification(context, request)

        val approved = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            pending.remove(id)
            runCatching {
                NotificationManagerCompat.from(context).cancel(request.notificationId)
            }
        }

        WhaleLog.d("确认结果 $id -> $approved")
        return approved
    }

    /** 由确认按钮 / 对话页弹窗调用。 */
    fun deliver(id: String, approved: Boolean) {
        pending[id]?.complete(approved)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    @SuppressLint("MissingPermission") // 已用 NotificationPermission.canPost 做运行时检查
    private fun postNotification(context: Context, request: ConfirmRequest) {
        if (!NotificationPermission.canPost(context)) {
            // 没有通知权限：只能靠对话页弹窗；两者都没有时请求会超时并被当作拒绝
            WhaleLog.w("没有通知权限，确认请求只能通过对话页弹窗")
            return
        }
        val contentIntent = Intent(context, ConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ConfirmActivity.EXTRA_ID, request.id)
            putExtra(ConfirmActivity.EXTRA_TITLE, request.action)
            putExtra(ConfirmActivity.EXTRA_DETAIL, request.detail)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            request.notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approveIntent = Intent(context, ConfirmActionReceiver::class.java).apply {
            action = ConfirmActionReceiver.ACTION_APPROVE
            putExtra(ConfirmActionReceiver.EXTRA_ID, request.id)
        }
        val denyIntent = Intent(context, ConfirmActionReceiver::class.java).apply {
            action = ConfirmActionReceiver.ACTION_DENY
            putExtra(ConfirmActionReceiver.EXTRA_ID, request.id)
        }

        val approvePendingIntent = PendingIntent.getBroadcast(
            context,
            request.notificationId + 1,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPendingIntent = PendingIntent.getBroadcast(
            context,
            request.notificationId + 2,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bigText = "${request.action}\n\n${request.reason}\n\n${request.detail}"

        val notification = NotificationCompat.Builder(context, WhaleApp.CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(context.getString(R.string.confirm_title))
            .setContentText(request.action)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(0, context.getString(R.string.confirm_approve), approvePendingIntent)
            .addAction(0, context.getString(R.string.confirm_deny), denyPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(request.notificationId, notification)
        }.onFailure {
            // 没有 POST_NOTIFICATIONS 时只能靠对话页弹窗；两者都没有就只能等超时拒绝
            WhaleLog.w("确认通知发送失败（可能缺少通知权限）", it)
        }
    }
}
