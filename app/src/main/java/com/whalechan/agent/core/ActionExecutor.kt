package com.whalechan.agent.core

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.whalechan.agent.access.AppsLauncher
import com.whalechan.agent.access.UiNode
import com.whalechan.agent.access.UiSnapshot
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.data.log.ErrorCategory
import com.whalechan.agent.notify.NotificationBus
import kotlinx.coroutines.delay

/**
 * 动作执行器：把 AgentDecision 翻译成无障碍调用。
 *
 * 设计要点：
 *  - 目标节点用“快照 ref + 当前树中的文字”双重定位，界面轻微变化也能点中；
 *  - 失败结果带上 ErrorCategory，直接喂给伴侣端的错误分析卡片；
 *  - 是否允许代填凭据由调用方传入（对应设置里的独立开关）。
 */
class ActionExecutor(private val context: Context) {

    suspend fun execute(
        decision: AgentDecision,
        snapshot: UiSnapshot,
        allowPasswordInput: Boolean = false
    ): ExecResult {
        val service = WhaleAccessibilityService.instance
            ?: return ExecResult.fail("无障碍服务没有连接，无法操作屏幕", ErrorCategory.PERMISSION_LOST)

        return when (decision.type) {
            ActionType.TAP -> tap(service, decision, snapshot)
            ActionType.TAP_XY -> tapByCoordinates(service, decision, snapshot)
            ActionType.LONG_PRESS -> longPress(service, decision, snapshot)
            ActionType.SWIPE -> swipe(service, decision)
            ActionType.INPUT -> input(service, decision, snapshot, allowPasswordInput)
            ActionType.OPEN_APP -> openApp(service, decision)

            ActionType.BACK ->
                if (service.performBack()) ExecResult.ok("已返回上一级")
                else ExecResult.fail("返回失败", ErrorCategory.GESTURE_TIMEOUT)

            ActionType.HOME ->
                if (service.performHome()) ExecResult.ok("已回到桌面")
                else ExecResult.fail("回桌面失败", ErrorCategory.GESTURE_TIMEOUT)

            ActionType.RECENTS ->
                if (service.performRecents()) ExecResult.ok("已打开最近任务")
                else ExecResult.fail("打开最近任务失败", ErrorCategory.GESTURE_TIMEOUT)

            ActionType.WAIT -> {
                val seconds = decision.seconds.coerceIn(0.2f, 10f)
                delay((seconds * 1000).toLong())
                ExecResult.ok("等待 ${seconds}s")
            }

            ActionType.REPLY_QQ -> {
                val conversation = decision.conversation
                val text = decision.text
                if (conversation.isNullOrBlank()) {
                    ExecResult.fail("reply_qq 缺少 conversation", ErrorCategory.LLM_INVALID_ACTION)
                } else if (text.isNullOrBlank()) {
                    ExecResult.fail("reply_qq 缺少 text", ErrorCategory.LLM_INVALID_ACTION)
                } else {
                    QqReplyFlow.reply(context, conversation, text)
                }
            }

            ActionType.READ_NOTIFICATIONS -> {
                val digest = NotificationBus.recentForPrompt(limit = 8, packageFilter = decision.packageName)
                if (digest.isBlank()) ExecResult.ok("最近没有捕获到相关通知")
                else ExecResult.ok("最近通知：\n$digest")
            }

            ActionType.ASK_USER, ActionType.DONE, ActionType.FAIL ->
                ExecResult.fail("该动作由主循环处理，不应到达执行器", ErrorCategory.LLM_INVALID_ACTION)

            ActionType.UNKNOWN ->
                ExecResult.fail("无法识别的动作，请只使用约定的 action 值", ErrorCategory.LLM_INVALID_ACTION)
        }
    }

    // ────────────────────────── 点击 ──────────────────────────

