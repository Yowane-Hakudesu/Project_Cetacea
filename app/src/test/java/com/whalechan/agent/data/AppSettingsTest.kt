package com.whalechan.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置的归一化与白名单解析（纯 Kotlin，不依赖 Android）。 */
class AppSettingsTest {

    @Test
    fun `normalizes out of range values`() {
        val s = AppSettings(maxSteps = 999, temperature = 5.0, maxRuntimeMinutes = -5)
            .normalized()
        assertEquals(500, s.maxSteps)
        assertEquals(2.0, s.temperature, 0.0001)
        assertEquals(0, s.maxRuntimeMinutes)
    }

    @Test
    fun `keeps sane values untouched`() {
        val s = AppSettings(maxSteps = 12, temperature = 0.2, maxRuntimeMinutes = 10).normalized()
        assertEquals(12, s.maxSteps)
        assertEquals(10, s.maxRuntimeMinutes)
        assertEquals(600_000L, s.maxRuntimeMs)
    }

    @Test
    fun `zero runtime minutes means unlimited window`() {
        val s = AppSettings(maxRuntimeMinutes = 0).normalized()
        assertEquals(0, s.maxRuntimeMinutes)
        assertEquals(0L, s.maxRuntimeMs)
    }

    @Test
    fun `step policy defaults to limited and accepts case insensitive values`() {
        assertEquals("LIMITED", AppSettings().stepPolicy)
        assertEquals("UNLIMITED", AppSettings(stepPolicy = "unlimited").normalized().stepPolicy)
    }

    @Test
    fun `whitelist accepts chinese comma space and newline`() {
        val s = AppSettings(autoReplyWhitelist = "张三，李四\n 王五 , 张三")
        assertEquals(setOf("张三", "李四", "王五"), s.whitelist())
    }

    @Test
    fun `blank whitelist means no filter`() {
        assertTrue(AppSettings(autoReplyWhitelist = "   ").whitelist().isEmpty())
    }

    @Test
    fun `detects cleartext endpoint`() {
        assertTrue(AppSettings(baseUrl = "http://192.168.1.5:11434/v1").isEndpointCleartext())
        assertFalse(AppSettings(baseUrl = "https://api.deepseek.com/v1").isEndpointCleartext())
    }

    @Test
    fun `trims base url and model id`() {
        val s = AppSettings(baseUrl = "  https://api.deepseek.com/v1/  ", modelId = " deepseek-chat ")
            .normalized()
        assertEquals("https://api.deepseek.com/v1", s.baseUrl)
        assertEquals("deepseek-chat", s.modelId)
    }
}
