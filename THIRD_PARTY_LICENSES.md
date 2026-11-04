# 第三方依赖与许可证

本项目以 MIT 许可证发布（见 `LICENSE`）。运行时与构建期依赖如下，均为宽松许可证，
与 MIT 兼容。升级依赖时请同步更新本文件。

## 运行时依赖

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` / `okio` | 4.12.0 | Apache-2.0 | 调用 LLM HTTP 接口 |
| `org.java-websocket:Java-WebSocket` | 1.5.7 | MIT | 手机端 WS 服务端 / 伴侣端客户端 |
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | AndroidX 基础 |
| `androidx.appcompat:appcompat` | 1.7.0 | Apache-2.0 | Activity / 兼容主题 |
| `androidx.activity:activity-ktx` | 1.9.3 | Apache-2.0 | Activity Result API |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | lifecycleScope / repeatOnLifecycle |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Apache-2.0 | 伴侣端聊天列表 |
| `androidx.room:room-runtime` / `room-ktx` | 2.6.1 | Apache-2.0 | 结构化执行日志 |
| `com.google.android.material:material` | 1.12.0 | Apache-2.0 | Material 主题与控件 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Apache-2.0 | 协程 |
| Kotlin Stdlib | 2.0.21 | Apache-2.0 | 语言运行时 |

## 构建期依赖

| 依赖 | 版本 | 许可证 |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Gradle Plugin | 2.0.21 | Apache-2.0 |
| KSP (`com.google.devtools.ksp`) | 2.0.21-1.0.28 | Apache-2.0 |
| Room Compiler | 2.6.1 | Apache-2.0 |

## 测试依赖（不进入 APK）

| 依赖 | 版本 | 许可证 |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| `org.json:json`（JVM 单测替身） | 20240303 | Public Domain（JSON License） |

## 未随仓库分发的内容

- Android SDK / Build Tools：由用户本机或 CI 安装，遵循 Android SDK 许可；
- 应用图标为仓库内自绘矢量图（`ic_whale.xml`），未使用第三方素材；
- 伴侣端的默认聊天背景为代码生成的渐变，不含第三方图片。
