package com.whalechan.agent.access

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.whalechan.agent.R
import com.whalechan.agent.WhaleApp
import com.whalechan.agent.core.NotificationPermission
import com.whalechan.agent.core.WhaleLog

/**
 * 应用启动器。
 *
 * 为什么单独成类：Android 10+ 后台启动 Activity 受限、Android 11+ 包可见性受限，
 * 这两件事都必须在这里集中处理，否则 open_app 会“看起来成功、实际什么都没发生”。
 *
 * 现实策略：
 *  1. 有悬浮窗权限 → 系统豁免后台启动，直接 startActivity；
 *  2. 本应用正有可见 Activity → 直接 startActivity；
 *  3. 都不满足 → 返回 NeedUserGesture，由调用方发一条可点击的通知，让用户亲手打开。
 * 绝不使用“假装成系统弹窗”之类的绕过手段。
 */
object AppsLauncher {

    sealed interface Result {
        data object Ok : Result
        data class NeedUserGesture(val packageName: String, val label: String) : Result
        data class Failed(val message: String) : Result
    }

    /** 常见别名 → 包名，避免依赖界面文字猜测。 */
    private val KNOWN_APPS = mapOf(
        "qq" to "com.tencent.mobileqq",
        "腾讯qq" to "com.tencent.mobileqq",
        "tim" to "com.tencent.tim",
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "抖音" to "com.ss.android.ugc.aweme",
        "douyin" to "com.ss.android.ugc.aweme",
        "tiktok" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "kuaishou" to "com.smile.gifmaker",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs",
        "微博" to "com.sina.weibo",
        "weibo" to "com.sina.weibo"
    )

    fun resolvePackage(context: Context, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // 已经是完整包名
        if (value.contains('.')) {
            return if (isInstalled(context, value)) value else null
        }

        KNOWN_APPS[value.lowercase()]?.let { pkg ->
            if (isInstalled(context, pkg)) return pkg
        }

        // 按应用显示名匹配桌面图标
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
        val hit = activities.firstOrNull { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            label.isNotEmpty() && (label.contains(value, ignoreCase = true) || value.contains(label, ignoreCase = true))
        }
        return hit?.activityInfo?.packageName
    }

    fun isInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess ||
            runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() != null
    }

    fun appLabel(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    /**
     * 是否具备“从后台启动 Activity”的能力。
     * SYSTEM_ALERT_WINDOW 是 Android 10+ 官方豁免条件；可见 Activity 本身就是前台。
     */
    fun canStartActivity(context: Context): Boolean =
        Settings.canDrawOverlays(context) || isSelfInForeground(context)

    private fun isSelfInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val pid = Process.myPid()
        val processes = runCatching { activityManager.runningAppProcesses }.getOrNull() ?: return false
        return processes.any { it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    fun launch(context: Context, packageName: String): Result {
        if (!isInstalled(context, packageName)) {
            return Result.Failed("找不到应用 $packageName（未安装，或 Android 11+ 包可见性未声明）")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.Failed("应用 $packageName 没有可启动的入口")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        if (!canStartActivity(context)) {
            val label = appLabel(context, packageName)
            WhaleLog.i("后台启动受限，改为通知用户手动打开 $label")
            return Result.NeedUserGesture(packageName, label)
        }

        return try {
            context.startActivity(launchIntent)
            Result.Ok
        } catch (t: Throwable) {
            WhaleLog.w("startActivity 失败：$packageName", t)
            Result.Failed("启动失败：${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    /** 后台启动被拒时的兜底：发一条可点击的通知，用户点了就等于给了前台手势。 */
    @SuppressLint("MissingPermission") // 已用 NotificationPermission.canPost 做运行时检查
    fun notifyToOpen(context: Context, packageName: String) {
        if (!NotificationPermission.canPost(context)) {
            WhaleLog.w("没有通知权限，无法发“请手动打开应用”的提示")
            return
        }
        val label = appLabel(context, packageName)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = context.getString(R.string.launch_needed_text, label)
        val notification = NotificationCompat.Builder(context, WhaleApp.CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(context.getString(R.string.launch_needed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(packageName.hashCode(), notification)
        }.onFailure { WhaleLog.w("发送启动提示通知失败", it) }
    }

    /** 判断包名是否属于 QQ 家族（供通知监听与回复流程复用）。 */
    fun isQqPackage(packageName: String): Boolean = packageName in QQ_PACKAGES

    val QQ_PACKAGES = setOf("com.tencent.mobileqq", "com.tencent.qqlite", "com.tencent.tim")
}
