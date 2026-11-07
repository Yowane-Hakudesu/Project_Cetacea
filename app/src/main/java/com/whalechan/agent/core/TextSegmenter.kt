package com.whalechan.agent.core

/**
 * 把 LLM 的一条回复拆成多条短消息，模拟真人“一句一句发”。
 *
 * 规则：
 *  - 先按换行/逗号/句号/问号/感叹号切句；
 *  - 每段最多 15 个字，超长硬切；
 *  - 最多 6 段，防止模型输出小作文刷屏。
 */
object TextSegmenter {

    const val MAX_CHARS = 15
    const val MAX_SEGMENTS = 6

    fun split(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isEmpty()) return emptyList()

        val sentences = cleaned
            .split(Regex("[\\n，,。.!！?？;；]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            if (sentence.length <= MAX_CHARS) {
                result += sentence
            } else {
                result += hardWrap(sentence)
            }
            if (result.size >= MAX_SEGMENTS) break
        }
        return result.take(MAX_SEGMENTS).filter { it.isNotBlank() }
    }

    private fun hardWrap(text: String): List<String> {
        val chunks = ArrayList<String>(text.length / MAX_CHARS + 1)
        var start = 0
        while (start < text.length) {
            val end = (start + MAX_CHARS).coerceAtMost(text.length)
            chunks += text.substring(start, end).trim()
            start = end
        }
        return chunks
    }
}
