package com.whalechan.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作解析是“模型输出”与“真正执行”之间唯一的收口，解析错了会点错地方。
 * 这里覆盖：正常 JSON、markdown 围栏、前后废话、嵌套 params、别名、垃圾输入。
 */
class DecisionParserTest {

    @Test
    fun `parses fenced tap by ref`() {
        val d = DecisionParser.parse(
            """
            ```json
            {"action":"tap","target":"#3","reason":"点推荐页"}
            ```
            """.trimIndent()
        )
        assertEquals(ActionType.TAP, d.type)
        assertEquals("#3", d.target)
        assertEquals("点推荐页", d.reason)
    }

    @Test
    fun `treats bare numeric target as ref and text target as label`() {
        val numeric = DecisionParser.parse("""{"action":"tap","target":"12"}""")
        assertEquals("#12", numeric.target)

        val byText = DecisionParser.parse("""{"action":"tap","target":"推荐"}""")
        assertNull(byText.target)
        assertEquals("推荐", byText.text)
    }

    @Test
    fun `parses tap_xy`() {
        val d = DecisionParser.parse("""{"action":"tap_xy","x":540,"y":2200}""")
        assertEquals(ActionType.TAP_XY, d.type)
        assertEquals(540, d.x)
        assertEquals(2200, d.y)
    }

    @Test
    fun `parses swipe and its distance`() {
        val d = DecisionParser.parse("""{"action":"swipe","direction":"down","distance":0.35}""")
        assertEquals(ActionType.SWIPE, d.type)
        assertEquals("down", d.direction)
        assertEquals(0.35f, d.distance, 0.001f)
    }

    @Test
    fun `supports scroll alias`() {
        val d = DecisionParser.parse("""{"action":"scroll_down"}""")
        assertEquals(ActionType.SWIPE, d.type)
        assertEquals("down", d.direction)
    }

    @Test
    fun `clamps swipe distance into safe range`() {
        val tooFar = DecisionParser.parse("""{"action":"swipe","direction":"up","distance":9}""")
        assertEquals(0.9f, tooFar.distance, 0.001f)
        val tooShort = DecisionParser.parse("""{"action":"swipe","direction":"up","distance":0.01}""")
        assertEquals(0.2f, tooShort.distance, 0.001f)
    }

    @Test
    fun `parses input with nested params`() {
        val d = DecisionParser.parse("""{"action":"input","params":{"target":"#9","text":"你好"}}""")
        assertEquals(ActionType.INPUT, d.type)
        assertEquals("#9", d.target)
        assertEquals("你好", d.text)
    }

    @Test
    fun `parses open_app`() {
        val d = DecisionParser.parse("""{"action":"open_app","package":"com.tencent.mobileqq"}""")
        assertEquals(ActionType.OPEN_APP, d.type)
        assertEquals("com.tencent.mobileqq", d.packageName)
    }

    @Test
    fun `parses reply_qq`() {
        val d = DecisionParser.parse(
            """{"action":"reply_qq","conversation":"张三","text":"在的，稍等"}"""
        )
        assertEquals(ActionType.REPLY_QQ, d.type)
        assertEquals("张三", d.conversation)
        assertEquals("在的，稍等", d.text)
    }

    @Test
    fun `parses done fail and ask_user`() {
        val done = DecisionParser.parse("""{"action":"done","summary":"已发送"}""")
        assertEquals(ActionType.DONE, done.type)
        assertEquals("已发送", done.summary)

        val fail = DecisionParser.parse("""{"action":"fail","reason":"找不到发送按钮"}""")
        assertEquals(ActionType.FAIL, fail.type)
        assertEquals("找不到发送按钮", fail.summary)

        val ask = DecisionParser.parse("""{"action":"ask_user","question":"回给谁？"}""")
        assertEquals(ActionType.ASK_USER, ask.type)
        assertEquals("回给谁？", ask.question)
    }

    @Test
    fun `tolerates prose around json`() {
        val d = DecisionParser.parse(
            "好的，我看一眼屏幕：\n{\"action\":\"back\"}\n先返回再说。"
        )
        assertEquals(ActionType.BACK, d.type)
    }

    @Test
    fun `garbage becomes fail instead of throwing`() {
        val d = DecisionParser.parse("我觉得应该点一下推荐")
        assertEquals(ActionType.FAIL, d.type)
        assertTrue(d.summary.orEmpty().contains("没有输出 JSON"))
    }

    @Test
    fun `unknown action becomes unknown`() {
        val d = DecisionParser.parse("""{"action":"teleport","x":1}""")
        assertEquals(ActionType.UNKNOWN, d.type)
    }

    @Test
    fun `parseRef only accepts refs and numbers`() {
        assertEquals(12, AgentDecision.parseRef("#12"))
        assertEquals(12, AgentDecision.parseRef("12"))
        assertNull(AgentDecision.parseRef("推荐"))
        assertNull(AgentDecision.parseRef(null))
    }

    @Test
    fun `oneLine is human readable for confirm dialog`() {
        val d = DecisionParser.parse("""{"action":"reply_qq","conversation":"张三","text":"好的"}""")
        assertTrue(d.oneLine().contains("张三"))
        assertTrue(d.oneLine().contains("好的"))
    }
}
