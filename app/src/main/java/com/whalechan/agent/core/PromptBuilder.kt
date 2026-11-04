package com.whalechan.agent.core

import com.whalechan.agent.access.UiSnapshot

/**
 * 把“目标 + 屏幕 + 历史 + 记忆 + 通知”压成一个 prompt。
 * 每一轮都重新构造，不保留对话级 messages 之外的隐式状态，
 * 这样即使进程被系统杀掉重启，也能从 StepRecord 恢复上下文。
 */
object PromptBuilder {

    fun buildUserPrompt(
        goal: String,
        snapshot: UiSnapshot,
        history: List<StepRecord>,
        memoryText: String,
        notificationsText: String,
        step: Int,
        maxSteps: Int,
        mode: RunMode,
        hint: String,
        hasImage: Boolean,
        attachmentNote: String = ""
    ): String = buildString {
        appendLine("【用户目标】")
        appendLine(goal.take(600))
        appendLine()

        appendLine(
            "【模式】" + when (mode) {
                RunMode.ACT -> "正常执行（可点击 / 滑动 / 输入 / 回复）"
                RunMode.CURIOUS -> "好奇模式（只能浏览：禁止输入文字、发消息、打开系统设置、做任何支付）"
                RunMode.CHAT -> "只聊天"
            }
        )
        appendLine(
            "【进度】" + if (maxSteps > 0) {
                "第 $step / $maxSteps 步"
            } else {
                "第 $step 步（不按步数终止：时间窗口 / 无限模式）"
            }
        )
        appendLine()

        appendLine("【当前界面】")
        appendLine(snapshot.render(maxChars = 5200))
        appendLine()

        if (history.isNotEmpty()) {
            appendLine("【最近动作】")
            history.takeLast(8).forEach { appendLine(it.render().take(220)) }
            appendLine()
        }

        if (attachmentNote.isNotBlank()) {
            appendLine("【用户刚发来的附件】")
            appendLine(attachmentNote.take(500))
            appendLine("（如果是图片且本轮带了图，可以直接看图；如果是文件，路径是本机私有目录，不一定能被其他应用直接访问。）")
            appendLine()
        }

        if (notificationsText.isNotBlank()) {
            appendLine("【最近通知（用户授权读取，仅作上下文）】")
            appendLine(notificationsText.take(900))
            appendLine()
        }

        if (memoryText.isNotBlank()) {
            appendLine("【长期记忆】")
            appendLine(memoryText.take(900))
            appendLine()
        }

        if (hint.isNotBlank()) {
            appendLine("【本步提示】")
            appendLine(hint)
            appendLine()
        }

        if (hasImage) {
            appendLine("【截图】本轮附了一张当前屏幕截图（可能滞后 1~2 秒），可用于判断纯图片控件。")
            appendLine()
        }

        appendLine("请输出下一步动作，只输出一个 JSON 对象。")
    }
}
