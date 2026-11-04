package com.whalechan.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whalechan.agent.core.AgentRuntime

/** 暂停通知上的“继续 / 放弃”，以及伴侣端/通知栏的紧急停止。 */
class TaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_CONTINUE -> AgentRuntime.engine.continuePaused()
            ACTION_ABORT -> AgentRuntime.engine.abortPaused()
            ACTION_STOP -> AgentRuntime.engine.cancel("通知栏停止")
            else -> return
        }
        PauseNotifier.cancel(context)
    }

    companion object {
        const val ACTION_CONTINUE = "com.whalechan.agent.action.TASK_CONTINUE"
        const val ACTION_ABORT = "com.whalechan.agent.action.TASK_ABORT"
        const val ACTION_STOP = "com.whalechan.agent.action.TASK_STOP"
        const val EXTRA_TASK_ID = "task_id"
    }
}
