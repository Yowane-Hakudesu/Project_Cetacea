package com.whalechan.agent.core

import com.whalechan.agent.data.social.SocialMemoryRepository

/**
 * 社交动作类型。名称会写进数据库，改名前先想清楚兼容性。
 */
enum class SocialActionType(val label: String) {
    REPLY_MESSAGE("发消息"),
    PUBLISH_FEED("发说说"),
    COMMENT("评论"),
    SHARE_VIDEO("转发视频")
}

/**
 * 限流规则表。窗口 = 0 表示「生命周期」：同一条动态一辈子只允许 1 次评论。
 */
data class RateRule(
    val type: SocialActionType,
    val scope: Scope,
    val windowMs: Long,
    val max: Int
) {
    enum class Scope { GLOBAL, PER_TARGET, LIFETIME_PER_TARGET }
}

object RateRules {

    val ALL: List<RateRule> = listOf(
        // 同一好友 30 秒内只发 1 条
        RateRule(SocialActionType.REPLY_MESSAGE, RateRule.Scope.PER_TARGET, 30_000L, 1),
        // 全局 1 分钟最多 3 条
        RateRule(SocialActionType.REPLY_MESSAGE, RateRule.Scope.GLOBAL, 60_000L, 3),
        // 24 小时最多 2 条说说
        RateRule(SocialActionType.PUBLISH_FEED, RateRule.Scope.GLOBAL, 24 * 3600_000L, 2),
        // 同一条动态生命周期内只评论 1 次
        RateRule(SocialActionType.COMMENT, RateRule.Scope.LIFETIME_PER_TARGET, 0L, 1),
        // 30 分钟最多转发 10 个视频
        RateRule(SocialActionType.SHARE_VIDEO, RateRule.Scope.GLOBAL, 30 * 60_000L, 10)
    )
}

sealed class RateVerdict {
    object Allowed : RateVerdict()

    data class Rejected(
        val type: SocialActionType,
        val message: String,
        val retryAfterMs: Long
    ) : RateVerdict()
}

/**
 * 执行器前置拦截器：完全信任模式也拦，只拦“社交频率”，不做价值判断。
 *
 * 设计原则：
 *  - 失败不抛异常，而是返回 ActionRejected: Cooldown 文本，让 LLM 自己改主意；
 *  - 判断全部基于 Room 的持久化账本，进程重启后限流仍然有效；
 *  - 评论的 targetKey 用屏幕文本指纹，不依赖平台私有 ID。
 */
class ActionRateLimiter(private val social: SocialMemoryRepository) {

    suspend fun canExecute(type: SocialActionType, targetKey: String): RateVerdict {
        val now = System.currentTimeMillis()
        for (rule in RateRules.ALL) {
            if (rule.type != type) continue

            val used: Int
            val windowStart: Long
            when (rule.scope) {
                RateRule.Scope.LIFETIME_PER_TARGET -> {
                    used = social.countForTarget(type.name, targetKey)
                    windowStart = 0L
                }
                RateRule.Scope.PER_TARGET -> {
                    windowStart = now - rule.windowMs
                    used = social.countSinceForTarget(type.name, targetKey, windowStart)
                }
                RateRule.Scope.GLOBAL -> {
                    windowStart = now - rule.windowMs
                    used = social.countSince(type.name, windowStart)
                }
            }

            if (used >= rule.max) {
                val retryAfter = if (rule.windowMs <= 0L) {
                    -1L
                } else {
                    (windowStart + rule.windowMs - now).coerceAtLeast(0L)
                }
                val wait = when {
                    retryAfter < 0L -> "这条动态已经评论过了，不能再评"
                    retryAfter >= 3600_000L -> "约 ${(retryAfter + 3600_000L - 1) / 3600_000L} 小时后才能再来"
                    retryAfter >= 60_000L -> "约 ${(retryAfter + 59_999L) / 60_000L} 分钟后才能再来"
                    else -> "约 ${(retryAfter + 999L) / 1000L} 秒后再说"
                }
                return RateVerdict.Rejected(
                    type = type,
                    message = "ActionRejected: Cooldown — ${describe(type, targetKey, rule)}；$wait。" +
                        "不要重复尝试这个动作，换一个目标或先做别的。",
                    retryAfterMs = retryAfter
                )
            }
        }
        return RateVerdict.Allowed
    }

    suspend fun record(
        type: SocialActionType,
        targetKey: String,
        summary: String,
        textHash: String = ""
    ) {
        social.record(type.name, targetKey, summary, textHash)
    }

    /** 同一句话在 windowMs 内已经成功发过 → 视为重复完成，不再执行。 */
    suspend fun isDuplicateCompletion(textHash: String, windowMs: Long = 10 * 60_000L): Boolean {
        if (textHash.isBlank()) return false
        return social.countByHash(textHash, System.currentTimeMillis() - windowMs) > 0
    }

    private fun describe(type: SocialActionType, targetKey: String, rule: RateRule): String {
        val who = when (type) {
            SocialActionType.REPLY_MESSAGE -> "给「$targetKey」发消息"
            SocialActionType.COMMENT -> "评论「${targetKey.take(12)}」"
            SocialActionType.PUBLISH_FEED -> "发说说"
            SocialActionType.SHARE_VIDEO -> "转发视频"
        }
        val limit = when (rule.scope) {
            RateRule.Scope.GLOBAL -> "全局"
            RateRule.Scope.PER_TARGET -> "同一对象"
            RateRule.Scope.LIFETIME_PER_TARGET -> "同一条"
        }
        return "$who 触发$limit 限制（${rule.max} 次 / ${windowText(rule)}）"
    }

    private fun windowText(rule: RateRule): String = when {
        rule.windowMs <= 0L -> "生命周期"
        rule.windowMs >= 24 * 3600_000L -> "24 小时"
        rule.windowMs >= 3600_000L -> "${rule.windowMs / 3600_000L} 小时"
        else -> "${rule.windowMs / 60_000L} 分钟"
    }
}
