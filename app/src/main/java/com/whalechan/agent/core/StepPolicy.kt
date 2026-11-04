package com.whalechan.agent.core

/**
 * 单任务的步数策略（设置页三选一）。
 *
 * LIMITED      —— 达到 maxSteps 时暂停并询问是否继续（默认，50 步）
 * TIME_WINDOW  —— 不看步数，只看时间窗口；到点结束
 * UNLIMITED    —— 只看任务完成信号与卡死检测；必须先在设置页勾选风险确认
 */
enum class StepPolicy(val label: String) {
    LIMITED("有限步数（到上限暂停询问）"),
    TIME_WINDOW("时间窗口（到点结束）"),
    UNLIMITED("无限模式（仅完成信号/卡死/手动停止）");

    companion object {
        fun from(raw: String?): StepPolicy =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: LIMITED
    }
}
