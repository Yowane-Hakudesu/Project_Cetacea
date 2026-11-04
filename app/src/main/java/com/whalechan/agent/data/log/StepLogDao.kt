package com.whalechan.agent.data.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface StepLogDao {

    @Insert
    suspend fun insert(log: StepLogEntity): Long

    @Query("SELECT * FROM step_logs ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<StepLogEntity>

    @Query("SELECT * FROM step_logs WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun byTask(taskId: String): List<StepLogEntity>

    @Query("SELECT COUNT(*) FROM step_logs")
    suspend fun count(): Int

    @Query("DELETE FROM step_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /**
     * 环形清理：只保留最近 keep 条。
     * 无限模式下步数没有上限，这条 SQL 是防止日志表无限膨胀的唯一保险。
     */
    @Query("DELETE FROM step_logs WHERE id NOT IN (SELECT id FROM step_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun keepOnlyRecent(keep: Int)

    @Query("DELETE FROM step_logs")
    suspend fun clear()
}