    private fun tap(
        service: WhaleAccessibilityService,
        decision: AgentDecision,
        snapshot: UiSnapshot
    ): ExecResult {
        val node = resolveNode(decision, snapshot)
        if (node != null && service.tapSnapshotNode(node, exactText = decision.text != null)) {
            return ExecResult.ok("已点击「${node.label.ifBlank { node.role }}」")
        }

        val fallbackText = decision.text
            ?: decision.target?.takeIf { AgentDecision.parseRef(it) == null }
        if (!fallbackText.isNullOrBlank() && service.tapByText(fallbackText, exact = decision.text != null)) {
            return ExecResult.ok("已按文字点击「$fallbackText」")
        }

        return ExecResult.fail(
            "没有找到可点击的目标：${decision.target ?: decision.text ?: "未指定"}",
            ErrorCategory.NODE_NOT_FOUND
        )
    }

    private fun tapByCoordinates(
        service: WhaleAccessibilityService,
        decision: AgentDecision,
        snapshot: UiSnapshot
    ): ExecResult {
        val x = decision.x ?: return ExecResult.fail("tap_xy 缺少 x", ErrorCategory.LLM_INVALID_ACTION)
        val y = decision.y ?: return ExecResult.fail("tap_xy 缺少 y", ErrorCategory.LLM_INVALID_ACTION)
        if (x !in 0..snapshot.screenWidth || y !in 0..snapshot.screenHeight) {
            return ExecResult.fail(
                "坐标 ($x, $y) 超出屏幕 ${snapshot.screenWidth}x${snapshot.screenHeight}",
                ErrorCategory.SCREEN_STATE_DRIFT
            )
        }
        return if (service.tapAt(x.toFloat(), y.toFloat())) {
            ExecResult.ok("已在 ($x, $y) 点击")
        } else {
            ExecResult.fail("手势注入被系统拒绝（可能处于锁屏或安全界面）", ErrorCategory.GESTURE_TIMEOUT)
        }
    }

    private fun longPress(
        service: WhaleAccessibilityService,
        decision: AgentDecision,
        snapshot: UiSnapshot
    ): ExecResult {
        val node = resolveNode(decision, snapshot)
        if (node != null && node.hasBounds) {
            return if (service.longPressAt(node.bounds.exactCenterX(), node.bounds.exactCenterY())) {
                ExecResult.ok("已长按「${node.label.ifBlank { node.role }}」")
            } else {
                ExecResult.fail("长按手势被拒绝", ErrorCategory.GESTURE_TIMEOUT)
            }
        }
        val x = decision.x
        val y = decision.y
        if (x != null && y != null && service.longPressAt(x.toFloat(), y.toFloat())) {
            return ExecResult.ok("已在 ($x, $y) 长按")
        }
        return ExecResult.fail("没有找到可长按的目标", ErrorCategory.NODE_NOT_FOUND)
    }

    // ────────────────────────── 滑动与输入 ──────────────────────────

    private fun swipe(service: WhaleAccessibilityService, decision: AgentDecision): ExecResult {
        val direction = decision.direction ?: "up"
        return if (service.swipe(direction, decision.distance)) {
            ExecResult.ok("已向 $direction 滑动")
        } else {
            ExecResult.fail("滑动手势被拒绝", ErrorCategory.GESTURE_TIMEOUT)
        }
    }

    private fun input(
        service: WhaleAccessibilityService,
        decision: AgentDecision,
        snapshot: UiSnapshot,
        allowPasswordInput: Boolean
    ): ExecResult {
        val value = decision.text ?: return ExecResult.fail("input 缺少 text", ErrorCategory.LLM_INVALID_ACTION)
        val target = resolveNode(decision, snapshot)
        val editable = service.findEditableNode(target?.bounds)
            ?: return ExecResult.fail("当前界面没有找到输入框", ErrorCategory.NODE_NOT_FOUND)

        val isPassword = runCatching { editable.isPassword }.getOrDefault(false)
        if (isPassword && !allowPasswordInput) {
            return ExecResult.fail(
                "目标是密码框，但设置里没有开启「允许代填凭据」",
                ErrorCategory.UNKNOWN
            )
        }

        return if (service.setText(editable, value, allowPasswordInput)) {
            ExecResult.ok("已写入「${value.take(20)}」")
        } else {
            ExecResult.fail("输入框拒绝写入", ErrorCategory.NODE_NOT_FOUND)
        }
    }

