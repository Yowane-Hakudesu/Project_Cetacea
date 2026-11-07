package com.whalechan.agent.data.social

import android.content.Context
import com.whalechan.agent.data.log.StepLogDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 社交记忆：写入动作账本，并生成给 LLM 的 [RecentActivity] 文本。
 *
 * 为什么用 Room 而不是内存：进程被系统回收后，仍然要记得「刚刚评论过谁」，
 * 否则重启后会把同一条动态再评论一遍。
 */
class SocialMemoryRepository(context: Context) {

    private val dao = StepLogDatabase.get(context).socialActionDao()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    suspend fun record(
        actionType: String,
        targetKey: String,
        summary: String,
        textHash: String = "",
        at: Long = System.currentTimeMillis()
    ) {
        dao.insert(
            SocialActionEntity(
                actionType = actionType,
                targetKey = targetKey,
                summary = summary,
                textHash = textHash,
                timestamp = at
            )
        )
        // 只保留 7 天，防止无限增长
        dao.deleteOlderThan(at - 7L * 24 * 3600 * 1000)
    }

    suspend fun countSince(type: String, since: Long): Int = dao.countSince(type, since)

    suspend fun countSinceForTarget(type: String, target: String, since: Long): Int =
        dao.countSinceForTarget(type, target, since)

    suspend fun countForTarget(type: String, target: String): Int =
        dao.countForTarget(type, target)

    suspend fun countByHash(hash: String, since: Long): Int = dao.countByHash(hash, since)

    /** 最近 N 分钟的社交记录，倒序。 */
    suspend fun recent(minutes: Int = 30, limit: Int = 12): List<SocialActionEntity> =
        dao.recent(System.currentTimeMillis() - minutes * 60_000L, limit)

    /**
     * 给 Prompt 用的纯文本块；没有记录时返回空串。
     * 例：
     *   - 15:27 评论了动态：上古卷轴6
     *   - 15:26 给「夏」发消息：都说了不打啦
     */
    suspend fun recentActivityText(minutes: Int = 30, limit: Int = 12): String {
        val items = recent(minutes, limit)
        if (items.isEmpty()) return ""
        return buildString {
            items.forEach { item ->
                append("- ").append(timeFormat.format(Date(item.timestamp)))
                    .append(' ').append(item.summary.take(60)).append('\n')
            }
        }.trimEnd()
    }

    suspend fun clear() = dao.clear()
}
