package com.whalechan.agent.notify

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.whalechan.agent.access.AppsLauncher
import com.whalechan.agent.core.WhaleLog

/**
 * 通知监听服务。
 *
 * 权限事实：一旦用户在系统里勾选本应用，这个服务能收到**设备上所有应用**的通知。
 * 代码层面只处理 QQ 包名，但权限覆盖面是全局的——这也是必须做隐私声明的原因。
 *
 * 它不做的事：
 *  - 不读取 QQ 聊天记录（没有这个能力）；
 *  - 不调用 QQ 内部接口（没有公开接口）；
 *  - 不把任何通知内容写进磁盘。
 */
class QqNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        NotificationBus.listenerConnected = true
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationBus.listenerConnected = true
        WhaleLog.i("通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationBus.listenerConnected = false
        WhaleLog.w("通知监听断开，尝试重新绑定")
        runCatching { requestRebind(ComponentName(this, QqNotificationListener::class.java)) }
            .onFailure { WhaleLog.w("requestRebind 失败", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val posted = sbn ?: return
        if (!AppsLauncher.isQqPackage(posted.packageName)) return

        val message = QqNotificationParser.parse(posted) ?: return

        // 刚用 UI 发出去的消息，QQ 会立刻回推一条同样的通知；这里抑制掉，避免自问自答
        if (ReplyLedger.isEcho(message.text)) {
            WhaleLog.d("忽略自己刚发出的消息回显")
            return
        }

        NotificationBus.publish(message)
        AutoReplyCoordinator.onMessage(applicationContext, message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        // 不需要处理移除事件；保留空实现是为了语义完整
    }
}
