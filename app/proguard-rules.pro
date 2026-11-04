# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# org.json 在 Android 上是系统实现，保留防止反射裁剪
-keep class org.json.** { *; }

# 保留无障碍服务（系统按类名绑定）
-keep class com.whalechan.agent.access.WhaleAccessibilityService { *; }
-keep class com.whalechan.agent.notify.QqNotificationListener { *; }
-keep class com.whalechan.agent.service.KeepAliveService { *; }
