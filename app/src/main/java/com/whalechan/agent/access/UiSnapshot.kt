package com.whalechan.agent.access

import android.graphics.Rect

/**
 * 一个无障碍节点的“快照副本”。
 * 故意不持有 AccessibilityNodeInfo：那个对象跨轮次会失效、且不同 ROM 回收行为不一致。
 * 执行动作时用 ref + 文本 + bounds 重新在当前树里定位。
 */
data class UiNode(
    val ref: Int,
    val text: String,
    val desc: String,
    val hint: String,
    /** 显示用标签：text → contentDescription → hint → 后代文本。 */
    val label: String,
    val className: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val bounds: Rect,
    val depth: Int
) {
    val role: String
        get() {
            val simple = className.substringAfterLast('.')
            val trimmed = simple.removeSuffix("View")
            return trimmed.ifBlank { simple }.ifBlank { "Node" }
        }

    val hasBounds: Boolean
        get() = !bounds.isEmpty

    /** 单行紧凑描述，尽量省 token。 */
    fun renderLine(): String = buildString {
        append('#').append(ref).append(' ').append('[').append(role).append(']')
        if (label.isNotBlank()) {
            append(" \"").append(label.replace('\n', ' ').take(80)).append('"')
        }
        if (desc.isNotBlank() && desc != label) {
            append(" desc=\"").append(desc.replace('\n', ' ').take(40)).append('"')
        }
        if (hint.isNotBlank() && hint != label) {
            append(" hint=\"").append(hint.replace('\n', ' ').take(40)).append('"')
        }
        if (clickable) append(" tap")
        if (longClickable && !clickable) append(" long")
        if (editable) append(" edit")
        if (scrollable) append(" scroll")
        if (password) append(" PASSWORD")
        if (checked) append(" checked")
        if (!enabled) append(" disabled")
        if (focused) append(" focused")
        append(" (").append(bounds.left).append(',')
            .append(bounds.top).append(',')
            .append(bounds.right).append(',')
            .append(bounds.bottom).append(')')
    }
}

/** 一次界面抓取的不可变结果。 */
data class UiSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>,
    val truncated: Boolean,
    val takenAt: Long
) {

    fun byRef(ref: Int): UiNode? = nodes.firstOrNull { it.ref == ref }

    fun containsText(value: String): Boolean {
        if (value.isBlank()) return false
        return nodes.any {
            it.text.contains(value, ignoreCase = true) || it.desc.contains(value, ignoreCase = true)
        }
    }

    fun focusedEditable(): UiNode? = nodes.firstOrNull { it.editable && it.focused }
        ?: nodes.firstOrNull { it.editable }

    /**
     * 用于“界面是否变化”的指纹。
     *
     * 只取 text + contentDescription + className，**不包含 bounds**：
     *  - 视频/直播画面在动，但节点文本不变，不该被判成“变化”；
     *  - 布局轻微位移（下拉刷新回弹）也不该算变化；
     *  - 真正的新文本（评论、标题、时间戳）会让指纹变化，这正是我们想要的。
     */
    fun signature(): String {
        val sb = StringBuilder(nodes.size * 12)
        nodes.forEach { node ->
            sb.append(node.text).append('\u0001')
                .append(node.desc).append('\u0001')
                .append(node.className).append('\u0002')
        }
        return "$packageName:${nodes.size}:${sb.toString().hashCode()}"
    }

    /**
     * 输出给 LLM 的简化界面描述。
     * @param maxChars 硬上限，防止超大界面（长列表）把上下文撑爆。
     */
    fun render(maxChars: Int = 6000): String = buildString {
        append("前台应用: ").append(packageName.ifBlank { "未知" })
        append("｜屏幕: ").append(screenWidth).append('x').append(screenHeight)
        append("｜可交互节点: ").append(nodes.size)
        if (truncated) append("（已截断，只保留前若干个）")
        append('\n')

        var omitted = 0
        for (index in nodes.indices) {
            val line = nodes[index].renderLine() + "\n"
            if (length + line.length > maxChars) {
                omitted = nodes.size - index
                break
            }
            append(line)
        }
        if (omitted > 0) {
            append("…（因长度限制省略 ").append(omitted).append(" 个节点）\n")
        }
    }
}
