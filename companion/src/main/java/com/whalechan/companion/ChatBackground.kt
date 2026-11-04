package com.whalechan.companion

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 聊天背景：把用户选的图片复制进应用私有目录，避免相册权限/URI 失效。
 * 只保留一张，换图时删旧图。
 */
class ChatBackground(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): File? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    fun setFrom(uri: Uri): File? {
        val dir = File(context.filesDir, "background").apply { mkdirs() }
        val target = File(dir, "chat_bg_${System.currentTimeMillis()}.img")
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
            if (target.length() <= 0L) return null
            current()?.delete()
            prefs.edit().putString(KEY_PATH, target.absolutePath).apply()
            target
        }.onFailure { target.delete() }.getOrNull()
    }

    fun clear() {
        current()?.delete()
        prefs.edit().remove(KEY_PATH).apply()
    }

    companion object {
        private const val PREFS = "companion_ui"
        private const val KEY_PATH = "chat_bg_path"
    }
}
