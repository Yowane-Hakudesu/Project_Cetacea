package com.whalechan.agent.data

import android.content.Context
import android.util.Base64
import com.whalechan.agent.core.WhaleLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密 API Key。
 *
 * 关键性质：
 *  - 密钥由 TEE / StrongBox 托管，应用只能请求加解密，**无法导出明文密钥**；
 *  - 密文与 IV 一起以 Base64 存在 SharedPreferences；
 *  - 换机恢复、清除凭据后密钥会失效，解密失败时按“没有 Key”处理并提示重填。
 *
 * 局限（必须知道）：
 *  - 这只能防“别的应用 / 备份文件 / 误发日志读到明文”；
 *  - 设备已 root 且攻击者能在本应用进程内执行代码时，仍可调用同样的解密接口。
 */
object KeystoreHelper {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "whale_chan_llm_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v1"

    /** 加密并返回可直接持久化的字符串：v1:<base64(iv)>:<base64(ciphertext+tag)> */
    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return buildString {
            append(PREFIX).append(':')
            append(Base64.encodeToString(iv, Base64.NO_WRAP)).append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    /** 解密失败返回 null（调用方应视为“没有配置 Key”）。 */
    fun decrypt(payload: String): String? {
        return try {
            val parts = payload.split(':')
            if (parts.size != 3 || parts[0] != PREFIX) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            WhaleLog.w("Keystore 解密失败（密钥可能已失效，需要重新填写 API Key）", t)
            null
        }
    }

    /** 删除密钥，旧密文将永久无法解密。 */
    fun resetKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }.onFailure { WhaleLog.w("重置 Keystore 密钥失败", it) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // 不做生物识别绑定：Agent 需要无人值守地发请求，锁屏后也要能工作
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
