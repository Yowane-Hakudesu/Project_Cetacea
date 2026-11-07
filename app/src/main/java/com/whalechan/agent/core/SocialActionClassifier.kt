package com.whalechan.agent.core

import com.whalechan.agent.access.UiSnapshot

/**
 * 把 LLM 的动作翻译成「社交动作」，供限流器与记忆账本使用。
 *
 * 平台没有公开的动作语义，只能看两点：
 *  1. 动作本身（reply_qq / 点击“发送/发表/转发”）；
 *  2. 当前屏幕是否出现评论、说说、转发相关词。
 *
 * 宁可漏判（不拦）也不误判（把普通点击当发说说），所以只认很明确的组合。
 */
object SocialActionClassifier {

    data class Plan(
        val type: SocialActionType,
        val targetKey: String,
        val summary: String,
        val textHash: String = ""
    )

    fun classify(
        decision: AgentDecision,
        snapshot: UiSnapshot,
        currentEntity: String?
    ): Plan? {
        if (decision.type == ActionType.REPLY_QQ) {
            val who = decision.conversation?.takeIf { it.isNotBlank() }
                ?: decision.target?.takeIf { it.isNotBlank() }
                ?: currentEntity
                ?: "未知会话"
            return Plan(
                type = SocialActionType.REPLY_MESSAGE,
                targetKey = who,
                summary = "给「$who」发消息：${(decision.text ?: "").take(20)}",
                textHash = SocialContext.stableHash(who + "|" + (decision.text ?: ""))
            )
        }

        if (!isTapLike(decision)) return null

        val screen = snapshot.nodes.joinToString(" ") { it.label }.take(4000)
        val label = (decision.text ?: decision.target ?: "").trim()

        // 评论：屏幕有“评论/说点什么”，点击的是“发送/发布”
        if (looksLikeComment(screen) && label.contains("发送")) {
            val key = SocialContext.postKey(snapshot) ?: "unknown_post"
            val title = SocialContext.postTitle(snapshot) ?: key
            return Plan(
                type = SocialActionType.COMMENT,
                targetKey = key,
                summary = "评论了动态：${title.take(24)}",
                textHash = SocialContext.stableHash("comment|$key|${decision.text ?: ""}")
            )
        }

        // 发说说：屏幕有“说说/发表”，点击的是“发表/发布”
        if (looksLikePublish(screen) &&
            (label.contains("发表") || label.contains("发布"))
        ) {
            return Plan(
                type = SocialActionType.PUBLISH_FEED,
                targetKey = "global",
                summary = "发了一条说说：${(decision.text ?: "").take(20)}",
                textHash = SocialContext.stableHash("feed|${decision.text ?: ""}")
            )
        }

        // 转发/分享视频：屏幕有转发/分享，点击的是“转发/分享”
        if (looksLikeShare(screen) &&
            (label.contains("转发") || label.contains("分享"))
        ) {
            return Plan(
                type = SocialActionType.SHARE_VIDEO,
                targetKey = "global",
                summary = "转发/分享了一个视频",
                textHash = SocialContext.stableHash(
                    "share|${snapshot.packageName}|${SocialContext.postKey(snapshot) ?: ""}"
                )
            )
        }

        return null
    }

    private fun isTapLike(decision: AgentDecision): Boolean = when (decision.type) {
        ActionType.TAP, ActionType.TAP_XY, ActionType.LONG_PRESS -> true
        else -> false
    }

    private fun looksLikeComment(screen: String): Boolean =
        screen.contains("评论") || screen.contains("说点什么") || screen.contains("写下你的评论")

    private fun looksLikePublish(screen: String): Boolean =
        screen.contains("说说") || screen.contains("发表")

    private fun looksLikeShare(screen: String): Boolean =
        screen.contains("转发") || screen.contains("分享")
}
