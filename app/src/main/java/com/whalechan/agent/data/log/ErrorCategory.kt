package com.whalechan.agent.data.log

/**
 * 错误分类：伴侣端直接拿 name 字段渲染成可读卡片，不需要看堆栈。
 * 前五类是需求里要求的；STUCK 是为「连续 N 步界面指纹无变化」单独加的——
 * 它和 SCREEN_STATE_DRIFT 的区别是：drift 指单步前后不一致，STUCK 指多步整体卡死。
 */
enum class ErrorCategory(val label: String, val advice: String) {
    NODE_NOT_FOUND(
        "找不到目标节点",
        "目标应用可能已改版，或当前界面被广告/弹窗遮挡；建议重新观察屏幕再决策"
    ),
    GESTURE_TIMEOUT(
        "手势超时或被拒",
        "检查无障碍服务是否存活；降低连续手势频率；锁屏状态下系统可能拒绝注入"
    ),
    LLM_INVALID_ACTION(
        "模型返回了无效动作",
        "检查 LLM 端点与模型是否支持结构化输出；对照日志里的原始响应"
    ),
    PERMISSION_LOST(
        "权限丢失",
        "无障碍或通知权限被系统/用户撤销，需要重新授予后重试"
    ),
    SCREEN_STATE_DRIFT(
        "界面与预期不符",
        "执行前后指纹不一致：可能弹出了新窗口、锁屏、切到其他应用，或界面改版"
    ),
    STUCK(
        "连续多步界面无变化",
        "Agent 判断卡死：目标可能在屏幕外、被遮挡，或应用无响应"
    ),
    APP_LAUNCH_TIMEOUT(
        "应用启动超时",
        "目标应用冷启动慢、停在开屏页或被杀；可延长等待、手动点一下，或检查该应用是否被系统限制后台启动"
    ),
    AUTONOMOUS_FALLBACK(
        "自动驾驶兜底动作",
        "模型没有给出可用指令，Agent 自己执行了一次安全滑动继续探索；属于正常兜底，不是故障"
    ),
    UNKNOWN(
        "未知错误",
        "查看 errorDetail 与当时的 UI 摘要"
    );

    companion object {
        fun fromName(name: String?): ErrorCategory =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
