package com.whalechan.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun `identical after punctuation is fully similar`() {
        assertTrue(TextSimilarity.similarity("都说了不打啦", "都说了不打啦！") > 0.95)
    }

    @Test
    fun `different sentences are not similar`() {
        assertTrue(TextSimilarity.similarity("都说了不打啦", "今天天气不错") < 0.2)
    }

    @Test
    fun `near duplicate is caught by threshold`() {
        val s = TextSimilarity.similarity("都说了不打啦，别催", "都说了不打啦别催了")
        assertTrue("近似复读应 >= 0.8，实际 $s", s >= 0.8)
    }

    @Test
    fun `max similarity checks recent history`() {
        val history = listOf("晚安", "都说了不打啦")
        assertEquals(1.0, TextSimilarity.maxSimilarity("晚安！", history), 0.01)
    }

    @Test
    fun `rate rules match product spec`() {
        val replyPerTarget = RateRules.ALL.first {
            it.type == SocialActionType.REPLY_MESSAGE && it.scope == RateRule.Scope.PER_TARGET
        }
        assertEquals(30_000L, replyPerTarget.windowMs)
        assertEquals(1, replyPerTarget.max)

        val replyGlobal = RateRules.ALL.first {
            it.type == SocialActionType.REPLY_MESSAGE && it.scope == RateRule.Scope.GLOBAL
        }
        assertEquals(3, replyGlobal.max)

        val feed = RateRules.ALL.first { it.type == SocialActionType.PUBLISH_FEED }
        assertEquals(2, feed.max)
        assertEquals(24 * 3600_000L, feed.windowMs)

        val comment = RateRules.ALL.first { it.type == SocialActionType.COMMENT }
        assertEquals(RateRule.Scope.LIFETIME_PER_TARGET, comment.scope)
        assertEquals(1, comment.max)

        val share = RateRules.ALL.first { it.type == SocialActionType.SHARE_VIDEO }
        assertEquals(10, share.max)
        assertEquals(30 * 60_000L, share.windowMs)
    }
}
