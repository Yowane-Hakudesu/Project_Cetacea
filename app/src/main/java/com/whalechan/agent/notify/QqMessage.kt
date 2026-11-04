package com.whalechan.agent.notify

import java.util.concurrent.ConcurrentHashMap

/** 一条从通知栏还原出来的 QQ 消息（不是聊天记录，只是通知里的那一行）。 */
data class QqMessage(
    val id: String,
    val packageName: String,
    val appLabel: String,
    /** 会话名：私聊是联系人名，群聊是群名。 */
    val conversation: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isGroup: Boolean,
    val key: String
)

/**
 * 发出消息后的“回显抑制”。
 * 我们刚用 UI 发出「好的」，QQ 会立刻推一条通知，内容一模一样；
 * 如果不抑制，Agent 会以为对方又发了一句，进而自己回复自己。
 */
object ReplyLedger {

    private const val WINDOW_MS = 20_000L
    private val recent = ConcurrentHashMap<String, Long>()

    fun record(text: String) {
        val key = normalize(text)
        if (key.isEmpty()) return
        recent[key] = System.currentTimeMillis()
        cleanup()
    }

    fun isEcho(text: String): Boolean {
        val key = normalize(text)
        if (key.isEmpty()) return false
        val at = recent[key] ?: return false
        if (System.currentTimeMillis() - at > WINDOW_MS) {
            recent.remove(key)
            return false
        }
        return true
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(200)

    private fun cleanup() {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > WINDOW_MS * 3 }
    }
}
