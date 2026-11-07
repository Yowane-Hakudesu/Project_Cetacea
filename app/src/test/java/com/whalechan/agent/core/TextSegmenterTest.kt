package com.whalechan.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun `splits long reply into short messages`() {
        val parts = TextSegmenter.split("都说了不打啦，你怎么又来了？快点睡觉，明天还要早起")
        assertTrue("应拆成多条", parts.size >= 3)
        assertTrue("每条不超过 15 字", parts.all { it.length <= TextSegmenter.MAX_CHARS })
        assertEquals("都说了不打啦", parts.first())
    }

    @Test
    fun `keeps short single sentence intact`() {
        assertEquals(listOf("晚安"), TextSegmenter.split("晚安"))
    }

    @Test
    fun `strips trailing punctuation and empty segments`() {
        val parts = TextSegmenter.split("好。\n\n，, ")
        assertEquals(listOf("好"), parts)
    }

    @Test
    fun `caps number of segments`() {
        val many = (1..20).joinToString("，") { "第${it}句" }
        assertTrue(TextSegmenter.split(many).size <= TextSegmenter.MAX_SEGMENTS)
    }
}
