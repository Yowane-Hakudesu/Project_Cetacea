package com.whalechan.protocol

import android.util.Log

/** 共享模块里用的极简日志（不能依赖 app 模块的 WhaleLog）。 */
object Plog {
    private const val TAG = "WhaleProtocol"

    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }
}
