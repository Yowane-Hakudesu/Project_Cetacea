package com.whalechan.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点候选顺序决定了“用户填的 Base URL 到底会不会用对”。
 * 三种常见写法都要覆盖：OpenAI 风格（带 /v1）、DeepSeek 风格（不带 /v1）、Ollama（只给 host:port）。
 */
class LlmClientEndpointTest {

    @Test
    fun `base ending with v1 gets a single candidate`() {
        assertEquals(
            listOf("https://api.openai.com/v1/chat/completions"),
            LlmClient.endpointCandidates("https://api.openai.com/v1")
        )
    }

    @Test
    fun `deepseek style base tries plain path first then v1`() {
        val candidates = LlmClient.endpointCandidates("https://api.deepseek.com")
        assertEquals("https://api.deepseek.com/chat/completions", candidates.first())
        assertTrue(candidates.contains("https://api.deepseek.com/v1/chat/completions"))
    }

    @Test
    fun `ollama style base falls back to v1`() {
        val candidates = LlmClient.endpointCandidates("http://192.168.1.5:11434")
        assertEquals(2, candidates.size)
        assertEquals("http://192.168.1.5:11434/chat/completions", candidates[0])
        assertEquals("http://192.168.1.5:11434/v1/chat/completions", candidates[1])
    }

    @Test
    fun `full endpoint is used as is`() {
        assertEquals(
            listOf("https://example.com/v1/chat/completions"),
            LlmClient.endpointCandidates("https://example.com/v1/chat/completions")
        )
    }

    @Test
    fun `trailing slash is trimmed`() {
        assertEquals(
            listOf("https://api.openai.com/v1/chat/completions"),
            LlmClient.endpointCandidates("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `blank base yields no candidates`() {
        assertTrue(LlmClient.endpointCandidates("   ").isEmpty())
    }
}
