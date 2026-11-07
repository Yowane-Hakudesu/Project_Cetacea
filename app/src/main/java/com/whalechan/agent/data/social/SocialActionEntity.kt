package com.whalechan.agent.data.social

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 社交动作账本：既用于「防刷屏」限流，也用于给 LLM 的 [RecentActivity] 记忆。
 *
 * targetKey：
 *  - 发消息：好友/群名
 *  - 评论：Post 指纹（由屏幕文本 hash 得到）
 *  - 发说说/转发：global
 */
@Entity(
    tableName = "social_actions",
    indices = [Index("actionType"), Index("targetKey"), Index("timestamp")]
)
data class SocialActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val targetKey: String,
    val summary: String,
    /** 成功发出的文本 hash，用于“同一句话不要原地重复发” */
    val textHash: String = "",
    val timestamp: Long
)
