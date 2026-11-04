package com.whalechan.agent.data.log

import android.content.Context
import com.whalechan.agent.core.WhaleLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 执行日志仓库。
 *
 * 写入是 fire-and-forget（IO 线程），不阻塞 Agent 循环——日志绝对不能成为执行的瓶颈。
 * 同时把每条新日志通过 SharedFlow 发出去，WebSocket 伴侣端据此做实时时间线。
 *
 * 清理策略（每 25 次写入触发一次）：
 *  1. 删除 7 天前的记录；
 *  2. 只保留最近 5000 条。
 * 这两个数字来自设置项，无限模式下也照样生效。
 */
class StepLogRepository(context: Context) {

    private val dao = StepLogDatabase.get(context).stepLogDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeCounter = AtomicInteger(0)

    private val _events = MutableSharedFlow<StepLogEntity>(extraBufferCapacity = 64)
    val events: SharedFlow<StepLogEntity> = _events.asSharedFlow()

    fun append(log: StepLogEntity) {
        scope.launch {
            runCatching {
                dao.insert(log)
                _events.tryEmit(log)
                if (writeCounter.incrementAndGet() % 25 == 0) {
                    prune()
                }
            }.onFailure { WhaleLog.w("写入执行日志失败", it) }
        }
    }

    suspend fun recent(limit: Int = 300): List<StepLogEntity> =
        runCatching { dao.recent(limit) }.getOrDefault(emptyList())

    suspend fun byTask(taskId: String): List<StepLogEntity> =
        runCatching { dao.byTask(taskId) }.getOrDefault(emptyList())

    suspend fun count(): Int = runCatching { dao.count() }.getOrDefault(0)

    suspend fun prune(retentionDays: Int = 7, maxRows: Int = 5000) {
        runCatching {
            val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
            dao.deleteOlderThan(cutoff)
            dao.keepOnlyRecent(maxRows.coerceAtLeast(100))
        }.onFailure { WhaleLog.w("清理执行日志失败", it) }
    }

    suspend fun clear() {
        runCatching { dao.clear() }.onFailure { WhaleLog.w("清空执行日志失败", it) }
    }
}
