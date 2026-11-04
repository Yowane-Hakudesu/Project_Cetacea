package com.whalechan.agent.core

import org.json.JSONObject

/** LLM 可以输出的动作类型（白名单，模型不能发明新动作）。 */
enum class ActionType {
    TAP, TAP_XY, LONG_PRESS, SWIPE, INPUT, OPEN_APP, BACK, HOME, RECENTS, WAIT,
    REPLY_QQ, READ_NOTIFICATIONS, ASK_USER, DONE, FAIL, UNKNOWN
}

/**
 * 一条结构化的动作指令。
 * 解析器的职责是把模型输出的任意 JSON “收拢”成这个固定形状，
 * 执行器只认这个类型，因此模型无法注入任意代码或任意 Intent。
 */
data class AgentDecision(
    val type: ActionType,
    val target: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val distance: Float = 0.6f,
    val x: Int? = null,
    val y: Int? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val seconds: Float = 1f,
    val conversation: String? = null,
    val question: String? = null,
    val summary: String? = null,
    val reason: String? = null,
    val expect: String? = null,
    val raw: String = ""
) {

    fun oneLine(): String = when (type) {
        ActionType.TAP -> "点击 ${target ?: text ?: "?"}"
        ActionType.TAP_XY -> "按坐标点击 (${x}, ${y})"
        ActionType.LONG_PRESS -> "长按 ${target ?: text ?: "?"}"
        ActionType.SWIPE -> "向${directionLabel(direction)}滑动 ${(distance * 100).toInt()}%"
        ActionType.INPUT -> "在 ${target ?: "输入框"} 输入「${text.orEmpty().take(24)}」"
        ActionType.OPEN_APP -> "打开 ${appName ?: packageName ?: "?"}"
        ActionType.BACK -> "返回"
        ActionType.HOME -> "回桌面"
        ActionType.RECENTS -> "打开最近任务"
        ActionType.WAIT -> "等待 ${seconds}s"
        ActionType.REPLY_QQ -> "回复「${conversation ?: "?"}」：${text.orEmpty().take(24)}"
        ActionType.READ_NOTIFICATIONS -> "读取通知"
        ActionType.ASK_USER -> "询问你：${question.orEmpty().take(40)}"
        ActionType.DONE -> "完成：${summary.orEmpty().take(40)}"
        ActionType.FAIL -> "放弃：${summary.orEmpty().take(40)}"
        ActionType.UNKNOWN -> "无法识别的动作"
    }

    private fun directionLabel(direction: String?): String = when (direction) {
        "up" -> "上"
        "down" -> "下"
        "left" -> "左"
        else -> "右"
    }

    /** 用于“界面没变化”检测。 */
    fun signature(): String =
        "$type|${target.orEmpty()}|${text.orEmpty()}|${direction.orEmpty()}|${packageName.orEmpty()}|${conversation.orEmpty()}"

    /** 用于确认弹窗的详细说明。 */
    fun explain(): String = buildString {
        appendLine("动作：${oneLine()}")
        if (!reason.isNullOrBlank()) appendLine("她的理由：$reason")
        if (!expect.isNullOrBlank()) appendLine("预期结果：$expect")
        appendLine("原始指令：${raw.replace(Regex("\\s+"), " ").take(320)}")
    }

    companion object {
        /** "#12" → 12；"12" → 12；其他（例如 "推荐"）→ null，表示按文本查找。 */
        fun parseRef(target: String?): Int? {
            val t = target?.trim() ?: return null
            val body = if (t.startsWith("#")) t.substring(1) else t
            return body.toIntOrNull()
        }
    }
}

/**
 * 把模型输出解析成 AgentDecision。
 * 设计原则：解析失败绝不抛异常到主循环，而是返回 FAIL（带原因），
 * 让主循环把“你输出格式不对”作为观察回填给模型，模型有机会自我修正。
 */
object DecisionParser {

    fun parse(raw: String): AgentDecision {
        val json = extractJsonObject(raw)
            ?: return AgentDecision(
                ActionType.FAIL,
                summary = "模型没有输出 JSON。原始输出：${raw.take(160)}",
                raw = raw
            )
        return try {
            fromJson(JSONObject(json), raw)
        } catch (t: Throwable) {
            AgentDecision(ActionType.FAIL, summary = "JSON 解析失败：${t.message}", raw = raw)
        }
    }

