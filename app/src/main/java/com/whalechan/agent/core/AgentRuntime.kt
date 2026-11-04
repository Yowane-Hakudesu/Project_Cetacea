package com.whalechan.agent.core

import android.content.Context
import com.whalechan.agent.data.SettingsRepository
import com.whalechan.agent.data.log.StepLogRepository

/**
 * 进程内单例装配点。
 * 只装配一次：Application.onCreate 里调用。之后任何组件（Service / Activity / 通知监听 / WebSocket）
 * 都通过 AgentRuntime.settings / memory / logs / engine 访问同一份状态。
 */
object AgentRuntime {

    lateinit var settings: SettingsRepository
        private set

    lateinit var memory: MemoryStore
        private set

    lateinit var logs: StepLogRepository
        private set

    lateinit var cache: ActionCache
        private set

    lateinit var engine: AgentEngine
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            settings = SettingsRepository(appContext)
            memory = MemoryStore(appContext)
            logs = StepLogRepository(appContext)
            cache = ActionCache(appContext)
            engine = AgentEngine(appContext, settings, memory, logs, cache)
            initialized = true
            WhaleLog.i("AgentRuntime 初始化完成")
        }
    }
}