    // ────────────────────────── 打开应用 ──────────────────────────

    /**
     * 打开应用：startActivity 是异步的，必须等窗口真正切过去再返回。
     *
     * 步骤：launch → awaitAppLaunch(≤5s) → 再等 1s（开屏/骨架屏）→ 复核前台包名。
     * 任何一步不满足都返回带 ErrorCategory 的失败，让主循环把原因回填给模型，而不是当作成功。
     */
    private suspend fun openApp(
        service: WhaleAccessibilityService,
        decision: AgentDecision
    ): ExecResult {
        val packageName = resolveAppPackage(decision)
            ?: return ExecResult.fail(
                "不知道要打开哪个应用：${decision.packageName ?: decision.appName ?: "未指定"}",
                ErrorCategory.NODE_NOT_FOUND
            )

        val label = AppsLauncher.appLabel(context, packageName)

        return when (val result = AppsLauncher.launch(context, packageName)) {
            is AppsLauncher.Result.Ok -> {
                val startedAt = SystemClock.elapsedRealtime()

                val arrived = service.awaitAppLaunch(packageName, APP_LAUNCH_TIMEOUT_MS)
                if (!arrived) {
                    val spent = SystemClock.elapsedRealtime() - startedAt
                    return ExecResult.fail(
                        "启动「$label」超时：${spent}ms 内没有进入前台（开屏广告、登录页、被杀或后台启动被拦）",
                        ErrorCategory.APP_LAUNCH_TIMEOUT
                    )
                }

                // 等开屏广告 / 首页骨架屏加载，避免抓到白屏节点树
                delay(APP_LAUNCH_SETTLE_MS)

                val current = service.currentPackage()
                if (current != packageName) {
                    return ExecResult.fail(
                        "「$label」启动后界面又切走了：当前=$current，预期=$packageName",
                        ErrorCategory.SCREEN_STATE_DRIFT
                    )
                }

                val spent = SystemClock.elapsedRealtime() - startedAt
                ExecResult.ok(
                    "已打开「$label」，等待 ${spent}ms 后界面就绪（节点 ${service.currentNodeCount()} 个）"
                )
            }

            is AppsLauncher.Result.NeedUserGesture -> {
                AppsLauncher.notifyToOpen(context, packageName)
                ExecResult.fail(
                    "Android 限制后台启动应用。已发通知，请你点一下打开「${result.label}」，然后让我继续",
                    ErrorCategory.PERMISSION_LOST
                )
            }

            is AppsLauncher.Result.Failed ->
                ExecResult.fail(result.message, ErrorCategory.UNKNOWN)
        }
    }

    private fun resolveAppPackage(decision: AgentDecision): String? {
        decision.packageName?.takeIf { it.isNotBlank() }?.let { raw ->
            AppsLauncher.resolvePackage(context, raw)?.let { return it }
        }
        decision.appName?.takeIf { it.isNotBlank() }?.let { raw ->
            AppsLauncher.resolvePackage(context, raw)?.let { return it }
        }
        return null
    }

    // ────────────────────────── 解析 ──────────────────────────

    private fun resolveNode(decision: AgentDecision, snapshot: UiSnapshot): UiNode? {
        AgentDecision.parseRef(decision.target)?.let { ref ->
            snapshot.byRef(ref)?.let { return it }
        }
        val label = decision.text ?: decision.target ?: return null
        return snapshot.nodes.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: snapshot.nodes.firstOrNull { it.label.contains(label, ignoreCase = true) }
    }

    /** 供 QqReplyFlow 之类的外部流程复用。 */
    fun editableNode(): AccessibilityNodeInfo? =
        WhaleAccessibilityService.instance?.findEditableNode()

    companion object {
        /** 冷启动等待上限：5s（可在调用处覆盖，但默认按需求走）。 */
        const val APP_LAUNCH_TIMEOUT_MS = 5_000L

        /** 进入前台后再等 1s，避开开屏广告与骨架屏。 */
        const val APP_LAUNCH_SETTLE_MS = 1_000L
    }
}