    private fun fromJson(obj: JSONObject, raw: String): AgentDecision {
        val params = obj.optJSONObject("params")
            ?: obj.optJSONObject("args")
            ?: obj.optJSONObject("arguments")
            ?: obj

        fun str(vararg keys: String): String? {
            for (source in arrayOf(params, obj)) {
                for (key in keys) {
                    val v = source.optString(key, "")
                    if (v.isNotBlank() && v != "null") return v.trim()
                }
            }
            return null
        }

        fun int(vararg keys: String): Int? {
            for (source in arrayOf(params, obj)) {
                for (key in keys) {
                    if (source.has(key) && !source.isNull(key)) {
                        val v = source.optInt(key, Int.MIN_VALUE)
                        if (v != Int.MIN_VALUE) return v
                    }
                }
            }
            return null
        }

        fun num(key: String): Float? = str(key)?.toFloatOrNull()

        fun direction(): String? {
            val d = (str("direction", "dir", "to") ?: "").lowercase()
            return when {
                d.startsWith("up") || d.contains("上") -> "up"
                d.startsWith("down") || d.contains("下") -> "down"
                d.startsWith("left") || d.contains("左") -> "left"
                d.startsWith("right") || d.contains("右") -> "right"
                else -> null
            }
        }

        val name = (str("action", "type", "tool", "name") ?: "")
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')

        val reason = str("reason", "why", "thought")
        val expect = str("expect", "expectation", "after")

        /** "#12" 保留为 ref；纯数字补上 #；否则当成可见文本。 */
        fun splitTarget(target: String?): Pair<String?, String?> {
            val t = target?.trim() ?: return null to null
            if (t.startsWith("#")) return t to null
            if (t.toIntOrNull() != null) return "#$t" to null
            return null to t
        }

        return when {
            name == "tap" || name == "click" || name == "press" -> {
                val x = int("x")
                val y = int("y")
                if (x != null && y != null) {
                    AgentDecision(ActionType.TAP_XY, x = x, y = y, reason = reason, expect = expect, raw = raw)
                } else {
                    val (ref, textFromTarget) = splitTarget(str("target", "ref", "node", "element"))
                    AgentDecision(
                        ActionType.TAP,
                        target = ref,
                        text = textFromTarget ?: str("text", "label", "content"),
                        reason = reason, expect = expect, raw = raw
                    )
                }
            }

            name == "tap_xy" || name == "click_xy" || name == "tap_at" -> AgentDecision(
                ActionType.TAP_XY,
                x = int("x"), y = int("y"), reason = reason, expect = expect, raw = raw
            )

            name == "long_press" || name == "long_click" || name == "longpress" -> {
                val (ref, textFromTarget) = splitTarget(str("target", "ref", "node"))
                AgentDecision(ActionType.LONG_PRESS, target = ref, text = textFromTarget, reason = reason, expect = expect, raw = raw)
            }

            name == "swipe" || name == "scroll" || name.startsWith("scroll_") || name == "fling" -> {
                val dir = direction() ?: when {
                    name.endsWith("_up") -> "up"
                    name.endsWith("_down") -> "down"
                    name.endsWith("_left") -> "left"
                    name.endsWith("_right") -> "right"
                    else -> "up"
                }
                val rawDistance = num("distance") ?: num("ratio") ?: num("percent")?.div(100f) ?: 0.6f
                AgentDecision(
                    ActionType.SWIPE,
                    direction = dir,
                    distance = rawDistance.coerceIn(0.2f, 0.9f),
                    reason = reason, expect = expect, raw = raw
                )
            }

            name == "input" || name == "set_text" || name == "type" || name == "enter_text" -> {
                val target = str("target", "ref", "node", "field")
                AgentDecision(
                    ActionType.INPUT,
                    target = target?.let { if (it.startsWith("#") || it.toIntOrNull() != null) {
                        if (it.startsWith("#")) it else "#$it"
                    } else null },
                    text = str("text", "value", "content", "input"),
                    reason = reason, expect = expect, raw = raw
                )
            }

            name == "open_app" || name == "launch_app" || name == "start_app" -> AgentDecision(
                ActionType.OPEN_APP,
                packageName = str("package", "package_name", "pkg", "packageName"),
                appName = str("app_name", "app", "label", "target"),
                reason = reason, expect = expect, raw = raw
            )

            name == "back" || name == "press_back" || name == "go_back" -> AgentDecision(
                ActionType.BACK, reason = reason, expect = expect, raw = raw
            )

            name == "home" || name == "go_home" -> AgentDecision(
                ActionType.HOME, reason = reason, expect = expect, raw = raw
            )

            name == "recents" || name == "recent_tasks" -> AgentDecision(
                ActionType.RECENTS, reason = reason, expect = expect, raw = raw
            )

            name == "wait" || name == "sleep" || name == "delay" -> AgentDecision(
                ActionType.WAIT,
                seconds = (num("seconds") ?: num("duration") ?: num("time") ?: 1f).coerceIn(0.2f, 10f),
                reason = reason, expect = expect, raw = raw
            )

            name == "reply_qq" || name == "reply" || name == "send_message" -> AgentDecision(
                ActionType.REPLY_QQ,
                conversation = str("conversation", "contact", "to", "who", "chat"),
                text = str("text", "message", "content", "reply"),
                reason = reason, expect = expect, raw = raw
            )

            name == "read_notifications" || name == "read_notif" || name == "check_notifications" -> AgentDecision(
                ActionType.READ_NOTIFICATIONS,
                packageName = str("package", "package_name", "pkg"),
                reason = reason, expect = expect, raw = raw
            )

            name == "ask_user" || name == "ask" || name == "clarify" -> AgentDecision(
                ActionType.ASK_USER,
                question = str("question", "text", "message"),
                reason = reason, raw = raw
            )

            name == "done" || name == "finish" || name == "complete" || name == "success" -> AgentDecision(
                ActionType.DONE,
                summary = str("summary", "result", "message", "text"),
                raw = raw
            )

            name == "fail" || name == "abort" || name == "give_up" || name == "error" -> AgentDecision(
                ActionType.FAIL,
                summary = str("summary", "reason", "message", "text"),
                raw = raw
            )

            else -> AgentDecision(ActionType.UNKNOWN, raw = raw)
        }
    }

    /**
     * 从“可能带 markdown 围栏、可能带前后废话”的输出里抠出第一个完整 JSON 对象。
     * 用括号深度 + 字符串状态机，避免被 JSON 字符串里的 { } 干扰。
     */
    fun extractJsonObject(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val fence = s.lastIndexOf("```")
            if (fence >= 0) s = s.substring(0, fence).trim()
        }
        val start = s.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
