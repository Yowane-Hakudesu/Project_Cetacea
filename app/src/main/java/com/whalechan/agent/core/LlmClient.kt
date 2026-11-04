package com.whalechan.agent.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val temperature: Double = 0.2,
    val jsonMode: Boolean = false
)

data class ChatMessage(val role: String, val content: String)

/** fatal=true 表示重试没有意义（Key 错、模型名错、请求格式被拒）。 */
class LlmException(message: String, val fatal: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 一个最小可用的 OpenAI 兼容客户端。
 * 兼容面：/chat/completions、Bearer 鉴权、data:image/jpeg;base64 视觉输入。
 * 不假设端点一定以 /v1 结尾：先试 `${base}/chat/completions`，404 再试 `${base}/v1/chat/completions`。
 */
class LlmClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(
        cfg: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 700,
        imageBase64Jpeg: String? = null
    ): String {
        val messages = JSONArray().apply {
            put(message("system", systemPrompt))
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", buildContent(userPrompt, imageBase64Jpeg))
                }
            )
        }
        return request(cfg, messages, maxTokens)
    }

    suspend fun chat(cfg: LlmConfig, messages: List<ChatMessage>, maxTokens: Int = 800): String {
        val array = JSONArray().apply {
            messages.forEach { put(message(it.role, it.content)) }
        }
        return request(cfg, array, maxTokens)
    }

    /** 设置页“测试连接”：真的发一次最小请求，验证鉴权、模型名、端点路径都通。 */
    suspend fun testConnection(cfg: LlmConfig): String {
        val started = SystemClock.elapsedRealtime()
        val reply = complete(
            cfg = cfg.copy(temperature = 0.0),
            systemPrompt = "你是连通性测试助手，只按用户要求回两个字。",
            userPrompt = "只回复两个字：在线",
            maxTokens = 16
        )
        val cost = SystemClock.elapsedRealtime() - started
        return "连接成功 · ${cost}ms · 模型回复「${reply.trim().take(30)}」"
    }

    private suspend fun request(
        cfg: LlmConfig,
        messages: JSONArray,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        if (cfg.baseUrl.isBlank()) throw LlmException("Base URL 为空", fatal = true)
        if (cfg.model.isBlank()) throw LlmException("Model ID 为空", fatal = true)

        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", cfg.temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (cfg.jsonMode) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }.toString()

        var lastFailure: LlmException? = null

        for (url in endpointCandidates(cfg.baseUrl)) {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMedia))
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "WhaleChan/0.1 (Android)")
                .apply {
                    if (cfg.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${cfg.apiKey}")
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 404) {
                        // 端点路径可能就是不带 /v1 或带 /v1，换下一个候选
                        lastFailure = LlmException("404 找不到接口：$url")
                        return@use
                    }
                    if (!response.isSuccessful) throw httpError(response.code, body)
                    return@withContext parseContent(body)
                }
            } catch (e: LlmException) {
                throw e
            } catch (e: IOException) {
                lastFailure = LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", cause = e)
            }
        }
        throw lastFailure ?: LlmException("请求失败：没有可用的端点")
    }

    private fun parseContent(raw: String): String {
        val root = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw LlmException("响应不是 JSON：${raw.take(200)}")
        }

        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            val message = first?.optJSONObject("message")
            val content: Any? = message?.opt("content") ?: first?.opt("text")
            val text: String? = when (content) {
                is String -> content
                is JSONArray -> {
                    val builder = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") builder.append(part.optString("text"))
                    }
                    builder.toString()
                }
                null -> null
                else -> content.toString()
            }
            if (!text.isNullOrBlank()) return text
        }

        // 少数兼容端点直接返回 content / output_text
        val direct = root.optString("output_text", "").ifBlank { root.optString("content", "") }
        if (direct.isNotBlank()) return direct

        val errorMessage = root.optJSONObject("error")?.optString("message")
        if (!errorMessage.isNullOrBlank()) throw LlmException("端点报错：$errorMessage")

        throw LlmException("模型返回了空内容：${raw.take(200)}")
    }

    private fun httpError(code: Int, body: String): LlmException {
        val message = when (code) {
            400 -> "400 请求被拒绝（模型可能不支持该参数）"
            401 -> "401 未授权：API Key 不正确或已失效"
            403 -> "403 被拒绝：Key 没有该模型权限，或端点要求额外鉴权"
            404 -> "404 找不到接口"
            408 -> "408 端点超时"
            413 -> "413 请求过大：界面描述太长，试试减小最大步数"
            429 -> "429 触发限流：稍后再试"
            in 500..599 -> "$code 端点服务端错误"
            else -> "$code 请求失败"
        }
        val fatal = code == 400 || code == 401 || code == 403
        return LlmException("$message｜${body.take(240).replace("\n", " ")}", fatal = fatal)
    }

    private fun message(role: String, content: String): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
    }

    private fun buildContent(text: String, imageBase64Jpeg: String?): Any {
        if (imageBase64Jpeg.isNullOrBlank()) return text
        return JSONArray().apply {
            put(JSONObject().apply { put("type", "text"); put("text", text) })
            put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply { put("url", "data:image/jpeg;base64,$imageBase64Jpeg") }
                    )
                }
            )
        }
    }

    companion object {
        /**
         * 端点候选列表：
         *  - `https://api.openai.com/v1`      → .../v1/chat/completions
         *  - `https://api.deepseek.com`       → .../chat/completions（DeepSeek 两种都支持）
         *  - `http://192.168.1.5:11434`       → 先 404，再 .../v1/chat/completions（Ollama）
         */
        fun endpointCandidates(baseUrl: String): List<String> {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return emptyList()
            if (base.endsWith("/chat/completions")) return listOf(base)

            val list = mutableListOf("$base/chat/completions")
            if (!base.endsWith("/v1")) list.add("$base/v1/chat/completions")
            return list.distinct()
        }
    }
}
