package com.whalechan.agent.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.whalechan.agent.R
import com.whalechan.agent.WhaleApp
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.core.AgentEvent
import com.whalechan.agent.core.AgentRuntime
import com.whalechan.agent.core.NotificationPermission
import com.whalechan.agent.core.Persona
import com.whalechan.agent.core.RunMode
import com.whalechan.agent.core.WhaleLog
import com.whalechan.agent.net.WsHub
import com.whalechan.agent.ui.ChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 常驻前台服务。
 *
 * 保活三件套：
 *  1. startForeground + 常驻通知（系统不会随手杀“有前台通知”的服务）；
 *  2. PowerManager.PARTIAL_WAKE_LOCK：只在有任务时持有（或用户开“激进保活”时一直持有），
 *     否则 8 小时待机会吃掉大量电；
 *  3. WifiManager.WifiLock：防止息屏后 Wi-Fi 降速/断开导致 LLM 请求超时。
 *
 * 版本差异见 README 的“前台服务与保活层”一节。
 */
class KeepAliveService : Service() {

    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        powerManager = getSystemService(PowerManager::class.java)
        acquireWifiLock()
        observeAgent()
        startHeartbeat()

        // 远程伴侣：服务常驻时同步拉起 WebSocket 服务端
        val settings = AgentRuntime.settings.load()
        if (settings.wsEnabled) {
            WsHub.start(this, settings.wsPort)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内完成 startForeground，否则系统抛 ANR/崩溃
        val initial = buildNotification(getString(R.string.notif_keepalive_idle))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, initial)
            }
        } catch (t: Throwable) {
            WhaleLog.e("startForeground 失败，服务退出", t)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_STOP_TASK -> {
                AgentRuntime.engine.cancel("你让我停下")
                updateNotification(getString(R.string.notif_keepalive_idle))
            }

            ACTION_RUN_TASK -> {
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                if (goal.isNotBlank()) launchTask(goal)
            }

            else -> Unit
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!AgentRuntime.settings.load().keepAliveEnabled) return
        // 用户从最近任务划掉时，用不定时闹钟把自己拉回来。OEM 可能直接拦截，这属于系统限制。
        runCatching {
            val alarmManager = getSystemService(AlarmManager::class.java)
            val restartIntent = Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000L, pendingIntent)
            WhaleLog.i("已安排 2 秒后重启常驻服务")
        }.onFailure { WhaleLog.w("安排重启失败", it) }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        releaseWifiLock()
        WsHub.stop()
        // 注意：这里**不能** cancel 任务。
        // 服务被系统回收（尤其是启动其他 App 时）会走到 onDestroy，
        // 以前在这里 cancel 会把正在进行的跨应用任务打断，表现为“第 2 步就停下”。
        // 用户主动停止走 ACTION_STOP / 对话页「停」按钮。
        WhaleLog.w("常驻服务被销毁；正在执行的任务不会被取消")
        super.onDestroy()
    }

    // ────────────────────────── 任务 ──────────────────────────

    private fun launchTask(goal: String) {
        if (AgentRuntime.engine.isRunning()) {
            WhaleLog.w("已有任务在跑，忽略新任务")
            return
        }
        acquireWakeLock()
        val started = AgentRuntime.engine.start(goal, RunMode.ACT)
        if (!started) {
            releaseWakeLock()
            WhaleLog.w("任务启动失败（可能刚结束上一个任务）")
        }
    }

    private fun observeAgent() {
        scope.launch {
            AgentRuntime.engine.state.collect { state ->
                if (state.running) {
                    acquireWakeLock()
                    val text = if (state.maxSteps <= 0) {
                        getString(
                            R.string.notif_running_unlimited,
                            state.goal.take(16),
                            state.step.coerceAtLeast(1)
                        )
                    } else {
                        getString(
                            R.string.notif_running,
                            state.goal.take(16),
                            state.step.coerceAtLeast(1),
                            state.maxSteps
                        )
                    }
                    updateNotification(
                        if (state.paused) getString(R.string.notif_paused, state.step) else text
                    )
                } else {
                    updateNotification(getString(R.string.notif_keepalive_idle))
                    if (!AgentRuntime.settings.load().aggressiveWakeLock) releaseWakeLock()
                }
            }
        }

        scope.launch {
            AgentRuntime.engine.events.collect { event ->
                if (event is AgentEvent.Notice) updateNotification(event.text.take(60))
            }
        }
    }

    // ────────────────────────── 锁 ──────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WhaleChan::AgentCpu"
        ).apply { setReferenceCounted(false) }

        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            .onSuccess {
                wakeLock = lock
                wakeLockSince = SystemClock.elapsedRealtime()
                WhaleLog.d("PARTIAL_WAKE_LOCK 已获取")
            }
            .onFailure { WhaleLog.w("PARTIAL_WAKE_LOCK 获取失败", it) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        wakeLockSince = 0L
        runCatching {
            if (lock.isHeld) lock.release()
            WhaleLog.d("PARTIAL_WAKE_LOCK 已释放")
        }.onFailure { WhaleLog.w("释放 WakeLock 失败", it) }
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            val lock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WhaleChan::Wifi"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            WhaleLog.d("WifiLock 已获取")
        }.onFailure { WhaleLog.w("WifiLock 获取失败（需要 ACCESS_WIFI_STATE，部分 ROM 会拒绝）", it) }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        wifiLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    /** 每 10 分钟续一次 WakeLock 超时（20 分钟），顺便决定要不要触发好奇模式。 */
    private fun startHeartbeat() {
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching {
                val settings = AgentRuntime.settings.load()
                if (settings.aggressiveWakeLock || AgentRuntime.engine.isRunning()) acquireWakeLock()
                maybeStartCuriosity(settings.allowCuriosity)
            }.onFailure { WhaleLog.w("心跳任务异常", it) }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun maybeStartCuriosity(enabled: Boolean) {
        if (!enabled) return
        if (AgentRuntime.engine.isRunning()) return
        if (!WhaleAccessibilityService.isReady()) return

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return
        if (!powerManager.isInteractive) return

        WhaleLog.i("好奇模式：自己看看手机")
        AgentRuntime.engine.start(Persona.randomCuriosityGoal(), RunMode.CURIOUS)
    }

    // ────────────────────────── 通知 ──────────────────────────

    @SuppressLint("MissingPermission") // 已用 NotificationPermission.canPost 做运行时检查
    private fun updateNotification(text: String) {
        if (!isRunning) return
        if (!NotificationPermission.canPost(this)) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        }.onFailure { WhaleLog.w("更新常驻通知失败", it) }
    }

    private fun buildNotification(text: String): Notification {
        val openChat = PendingIntent.getActivity(
            this,
            REQUEST_CHAT,
            Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopTask = PendingIntent.getService(
            this,
            REQUEST_STOP_TASK,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVICE,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WhaleApp.CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(getString(R.string.notif_keepalive_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openChat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_action_chat), openChat)
            .addAction(0, getString(R.string.notif_action_stop_task), stopTask)
            .addAction(0, getString(R.string.notif_action_stop), stopService)
            .build()
    }

    private fun stopEverything() {
        AgentRuntime.engine.cancel("你关掉了常驻服务")
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.whalechan.agent.action.START"
        const val ACTION_STOP = "com.whalechan.agent.action.STOP"
        const val ACTION_RUN_TASK = "com.whalechan.agent.action.RUN_TASK"
        const val ACTION_STOP_TASK = "com.whalechan.agent.action.STOP_TASK"
        const val EXTRA_GOAL = "extra_goal"

        const val NOTIF_ID = 1001
        private const val REQUEST_CHAT = 11
        private const val REQUEST_STOP_TASK = 12
        private const val REQUEST_STOP_SERVICE = 13
        private const val RESTART_REQUEST_CODE = 14

        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L
        private const val HEARTBEAT_MS = 10 * 60 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 本次 WakeLock 的起始时刻（SystemClock.elapsedRealtime），0 表示未持有。 */
        @Volatile
        private var wakeLockSince: Long = 0L

        /** 设置页/诊断页展示“当前 WakeLock 持有时间”。 */
        fun wakeLockHeldMillis(): Long =
            if (wakeLockSince == 0L) 0L else SystemClock.elapsedRealtime() - wakeLockSince

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
                if (isRunning) {
                    // 服务已在运行：直接 startService 不会触发 Android 12+ 的“后台启动前台服务”限制
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }.onFailure { WhaleLog.e("启动常驻服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        /**
         * 由对话页 / 自动回复 / 通知触发。
         *
         * 注意：Android 12+ 禁止后台应用启动前台服务，而 NotificationListenerService 的回调
         * 不在豁免名单里。所以“服务已在跑”时必须走 startService（应用持有前台服务即被视为前台），
         * 否则 isRunning=false 时只能从可见界面发起，否则会被系统拒绝。
         */
        fun startTask(context: Context, goal: String): Boolean = try {
            val intent = Intent(context, KeepAliveService::class.java)
                .setAction(ACTION_RUN_TASK)
                .putExtra(EXTRA_GOAL, goal)
            if (isRunning) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
            true
        } catch (t: Throwable) {
            WhaleLog.e("提交任务失败（Android 12+ 可能禁止后台启动前台服务）", t)
            false
        }
    }
}
