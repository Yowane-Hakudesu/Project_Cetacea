package com.whalechan.agent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedPlan(
    val goalKey: String,
    val fingerprint: String,
    val decisions: List<String>,
    val summary: String,
    val at: Long
)

/**
 * SMART_LAZY 的工程实现：动作序列缓存。
 *
 * 命中条件：同一个目标文本 + 同一个开局屏幕指纹。
 * 回放时仍然逐步执行、逐步校验；任何一步失败就放弃缓存、回退到 LLM 重新规划。
 *
 * 不缓存的情况：
 *  - 本次任务里出现过支付/删除类字眼（避免把“点支付”固化成宏）；
 *  - 步数超过 25（太长的序列复用价值低，且更难校验）；
 *  - 用户在设置里关掉了缓存回放。
 */
class ActionCache(context: Context) {

    private val file = File(context.filesDir, "action_cache.json")
    private val lock = Any()
    private val entries = mutableListOf<CachedPlan>()

    init {
        load()
    }

    fun lookup(goal: String, fingerprint: String?): CachedPlan? {
        if (fingerprint.isNullOrBlank()) return null
        val key = goalKey(goal)
        return synchronized(lock) {
            entries.lastOrNull { it.goalKey == key && it.fingerprint == fingerprint }
        }
    }

    /**
     * @return true 表示已写入（未写入通常是因为动作里含敏感操作）
     */
    fun save(goal: String, fingerprint: String, decisions: List<AgentDecision>, summary: String): Boolean {
        if (fingerprint.isBlank() || decisions.isEmpty() || decisions.size > 25) return false
        if (decisions.any { isSensitive(it) }) return false

        val plan = CachedPlan(
            goalKey = goalKey(goal),
            fingerprint = fingerprint,
            decisions = decisions.map { it.raw },
            summary = summary.take(200),
            at = System.currentTimeMillis()
        )
        synchronized(lock) {
            entries.removeAll { it.goalKey == plan.goalKey && it.fingerprint == plan.fingerprint }
            entries.add(plan)
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            persist()
        }
        return true
    }

    fun invalidate(plan: CachedPlan) {
        synchronized(lock) {
            entries.removeAll { it.goalKey == plan.goalKey && it.fingerprint == plan.fingerprint }
            persist()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            persist()
        }
    }

    /** 缓存里的动作只要碰到这些词就不固化，避免把不可逆操作变成“宏”。 */
    private fun isSensitive(decision: AgentDecision): Boolean {
        val text = decision.oneLine() + " " + decision.text.orEmpty() + " " + decision.conversation.orEmpty()
        return SENSITIVE_WORDS.any { text.contains(it, ignoreCase = true) }
    }

    private fun goalKey(goal: String): String =
        goal.trim().lowercase().replace(Regex("\\s+"), " ").take(200)

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            runCatching {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("entries") ?: return
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val decisionArray = o.optJSONArray("decisions") ?: continue
                    val decisions = ArrayList<String>(decisionArray.length())
                    for (j in 0 until decisionArray.length()) {
                        decisions.add(decisionArray.optString(j))
                    }
                    entries.add(
                        CachedPlan(
                            goalKey = o.optString("goalKey"),
                            fingerprint = o.optString("fingerprint"),
                            decisions = decisions,
                            summary = o.optString("summary"),
                            at = o.optLong("at")
                        )
                    )
                }
            }.onFailure { WhaleLog.w("动作缓存读取失败，已忽略", it) }
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            root.put(
                "entries",
                JSONArray().also { array ->
                    entries.forEach { plan ->
                        array.put(
                            JSONObject().apply {
                                put("goalKey", plan.goalKey)
                                put("fingerprint", plan.fingerprint)
                                put("decisions", JSONArray().also { d -> plan.decisions.forEach { d.put(it) } })
                                put("summary", plan.summary)
                                put("at", plan.at)
                            }
                        )
                    }
                }
            )
            val tmp = File(file.parentFile, "action_cache.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { WhaleLog.w("动作缓存写入失败", it) }
    }

    companion object {
        private const val MAX_ENTRIES = 30
        private val SENSITIVE_WORDS = listOf(
            "支付", "付款", "转账", "购买", "充值", "打赏", "提现",
            "删除", "卸载", "清除数据", "注销", "退出登录", "解绑"
        )
    }
}
