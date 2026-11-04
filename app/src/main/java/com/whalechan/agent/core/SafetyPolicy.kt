package com.whalechan.agent.core

import com.whalechan.agent.access.UiNode
import com.whalechan.agent.access.UiSnapshot
import com.whalechan.agent.data.AppSettings

/**
 * 安全策略：在“模型说要做”与“真的去做”之间插一层确定性判断。
 *
 * 三档：
 *  - SAFE     直接执行（普通滑动、点已知按钮）
 *  - CONFIRM  必须用户点“确认”（付款、删除、发消息、系统设置、金融类应用）
 *  - FORBIDDEN 永远不做（代填密码/验证码、在系统权限弹窗里代点、好奇模式输入内容）
 *
 * 这一层不依赖模型自觉：即使模型被提示词注入带偏，违规动作也出不去。
 */
object SafetyPolicy {

    enum class Risk { SAFE, CONFIRM, FORBIDDEN }

    data class Verdict(val risk: Risk, val reason: String = "")

    private val PASSWORD_WORDS = listOf(
        "密码", "支付密码", "验证码", "安全码", "口令", "cvv", "pin", "密保"
    )

    private val PAYMENT_WORDS = listOf(
        "确认支付", "立即支付", "付款", "转账", "收银台", "确认订单", "立即购买",
        "充值", "打赏", "提现", "开通会员", "自动续费", "免密支付", "发红包", "转账"
    )

    private val DESTRUCTIVE_WORDS = listOf(
        "删除", "卸载", "清除数据", "注销账号", "退出登录", "恢复出厂", "格式化", "解绑", "解散群"
    )

    private val SYSTEM_PACKAGES = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.huawei.systemmanager"
    )

    private val FINANCE_PACKAGES = setOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.chinamworld.main",
        "cmb.pb",
        "com.icbc",
        "com.bankcomm.Bankcomm",
        "com.android.bankabc"
    )

    /** 好奇模式允许打开的应用：只读内容类，不含社交/支付/设置。 */
    private val CONTENT_PACKAGES = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "tv.danmaku.bili",
        "com.xingin.xhs",
        "com.sina.weibo"
    )

    fun evaluate(
        decision: AgentDecision,
        snapshot: UiSnapshot?,
        settings: AppSettings,
        mode: RunMode
    ): Verdict {
        val packageName = snapshot?.packageName.orEmpty()
        val node = resolveNode(decision, snapshot)
        val label = actionLabel(decision, node)

        // ① 好奇模式：只许看，不许动手
        if (mode == RunMode.CURIOUS) {
            when (decision.type) {
                ActionType.INPUT, ActionType.REPLY_QQ ->
                    return Verdict(Risk.FORBIDDEN, "好奇模式只能浏览，不能输入内容或发消息")

                ActionType.OPEN_APP -> {
                    val target = decision.packageName.orEmpty()
                    if (target !in CONTENT_PACKAGES) {
                        return Verdict(Risk.FORBIDDEN, "好奇模式只允许打开内容类应用，不允许打开 $target")
                    }
                }

                ActionType.TAP, ActionType.TAP_XY, ActionType.LONG_PRESS -> {
                    if (PAYMENT_WORDS.any { label.contains(it, ignoreCase = true) } ||
                        DESTRUCTIVE_WORDS.any { label.contains(it, ignoreCase = true) }
                    ) {
                        return Verdict(Risk.FORBIDDEN, "好奇模式不点支付/删除类按钮")
                    }
                }

                else -> Unit
            }
        }

        // ② 密码 / 验证码：即使 FULL_TRUST 也受“允许代填凭据”这个单独开关约束
        if (decision.type == ActionType.INPUT) {
            val fieldText = listOfNotNull(node?.hint, node?.label, node?.desc, node?.text)
                .joinToString(" ")
            val passwordInSnapshot = snapshot?.nodes?.any { it.password && it.focused } == true
            if (node?.password == true || passwordInSnapshot ||
                PASSWORD_WORDS.any { fieldText.contains(it, ignoreCase = true) }
            ) {
                if (!settings.allowCredentialEntry) {
                    return Verdict(
                        Risk.FORBIDDEN,
                        "这是密码/验证码输入框；需要在设置里单独开启「允许代填凭据」"
                    )
                }
            }
        }

        // ③ 完全信任：跳过其余全部拦截（仍然写日志）
        if (settings.fullTrustMode) {
            return Verdict(Risk.SAFE)
        }

        // ④ 系统设置 / 权限弹窗：必须用户亲手点
        if (packageName in SYSTEM_PACKAGES &&
            decision.type in setOf(ActionType.TAP, ActionType.TAP_XY, ActionType.LONG_PRESS, ActionType.INPUT)
        ) {
            return Verdict(Risk.FORBIDDEN, "系统设置与权限弹窗必须由你亲手操作，我不代点")
        }

        // ⑤ 付款 / 破坏性操作
        if (PAYMENT_WORDS.any { label.contains(it, ignoreCase = true) }) {
            return Verdict(Risk.CONFIRM, "这一步看起来涉及付款或交易：$label")
        }
        if (DESTRUCTIVE_WORDS.any { label.contains(it, ignoreCase = true) }) {
            return Verdict(Risk.CONFIRM, "这一步可能删除内容或退出账号：$label")
        }

        // ⑥ 代发消息
        if (decision.type == ActionType.REPLY_QQ && !settings.autoSendWithoutConfirm) {
            return Verdict(Risk.CONFIRM, "要代你给「${decision.conversation ?: "对方"}」发一条消息")
        }

        // ⑦ 打开系统设置 / 身处金融类应用
        val openingSystem = decision.type == ActionType.OPEN_APP &&
            decision.packageName?.let { it in SYSTEM_PACKAGES } == true
        if (openingSystem) {
            return Verdict(Risk.CONFIRM, "要打开系统设置吗？")
        }
        if (packageName in FINANCE_PACKAGES) {
            return Verdict(Risk.CONFIRM, "当前在支付/银行类应用里，每一步我都想先问你")
        }

        return Verdict(Risk.SAFE)
    }

    private fun resolveNode(decision: AgentDecision, snapshot: UiSnapshot?): UiNode? {
        if (snapshot == null) return null
        AgentDecision.parseRef(decision.target)?.let { ref ->
            snapshot.byRef(ref)?.let { return it }
        }
        // 注意：INPUT 的 text 字段是"要输入的内容"，不是控件标签
        val label = when (decision.type) {
            ActionType.INPUT -> null
            else -> decision.target ?: decision.text
        } ?: return null
        return snapshot.nodes.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: snapshot.nodes.firstOrNull { it.label.contains(label, ignoreCase = true) }
    }

    private fun actionLabel(decision: AgentDecision, node: UiNode?): String = buildString {
        append(decision.oneLine())
        if (node != null) {
            append("｜控件：").append(node.label.ifBlank { node.role })
            if (node.hint.isNotBlank()) append(' ').append(node.hint)
        }
        if (decision.type == ActionType.REPLY_QQ) {
            append("｜内容：").append(decision.text.orEmpty().take(60))
        }
        if (decision.type == ActionType.OPEN_APP) {
            append('｜').append(decision.appName.orEmpty())
        }
    }
}
