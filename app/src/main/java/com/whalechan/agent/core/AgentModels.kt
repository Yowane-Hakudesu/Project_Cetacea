package com.whalechan.agent.core

import com.whalechan.agent.data.log.ErrorCategory

/** Agent 的运行模式。 */
enum class RunMode {
    /** 纯聊天：只调 LLM，不碰屏幕。 */
    CHAT,

    /** 正常任务：完整动作集 + 安全策略。 */
    ACT,

    /** 好奇模式：只允许浏览类动作，用来“自己看看手机”。 */
    CURIOUS
}

/** 一步执行记录，压缩后回填给下一轮 prompt。 */
data class StepRecord(
    val step: Int,
    val action: String,
    val result: String,
    val screen: String
) {
    fun render(): String = "第${step}步 · $action → $result（界面: $screen）"
}

/**
 * 动作执行结果。
 * category 只在失败时填，直接映射到伴侣端的错误分析卡片。
 */
data class ExecResult(
    val ok: Boolean,
    val detail: String,
    val category: ErrorCategory? = null
) {
    companion object {
        fun ok(detail: String) = ExecResult(true, detail)
        fun fail(detail: String, category: ErrorCategory = ErrorCategory.UNKNOWN) =
            ExecResult(false, detail, category)
    }
}

/** 给 UI / 前台服务 / 伴侣端消费的 Agent 事件流。 */
sealed interface AgentEvent {
    data class Thinking(val step: Int, val maxSteps: Int) : AgentEvent
    data class Planned(val step: Int, val action: String) : AgentEvent
    data class Acted(val step: Int, val action: String, val ok: Boolean, val detail: String) : AgentEvent
    data class AssistantMessage(val text: String) : AgentEvent
    data class Notice(val text: String) : AgentEvent

    /** 达到步数上限，等待用户“继续/放弃”。 */
    data class Paused(val step: Int, val taskId: String) : AgentEvent
    data object Resumed : AgentEvent

    /** 连续多步界面无变化，判定卡死（对应人格的 TIMEOUT_SIGNAL）。 */
    data class Stuck(val taskId: String, val fingerprint: String, val lastAction: String) : AgentEvent

    data class Finished(val ok: Boolean, val summary: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
}
