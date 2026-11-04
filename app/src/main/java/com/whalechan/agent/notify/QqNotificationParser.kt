package com.whalechan.agent.notify

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.whalechan.agent.access.AppsLauncher

/**
 * 把 QQ 的通知还原成 (会话, 发件人, 正文, 时间)。
 *
 * 必须说清楚的事实：
 *  - 这里拿到的是“通知栏里显示的那一条”，不是聊天记录；
 *  - 用户关掉 QQ 的“通知显示消息内容”，正文就只有“你收到一条新消息”，此时无法读到内容；
 *  - 群聊的发件人依赖 MessagingStyle 或标题启发式，命中不了就退化成群名。
 */
object QqNotificationParser {

    private val GROUP_SUMMARY_TEXT = Regex("^\\d+\\s*条新消息.*")
    private val GROUP_SENDER_PREFIX = Regex("^(.{1,24}?)[:：]\\s*(.+)$")

    fun parse(sbn: StatusBarNotification): QqMessage? {
        val packageName = sbn.packageName ?: return null
        if (!AppsLauncher.isQqPackage(packageName)) return null

        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null

        val extras = notification.extras ?: return null

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        var conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()

        var sender = ""
        var isGroup = false

        // ① MessagingStyle：最可靠
        val bundles = runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
        if (!bundles.isNullOrEmpty()) {
            val last = bundles.lastOrNull() as? Bundle
            if (last != null) {
                last.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { text = it }
                val senderChar = last.getCharSequence("sender")?.toString()?.trim().orEmpty()
                // Person 需要 API 28；把 .name 的访问也放进版本判断里，避免在 26/27 上加载该类
                val senderPersonName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { last.getParcelable("sender_person") as? Person }
                        .getOrNull()
                        ?.name
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                } else {
                    ""
                }
                val resolved = senderChar.ifBlank { senderPersonName }
                if (resolved.isNotEmpty()) sender = resolved
            }
            if (bundles.size > 1) isGroup = true
        }

        // ② 没有正文时退回 subText
        if (text.isBlank()) text = subText
        if (text.isBlank()) return null

        // ③ 群消息汇总（"3条新消息"）没有回复价值
        if (GROUP_SUMMARY_TEXT.matches(text)) return null

        if (conversation.isBlank()) conversation = title
        if (title.isBlank()) title = conversation

        val people = runCatching { extras.getParcelableArray(Notification.EXTRA_PEOPLE_LIST) }.getOrNull()
        if ((people?.size ?: 0) > 1) isGroup = true

        if (sender.isBlank()) sender = title

        // ④ 群聊常见格式 "李四: 内容"。只在已判定为群聊时拆分，避免拆坏私聊正文里的冒号。
        if (isGroup) {
            GROUP_SENDER_PREFIX.matchEntire(text)?.let { match ->
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotBlank() && !candidate.equals(conversation, ignoreCase = true)) {
                    sender = candidate
                    text = match.groupValues[2].trim()
                }
            }
        }

        if (text.isBlank()) return null

        val appLabel = when (packageName) {
            "com.tencent.tim" -> "TIM"
            "com.tencent.qqlite" -> "QQ轻聊版"
            else -> "QQ"
        }

        val timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val finalConversation = conversation.ifBlank { title.ifBlank { sender } }.ifBlank { "未知会话" }
        val finalSender = sender.ifBlank { finalConversation }
        // 同一分钟内同样的“会话+发件人+正文”视为同一条（通知更新会重复回调）
        val key = "$packageName|$finalConversation|$finalSender|$text|${timestamp / 60_000}"

        return QqMessage(
            id = key.hashCode().toString(),
            packageName = packageName,
            appLabel = appLabel,
            conversation = finalConversation,
            sender = finalSender,
            text = text,
            timestamp = timestamp,
            isGroup = isGroup,
            key = key
        )
    }
}
