package com.whalechan.agent.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 三档步数策略的字符串映射（设置页存的是枚举名）。 */
class StepPolicyTest {

    @Test
    fun `parses known policies case insensitively`() {
        assertEquals(StepPolicy.LIMITED, StepPolicy.from("LIMITED"))
        assertEquals(StepPolicy.TIME_WINDOW, StepPolicy.from("time_window"))
        assertEquals(StepPolicy.UNLIMITED, StepPolicy.from("Unlimited"))
    }

    @Test
    fun `unknown or null falls back to limited`() {
        assertEquals(StepPolicy.LIMITED, StepPolicy.from(null))
        assertEquals(StepPolicy.LIMITED, StepPolicy.from(""))
        assertEquals(StepPolicy.LIMITED, StepPolicy.from("whatever"))
    }
}
