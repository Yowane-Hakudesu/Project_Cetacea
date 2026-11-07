package com.whalechan.agent.core

import com.whalechan.agent.access.UiSnapshot
import java.security.MessageDigest

/**
 * 从当前屏幕里提取「正在和谁说话」「这条动态是什么」。
 *
 * 无障碍拿不到应用内部数据模型，只能靠布局启发式：
 *  - CurrentEntity：屏幕顶部区域、水平居中、非系统 chrome 的文本；
 *  - PostKey：信息流区域里最长的一段文本（通常是标题/正文首行），hash 后当稳定 ID。
 *
 * 启发式一定会出错，所以所有调用方都要允许 null，并在拿不到时退回「未知」，
 * 绝不能因为识别失败就编造一个好友名。
 */
object SocialContext {

    private val CHROME = setOf(
        "QQ", "TIM", "返回", "更多", "聊天", "消息", "动态", "联系人", "空间",
        "设置", "取消", "发送", "搜索", "发现", "看点", "推荐", "关注", "首页"
    )

    private val TIME_PATTERN = Regex("^\\d{1,2}:\\d{2}$")

    /** 当前聊天窗口标题（好友/群名）；不在聊天页时返回 null。 */
    fun currentEntity(snapshot: UiSnapshot): String? {
        val limitTop = (snapshot.screenHeight * 0.18f).toInt().coerceAtLeast(120)
        val centerX = snapshot.screenWidth / 2f

        return snapshot.nodes.asSequence()
            .filter { it.text.isNotBlank() && !it.editable && !it.password }
            .filter { it.bounds.top in 0..limitTop && it.bounds.height() > 0 }
            .map { node -> node.text.trim() to node }
            .filter { (text, _) ->
                text.length in 1..16 &&
                    text !in CHROME &&
                    !TIME_PATTERN.matches(text) &&
                    text.any { it.isLetterOrDigit() }
            }
            .sortedWith(
                compareBy(
                    // 标题通常在中上、水平居中；越靠上越可能是状态栏
                    { (_, node) -> kotlin.math.abs((node.bounds.left + node.bounds.right) / 2f - centerX) },
                    { (_, node) -> node.bounds.top }
                )
            )
            .map { it.first }
            .firstOrNull()
    }

    /** 信息流里最显眼的一段文本，作为这条 Post 的稳定标识来源。 */
    fun postTitle(snapshot: UiSnapshot): String? {
        val top = (snapshot.screenHeight * 0.15f).toInt()
        val bottom = (snapshot.screenHeight * 0.8f).toInt()
        return snapshot.nodes.asSequence()
            .filter { it.text.length in 4..60 && !it.editable }
            .filter { it.bounds.top in top..bottom }
            .maxByOrNull { it.text.length }
            ?.text
            ?.trim()
    }

    /** 动态指纹：标题文本 + 包名，取 SHA-1 前 16 位。 */
    fun postKey(snapshot: UiSnapshot): String? {
        val title = postTitle(snapshot)?.take(60) ?: return null
        val raw = "${snapshot.packageName}|$title"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 是否在聊天会话页（有输入框 + 发送按钮）。 */
    fun isChatScreen(snapshot: UiSnapshot): Boolean =
        snapshot.nodes.any { it.editable } &&
            snapshot.nodes.any { it.label.contains("发送") || it.label.contains("Send") }

    fun stableHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
