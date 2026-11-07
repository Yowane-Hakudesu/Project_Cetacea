package com.whalechan.agent.core

/**
 * 文本相似度：字符二元组 Jaccard。
 *
 * 比编辑距离更适合短中文句子：
 *  - “都说了不打啦” vs “都说了不打啦！” ≈ 1.0
 *  - “都说了不打啦” vs “今天天气不错” ≈ 0
 *  - 不依赖分词，纯本地计算，零额外依赖。
 */
object TextSimilarity {

    private const val PUNCTUATION = "，。！？、；：,.!?;:\"'“”‘’（）()《》<>【】[]…—~～ "

    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) {
            return if (x == y) 1.0 else 0.0
        }
        val gx = bigrams(x)
        val gy = bigrams(y)
        val intersection = gx.count { it in gy }
        val union = gx.size + gy.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union
    }

    /** 与一组历史文本的最大相似度。 */
    fun maxSimilarity(text: String, history: List<String>): Double =
        history.maxOfOrNull { similarity(text, it) } ?: 0.0

    private fun normalize(text: String): String =
        text.lowercase().filterNot { it.isWhitespace() || it in PUNCTUATION }

    private fun bigrams(text: String): Set<String> {
        val set = HashSet<String>(text.length)
        for (i in 0 until text.length - 1) set.add(text.substring(i, i + 2))
        return set
    }
}
