package com.whalechan.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** 附件读写：图片压缩、文件读取、名字/大小解析。 */
object AttachmentCodec {

    fun displayName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() } ?: fallback
                } else {
                    fallback
                }
            } ?: fallback
        }.getOrDefault(fallback)
    }

    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * 图片：先按 1600px 采样解码，再按质量阶梯压到 limit 以下。
     * 失败返回 null（调用方提示“图片太大/无法读取”）。
     */
    fun compressImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1600,
        limit: Int = 4 * 1024 * 1024
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        var quality = 88
        var bytes: ByteArray
        val output = ByteArrayOutputStream()
        do {
            output.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 12
        } while (bytes.size > limit && quality >= 40)
        bitmap.recycle()

        return if (bytes.size <= limit) bytes else null
    }

    /** 文件：最多读 limit 字节，超过直接返回 null。 */
    fun readFile(context: Context, uri: Uri, limit: Int = 4 * 1024 * 1024): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
