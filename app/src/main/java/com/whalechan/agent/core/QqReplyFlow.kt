package com.whalechan.agent.core

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.whalechan.agent.access.AppsLauncher
import com.whalechan.agent.access.UiSnapshot
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.data.log.ErrorCategory
import com.whalechan.agent.notify.ReplyLedger
import kotlinx.coroutines.delay

/**
 * “在 QQ 里回一条消息”的确定性流程。
 *
 * 为什么不用 LLM 逐步摸索：这条路径步骤固定、失败点明确，写成确定性流程成功率更高；
 * LLM 只负责决定“回给谁、回什么”，不负责点哪。
 *
 * 步骤：确保 QQ 在前台 → 定位会话 → 找到输入框 → ACTION_SET_TEXT → 点“发送” → 检查界面是否出现该消息。
 * 每一步都可能失败：QQ 改版、登录态失效、青少年模式、输入框换成自绘控件、发送按钮换图标。
 * 失败时明确返回原因，绝不假装成功。
 */
object QqReplyFlow {

    private const val LAUNCH_TIMEOUT_MS = 10_000L
    private const val INPUT_TIMEOUT_MS = 8_000L

    suspend fun reply(context: Context, conversation: String, text: String): ExecResult {
        val service = WhaleAccessibilityService.instance
            ?: return ExecResult.fail("无障碍服务没有连接，打不开 QQ")

        if (conversation.isBlank()) {
            return ExecResult.fail("没有指定要回复的会话", ErrorCategory.LLM_INVALID_ACTION)
        }
        if (text.isBlank()) {
            return ExecResult.fail("回复内容为空", ErrorCategory.LLM_INVALID_ACTION)
        }

        // ① 确保处于 QQ 前台
        var snapshot = service.snapshot()
        if (snapshot.packageName !in AppsLauncher.QQ_PACKAGES) {
            val packageName = AppsLauncher.QQ_PACKAGES
                .firstOrNull { AppsLauncher.isInstalled(context, it) }
                ?: return ExecResult.fail("设备上没有找到 QQ / TIM", ErrorCategory.NODE_NOT_FOUND)

            when (val result = AppsLauncher.launch(context, packageName)) {
                is AppsLauncher.Result.Failed ->
                    return ExecResult.fail(result.message, ErrorCategory.UNKNOWN)
                is AppsLauncher.Result.NeedUserGesture -> {
                    AppsLauncher.notifyToOpen(context, packageName)
                    return ExecResult.fail(
                        "Android 不允许我在后台打开 QQ；已发一条通知，你点开之后我就能继续",
                        ErrorCategory.PERMISSION_LOST
                    )
                }
                is AppsLauncher.Result.Ok -> Unit
            }

            snapshot = awaitPackage(service, packageName, LAUNCH_TIMEOUT_MS)
                ?: return ExecResult.fail(
                    "打开 QQ 后 ${LAUNCH_TIMEOUT_MS / 1000} 秒内没有看到 QQ 界面（可能停在登录页）",
                    ErrorCategory.SCREEN_STATE_DRIFT
                )
        }

        // ② 定位会话
        if (!openConversation(service, conversation)) {
            return ExecResult.fail(
                "没能在 QQ 里定位到「$conversation」会话。可以先把该会话置顶，或手动打开一次让它出现在最近会话里",
                ErrorCategory.NODE_NOT_FOUND
            )
        }

        // ③ 写入输入框
        val input = awaitEditable(service, INPUT_TIMEOUT_MS)
            ?: return ExecResult.fail("没有找到聊天输入框，QQ 界面可能改版了", ErrorCategory.NODE_NOT_FOUND)
        if (!service.setText(input, text)) {
            return ExecResult.fail(
                "输入框拒绝写入（可能是安全输入框，或 QQ 的防自动化限制）",
                ErrorCategory.UNKNOWN
            )
        }
        delay(500)

        // ④ 点发送
        if (!tapSend(service)) {
            return ExecResult.fail("没有找到“发送”按钮，QQ 界面可能改版了", ErrorCategory.NODE_NOT_FOUND)
        }
        delay(1500)

        // ⑤ 校验：消息必须出现在非输入框节点里，且输入框已被清空
        val after = service.snapshot()
        val snippet = text.take(24)
        val stillInInput = after.nodes.any { node ->
            node.editable && (node.text.contains(snippet, ignoreCase = true))
        }
        val appeared = after.nodes.any { node ->
            !node.editable && (
                node.text.contains(snippet, ignoreCase = true) ||
                    node.label.contains(snippet, ignoreCase = true)
                )
        }
        if (stillInInput || !appeared) {
            return ExecResult.fail(
                "点了发送，但界面上没有出现这条消息，可能没有发送成功",
                ErrorCategory.SCREEN_STATE_DRIFT
            )
        }

        ReplyLedger.record(text)
        return ExecResult.ok("已通过界面操作在「$conversation」发送：${text.take(30)}")
    }

    // ────────────────────────── 内部步骤 ──────────────────────────

    private suspend fun awaitPackage(
        service: WhaleAccessibilityService,
        packageName: String,
        timeoutMs: Long
    ): UiSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = service.snapshot()
            if (snapshot.packageName == packageName && snapshot.nodes.isNotEmpty()) return snapshot
            delay(400)
        }
        return null
    }

    private suspend fun awaitEditable(
        service: WhaleAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            service.findEditableNode()?.let { return it }
            delay(400)
        }
        return null
    }

    private suspend fun openConversation(
        service: WhaleAccessibilityService,
        conversation: String
    ): Boolean {
        // 已经在目标会话里
        if (service.hasEditable() && service.snapshot().containsText(conversation)) return true

        // 会话列表里直接可见
        if (service.tapByText(conversation, exact = false)) {
            delay(900)
            if (service.hasEditable()) return true
        }

        // 退而求其次：用 QQ 的搜索
        val searchEntry = service.findNodesByText("搜索", exact = false).firstOrNull()
            ?: service.findNodesByText("Search", exact = false).firstOrNull()
        if (searchEntry != null && service.clickNode(searchEntry)) {
            delay(700)
            val searchBox = service.findEditableNode()
            if (searchBox != null && service.setText(searchBox, conversation)) {
                delay(1500)
                if (service.tapByText(conversation, exact = false)) {
                    delay(1200)
                    if (service.hasEditable()) return true
                }
            }
        }
        return false
    }

    private fun tapSend(service: WhaleAccessibilityService): Boolean {
        val exact = service.findNodesByText("发送", exact = true)
        val fuzzy = service.findNodesByText("发送", exact = false)
        val english = service.findNodesByText("Send", exact = false)
        val candidates = (exact + fuzzy + english).distinct()
        for (node in candidates) {
            if (service.clickNode(node)) return true
        }
        return false
    }
}
