package com.whalechan.agent.notify

import com.whalechan.agent.core.WhaleLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知监听与 Agent 之间的环形缓冲区。
 * 只保留最近 80 条，进程内共享；不落盘（重启后由新通知重新填充）。
 */
object NotificationBus {

    private const val CAPACITY = 80
    private const val SEEN_CAPACITY = 400

    private val _messages = MutableStateFlow<List<QqMessage>>(emptyList())
    val messages: StateFlow<List<QqMessage>> = _messages.asStateFlow()

    @Volatile
    var listenerConnected: Boolean = false

    private val seen = LinkedHashSet<String>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun publish(message: QqMessage) {
        if (!markSeen(message.key)) return
        _messages.update { current -> (current + message).takeLast(CAPACITY) }
        WhaleLog.d("QQ 通知｜${message.conversation} / ${message.sender}：${message.text.take(40)}")
    }

    private fun markSeen(key: String): Boolean = synchronized(seen) {
        if (seen.contains(key)) return false
        seen.add(key)
        while (seen.size > SEEN_CAPACITY) {
            val oldest = seen.first()
            seen.remove(oldest)
        }
        true
    }

    fun recent(limit: Int = 10): List<QqMessage> = _messages.value.takeLast(limit)

    fun lastFrom(conversation: String): QqMessage? =
        _messages.value.lastOrNull { it.conversation.equals(conversation, ignoreCase = true) }

    /** 拼成 prompt 里的一段纯文本。 */
    fun recentForPrompt(limit: Int = 6, packageFilter: String? = null): String {
        val list = _messages.value
            .filter { packageFilter == null || it.packageName == packageFilter }
            .takeLast(limit)
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { message ->
            val who = if (message.isGroup) "${message.conversation}/${message.sender}" else message.sender
            val time = timeFormat.format(Date(message.timestamp))
            "[$time] ${message.appLabel} · $who：${message.text.replace('\n', ' ').take(80)}"
        }
    }

    fun clear() {
        _messages.value = emptyList()
        synchronized(seen) { seen.clear() }
    }
}
