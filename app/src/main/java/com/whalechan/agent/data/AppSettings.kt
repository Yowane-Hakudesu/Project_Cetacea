package com.whalechan.agent.data

/**
 * 用户可配置的全部设置项。
 * 注意：API Key 不在这里，它单独走 Android Keystore 加密后存 SharedPreferences。
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val modelId: String = DEFAULT_MODEL,
    val temperature: Double = 0.2,

    // ── 步数策略 ──
    /** LIMITED / TIME_WINDOW / UNLIMITED，见 core/StepPolicy.kt */
    val stepPolicy: String = "LIMITED",
    /** A 档步数上限，1–500 */
    val maxSteps: Int = 50,
    /** B 档时间窗口（分钟），0 表示不限时 */
    val maxRuntimeMinutes: Int = 30,
    /** C 档必须显式勾选的风险确认 */
    val unlimitedConsent: Boolean = false,
    /** 连续多少步界面指纹不变判定卡死（默认 15 步） */
    val stallLimit: Int = 15,
    /** 或者：界面连续多少秒没有任何文本/节点变化判定卡死（默认 30s） */
    val stallWindowSeconds: Int = 30,

    // ── 信任模式 ──
    /** FULL_TRUST：不再做任何操作前拦截，只保留日志 */
    val fullTrustMode: Boolean = false,
    /**
     * 是否允许向密码/验证码输入框写内容。
     * 这是 FULL_TRUST 之外单独的一道开关：即使完全信任，默认也不代填凭据，
     * 因为凭据泄露的后果不可逆，且这类输入框通常伴随风控。
     */
    val allowCredentialEntry: Boolean = false,
    /** 屏幕指纹命中时优先回放历史动作序列（SMART_LAZY） */
    val cacheReplayEnabled: Boolean = true,

    // ── 远程伴侣 ──
    val wsEnabled: Boolean = false,
    val wsPort: Int = 8787,

    // ── 日志 ──
    val logRetentionDays: Int = 7,
    val logMaxRows: Int = 5000,

    // ── 保活 ──
    val keepAliveEnabled: Boolean = true,
    /** 激进保活：空闲时也持有 PARTIAL_WAKE_LOCK。更稳，但明显更耗电。 */
    val aggressiveWakeLock: Boolean = false,

    // ── 行为 ──
    /** 代发 QQ 消息是否免确认（FULL_TRUST 下此开关无意义，因为已全局免确认）。 */
    val autoSendWithoutConfirm: Boolean = false,
    val useVision: Boolean = false,
    val allowCuriosity: Boolean = false,
    val jsonMode: Boolean = false,
    val personaExtra: String = "",

    // ── QQ 自动回复 ──
    val autoReplyEnabled: Boolean = false,
    val autoReplyWhitelist: String = "",
    val autoReplyCooldownSec: Int = 60
) {

    fun normalized(): AppSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        modelId = modelId.trim(),
        temperature = temperature.coerceIn(0.0, 2.0),
        stepPolicy = stepPolicy.uppercase(),
        maxSteps = maxSteps.coerceIn(1, 500),
        maxRuntimeMinutes = maxRuntimeMinutes.coerceIn(0, 720),
        stallLimit = stallLimit.coerceIn(2, 50),
        stallWindowSeconds = stallWindowSeconds.coerceIn(5, 300),
        wsPort = wsPort.coerceIn(1024, 65535),
        logRetentionDays = logRetentionDays.coerceIn(1, 90),
        logMaxRows = logMaxRows.coerceIn(500, 50_000),
        autoReplyCooldownSec = autoReplyCooldownSec.coerceIn(10, 3600),
        autoReplyWhitelist = autoReplyWhitelist.trim(),
        personaExtra = personaExtra.trim()
    )

    /** 0 表示不限时。 */
    val maxRuntimeMs: Long
        get() = if (maxRuntimeMinutes <= 0) 0L else maxRuntimeMinutes.coerceIn(1, 720) * 60_000L

    /** 白名单解析：空列表表示不过滤。 */
    fun whitelist(): Set<String> = autoReplyWhitelist
        .split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isEndpointCleartext(): Boolean = baseUrl.startsWith("http://", ignoreCase = true)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
