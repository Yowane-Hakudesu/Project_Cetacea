package com.whalechan.agent.data.social

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SocialActionDao {

    @Insert
    suspend fun insert(action: SocialActionEntity): Long

    @Query("SELECT COUNT(*) FROM social_actions WHERE actionType = :type AND timestamp >= :since")
    suspend fun countSince(type: String, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM social_actions WHERE actionType = :type " +
            "AND targetKey = :target AND timestamp >= :since"
    )
    suspend fun countSinceForTarget(type: String, target: String, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM social_actions WHERE actionType = :type AND targetKey = :target"
    )
    suspend fun countForTarget(type: String, target: String): Int

    @Query("SELECT * FROM social_actions WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(since: Long, limit: Int): List<SocialActionEntity>

    @Query(
        "SELECT COUNT(*) FROM social_actions WHERE textHash = :hash AND timestamp >= :since"
    )
    suspend fun countByHash(hash: String, since: Long): Int

    @Query("DELETE FROM social_actions WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM social_actions")
    suspend fun clear()
}
