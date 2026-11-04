package com.whalechan.agent.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 极简日志：Logcat + 200 条环形缓冲。
 * 环形缓冲用于在“设置页 / 对话页”里看最近发生了什么，不需要连电脑。
 * 注意：不要把 API Key、通知正文写进日志。
 */
object WhaleLog {

    private const val TAG = "WhaleChan"
    private const val CAPACITY = 200

    private val ring = ArrayDeque<String>(CAPACITY)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun d(message: String) = append("D", message)
    fun i(message: String) = append("I", message)

    fun w(message: String, throwable: Throwable? = null) =
        append("W", message + suffix(throwable))

    fun e(message: String, throwable: Throwable? = null) =
        append("E", message + suffix(throwable))

    private fun suffix(t: Throwable?): String =
        if (t == null) "" else " | ${t.javaClass.simpleName}: ${t.message}"

    private fun append(level: String, message: String) {
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            "I" -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
        val line = "${formatter.format(Date())} $level $message"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    fun tail(count: Int = 60): List<String> = synchronized(ring) { ring.toList().takeLast(count) }
}
