package com.whalechan.agent.data

import android.content.Context
import com.whalechan.agent.core.WhaleLog

/**
 * 设置持久化：普通字段用 SharedPreferences，API Key 用 Keystore 加密后再存。
 * 读取线程安全：SharedPreferences 本身线程安全，这里只做一次字段映射。
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            baseUrl = prefs.getString(KEY_BASE_URL, d.baseUrl) ?: d.baseUrl,
            modelId = prefs.getString(KEY_MODEL, d.modelId) ?: d.modelId,
            temperature = prefs.getFloat(KEY_TEMPERATURE, d.temperature.toFloat()).toDouble(),

            stepPolicy = prefs.getString(KEY_STEP_POLICY, d.stepPolicy) ?: d.stepPolicy,
            maxSteps = prefs.getInt(KEY_MAX_STEPS, d.maxSteps),
            maxRuntimeMinutes = prefs.getInt(KEY_MAX_RUNTIME, d.maxRuntimeMinutes),
            unlimitedConsent = prefs.getBoolean(KEY_UNLIMITED_CONSENT, d.unlimitedConsent),
            stallLimit = prefs.getInt(KEY_STALL_LIMIT, d.stallLimit),
            stallWindowSeconds = prefs.getInt(KEY_STALL_WINDOW, d.stallWindowSeconds),

            fullTrustMode = prefs.getBoolean(KEY_FULL_TRUST, d.fullTrustMode),
            allowCredentialEntry = prefs.getBoolean(KEY_ALLOW_CREDENTIAL, d.allowCredentialEntry),
            cacheReplayEnabled = prefs.getBoolean(KEY_CACHE_REPLAY, d.cacheReplayEnabled),
            leaveSocialAppOnFinish = prefs.getBoolean(KEY_LEAVE_SOCIAL, d.leaveSocialAppOnFinish),

            wsEnabled = prefs.getBoolean(KEY_WS_ENABLED, d.wsEnabled),
            wsPort = prefs.getInt(KEY_WS_PORT, d.wsPort),
            relayEnabled = prefs.getBoolean(KEY_RELAY_ENABLED, d.relayEnabled),
            relayUrl = prefs.getString(KEY_RELAY_URL, d.relayUrl) ?: d.relayUrl,
            relayToken = prefs.getString(KEY_RELAY_TOKEN, d.relayToken) ?: d.relayToken,
            pairCode = prefs.getString(KEY_PAIR_CODE, null)?.takeIf { it.isNotBlank() }
                ?: newPairCode().also { prefs.edit().putString(KEY_PAIR_CODE, it).apply() },

            logRetentionDays = prefs.getInt(KEY_LOG_DAYS, d.logRetentionDays),
            logMaxRows = prefs.getInt(KEY_LOG_ROWS, d.logMaxRows),

            keepAliveEnabled = prefs.getBoolean(KEY_KEEP_ALIVE, d.keepAliveEnabled),
            aggressiveWakeLock = prefs.getBoolean(KEY_AGGRESSIVE_WAKE, d.aggressiveWakeLock),
            autoSendWithoutConfirm = prefs.getBoolean(KEY_AUTO_SEND, d.autoSendWithoutConfirm),
            useVision = prefs.getBoolean(KEY_VISION, d.useVision),
            allowCuriosity = prefs.getBoolean(KEY_CURIOSITY, d.allowCuriosity),
            jsonMode = prefs.getBoolean(KEY_JSON_MODE, d.jsonMode),
            personaExtra = prefs.getString(KEY_PERSONA, d.personaExtra) ?: d.personaExtra,
            autoReplyEnabled = prefs.getBoolean(KEY_AUTO_REPLY, d.autoReplyEnabled),
            autoReplyWhitelist = prefs.getString(KEY_AUTO_REPLY_WHITELIST, d.autoReplyWhitelist)
                ?: d.autoReplyWhitelist,
            autoReplyCooldownSec = prefs.getInt(KEY_AUTO_REPLY_COOLDOWN, d.autoReplyCooldownSec)
        ).normalized()
    }

    fun save(settings: AppSettings) {
        val s = settings.normalized()
        prefs.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_MODEL, s.modelId)
            .putFloat(KEY_TEMPERATURE, s.temperature.toFloat())
            .putString(KEY_STEP_POLICY, s.stepPolicy)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putInt(KEY_MAX_RUNTIME, s.maxRuntimeMinutes)
            .putBoolean(KEY_UNLIMITED_CONSENT, s.unlimitedConsent)
            .putInt(KEY_STALL_LIMIT, s.stallLimit)
            .putInt(KEY_STALL_WINDOW, s.stallWindowSeconds)
            .putBoolean(KEY_FULL_TRUST, s.fullTrustMode)
            .putBoolean(KEY_ALLOW_CREDENTIAL, s.allowCredentialEntry)
            .putBoolean(KEY_CACHE_REPLAY, s.cacheReplayEnabled)
            .putBoolean(KEY_LEAVE_SOCIAL, s.leaveSocialAppOnFinish)
            .putBoolean(KEY_WS_ENABLED, s.wsEnabled)
            .putInt(KEY_WS_PORT, s.wsPort)
            .putBoolean(KEY_RELAY_ENABLED, s.relayEnabled)
            .putString(KEY_RELAY_URL, s.relayUrl)
            .putString(KEY_RELAY_TOKEN, s.relayToken)
            .putString(KEY_PAIR_CODE, s.pairCode)
            .putInt(KEY_LOG_DAYS, s.logRetentionDays)
            .putInt(KEY_LOG_ROWS, s.logMaxRows)
            .putBoolean(KEY_KEEP_ALIVE, s.keepAliveEnabled)
            .putBoolean(KEY_AGGRESSIVE_WAKE, s.aggressiveWakeLock)
            .putBoolean(KEY_AUTO_SEND, s.autoSendWithoutConfirm)
            .putBoolean(KEY_VISION, s.useVision)
            .putBoolean(KEY_CURIOSITY, s.allowCuriosity)
            .putBoolean(KEY_JSON_MODE, s.jsonMode)
            .putString(KEY_PERSONA, s.personaExtra)
            .putBoolean(KEY_AUTO_REPLY, s.autoReplyEnabled)
            .putString(KEY_AUTO_REPLY_WHITELIST, s.autoReplyWhitelist)
            .putInt(KEY_AUTO_REPLY_COOLDOWN, s.autoReplyCooldownSec)
            .apply()
    }

    /** 返回解密后的 Key；没有配置或解密失败时返回 null。 */
    fun apiKey(): String? {
        val encrypted = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val plain = KeystoreHelper.decrypt(encrypted)
        if (plain.isNullOrBlank()) {
            WhaleLog.w("已保存的 API Key 无法解密，已清除，请重新填写")
            prefs.edit().remove(KEY_API_KEY).apply()
            return null
        }
        return plain
    }

    fun saveApiKey(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val encrypted = KeystoreHelper.encrypt(trimmed)
        prefs.edit().putString(KEY_API_KEY, encrypted).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(): Boolean = !prefs.getString(KEY_API_KEY, null).isNullOrBlank()

    /** 伴侣端配对 token：首次需要时生成并持久化。 */
    fun pairingToken(): String {
        val existing = prefs.getString(KEY_PAIRING_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val token = (1..12).map { "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".random() }.joinToString("")
        prefs.edit().putString(KEY_PAIRING_TOKEN, token).apply()
        return token
    }

    fun regeneratePairingToken(): String {
        val token = (1..12).map { "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".random() }.joinToString("")
        prefs.edit().putString(KEY_PAIRING_TOKEN, token).apply()
        return token
    }

    /** 中继房间号：手机端生成，伴侣端输入同一个即可配对。 */
    fun newPairCode(): String =
        (1..6).map { "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".random() }.joinToString("")

    fun regeneratePairCode(): String {
        val code = newPairCode()
        prefs.edit().putString(KEY_PAIR_CODE, code).apply()
        return code
    }

    companion object {
        private const val PREF_NAME = "whale_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model_id"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_STEP_POLICY = "step_policy"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_MAX_RUNTIME = "max_runtime_minutes"
        private const val KEY_UNLIMITED_CONSENT = "unlimited_consent"
        private const val KEY_STALL_LIMIT = "stall_limit"
        private const val KEY_STALL_WINDOW = "stall_window_seconds"
        private const val KEY_FULL_TRUST = "full_trust_mode"
        private const val KEY_ALLOW_CREDENTIAL = "allow_credential_entry"
        private const val KEY_CACHE_REPLAY = "cache_replay_enabled"
        private const val KEY_LEAVE_SOCIAL = "leave_social_app_on_finish"
        private const val KEY_WS_ENABLED = "ws_enabled"
        private const val KEY_WS_PORT = "ws_port"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_TOKEN = "relay_token"
        private const val KEY_PAIR_CODE = "pair_code"
        private const val KEY_LOG_DAYS = "log_retention_days"
        private const val KEY_LOG_ROWS = "log_max_rows"
        private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val KEY_AGGRESSIVE_WAKE = "aggressive_wake_lock"
        private const val KEY_AUTO_SEND = "auto_send_without_confirm"
        private const val KEY_VISION = "use_vision"
        private const val KEY_CURIOSITY = "allow_curiosity"
        private const val KEY_JSON_MODE = "json_mode"
        private const val KEY_PERSONA = "persona_extra"
        private const val KEY_AUTO_REPLY = "auto_reply_enabled"
        private const val KEY_AUTO_REPLY_WHITELIST = "auto_reply_whitelist"
        private const val KEY_AUTO_REPLY_COOLDOWN = "auto_reply_cooldown"
        private const val KEY_API_KEY = "api_key_enc"
        private const val KEY_PAIRING_TOKEN = "pairing_token"
    }
}
