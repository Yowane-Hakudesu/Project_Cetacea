package com.whalechan.agent.notify

import android.content.Context
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.WhaleLog

/**
 * QQ 消息的“自动决策是否回复”入口。
 *
 * 它只负责判断“要不要叫醒 Agent”，回复什么由 LLM 决定，怎么发由 QqReplyFlow 决定。
 * 三道刹车：
 *  1. 总开关（默认关）；
 *  2. 白名单（留空=全部，但可以只盯几个人/群）；
 *  3. 同一会话冷却（默认 60 秒），避免两边互发导致死循环。
 *
 * 新增：任务抢占保护。Agent 正在专注做事（刷视频/看内容）时，不立刻切走，
 * 而是把回复任务放进 [PendingInbox]，等当前任务结束后再处理。
 */
object AutoReplyCoordinator {

    private val lastHandledAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun onMessage(context: Context, message: QqMessage) {
        val settings = AgentRuntime.settings.load()
        if (!settings.autoReplyEnabled) return

        val whitelist = settings.whitelist()
        if (whitelist.isNotEmpty()) {
            val matched = whitelist.any { keyword ->
                message.conversation.contains(keyword, ignoreCase = true) ||
                    message.sender.contains(keyword, ignoreCase = true)
            }
            if (!matched) return
        }

        val now = System.currentTimeMillis()
        val last = lastHandledAt[message.conversation] ?: 0L
        if (now - last < settings.autoReplyCooldownSec * 1000L) {
            WhaleLog.d("自动回复冷却中：${message.conversation}")
            return
        }

        lastHandledAt[message.conversation] = now

        val goal = buildString {
            appendLine("你收到一条新的 ${message.appLabel} 消息，请判断要不要回复。")
            appendLine("会话：${message.conversation}")
            appendLine("发件人：${message.sender}")
            appendLine("内容：${message.text}")
            appendLine("如果值得回复，请调用 reply_qq 动作，内容不超过 15 个字、一句话，符合你的人设，不要编造对方没说过的事。")
            appendLine("如果不需要回应（广告、通知、无需回复），直接输出 done，summary 写“不需要回复”。")
        }

        if (AgentRuntime.engine.isRunning()) {
            // 正在专注做事：先存信箱，等当前任务结束再回复，避免“刷一半突然切走”
            val queued = PendingInbox.offer(
                PendingInbox.Item(goal = goal, conversation = message.conversation)
            )
            WhaleLog.i(
                if (queued) "Agent 正忙，消息已放入待处理信箱：${message.conversation}（当前 ${PendingInbox.size()} 条）"
                else "待处理信箱已满，丢弃：${message.conversation}"
            )
            return
        }

        val started = com.whalechan.agent.service.KeepAliveService.startTask(context, goal)
        WhaleLog.i(
            if (started) "自动回复任务已提交：${message.conversation}"
            else "自动回复任务提交失败：${message.conversation}"
        )
    }
}
