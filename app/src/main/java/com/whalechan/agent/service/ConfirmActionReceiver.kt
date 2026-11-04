package com.whalechan.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.whalechan.agent.core.ConfirmationGate

/** 确认通知上的两个按钮：只做一件事——把结果交给 ConfirmationGate。 */
class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val safeIntent = intent ?: return
        val id = safeIntent.getStringExtra(EXTRA_ID) ?: return
        when (safeIntent.action) {
            ACTION_APPROVE -> ConfirmationGate.deliver(id, true)
            ACTION_DENY -> ConfirmationGate.deliver(id, false)
            else -> return
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(id.hashCode() and 0x7fffffff)
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.whalechan.agent.action.CONFIRM_APPROVE"
        const val ACTION_DENY = "com.whalechan.agent.action.CONFIRM_DENY"
        const val EXTRA_ID = "confirm_id"
    }
}
