package com.whalechan.agent.notify

/**
 * 待处理信箱：Agent 正在专注做事（刷视频、看内容）时，QQ 新消息先放这里，
 * 等当前任务结束/屏幕闲置后再处理，避免“刷一半突然切走”的心不在焉感。
 *
 * 只在进程内存里，杀进程会丢；这是刻意的：重启后不该把半小时前的“在吗”再回一遍。
 */
object PendingInbox {

    data class Item(
        val goal: String,
        val conversation: String,
        val at: Long = System.currentTimeMillis()
    )

    private const val MAX_SIZE = 20

    private val queue = ArrayDeque<Item>()

    @Synchronized
    fun offer(item: Item): Boolean {
        if (queue.size >= MAX_SIZE) return false
        queue.addLast(item)
        return true
    }

    @Synchronized
    fun poll(): Item? = queue.removeFirstOrNull()

    @Synchronized
    fun peek(): Item? = queue.firstOrNull()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
    }
}
