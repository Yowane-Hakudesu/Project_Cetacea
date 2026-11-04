package com.whalechan.agent.core

import java.io.File

/**
 * 伴侣端发来的最新附件（图片/文件）。
 *
 * 只保留最新一份，避免无界内存；图片的 base64 在第一次被视觉模型消费后清掉，
 * 但文件路径与说明会一直保留到下一份附件到来，方便 Agent 在后续步骤里引用。
 */
object AttachmentBus {

    data class Item(
        val kind: String,
        val name: String,
        val mime: String,
        val file: File,
        val imageBase64: String?,
        val at: Long
    )

    @Volatile
    private var latest: Item? = null

    fun offer(kind: String, name: String, mime: String, file: File, imageBase64: String?) {
        latest = Item(kind, name, mime, file, imageBase64, System.currentTimeMillis())
    }

    fun current(): Item? = latest

    /** 给 prompt 的一行说明；没有附件时返回空串。 */
    fun note(): String {
        val item = latest ?: return ""
        return "类型=${item.kind}，名称=${item.name}，MIME=${item.mime}，本地路径=${item.file.absolutePath}"
    }

    /**
     * 取出图片 base64 供视觉模型使用，并清掉它（同一张图只发送一次，避免每轮重复计费）。
     */
    fun takeImageForModel(): String? {
        val item = latest ?: return null
        val base64 = item.imageBase64
        if (base64.isNullOrBlank()) return null
        latest = item.copy(imageBase64 = null)
        return base64
    }

    fun clear() {
        latest = null
    }
}
