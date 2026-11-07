package com.whalechan.agent.core

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.whalechan.agent.access.AppsLauncher
import com.whalechan.agent.access.UiSnapshot
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.data.log.ErrorCategory
import com.whalechan.agent.notify.ReplyLedger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * “在 QQ 里回一条消息”的确定性流程。
 *
 * 为什么不用 LLM 逐步摸索：这条路径步骤固定、失败点明确，写成确定性流程成功率更高；
 * LLM 只负责决定“回给谁、回什么”，不负责点哪。
 *
 * 步骤：确认 QQ 在前台 → 定位会话 → 找到输入框 → 按句拆分逐条发送（每段 ≤15 字，
 * 间隔 1.5~3 秒）→ 每条都校验是否出现在聊天列表 → 记入社交账本（供限流与记忆使用）。
 */
object QqReplyFlow {

    private const val LAUNCH_TIMEOUT_MS = 10_000L
    private const val INPUT_TIMEOUT_MS = 8_000L
    private const val SEND_SETTLE_MS = 1_300L

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
                ?: return ExecResult.fail("设备上没找到 QQ / TIM", ErrorCategory.NODE_NOT_FOUND)

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

        // ③ 按句拆分，逐条发送
        val segments = TextSegmenter.split(text)
        if (segments.isEmpty()) {
            return ExecResult.fail("回复内容拆分为空", ErrorCategory.LLM_INVALID_ACTION)
        }

        val sent = ArrayList<String>(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val input = awaitEditable(service, INPUT_TIMEOUT_MS)
                ?: return partial(conversation, sent, "没有找到聊天输入框，QQ 界面可能改版了")

            if (!service.setText(input, segment)) {
                return partial(conversation, sent, "输入框拒绝写入（可能是安全输入框，或 QQ 的防自动化限制）")
            }
            delay(350)

            if (!tapSend(service)) {
                return partial(conversation, sent, "没有找到“发送”按钮，QQ 界面可能改版了")
            }
            delay(SEND_SETTLE_MS)

            // ④ 完成判定：必须出现在非输入框节点里
            val after = service.snapshot()
            if (!appeared(after, segment)) {
                return partial(conversation, sent, "点了发送，但界面上没有出现「${segment.take(12)}」")
            }

            sent += segment
            ReplyLedger.record(segment)

            if (index < segments.lastIndex) {
                // 真人打字节奏：1.5~3 秒一条
                delay(Random.nextLong(1_500L, 3_001L))
            }
        }

        // 一次“回复动作”只记一次账：限流按「回合」算，而不是按气泡数算，
        // 否则一条回复被拆成 2~3 段就会把自己的 30 秒限额吃满。
        runCatching {
            AgentRuntime.rateLimiter.record(
                type = SocialActionType.REPLY_MESSAGE,
                targetKey = conversation,
                summary = "给「$conversation」发消息：${sent.joinToString("").take(20)}",
                textHash = SocialContext.stableHash("$conversation|$text")
            )
        }

        return ExecResult.ok(
            "已在「$conversation」按 ${sent.size} 条发送：${sent.joinToString(" / ").take(60)}"
        )
    }

    private fun partial(conversation: String, sent: List<String>, reason: String): ExecResult {
        val detail = if (sent.isEmpty()) {
            "发送失败：$reason"
        } else {
            "已发出 ${sent.size} 条（${sent.joinToString(" / ").take(40)}），后续失败：$reason"
        }
        return ExecResult.fail(detail, ErrorCategory.SCREEN_STATE_DRIFT)
    }

    private fun appeared(snapshot: UiSnapshot, segment: String): Boolean {
        val snippet = segment.take(12)
        if (snippet.isBlank()) return true
        return snapshot.nodes.any { node ->
            !node.editable && (
                node.text.contains(snippet, ignoreCase = true) ||
                    node.label.contains(snippet, ignoreCase = true)
                )
        }
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
        if (service.hasEditable() && service.snapshot().containsText(conversation)) return true

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
