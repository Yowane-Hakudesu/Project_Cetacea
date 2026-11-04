package com.whalechan.agent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MemoryChatTurn(val role: String, val text: String, val at: Long)
data class MemoryTask(val goal: String, val summary: String, val success: Boolean, val at: Long)

/**
 * 端侧记忆：全部落在应用私有目录的 JSON 文件里，不上传。
 * 三类内容：
 *  - facts：用户偏好 / 长期事实（例如“用户叫小陈”“回消息喜欢简短”）
 *  - tasks：最近做过的任务，用于“上次那个事”式指代
 *  - chats：最近聊天，给对话页提供上下文
 *
 * 上限都很小（facts 40 / tasks 30 / chats 80），避免 prompt 无限膨胀。
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "whale_memory.json")
    private val lock = Any()

    private val facts = mutableListOf<String>()
    private val tasks = mutableListOf<MemoryTask>()
    private val chats = mutableListOf<MemoryChatTurn>()

    init {
        load()
    }

    // ────────────────────────── 写入 ──────────────────────────

    fun appendChat(role: String, text: String) {
        val content = text.trim().take(2000)
        if (content.isEmpty()) return
        synchronized(lock) {
            chats.add(MemoryChatTurn(role, content, System.currentTimeMillis()))
            while (chats.size > MAX_CHATS) chats.removeAt(0)
            persist()
        }
    }

    fun recentChat(limit: Int = 12): List<MemoryChatTurn> =
        synchronized(lock) { chats.takeLast(limit.coerceAtLeast(1)) }

    fun addFact(fact: String) {
        val content = fact.trim().trimStart('-', '·').trim().take(200)
        if (content.length < 2) return
        synchronized(lock) {
            if (facts.any { it.equals(content, ignoreCase = true) }) return
            facts.add(content)
            while (facts.size > MAX_FACTS) facts.removeAt(0)
            persist()
        }
    }

    fun recordTask(goal: String, summary: String, success: Boolean) {
        synchronized(lock) {
            tasks.add(
                MemoryTask(
                    goal = goal.take(300),
                    summary = summary.take(300),
                    success = success,
                    at = System.currentTimeMillis()
                )
            )
            while (tasks.size > MAX_TASKS) tasks.removeAt(0)
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            facts.clear()
            tasks.clear()
            chats.clear()
            persist()
        }
    }

    // ────────────────────────── 读取 ──────────────────────────

    fun allFacts(): List<String> = synchronized(lock) { facts.toList() }

    fun recentTasks(limit: Int = 5): List<MemoryTask> = synchronized(lock) { tasks.takeLast(limit) }

    /** 送进 prompt 的压缩记忆。 */
    fun summaryForPrompt(maxChars: Int = 1000): String {
        val built = synchronized(lock) {
            buildString {
                val recentFacts = facts.takeLast(8)
                if (recentFacts.isNotEmpty()) {
                    appendLine("· 关于用户：")
                    recentFacts.forEach { appendLine("  - $it") }
                }
                val recentTasks = tasks.takeLast(3)
                if (recentTasks.isNotEmpty()) {
                    appendLine("· 最近任务：")
                    recentTasks.forEach {
                        appendLine(
                            "  - ${if (it.success) "完成" else "未完成"}：" +
                                "${it.goal.take(50)} → ${it.summary.take(50)}"
                        )
                    }
                }
            }
        }
        return if (built.isBlank()) "" else built.take(maxChars)
    }

    /** 极简关键词召回：没有向量库，只按“目标里的词是否出现在事实里”打分。 */
    fun relevantFacts(query: String, limit: Int = 3): List<String> {
        val tokens = query.split(Regex("[\\s，。,.!?！？、：:；;]+"))
            .filter { it.length >= 2 }
            .distinct()
        if (tokens.isEmpty()) return emptyList()
        return synchronized(lock) {
            facts.map { fact -> fact to tokens.count { fact.contains(it, ignoreCase = true) } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }
    }

    // ────────────────────────── 持久化 ──────────────────────────

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            runCatching {
                val root = JSONObject(file.readText())
                root.optJSONArray("facts")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) facts.add(v)
                    }
                }
                root.optJSONArray("tasks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        tasks.add(
                            MemoryTask(
                                goal = o.optString("goal"),
                                summary = o.optString("summary"),
                                success = o.optBoolean("success"),
                                at = o.optLong("at")
                            )
                        )
                    }
                }
                root.optJSONArray("chats")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        chats.add(
                            MemoryChatTurn(
                                role = o.optString("role", "user"),
                                text = o.optString("text"),
                                at = o.optLong("at")
                            )
                        )
                    }
                }
            }.onFailure { WhaleLog.w("记忆文件读取失败，已忽略", it) }
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            root.put("facts", JSONArray().also { arr -> facts.forEach { arr.put(it) } })
            root.put(
                "tasks",
                JSONArray().also { arr ->
                    tasks.forEach { t ->
                        arr.put(
                            JSONObject().apply {
                                put("goal", t.goal)
                                put("summary", t.summary)
                                put("success", t.success)
                                put("at", t.at)
                            }
                        )
                    }
                }
            )
            root.put(
                "chats",
                JSONArray().also { arr ->
                    chats.forEach { c ->
                        arr.put(
                            JSONObject().apply {
                                put("role", c.role)
                                put("text", c.text)
                                put("at", c.at)
                            }
                        )
                    }
                }
            )
            val tmp = File(file.parentFile, "whale_memory.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { WhaleLog.w("记忆写入失败", it) }
    }

    companion object {
        private const val MAX_FACTS = 40
        private const val MAX_TASKS = 30
        private const val MAX_CHATS = 80
    }
}
