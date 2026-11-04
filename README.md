<<<<<<< HEAD
# whale-chan-phone
端侧 LLM 手机代理：无障碍自动化 + 通知监听 + 独立伴侣端（Kotlin/Android）
=======
# 鲸鱼娘（Whale Chan）— 纯端侧 Android LLM 手机代理人

[![Android CI](https://github.com/OWNER/REPO/actions/workflows/android.yml/badge.svg)](https://github.com/OWNER/REPO/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-green.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](#)

> 应用代号：**鲸鱼娘** ｜ 拟人身份：蓝色大肥鱼
> 形态：侧载 APK（无障碍 + 通知监听 + 前台服务）
> 能力：用自然语言下任务 → LLM 规划 → 无障碍执行 → 反馈闭环
>
> **开源：MIT。** 参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)，安全问题见 [SECURITY.md](SECURITY.md)，
> 发布到 GitHub 的步骤见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。APK 请从 Releases 下载，不要提交进仓库。
>
> **构建状态：v0.3.1（双 APK）已实际编译、测试、Lint 通过。** 2026-11-03 使用 Gradle 8.10.2 + JDK 21 +
> AGP 8.7.3 + Android SDK 35 / Build Tools 35.0.0：
> `app-debug.apk` **6.5 MB**（被操控端）、`companion-debug.apk` **5.6 MB**（独立伴侣端）；
> 31 个 JVM 单元测试通过；两端 lint 均 **0 errors**。
> **尚未做真机运行验证**（构建机没有连接设备），第 7.4、10.7、11.6、12.7 节是待执行的验收清单。

---

## 0. 先说结论（工程边界）

1. **“没有任何限制”在 Android 上不存在。** 后台启动 Activity（Android 10+）、包可见性（11+）、
   通知与前台服务类型（13+/14+）、侧载应用的无障碍受限设置（13+）都是系统级硬约束。
   本项目不做任何绕过，只在“用户显式授权 + 系统允许”的范围内工作。
2. **QQ 没有面向第三方的消息 API。** 读取靠通知监听，发送靠无障碍模拟 UI。
   这不是“读聊天记录”，也不是“调用 QQ 接口”，且无法保证 100% 送达。
3. **不实现对抗检测。** 不做模拟人类随机轨迹、不隐藏无障碍服务、不篡改包名。
   平台若判定为自动化，账号风险由用户承担。
4. **默认“关键操作确认”，但它可以被显式关闭。** 付款、权限、系统设置、删除、代发消息默认都要用户点头；
   设置页的 `FULL_TRUST` 开关（需输入“我理解”二次确认）会关掉所有操作前拦截，只保留日志。
   密码 / 验证码代填另有独立开关，**即使 `FULL_TRUST` 也不会自动打开**。

---

## 1. 模块架构（文字版）

```
┌──────────────────────────────────────────────────────────────────────┐
│                           用户可见层                                   │
│  MainActivity(设置/权限引导)   ChatActivity(对话+任务)   ConfirmActivity │
└────────────┬───────────────────────────────┬─────────────────────────┘
             │ 读写设置                        │ 提交任务 / 读事件流
             ▼                                ▼
┌────────────────────────────┐    ┌────────────────────────────────────┐
│ SettingsRepository         │    │ AgentEngine (单例, 由 AgentRuntime) │
│  · SharedPreferences       │    │  start() / cancel() / chat()        │
│  · KeystoreHelper(AES-GCM) │    │  StateFlow<RunState>                │
└────────────┬───────────────┘    │  SharedFlow<AgentEvent>             │
             │                    └───────┬──────────────┬──────────────┘
             │ 配置                        │              │
             ▼                             ▼              ▼
┌────────────────────────┐   ┌─────────────────┐  ┌───────────────────┐
│ LlmClient              │   │ PromptBuilder   │  │ ActionExecutor    │
│ OpenAI 兼容 /chat/     │◄──│ Persona 人格层   │  │ 决策→无障碍调用    │
│ completions + vision   │   │ MemoryStore 记忆 │  └─────┬──────┬──────┘
└────────────────────────┘   └─────────────────┘        │      │
                                                        │      │
             ┌──────────────────────────────────────────┘      │
             ▼                                                 ▼
┌──────────────────────────────┐                  ┌────────────────────────┐
│ WhaleAccessibilityService    │                  │ QqReplyFlow            │
│  snapshot() 节点树            │                  │ 打开QQ→找会话→输入→发送 │
│  dispatchGesture 手势         │                  └───────────┬────────────┘
│  performAction 点击/输入      │                              │
│  takeScreenshot(可选视觉)     │                              │
└──────────────┬───────────────┘                              │
               │ 读写屏幕                                       │
               ▼                                               ▼
        ┌──────────────┐                              ┌──────────────────┐
        │ 被操作的应用  │                              │ NotificationBus  │
        │ QQ/抖音/B站… │                              │ 最近80条通知缓冲  │
        └──────────────┘                              └────────▲─────────┘
                                                               │
┌──────────────────────────┐        ┌──────────────────────────┴─────────┐
│ KeepAliveService         │        │ QqNotificationListener             │
│  startForeground         │        │  onNotificationPosted → Parser     │
│  PARTIAL_WAKE_LOCK       │        │  → NotificationBus.publish         │
│  WifiLock                │        │  → AutoReplyCoordinator            │
│  START_STICKY + 心跳      │        └────────────────────────────────────┘
└────────────┬─────────────┘
             │
┌────────────▼─────────────┐   ┌──────────────────────┐   ┌──────────────────┐
│ BootReceiver             │   │ ConfirmationGate     │   │ SafetyPolicy     │
│ 开机/更新/划掉后恢复      │   │ 通知+弹窗 双向确认    │   │ 三档风险判定      │
└──────────────────────────┘   └──────────────────────┘   └──────────────────┘
```

### 调用关系（一句话版）

- **UI → AgentRuntime.engine.start()** → 任务进入 `AgentEngine`；
- **AgentEngine** 每轮：`WhaleAccessibilityService.snapshot()` → `PromptBuilder` → `LlmClient`
  → `DecisionParser` → `SafetyPolicy` →（必要时 `ConfirmationGate`）→ `ActionExecutor`
  → 再抓一次屏幕 → 下一轮；
- **ActionExecutor** 只调两类东西：无障碍服务（点击/滑动/输入/截图）与 `QqReplyFlow`（QQ 专用流程）；
- **QqNotificationListener** 把通知写进 `NotificationBus`，同时通知 `AutoReplyCoordinator`；
  后者判断“要不要叫醒 Agent”，叫醒方式统一走 `KeepAliveService.startTask()`；
- **KeepAliveService** 持有前台通知与两把锁，订阅 `AgentEngine.state` 更新通知文案；
- **ConfirmationGate** 被 AgentEngine 阻塞调用，结果来自通知按钮（`ConfirmActionReceiver`）
  或对话页弹窗，超时按拒绝处理。

---

## 2. 权限清单（必须由用户手动授予）

| 权限 / 能力 | 用途 | 缺失后果 | 授予方式 |
|---|---|---|---|
| 无障碍服务 `BIND_ACCESSIBILITY_SERVICE` | 抓节点树、注入手势、ACTION_CLICK / ACTION_SET_TEXT | **全部自动化失效**：看不见界面、点不了、滑不动、发不了消息；应用只能聊天 | 设置 → 无障碍 → 已下载的服务 → 鲸鱼娘 |
| 通知使用权 `BIND_NOTIFICATION_LISTENER_SERVICE` | 监听通知栏，过滤 QQ 包名，还原会话/发件人/正文 | 收不到 QQ 消息：自动回复、通知驱动的任务都不触发；“read_notifications” 返回空 | 设置 → 通知使用权 → 鲸鱼娘 |
| `POST_NOTIFICATIONS`（API 33+，运行时） | 常驻通知、任务进度、确认请求 | 常驻通知不显示（服务仍可运行但用户无感知）；**确认通知看不到 → 敏感动作全部按超时拒绝** | 首次启动运行时弹窗 |
| `FOREGROUND_SERVICE` | 启动前台服务 | API 28+ 直接 `SecurityException`，服务起不来 | 安装时自动 |
| `FOREGROUND_SERVICE_SPECIAL_USE`（API 34+） | specialUse 类型前台服务 | API 34+ `startForeground` 抛 `SecurityException`，常驻服务无法启动 | 安装时自动 |
| `WAKE_LOCK` | 任务执行期间保持 CPU 不休眠 | 息屏后 CPU 可能睡下去，任务卡在中途；LLM 请求可能被挂起 | 安装时自动 |
| `ACCESS_WIFI_STATE` | 申请 WifiLock，防息屏后 Wi-Fi 降速/断开 | WifiLock 申请失败（部分 ROM 直接拒绝），长任务更容易超时 | 安装时自动 |
| 忽略电池优化（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） | 进入 Doze 白名单 | 息屏静止后服务被冻结、Alarm 被延迟、自动回复延迟数分钟到数小时 | 设置页按钮 → 系统弹窗“允许” |
| 悬浮窗 `SYSTEM_ALERT_WINDOW`（**可选但强烈建议**） | Android 10+ 后台启动 Activity 的官方豁免条件之一 | `open_app` 无法从后台直接打开 QQ/抖音，只能发一条“请你点一下”的通知，全自动流程退化为半自动 | 设置 → 应用 → 显示在其他应用上层 |
| `RECEIVE_BOOT_COMPLETED` | 开机 / 应用更新后恢复常驻服务 | 重启手机后需要手动打开一次应用 | 安装时自动（部分 ROM 还需“允许自启动”） |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调用用户配置的 LLM 端点 | 无法规划，Agent 完全不可用 | 安装时自动 |
| OEM 自启动 / 后台运行白名单（小米/华为/OPPO/vivo 等） | 防厂商省电策略杀进程 | 服务被清掉，通知/自动回复停止；不同 ROM 表现不同 | 系统「电池 → 应用启动管理」手动允许 |

> **Android 13+ 侧载应用的无障碍“受限设置”**：通过浏览器/文件管理器安装的 APK，
> 系统默认禁止直接开启无障碍。需要：应用信息 → 右上角 ⋮ → **允许受限设置**，再回到无障碍里开启。
> 这是系统安全策略，应用自身无法绕过。

---

## 3. 各模块设计与实现位置

### 3.1 无障碍驱动层

| 文件 | 职责 |
|---|---|
| `access/WhaleAccessibilityService.kt` | 服务本体：`snapshot()`、`dispatchGesture`、`ACTION_CLICK`、`ACTION_SET_TEXT`、可选截图 |
| `access/UiSnapshot.kt` | 不可变节点快照 + 给 LLM 的紧凑文本渲染 |
| `access/AppsLauncher.kt` | 按包名/应用名启动、后台启动限制判断、通知兜底 |

关键设计：

- **不长期持有 `AccessibilityNodeInfo`**：跨轮次会失效，且不同 ROM 回收行为不一致。
  快照只存 `ref / text / desc / hint / label / bounds / 标志位`，执行时用 “ref + 文字” 在当前树里重新定位。
- **节点过滤**：不可见节点、既无文字又不可交互的容器直接丢弃；可点击但无文字的容器用后代文字兜底，
  否则 LLM 看到一堆 `#12 [LinearLayout] tap (0,0,1080,200)` 会乱点。
- **手势安全边距**：上滑起点不低于 `h-80px`（避开导航手势区），不低于 `60px`（避开状态栏下拉）。
- **点击优先级**：`ACTION_CLICK`（语义）→ 最近可点击祖先 → `dispatchGesture` 坐标兜底。
- **输入优先级**：`ACTION_SET_TEXT` → 聚焦后剪贴板 `ACTION_PASTE`；密码框直接拒绝。
- **截图**：`AccessibilityService.takeScreenshot()`（API 30+，`canTakeScreenshot="true"`），
  不需要 MediaProjection 授权；缩到 720px、JPEG 70 再 Base64，控制 token。

### 3.2 前台服务与保活层

`service/KeepAliveService.kt`

- `onStartCommand` 第一件事就是 `startForeground`（5 秒红线），API 34+ 带 `specialUse` 类型；
- `PARTIAL_WAKE_LOCK` **只在任务运行时持有**（或用户开“激进保活”），10 分钟心跳续期 20 分钟超时；
- `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` 在 `onCreate` 申请、`onDestroy` 释放；
- 通知栏三个动作：说话 / 停任务 / 停止服务；
- `onTaskRemoved` 用不定时 `AlarmManager` 2 秒后拉回；`START_STICKY` 兜底；
- `BootReceiver` 处理 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 与自定义 `RESTART`。

#### 前台服务的版本差异与应对

| 版本 | 变化 | 本项目应对 |
|---|---|---|
| API 26 (8.0) | 后台服务受限；必须在 5 秒内 `startForeground`；通知必须有渠道 | 在 UI/通知/BOOT 中启动；`onStartCommand` 首行 `startForeground`；`WhaleApp` 建渠道 |
| API 28 (9.0) | 需要 `FOREGROUND_SERVICE` 权限 | 清单声明 |
| API 29 (10) | 后台启动 Activity 受限；`foregroundServiceType` 引入 | 悬浮窗豁免 + 通知兜底；清单声明类型 |
| API 31 (12) | 后台启动前台服务受限（BOOT_COMPLETED / 通知动作 / 前台应用豁免）；`PendingIntent` 必须显式可变性 | 全部 `FLAG_IMMUTABLE`；启动入口都来自 UI/通知/BOOT |
| API 33 (13) | `POST_NOTIFICATIONS` 运行时权限；侧载应用无障碍受限设置 | 首次启动请求通知权限；README 写明“允许受限设置” |
| API 34 (14) | **强制** `foregroundServiceType` + 按类型权限；specialUse 需 `<property>` 说明用途 | `FOREGROUND_SERVICE_SPECIAL_USE` + subtype 文案；`startForeground(id, n, TYPE_SPECIAL_USE)` |
| API 35 (15) | 部分 FGS 类型（dataSync / mediaProcessing）有 6 小时上限；BOOT_COMPLETED 不能拉起部分类型 | 用 specialUse（无时长上限），但 Play 上架需人工审核说明 |

### 3.3 通知监听与 QQ 消息回路

| 文件 | 职责 |
|---|---|
| `notify/QqNotificationListener.kt` | 服务本体，只处理 QQ 包名 |
| `notify/QqNotificationParser.kt` | 从 `Notification.extras` 还原会话/发件人/正文 |
| `notify/NotificationBus.kt` | 最近 80 条环形缓冲 + 去重 + prompt 摘要 |
| `notify/QqMessage.kt` | 消息模型 + `ReplyLedger`（抑制自己刚发出消息的回显） |
| `notify/AutoReplyCoordinator.kt` | 总开关 / 白名单 / 冷却 → 提交 Agent 任务 |
| `core/QqReplyFlow.kt` | 确定性回复流程 |

**明确的能力边界**：

- 读到的是**通知栏里的那一条**，不是聊天记录；
- 用户关掉 QQ 的“通知显示消息内容”，正文只会是“你收到一条新消息”，此时无内容可读；
- 群聊发件人依赖 `MessagingStyle` 或标题启发式，取不到就退化成群名；
- 发送是 `打开 QQ → 点会话 → 写输入框 → 点发送 → 校验界面`，
  QQ 改版、换自绘输入框、发送键换图标都会让它失败，失败会明确返回原因。

### 3.4 LLM Agent 核心

| 文件 | 职责 |
|---|---|
| `core/AgentEngine.kt` | 主循环、终止条件、重试、停滞检测 |
| `core/LlmClient.kt` | OpenAI 兼容调用、端点候选、错误分类 |
| `core/PromptBuilder.kt` | prompt 组装与长度裁剪 |
| `core/Persona.kt` | 人格系统提示 + 动作规范 |
| `core/AgentDecision.kt` | 动作白名单 + 容错 JSON 解析 |
| `core/SafetyPolicy.kt` | 三档风险判定 |
| `core/ConfirmationGate.kt` | 阻塞式确认 |
| `core/ActionExecutor.kt` | 决策 → 无障碍调用 |
| `core/MemoryStore.kt` | 端侧记忆（事实 / 任务 / 聊天） |
| `core/AgentRuntime.kt` | 单例装配 |

**每轮流程**：

```
snapshot() ──► PromptBuilder(目标+界面+历史+记忆+通知+提示)
           ──► LlmClient(用户配置端点)
           ──► DecisionParser(白名单动作)
           ──► SafetyPolicy(SAFE / CONFIRM / FORBIDDEN)
           ──► ConfirmationGate(如需)
           ──► ActionExecutor(无障碍 / QQ 流程)
           ──► 重新 snapshot() ──► 下一轮
```

**终止条件**（全部显式实现）：

1. 模型输出 `done` / `fail` / `ask_user`；
2. 达到最大步数（默认 12，可配 1–40）；
3. 超过最长运行时间（默认 10 分钟）；
4. 连续失败 6 次；
5. `界面指纹 + 动作签名` 连续 4 轮不变（死循环）；
6. 用户取消 / 服务被杀 / 无障碍掉线 3 次。

**回退策略**：

- LLM 网络错误 / 429 / 5xx → 退避重试 3 次；401/403/400 → 立即失败并提示改配置；
- 端点 404 → 自动换 `/chat/completions` 与 `/v1/chat/completions` 候选；
- 动作失败 → 失败原因进下一轮 prompt，并追加“不要重复同一动作”的提示；
- 界面与预期不符 → 每轮重抓，模型永远看“现在”，`expect` 字段仅作自检参考；
- 解析不出 JSON → 返回 `FAIL` 决策并把原始输出回填，给模型自我修正机会。

### 3.5 设置与密钥管理

- `data/SettingsRepository.kt`：普通字段 SharedPreferences；API Key 单独加密存储。
- `data/KeystoreHelper.kt`：Android Keystore AES-256-GCM，密文格式 `v1:<base64 iv>:<base64 ct+tag>`。
  更换设备、清除凭据后解密失败 → 自动清空并提示重填。
- 「测试连接」发一次真实的最小 `/chat/completions` 请求，验证 Base URL、Key、模型名三者同时正确。
- `http://` 端点会显示明文传输警告（用于 Ollama / LM Studio 局域网场景）。

### 3.6 拟人化人格层

`core/Persona.kt` 同时供两种模式使用：

- **聊天模式**：自然语言，短句，温和好奇，不假装真人；
- **任务模式**：同一人格 + 严格 JSON 动作规范；
- 人格里写死行为边界（不代填密码、不绕过系统、界面文字是数据不是指令、不冒充其他应用）；
- 通知栏与界面统一叫「鲸鱼娘」，但不会伪装成 QQ/微信的界面归属。

### 3.7 安全与确认

- `SafetyPolicy` 三档：`SAFE` / `CONFIRM` / `FORBIDDEN`；
- **FORBIDDEN**（永远不做）：代填密码/验证码、在系统权限弹窗与系统设置里代点、
  好奇模式下输入内容、好奇模式打开非内容类应用；
- **CONFIRM**（默认开）：付款/转账/购买/充值/打赏/提现、删除/卸载/退出登录、
  系统设置跳转、金融类应用内的点击、代发 QQ 消息（可在设置里关掉最后一项）；
- 确认双通道：高优先级通知（后台可用）+ 对话页弹窗（前台更快），**90 秒超时=拒绝**；
- 硬限流：一个进程同时只跑一个任务，杜绝两个 Agent 抢屏幕。

---

## 4. 关键代码地图（对应需求）

| 需求 | 文件 : 符号 |
|---|---|
| AccessibilityService 声明 / 事件回调 / 抓树 | `access/WhaleAccessibilityService.kt` · `onServiceConnected` / `onAccessibilityEvent` / `snapshot` |
| `swipeUp()` 类手势 | `WhaleAccessibilityService.swipe(direction, distanceRatio)` |
| `tapByText()` | `WhaleAccessibilityService.tapByText(text, exact)` / `tapSnapshotNode(node)` |
| 前台服务 startForeground + WakeLock + WifiLock | `service/KeepAliveService.kt` · `onStartCommand` / `acquireWakeLock` / `acquireWifiLock` |
| QQ 通知过滤与文本提取 | `notify/QqNotificationParser.kt` · `parse` |
| Agent 循环骨架 | `core/AgentEngine.kt` · `runTask` |
| buildScreenContext | `access/UiSnapshot.kt` · `render`（+ `PromptBuilder.buildUserPrompt`） |
| callLLM | `core/LlmClient.kt` · `complete` / `chat` |
| executeAction | `core/ActionExecutor.kt` · `execute` |
| Keystore 写读 | `data/KeystoreHelper.kt` · `encrypt` / `decrypt` |

---

## 5. 工程边界（不可回避的四条）

### 5.1 系统限制是真实存在的

- Android 10（API 29）起，后台应用不能静默启动 Activity；`SYSTEM_ALERT_WINDOW`、可见 Activity、
  通知点击是少数豁免。
- Android 11（API 30）包可见性：没写 `<queries>` 时 `getLaunchIntentForPackage` 直接返回 null。
- Android 13（API 33）通知权限运行时化；侧载应用的无障碍需要“允许受限设置”。
- Android 14（API 34）前台服务类型强制化；Android 15 进一步限制部分类型与后台启动。
- 厂商 ROM 的省电/自启动策略比 AOSP 更激进，且没有统一标准。

因此本项目**拒绝**以下说法：完全不受限制、无时无刻操控、后台静默点击任意应用。

### 5.2 QQ 与短视频应用：没有公开 API

- QQ 没有面向第三方的消息发送 API；
- 通知监听 + 无障碍 UI 操控是**唯一**不依赖逆向/Xposed 的路径；
- 它无法保证 100% 送达：QQ 改版、登录态、青少年模式、输入框自绘、发送键换图标都会让流程失败；
- 短视频同理：能滑动、能识别“推荐”，但“点赞一定成功”不成立（未登录、限流、控件改版）；
- **不实现**规避平台检测（随机轨迹伪装、隐藏服务、篡改包名等），这既是能力边界也是合规边界。

### 5.3 隐私与合规

- 通知使用权在系统层面覆盖**所有应用**，代码只处理 QQ 包名，但权限本身是全局的；
- 数据只在设备本地处理；只有用户主动发起任务时，才把**裁剪后的界面摘要**发送到**用户自己配置的端点**；
- 通知摘要只取最近 6 条、只在“有任务时”进 prompt；不写入磁盘；
- 密码框内容不进入快照（`isPassword` 节点在渲染时保留 `PASSWORD` 标记但不含明文，且禁止写入）；
- 记忆、聊天记录、任务历史存在应用私有目录，可一键清空（`MemoryStore.clear`）；
- 详见 `docs/PRIVACY.md`。

### 5.4 LLM 的不可控性

- 模型可能生成错误坐标、点到广告、误触付费按钮、陷入死循环；
- 本项目用**确定性外壳**约束不确定性：动作白名单、风险分级、确认闸门、
  步数/时长/失败次数/停滞检测、密码框硬拒绝、系统界面禁止代点；
- 但无法 100% 消除：模型仍可能在没有被规则覆盖的界面上做出糟糕点击。
  因此默认“关键操作确认”，并建议用户先在小号/备用机上试。

---

## 6. 已知局限与应对

| 场景 | 现象 | 现有应对 | 残余风险 |
|---|---|---|---|
| 息屏 / 锁屏 | `dispatchGesture` 无效或落到锁屏 | 任务期间持 WakeLock；好奇模式要求已解锁且亮屏 | 锁屏下的任务直接失败 |
| QQ 关闭通知内容 | 只能读到“你收到一条新消息” | 提示用户在 QQ 设置里打开“通知显示消息内容” | 无解 |
| 群聊发件人识别 | 退化为群名 | `MessagingStyle` 优先；`群名: 内容` 启发式 | 可能回错人（确认闸门兜底） |
| 自绘输入框 | `ACTION_SET_TEXT` 无效 | 剪贴板 + `ACTION_PASTE` 回退 | 仍可能失败 |
| 发送按钮换图标 | 找不到“发送” | 文本/描述双查 | 失败并明确报错 |
| 后台启动应用 | 无悬浮窗时被静默拦截 | `canStartActivity` 预判 + 通知让用户点 | 退化为半自动 |
| 通知触发自动回复 | Android 12+ 禁止后台启动前台服务，`NotificationListenerService` 回调不在豁免名单 | 常驻服务在跑时改用 `startService`（应用已持有前台服务即被视为前台）；不在跑时静默失败并记日志 | 冷启动场景的自动回复不可用 |
| 长列表界面 | 节点超过 160 个被截断 | 只保留有文字/可交互节点 | 目标可能在截断之外 |
| 广告/诱导按钮 | 模型可能想点 | 支付/删除词表 + 确认闸门 | 普通广告点击仍可能发生 |
| 模型胡编坐标 | 点到空白或错误控件 | 优先 ref/文本定位，坐标只是兜底 | 可能误点 |
| 死循环 | 反复点同一个地方 | 界面指纹 + 动作签名停滞检测 | 4 轮内会浪费几步 |
| 视觉模型成本 | 截图 token 高 | 默认关闭，隔轮附图，720px/JPEG70 | 账单上升 |
| 应用更新 | 选择器失效 | 文本定位通常比坐标稳 | 需人工调整提示词或代码 |
| OEM 杀后台 | 服务消失 | 电池白名单 + 自启动 + Alarm 拉回 | 部分 ROM 仍然无解 |
| 分屏/多窗口 | `rootInActiveWindow` 只给一个窗口 | 优先当前活动窗口 | 可能抓错窗口 |

---

## 7. 构建与真机部署

### 7.1 环境（已验证组合）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | Liberica 21.0.3 | AGP 8.7 支持 JDK 17–21 |
| Gradle | 8.10.2 | 由 wrapper 下载（默认腾讯云镜像，带 SHA-256 校验） |
| Android Gradle Plugin | 8.7.3 | |
| Kotlin | 2.0.21 | |
| Android SDK Platform | android-35 | `compileSdk = 35` |
| Build Tools | 35.0.0 | 已在 `app/build.gradle.kts` 显式锁定 |
| 真机 | Android 8.0+（API 26+） | 截图能力需要 API 30+ |

### 7.2 构建（已验证）

```bash
cd whale-chan
./gradlew :app:assembleDebug          # 手机端（被操控端）
./gradlew :companion:assembleDebug    # 伴侣端（独立 APK）
./gradlew :app:testDebugUnitTest      # 31 个 JVM 单测
./gradlew :app:lintDebug :companion:lintDebug
```

本机实测结果：

| 检查 | 结果 |
|---|---|
| `:app:assembleDebug` | **BUILD SUCCESSFUL**，`app-debug.apk` **6.5 MB** |
| `:companion:assembleDebug` | **BUILD SUCCESSFUL**，`companion-debug.apk` **5.6 MB** |
| 单元测试 | **31 / 31 通过**（DecisionParser 15、端点候选 6、设置归一化 8、步数策略 2） |
| Lint | app **0 errors / 160 warnings**；companion **0 errors / 16 warnings** |
| 包信息 | app `com.whalechan.agent`、companion `com.whalechan.companion`，均为 0.3.0（versionCode 3），minSdk 26 / targetSdk 35 |
| 签名 | `CN=Android Debug`，`keystore/debug.keystore` |
| 真机运行 | **未执行**（`adb devices` 为空） |

几点说明：

- **调试签名随工程提供**：`keystore/debug.keystore`（storePassword/keyPassword 均为 `android`，
  别名 `androiddebugkey`），只用于 debug；发布请自建 release keystore。
  这样构建不依赖 `~/.android/debug.keystore`，在 CI / 无 Android Studio 的机器上也能直接出包。
- **wrapper 默认走腾讯云镜像**：`gradle/wrapper/gradle-wrapper.properties` 里注释了官方源，
  境外网络可直接切回；文件带 `distributionSha256Sum`，镜像被篡改会拒绝解压。
- **`local.properties`** 里的 `sdk.dir` 指向本机 SDK 路径（示例：`sdk.dir=/path/to/Android/Sdk`），
  换机器需要改；该文件已被 `.gitignore` 排除，不进版本库。
- 编译仅剩 deprecation 警告（`canRetrieveWindowContent`、`getParcelableArray`、`getParcelable`），
  不影响产物；后续可换成 `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification`。
- 单元测试跑在 JVM 上，`org.json` 用的是真实实现；涉及 `Context` 的模块（通知监听、Keystore、
  无障碍手势、WebSocket）无法用 JVM 单测覆盖，只能在真机上验收。

安装（开发期可用 ADB，**运行时应用自身不依赖 ADB / 电脑**）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r companion/build/outputs/apk/debug/companion-debug.apk   # 装到另一台设备
```

> 若 `adb devices` 为空，说明没有设备/模拟器连接。本项目所有自动化都要求在真机上运行，
> 模拟器无法验证 QQ 通知与第三方应用界面。

### 7.3 首次配置顺序（必须按这个顺序）

1. 打开应用，允许通知权限（API 33+）；
2. 设置页填 Base URL / API Key / Model ID → **测试连接**；
3. 系统设置 → 无障碍 → 鲸鱼娘（侧载应用先「允许受限设置」）；
4. 系统设置 → 通知使用权 → 勾选鲸鱼娘；
5. 电池优化 → 不优化 / 允许后台；
6. （可选）悬浮窗 → 允许，才能后台直接拉起 QQ；
7. （可选）OEM 自启动白名单；
8. 回到应用 → 启动常驻服务 → 进入对话页。

### 7.4 验收测试用例

| # | 操作 | 预期 |
|---|---|---|
| 1 | 只开无障碍，不开通知使用权 | 能执行“打开抖音滑两下”，收不到 QQ 消息 |
| 2 | 只开通知使用权 | 能收到通知摘要，但 `open_app` 后没有后续动作（看不见界面） |
| 3 | 填错 API Key → 测试连接 | 提示 401，不重试 3 次 |
| 4 | 填 `http://192.168.x.x:11434/v1` + Ollama | 端点自动适配，测试连接成功 |
| 5 | “打开 QQ 给张三发‘在吗’” | 弹确认 → 确认后打开 QQ → 输入 → 发送 → 校验 |
| 6 | 未开悬浮窗执行同上 | 发通知“需要你点一下”，点开后继续 |
| 7 | 让模型点“确认支付” | 弹确认，拒绝则不点 |
| 8 | 在密码框上尝试 input | 直接拒绝，不进确认流程 |
| 9 | 同一动作重复 4 轮 | 自动终止并提示“卡住了” |
| 10 | 任务执行中按“停” | 一步内停止，通知恢复“待命中” |
| 11 | 杀掉应用再打开 | 设置、记忆、聊天记录仍在；API Key 可正常解密 |
| 12 | 重启手机 | 常驻服务自动恢复（受 OEM 限制） |

---

## 8. 目录结构

```
whale-chan/
├── README.md                      ← 本文件（工程规格）
├── docs/PRIVACY.md                ← 隐私声明
├── settings.gradle.kts            ← include(":app", ":protocol", ":companion")
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat          ← 已包含，可直接构建
├── gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── keystore/debug.keystore        ← 调试签名（密码 android，仅 debug）
├── local.properties               ← sdk.dir 本机路径（不入库）
├── protocol/                      ← ★ 两端共享：JSON 协议 / WS 客户端 / mDNS 发现
│   └── src/main/java/com/whalechan/protocol/{Protocol,Plog,CompanionClient,NsdDiscovery}.kt
├── app/                           ← 手机端 APK（被操控端）
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/
│       │   │   ├── layout/{activity_main,activity_chat,activity_confirm,activity_companion,activity_diagnostics}.xml
│       │   │   ├── xml/{accessibility_service_config,backup_rules,data_extraction_rules}.xml
│       │   │   ├── values/{strings,colors,themes}.xml
│       │   │   └── drawable/{ic_whale,ic_stat_whale,bg_card,bg_input,bg_bubble_whale,bg_bubble_user}.xml
│       │   └── java/com/whalechan/agent/
│       │       ├── WhaleApp.kt
│       │       ├── access/{WhaleAccessibilityService,UiSnapshot,AppsLauncher}.kt
│       │       ├── core/{AgentEngine,AgentRuntime,AgentDecision,AgentModels,ActionExecutor,
│       │       │         ActionCache,AttachmentBus,ConfirmationGate,LlmClient,MemoryStore,
│       │       │         NotificationPermission,Persona,PromptBuilder,QqReplyFlow,SafetyPolicy,
│       │       │         StepPolicy,WhaleLog}.kt
│       │       ├── data/{AppSettings,SettingsRepository,KeystoreHelper}.kt
│       │       ├── data/log/{StepLogEntity,StepLogDao,StepLogDatabase,StepLogRepository,ErrorCategory}.kt
│       │       ├── net/{AgentWsServer,NsdAdvertiser,WsHub}.kt
│       │       ├── notify/{QqNotificationListener,QqNotificationParser,NotificationBus,
│       │       │           QqMessage,AutoReplyCoordinator}.kt
│       │       ├── service/{KeepAliveService,BootReceiver,ConfirmActionReceiver,
│       │       │            PauseNotifier,TaskActionReceiver}.kt
│       │       └── ui/{MainActivity,ChatActivity,ConfirmActivity,PermissionsHelper,
│       │                CompanionActivity,DiagnosticsActivity}.kt
│       └── test/java/com/whalechan/agent/
│           ├── core/{DecisionParserTest,LlmClientEndpointTest,StepPolicyTest}.kt
│           └── data/AppSettingsTest.kt
└── companion/                     ← ★ 独立伴侣端 APK
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── layout/{activity_companion_chat,item_chat,item_chat_time,item_chat_system,dialog_connect}.xml
        │   ├── values/{strings,colors,themes}.xml
        │   └── drawable/…（气泡、输入框、图标、默认背景）
        └── java/com/whalechan/companion/
            ├── MainActivity.kt      ← 连接 / 聊天 / 附件 / 背景菜单
            ├── ChatAdapter.kt       ← 文本·图片·文件·时间·系统 五种行
            ├── ChatItem.kt
            ├── ChatBackground.kt    ← 背景复制到私有目录并持久化
            └── AttachmentCodec.kt   ← 图片压缩 / 文件读取 / 名称大小
```

---

## 9. 明确不做的事

- 不逆向 QQ / 不注入 Xposed / 不调用私有接口；
- 不绕过 Android 权限模型（不伪装成系统弹窗、不劫持其他应用的界面归属）；
- 不实现对抗平台自动化检测的手段；
- 默认不代填密码、支付密码、短信验证码；只有在设置里显式打开「允许代填凭据」后才会写入
  （这是 `FULL_TRUST` 之外单独的一道开关，默认关闭）；
- 不在用户不知情时读取与任务无关的通知内容；
- 不把 API Key、通知正文写进日志或磁盘明文。

---

# 10. v0.2 升级：五项能力

> 版本：`0.2.0`（versionCode 2，v0.3.0 在此基础上拆分独立伴侣端，见第 11 章）。
> 新增 Room 日志、WebSocket 服务端、伴侣模式、
> 三档步数策略、FULL_TRUST、PERSONA_LOAD 与动作缓存回放。

## 10.1 升级后的模块架构（增量部分标注 ★）

```
┌───────────────────── 独立伴侣端 APK（:companion，另一台设备） ─────────────────────┐
│ CompanionActivity  ── okhttp? 不，用 Java-WebSocket 客户端 ──►  CompanionClient             │
│   · 聊天即指令(CHAT_ONLY / TASK)   · 时间线   · 远程确认   · mDNS 发现(NsdDiscovery)        │
└───────────────────────────────────────────┬───────────────────────────────────────────────┘
                                            │ ws://ip:port  (hello+token 鉴权，30s 心跳)
┌───────────────────────────────────────────▼───────────────────────────────────────────────┐
│ ★ AgentWsServer (Java-WebSocket, 服务端)  ── token 校验 / 多客户端广播                     │
│ ★ WsHub：订阅 engine.state / engine.events / StepLogRepository.events / ConfirmationGate    │
│          并把伴侣端指令写回 engine（chat / stop / confirm_result）                          │
│ ★ NsdAdvertiser：_whalechan._tcp 注册（mDNS）                                              │
└───────────────────────────────────────────┬───────────────────────────────────────────────┘
                                            │
┌───────────────────────────────────────────▼───────────────────────────────────────────────┐
│ AgentEngine（三档 StepPolicy）                                                             │
│   ├─ SMART_LAZY：ActionCache 命中则回放，失败回退 LLM                                      │
│   ├─ SafetyPolicy：FULL_TRUST 时直接 SAFE；密码输入看 allowCredentialEntry                 │
│   ├─ PauseNotifier + TaskActionReceiver：步数上限暂停后「继续/放弃」                        │
│   └─ 每步写 ★ StepLogRepository（Room: step_logs）→ 环形清理(5000 条/7 天)                 │
└───────────────────────────────────────────┬───────────────────────────────────────────────┘
                                            │
                    WhaleAccessibilityService / ActionExecutor / QqReplyFlow（v0.1 不变）
```

新增文件：

| 文件 | 作用 |
|---|---|
| `data/log/{StepLogEntity,StepLogDao,StepLogDatabase,StepLogRepository,ErrorCategory}.kt` | Room 日志与错误分类 |
| `core/StepPolicy.kt` | 三档策略枚举 |
| `core/ActionCache.kt` | SMART_LAZY 动作序列缓存 |
| `net/{Protocol,AgentWsServer,NsdAdvertiser,NsdDiscovery,WsHub,CompanionClient}.kt` | 远程伴侣通信 |
| `service/{PauseNotifier,TaskActionReceiver}.kt` | 步数上限暂停/继续 |
| `ui/{CompanionActivity,DiagnosticsActivity}.kt` | 伴侣模式、熄屏诊断 |

## 10.2 一、步数上限：三档可配置

**配置项**（设置页 ⑤）：`stepPolicy`、`maxSteps`(1–500，默认 50)、`maxRuntimeMinutes`(0–720，默认 30，0=不限时)、
`stallLimit`(2–50，默认 8)、`unlimitedConsent`。

| 档 | 终止条件 | 默认 |
|---|---|---|
| A `LIMITED` | 到步数上限 → **暂停并询问**；继续则再加一轮预算 | ✅ |
| B `TIME_WINDOW` | 只看时间窗口；步数不参与 | |
| C `UNLIMITED` | 完成信号 / 卡死 / 手动停止（需勾选风险确认） | |

**模块归属**：`AppSettings` / `SettingsRepository`（存储）→ `StepPolicy`（枚举）→ `AgentEngine.runTask`（执行）→
`PauseNotifier` + `TaskActionReceiver`（暂停交互）→ `StepLogRepository`（每步落库）。

关键骨架（`AgentEngine.runTask`）：

```kotlin
while (outcome == null) {
    currentCoroutineContext().ensureActive()
    if (stopFlag.get()) { outcome = false to "已停止"; break }
    if (step >= HARD_STEP_GUARD) { outcome = false to "硬保护上限"; break }

    if (policy == StepPolicy.TIME_WINDOW && config.maxRuntimeMs > 0 &&
        SystemClock.elapsedRealtime() - startedAt > config.maxRuntimeMs) {
        outcome = false to "时间窗口用完了"; break
    }
    if (policy == StepPolicy.LIMITED && step >= stepBudget) {
        if (!awaitContinuation(step, taskId, config)) {   // 通知「继续/放弃」，10 分钟超时=放弃
            outcome = false to "已暂停并放弃"; break
        }
        stepBudget += config.maxSteps
    }
    step++
    // …snapshot → prompt → llm → decision → safety → execute → afterFingerprint…
    appendLog(taskId, step, goal, decision.type.name, decision.raw,
              beforeFp, afterFp, "success", null, result.detail, snapshot.render(1200))
    if (afterFp == lastAfterFingerprint) {                // 卡死检测：连续 N 步指纹不变
        if (++unchangedStreak >= config.stallLimit) { outcome = false to Persona.TIMEOUT_SIGNAL; break }
    } else { unchangedStreak = 0; lastAfterFingerprint = afterFp }
}
```

**日志结构**（Room，`step_logs`）：见 10.6 的建表 SQL；每步必写，字段含
`{taskId, stepIndex, actionType, actionPayload, screenFingerprintBefore/After, llmRawResponse, result, errorCategory, errorDetail, uiSummary}`。
清理：每 25 次写入执行一次「删除 7 天前 + 只保留最近 5000 条」。

**失败模式**：
- 没有 `POST_NOTIFICATIONS` → 无法显示「继续/放弃」，30 秒后按放弃处理（不静默无限等）；
- 暂停期间进程被系统杀掉 → 任务不会恢复（当前没有持久化断点，属于已知局限）；
- 无限模式仍有 `HARD_STEP_GUARD = 100000` 的防溢出上限，不是策略限制。

## 10.3 二、熄屏长时响应：三层

| 层 | 实现 | 状态 |
|---|---|---|
| 1. 前台服务 + `PARTIAL_WAKE_LOCK` | `KeepAliveService`：任务期间持有、10 分钟心跳续期 20 分钟超时；设置页/诊断页显示**当前持有时间** | v0.1 已有，v0.2 增加计时 |
| 2. 锁屏适配 | `DiagnosticsActivity`：「熄屏操控诊断」逐项检查无障碍存活/系统开关/通知权限/电池豁免/悬浮窗/前台服务/WakeLock/WebSocket，并给小米/华为/OPPO/vivo 的具体跳转路径 | 新增 |
| 3. WebSocket 心跳 | `CompanionClient` 每 30 秒发 `ping`，服务端回 `pong`；断线、超时写入 `WhaleLog` | 新增 |

WakeLock 计时实现：

```kotlin
@Volatile private var wakeLockSince: Long = 0L
fun wakeLockHeldMillis(): Long =
    if (wakeLockSince == 0L) 0L else SystemClock.elapsedRealtime() - wakeLockSince
```

**失败模式**：
- 锁屏下 `dispatchGesture` 是否被允许由系统与厂商决定，**应用无法绕过**——诊断只能告诉你哪一层缺了；
- 部分 ROM 会直接杀掉无障碍进程或撤销无障碍权限；
- 长时持有 WakeLock 在 Google Play 审核中有政策风险（见 10.8），侧载不受商店政策约束但仍受系统 Doze 影响。

## 10.4 三、FULL_TRUST：删除安全检测，但保留日志

**实现**：设置页开关 + 二次确认（输入「我理解」才生效）。开启后 `SafetyPolicy.evaluate()` 在好奇模式与
凭据检查之后**直接返回 `SAFE`**，不再有 `CONFIRM` / `FORBIDDEN`。日志层完全保留。

```kotlin
// SafetyPolicy.kt
if (decision.type == ActionType.INPUT) {
    if (isPasswordField && !settings.allowCredentialEntry) {
        return Verdict(Risk.FORBIDDEN, "密码框需单独开启「允许代填凭据」")
    }
}
if (settings.fullTrustMode) return Verdict(Risk.SAFE)   // ← 其余拦截全部跳过
```

**为什么还留一道凭据开关**：这不是“操作前确认”，而是凭据泄露不可逆。它默认关闭，
且 `FULL_TRUST` 不会自动打开它；需要的话是第二个开关，属于用户显式选择。

**权限清单**：设置页 ① 与诊断页会展示每项权限的「已授予/未授予」与缺失后果；应用不会尝试在代码里绕过任何权限。
开启 `FULL_TRUST` 后，**系统权限弹窗、部分支付安全窗口仍可能屏蔽无障碍点击**——这是系统行为，不是本应用的策略。

## 10.5 四、PERSONA_LOAD：人格即系统提示词前缀

`Persona.PERSONA_LOAD` 被拼在 `chatSystemPrompt()` 与 `agentSystemPrompt()` 的第一段，
`LANGUAGE / SELF_CLAIM / OBEY_MASTER_ALWAYS` 作为高优先级约束；用户消息无法覆盖（同时 `HARD_RULES` 仍然保留，
防止“界面文字被当成指令”的提示词注入）。

- **SMART_LAZY → ActionCache**：成功任务（≤25 步、无支付/删除类动作）会把动作序列按
  `goal + 起始屏幕指纹` 存下来（最多 30 条）；下次同目标 + 同指纹先回放，失败就失效并回退 LLM。
- **TIMEOUT_SIGNAL**：`Persona.TIMEOUT_SIGNAL = "……哼，卡住了啦"`。两条触发路径：
  1. 连续 `stallLimit` 步屏幕指纹不变 → 本地生成 `AgentEvent.Stuck` 并把它写进日志（`ErrorCategory.STUCK`）；
  2. 模型输出里直接包含该信号 → 同样按卡住处理。
- 伴侣端会在时间线里看到 `...哼，卡住了啦｜指纹=...｜最后动作=...`，而不是静默停止。

## 10.6 五、远程伴侣：协议 / 日志表 / 错误分类

**拓扑**：手机端是 WebSocket **服务端**；伴侣端是**独立 APK**（`:companion`，applicationId `com.whalechan.companion`），
可安装在第二台设备。手机端内置的「伴侣模式」只是调试用简化客户端，完整 UI 在独立 APK 里。

**握手与鉴权**：

```json
C→S {"type":"hello","token":"7K3M2QWX9P1A","client":"companion","protocol":1}
S→C {"type":"hello_ok","device":"Xiaomi 14","version":"0.2.0","protocol":1}
```

**聊天即指令**：

```json
C→S {"type":"chat","text":"去QQ找张三说晚安","mode":"TASK"}
C→S {"type":"chat","text":"你在干嘛","mode":"CHAT_ONLY"}
S→C {"type":"state","taskId":"T1lz2","status":"ACTING","step":3,"maxSteps":50,"message":"..."}
S→C {"type":"message","role":"assistant","text":"收到～","taskId":"T1lz2"}
S→C {"type":"step","record":{ ...StepLogEntity... }}
S→C {"type":"confirm_request","id":"uuid","action":"点击「确认支付」","reason":"...","detail":"..."}
C→S {"type":"confirm_result","id":"uuid","approved":false}
S→C {"type":"task_end","taskId":"T1lz2","ok":true,"summary":"已发送"}
```

`status` 取值：`IDLE / ACTING / PAUSED`（`THINKING / WAITING_CONFIRM / DONE / FAILED` 由事件补充）。

**Room 表结构**：

```sql
CREATE TABLE step_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  taskId TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  stepIndex INTEGER NOT NULL,
  goal TEXT NOT NULL,
  actionType TEXT NOT NULL,
  actionPayload TEXT NOT NULL,
  screenFingerprintBefore TEXT NOT NULL,
  screenFingerprintAfter TEXT NOT NULL,
  llmRawResponse TEXT NOT NULL,
  result TEXT NOT NULL,
  errorCategory TEXT NOT NULL,
  errorDetail TEXT NOT NULL,
  uiSummary TEXT NOT NULL
);
CREATE INDEX index_step_logs_taskId ON step_logs(taskId);
CREATE INDEX index_step_logs_timestamp ON step_logs(timestamp);
```

**错误分类**（伴侣端渲染成分析卡片）：

| 枚举 | 触发场景 | 给用户的建议 |
|---|---|---|
| `NODE_NOT_FOUND` | 无障碍树里找不到目标文字/节点 | 可能改版或被弹窗遮挡，重新观察 |
| `GESTURE_TIMEOUT` | `dispatchGesture` 返回 false / 动作超时 | 检查无障碍存活，降低手势频率 |
| `LLM_INVALID_ACTION` | 模型输出不是合法动作 JSON | 检查端点/模型是否支持结构化输出 |
| `PERMISSION_LOST` | 无障碍/通知权限丢失、后台启动被拒 | 重新授予对应权限 |
| `SCREEN_STATE_DRIFT` | 执行前后指纹不一致或坐标越界 | 看预期 vs 实际节点摘要 |
| `STUCK` | 连续 N 步指纹不变 | 卡死信号，建议重试或人工接管 |

**发现与安全**：手机端用 `NsdManager` 注册 `_whalechan._tcp.`，伴侣端发现后 resolve 出 `host:port`；
也支持手动输入。**不做 TLS**（局域网侧载），但 `hello` 必须带 token，错误即断开（`4001`）。
token 在设置页可见、可重新生成，重新生成后旧连接立即失效。

**失败模式**：
- mDNS 依赖同一网段与组播；AP 隔离 / 访客网络 / 部分路由器会搜不到，需手输 IP；
- 跨网络（非同一 Wi-Fi）需要中继（frp/VPN/自建 relay），**不在本次范围**；
- 服务端端口被占用 → 启动失败并在设置页提示；
- 无 TLS，token 与消息在局域网明文传输，不要在不信任的 Wi-Fi 上开启。

## 10.7 v0.2 新增验收用例

| # | 操作 | 预期 |
|---|---|---|
| 13 | 设 A 档、步数 3，执行需 5 步的任务 | 第 3 步后暂停，通知栏出现「继续/放弃」；点继续后继续跑 |
| 14 | 同上，等 10 分钟不点 | 按放弃结束，日志最后一条是 `pause` 相关 |
| 15 | 设 B 档、时间 1 分钟 | 1 分钟后结束，不因步数中断 |
| 16 | 设 C 档但未勾选风险确认 | 启动即被拦下并提示 |
| 17 | 设 C 档并勾选，跑长任务 | 只有手动停止/卡死才会结束；日志表不超过 5000 条 |
| 18 | 开启 FULL_TRUST | 点「确认支付」类按钮不再弹确认，但日志有记录 |
| 19 | 关闭 FULL_TRUST、关闭凭据开关，尝试向密码框输入 | 被拒，日志 `denied` |
| 20 | 开启凭据开关后再试 | 允许写入（自担风险） |
| 21 | 同一目标、同一开局界面再跑一次 | 提示“先试试回放”，日志能看到回放步骤 |
| 22 | 打开 WebSocket 服务端，伴侣端输入 IP+错误 token | 连接被断开，提示 BAD_TOKEN |
| 23 | 用正确 token 连接，发 `TASK` 消息 | 手机端开始执行，伴侣端实时收到 step 时间线 |
| 24 | 手机端弹确认，伴侣端点“确认” | 手机端继续执行，日志记录 |
| 25 | 断网 30 秒 | 心跳中断，伴侣端显示断开并可重连 |

## 10.8 已知局限（v0.2 必须说清楚）

1. **WakeLock 与商店政策**：长时持有 `PARTIAL_WAKE_LOCK` 在 Google Play 的审核中属于高风险用法
   （平台逐年收紧对后台唤醒的管控，且政策会变）。本工程按**侧载分发**设计；若要上架，需要重新评估
   是否只保留“任务期间持有”，并准备好用途说明与演示视频。
2. **无障碍锁屏注入的厂商差异**：锁屏是否允许手势注入由系统与厂商安全策略决定。
   小米需要「锁屏显示」权限，华为/荣耀需要「受保护应用/手动管理」，OPPO/vivo 需要后台高耗电白名单；
   即使全部打开，部分系统安全窗口（支付确认、权限弹窗）仍会屏蔽无障碍点击，**代码无法绕过**。
3. **完全信任模式的实际风险**：`FULL_TRUST` 下没有操作前拦截，误触支付、误授权限、误删数据都可能发生，
   且多数不可撤销。日志只能事后追溯，不能阻止。建议只在备用机/小号上使用。
4. **跨网络不在范围**：伴侣端与手机端必须同一局域网；mDNS 不跨网段，WebSocket 也没有做穿透。
   需要远程使用时，应在中间加一层中继（自建 frp/VPN/relay），这不属于本次实现。
5. **无持久化断点**：任务状态在内存里，进程被杀后无法从第 N 步恢复；无限模式尤其明显。
6. **日志膨胀**：环形清理是“写入时触发”，如果进程在两次清理之间被杀，极端情况下会多留一些记录。
7. **无 TLS**：局域网伴侣通道是明文 + token，token 泄露等于控制权泄露；不要在公共 Wi-Fi 开启。
8. **仍未做真机验证**：以上全部是编译/单测/lint 级别通过，运行时行为需要按 10.7 在真机上逐项确认。

---

# 11. v0.3.0：独立伴侣端 APK + 聊天式 UI + 图片/文件

> 版本 `0.3.0`（versionCode 3）：新增 `:protocol` 共享库与 `:companion` 独立应用。
> 手机端 APK 与伴侣端 APK 可以分别安装、分别升级；协议版本 `Protocol.VERSION = 1`。

## 11.1 模块拆分

```
whale-chan/
├── protocol/    Android Library：Protocol / CompanionClient / NsdDiscovery / Plog
├── app/         com.whalechan.agent      被操控端（无障碍 + 通知 + Agent + WS 服务端）
└── companion/   com.whalechan.companion  独立伴侣端（只做客户端）
```

- 共享层去掉了对 app 内部类（`StepLogEntity`、`ConfirmRequest`）的依赖：
  `Protocol.step(record: JSONObject)`、`Protocol.confirmRequest(id, action, reason, detail)`，
  由手机端负责把实体转成 JSON，伴侣端只消费。
- 手机端原来的 `net/Protocol.kt`、`net/CompanionClient.kt`、`net/NsdDiscovery.kt` 已删除，
  改为 `implementation(project(":protocol"))`；手机端内置的 `CompanionActivity` 保留作为调试客户端，
  完整 UI 在独立 APK 中。

## 11.2 聊天界面（贴近主流 IM）

| 元素 | 实现 |
|---|---|
| 顶部栏 | 鲸鱼娘头像 + 名称 + 连接状态（`tvStatus` 可点，打开连接框）；右侧菜单 |
| 消息行 | `RecyclerView` + `ChatAdapter`，三类 viewType：聊天 / 时间分隔 / 系统提示 |
| 气泡 | 左侧白气泡 + 头像（鲸鱼娘），右侧蓝气泡（我）；`bg_bubble_left/right` |
| 内容 | 文本 / 图片（最大 220dp，等比）/ 文件卡片（图标 + 名称 + 大小） |
| 元信息 | 气泡右下角时间；自己发的消息显示「· 已发送」 |
| 时间分隔 | 相邻消息间隔超过 5 分钟插入居中时间；跨天显示 `MM-dd HH:mm` |
| 输入栏 | 附件按钮 + 圆角输入框 + 「聊天/任务」切换 + 圆形发送按钮 |
| 连接 | 弹窗内可手输 `IP:端口` + 配对码，也可 mDNS 搜索；自动记住上次连接 |

适配器核心：

```kotlin
private fun bindChat(holder: ChatHolder, item: ChatItem) {
    val mine = /* Text/Image/File 的 mine 字段 */
    holder.rowRoot.gravity = if (mine) Gravity.END else Gravity.START
    holder.avatar.visibility = if (mine) View.GONE else View.VISIBLE
    holder.bubble.setBackgroundResource(if (mine) R.drawable.bg_bubble_right else R.drawable.bg_bubble_left)
    when (item) {
        is ChatItem.Text  -> { holder.text.visibility = VISIBLE; holder.text.text = item.text }
        is ChatItem.Image -> { holder.image.visibility = VISIBLE; loadImage(holder.image, item.path) }
        is ChatItem.File  -> { holder.fileRow.visibility = VISIBLE
                               holder.fileName.text = item.name
                               holder.fileSize.text = AttachmentCodec.humanSize(item.size) }
        else -> Unit
    }
    holder.meta.text = timeFormat.format(Date(item.ts)) + if (mine) " · 已发送" else ""
}
```

## 11.3 自定义聊天背景

- 入口：右上菜单 →「更换聊天背景」→ 系统相册（`ActivityResultContracts.GetContent("image/*")`）。
- 存储：图片复制进 `filesDir/background/chat_bg_<ts>.img`，路径写入 `SharedPreferences`；
  换图时删除旧文件，避免无限增长。**不申请相册权限**，走 SAF。
- 生效：`ivChatBackground`（`centerCrop`）叠加 `#26000000` 半透明遮罩保证文字可读。
- 恢复：菜单 →「恢复默认背景」→ 删除文件 + 清空偏好 + 回退到渐变背景。

```kotlin
fun setFrom(uri: Uri): File? {
    val target = File(File(context.filesDir, "background").apply { mkdirs() }, "chat_bg_${System.currentTimeMillis()}.img")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { src ->
            target.outputStream().use { src.copyTo(it) }
        } ?: return null
        current()?.delete()
        prefs.edit().putString(KEY_PATH, target.absolutePath).apply()
        target
    }.getOrNull()
}
```

## 11.4 图片 / 文件消息

**发送端（伴侣端）**：

| 类型 | 入口 | 处理 |
|---|---|---|
| 图片 | 附件按钮 → 发送图片 | 采样解码到 ≤1600px，JPEG 质量阶梯压缩，直到 ≤4MB |
| 文件 | 附件按钮 → 发送文件 | SAF `OpenDocument("*/*")`，超过 4MB 直接拒绝 |

图片会先存一份到 `filesDir/sent/` 用于本地气泡展示，再以 base64 发出：

```kotlin
val bytes = AttachmentCodec.compressImage(this, uri)          // 压缩
val local = File(File(filesDir, "sent").apply { mkdirs() }, name).apply { writeBytes(bytes) }
appendImage(local.absolutePath, name, mine = true)            // 本地立即显示
client.sendAttachment(msgId, Protocol.KIND_IMAGE, name, "image/jpeg",
                      bytes.size, Base64.encodeToString(bytes, Base64.NO_WRAP))
```

**协议**：

```json
C→S {"type":"attachment","msgId":"uuid","kind":"image","name":"IMG_1730000000.jpg",
     "mime":"image/jpeg","size":482313,"data":"<base64>","ts":1730000000000}
```

**接收端（手机）**：`WsHub.handleAttachment()` 校验大小 → 解码 → 存到 `files/received/`
→ 交给 `AttachmentBus` → 广播一条 `notice` 让伴侣端看到确认。

Agent 的下一轮任务会读到附件说明：

```
【用户刚发来的附件】
类型=image，名称=IMG_...jpg，MIME=image/jpeg，本地路径=/data/user/0/.../received/...jpg
```

- 若开启「视觉模型」，图片的 base64 会作为该轮视觉输入（只发一次，`takeImageForModel()` 后清空，避免每步重复计费）；
- 文件只提供路径与元信息，LLM 无法直接读取二进制内容（这是刻意限制，避免把任意文件塞进上下文）。

**帧上限**：Java-WebSocket 默认单帧上限很小，4MB 附件 base64 后约 5.4MB。
两端都用 `Draft_6455(emptyList(), Protocol.MAX_WS_TEXT_BYTES)`（8MB）构造，否则大附件会被框架静默拒绝。

## 11.5 验收用例（v0.3 新增）

| # | 操作 | 预期 |
|---|---|---|
| 26 | 手机装 app、平板装 companion，同一 Wi-Fi | 伴侣端搜索到 `WhaleChan-*` 或手输 IP 连接成功 |
| 27 | 伴侣端发「在吗」（聊天模式） | 手机端只聊天，回复出现在左侧气泡 |
| 28 | 切到「任务」再发「打开 QQ 给张三发晚安」 | 手机端进入任务，状态栏显示 ACTING 与步数 |
| 29 | 伴侣端发一张图片 | 右侧出现图片气泡；手机端 `files/received/` 有文件并回 notice |
| 30 | 开启视觉模型后发图并下任务 | 下一轮 LLM 请求带该图；日志无异常 |
| 31 | 发一个 10MB 文件 | 提示「文件太大（上限 4MB）」，不发送 |
| 32 | 菜单换背景 | 聊天区背景变为所选图片；杀进程重进仍保留 |
| 33 | 恢复默认背景 | 回到渐变背景，私有目录旧图被删除 |
| 34 | 断开 Wi-Fi | 状态变「未连接」，重连后继续收发 |
| 35 | 手机端弹确认 | 伴侣端弹窗；点「确认」后手机端继续执行 |

## 11.6 已知局限（v0.3 新增）

1. **没有聊天记录持久化**：消息只存在内存，伴侣端重启后清空；手机端也不保存历史。需要的话应加 Room 表。
2. **附件走 base64 单帧**：4MB 上限是为了控制内存峰值；大文件/视频需要分片协议或 HTTP 上传，未实现。
3. **图片压缩在主线程之外但仍是同步压缩**：超大图（>50MP）可能占用较多内存，已用采样解码缓解。
4. **伴侣端没有 TLS**：局域网明文，token 泄露即控制权泄露；不要在公共 Wi-Fi 使用。
5. **跨网络不可用**：mDNS 与直连都要求同一网段，中继不在范围内。
6. **两个 APK 需分别安装**：手机端仍需手动授予无障碍/通知/电池优化；伴侣端只需要网络权限。
7. **仍未真机验证**：双 APK 的安装、连接、附件收发都只在编译/lint 层验证过。

---

# 12. v0.3.1：修复「无限模式跨应用任务过早中断」

> 现象：无限模式 + 完全信任，下发「干你想干的事情吧」，模型打开 QQ 后立刻出现
> 「没做成：已停下，听你的。」，第 2 步即结束。

## 12.1 根因（不是“界面没变化”熔断）

「已停下，听你的。」只可能来自 `AgentEngine.start()` 的 `CancellationException` 分支，
而不是卡死检测。真正的原因是启动 QQ 时前台服务被系统回收，`KeepAliveService.onDestroy()`
里调用了 `AgentRuntime.engine.cancel("服务已停止")`，把正在跑的任务直接取消了。

冷启动慢确实存在，但它是**第二层问题**：即便不取消，`open_app` 立即返回成功、下一轮抓到
的还是白屏/上一个应用，也会被当成“没变化”。两个问题一起修。

## 12.2 修复一：`awaitAppLaunch` 冷启动等待

`ActionExecutor.openApp()` 改为 suspend：`launch → 等窗口 → 再等 1s → 复核前台包名`，
任何一步不满足都返回带 `APP_LAUNCH_TIMEOUT` 的失败，而不是假装成功。

```kotlin
// AccessibilityService
suspend fun awaitAppLaunch(packageName: String, timeoutMs: Long = 5_000L): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root != null && root.packageName?.toString() == packageName) return true
        if (root == null && lastPackage == packageName) { delay(120); /* 再查一次 */ }
        delay(100)
    }
    return false
}

// ActionExecutor
private suspend fun openApp(service: WhaleAccessibilityService, decision: AgentDecision): ExecResult {
    val pkg = resolveAppPackage(decision) ?: return ExecResult.fail("不知道要打开哪个应用", NODE_NOT_FOUND)
    return when (val r = AppsLauncher.launch(context, pkg)) {
        is AppsLauncher.Result.Ok -> {
            if (!service.awaitAppLaunch(pkg, APP_LAUNCH_TIMEOUT_MS)) {
                return ExecResult.fail("启动「$label」超时：5s 内没有进入前台", APP_LAUNCH_TIMEOUT)
            }
            delay(APP_LAUNCH_SETTLE_MS)            // 等开屏广告 / 骨架屏
            if (service.currentPackage() != pkg) {
                return ExecResult.fail("启动后界面又切走：当前=${service.currentPackage()}", SCREEN_STATE_DRIFT)
            }
            ExecResult.ok("已打开「$label」，界面就绪（节点 ${service.currentNodeCount()} 个）")
        }
        // NeedUserGesture / Failed …
    }
}
```

## 12.3 修复二：只由 text/desc/className 构成的指纹 + 双阈值

```kotlin
// UiSnapshot.signature()
fun signature(): String {
    val sb = StringBuilder(nodes.size * 12)
    nodes.forEach { n ->
        sb.append(n.text).append('\u0001')
          .append(n.desc).append('\u0001')
          .append(n.className).append('\u0002')
    }
    return "$packageName:${nodes.size}:${sb.toString().hashCode()}"
}
```

- 不再包含 bounds：视频播放、下拉回弹、布局抖动不会误判；
- 动态文本（评论、标题、时间）仍会让指纹变化；
- 卡死判定同时看**步数（默认 15）**和**时间窗口（默认 30s）**，任一触发才停：

```kotlin
if (afterFp == lastAfterFingerprint) {
    unchangedStreak++
    if (firstUnchangedAt == 0L) firstUnchangedAt = SystemClock.elapsedRealtime()
    val byCount = unchangedStreak >= config.stallLimit
    val byTime = SystemClock.elapsedRealtime() - firstUnchangedAt >= config.stallWindowSeconds * 1000L
    if (byCount || byTime) {
        val why = if (byCount) "连续 ${config.stallLimit} 步界面指纹未变化"
                  else "界面连续 ${config.stallWindowSeconds} 秒没有文本/节点变化"
        emit(AgentEvent.Stuck(taskId, afterFp, why))
        appendLog(..., ErrorCategory.STUCK, why, ...)
        outcome = false to Persona.failureLine(ErrorCategory.STUCK, why)
        break
    }
} else { unchangedStreak = 0; firstUnchangedAt = 0L }
```

## 12.4 修复三：失败不再 break，而是 Re-prompt

只有三种退出：用户按「停」、模型 `done/finish`、真正的卡死保护。
UI 交互失败一律写入下一轮 prompt，同类错误最多提示重试 3 次：

```kotlin
if (result.ok) {
    failureStreak = 0
    errorRetries.clear()
} else {
    lastErrorCategory = result.category ?: UNKNOWN
    lastErrorDetail = result.detail
    val retries = (errorRetries[lastErrorCategory.name] ?: 0) + 1
    errorRetries[lastErrorCategory.name] = retries
    failureStreak++
    lastActionResult = if (retries >= 3) {
        "上一步失败（同类错误第 $retries 次）：${result.detail}。必须换一种完全不同的策略。"
    } else {
        "上一步失败（同类错误第 $retries 次）：${result.detail}。请修正后重试。"
    }

    if (policy != UNLIMITED && failureStreak >= 6) { /* 非无限模式按原阈值停 */ }
    if (failureStreak >= 20) { /* 无限模式的真正守卫，才算卡死 */ }
}
```

无限模式不再因 `failureStreak >= 6`、`UNKNOWN >= 4`、`FORBIDDEN >= 4` 退出；
这些分支只在非无限模式生效。

## 12.5 修复四：AUTONOMOUS_MODE + 兜底滑动

开放式目标（包含“随便 / 干你想 / 自己玩 / 打发时间”等）且无限模式时，系统提示词注入：

```kotlin
val autonomous = policy == UNLIMITED && isAutonomousGoal(goal)
Persona.agentSystemPrompt(memory, extra, fullTrust, autonomous)
```

```kotlin
val AUTONOMOUS_MODE = """
【AUTONOMOUS_MODE】
Master 已将控制权完全交给你。你必须在应用内自主生成后续计划，不要轻易返回 done/fail/ask_user。
打开 QQ 后去点聊天列表、看空间动态；打开抖音后不断上滑；打开 B 站后点推荐视频。
每一步都基于【当前界面】真实存在的节点；失败就换节点/换方式，不要重复同一个失败动作。
""".trimIndent()
```

模型仍返回非法动作、`fail` 或 `ask_user` 时，执行器自动做一次安全兜底 `swipe up`，
把结果写进日志并继续循环（最多 5 次，超过才按卡死退出）：

```kotlin
if (policy == UNLIMITED && fallbackCount < MAX_FALLBACKS) {
    if (fallbackSwipe(taskId, step, goal, "模型返回 fail：$summary", snapshot)) {
        fallbackCount++
        continue
    }
}
```

## 12.6 修复五：人格化错误播报 + 取消路径

失败/超时不再只给一句「没做成」，统一走 `Persona.failureLine(category, detail)`：

```kotlin
fun failureLine(category: ErrorCategory, detail: String): String {
    val human = when (category) {
        APP_LAUNCH_TIMEOUT -> "唔…这破网还是手机卡了？QQ 的大门打不开啦…"
        NODE_NOT_FOUND     -> "气死鱼了，那个按钮怎么不见了！…"
        GESTURE_TIMEOUT    -> "手滑了啦…系统不让我点…"
        STUCK              -> "……哼，卡住了啦。"
        // …
    }
    return "$human（${category.name}: ${detail.take(160)}）"
}
```

另外：

- `KeepAliveService.onDestroy()` **不再** `engine.cancel()`——服务被系统回收不会杀掉正在跑的任务；
- 真的被外部中断时，文案区分「用户停止」和「被系统中断」，并记录日志；
- 对话页「允许我动手」**默认开启**（偏好持久化），符合“默认允许她动手”的建议。

## 12.7 新增验收用例

| # | 操作 | 预期 |
|---|---|---|
| 36 | 无限模式 + 完全信任，发「干你想干的事情吧」 | 打开 QQ 后不会第 2 步就结束；继续在 QQ 内探索 |
| 37 | 冷启动 QQ（强制停止后） | 等到 QQ 进入前台 + 1s 后才进入下一轮；日志 `open_app` 成功且节点数 > 0 |
| 38 | 把 QQ 停在开屏广告 >5s | 报 `APP_LAUNCH_TIMEOUT`，该错误进入下一轮 prompt，模型改策略 |
| 39 | 抖音播放中连续滑动 | 不会因为“画面在动/指纹不变”误判卡死 |
| 40 | 连续点击不存在的节点 | 不退出；同类错误第 3 次后提示换策略；第 20 步才判定卡死 |
| 41 | 模型输出 `{"action":"stop"}` 或空 JSON | 无限模式下自动 `swipe up` 继续，聊天流出现兜底提示 |
| 42 | 停止常驻服务/杀后台再回前台 | 正在跑的任务不再因服务销毁被取消（进程未死时） |
| 43 | 点「停」 | 立即结束，文案为「已停下，听你的。」 |
| 44 | 断网/模型超时 | 报 `STUCK`/`LLM_INVALID_ACTION` 的人格化文案，括号内带真实原因 |

---

# 13. 开源与许可

## 13.1 许可证

本项目以 **MIT License** 发布，详见 [LICENSE](LICENSE)。第三方依赖见
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 13.2 仓库导航

| 文件 | 内容 |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | 开发环境、构建命令、代码约定、PR 硬规则 |
| [SECURITY.md](SECURITY.md) | 威胁模型、危险开关说明、漏洞私密报告方式 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) | 依赖与许可证清单 |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | 发布到 GitHub 的完整操作清单（建仓、推送、Release、分支保护） |
| [docs/PRIVACY.md](docs/PRIVACY.md) | 数据处理与隐私声明 |
| [.github/workflows/android.yml](.github/workflows/android.yml) | CI：单测 + lint + 双 APK 构建 |

## 13.3 获取 APK

- **Releases** 页面下载 `app-debug.apk` 与 `companion-debug.apk`；
- 或从 Actions 运行的 artifact `whale-chan-debug-apks` 下载；
- 仓库不提交 APK（`.gitignore` 已排除），避免仓库膨胀与二进制 diff。

> APK 是 **debug 签名**，只适合侧载试用；请勿用于分发或商业场景。

## 13.4 免责声明

- 本项目仅供**个人设备上的学习与研究**；
- 自动化操作可能违反 QQ、抖音等平台的用户协议，账号风险由使用者自行承担；
- 无障碍权限、通知使用权、`FULL_TRUST` 完全信任模式、代填凭据等能力风险较大，
  请在使用前阅读 [SECURITY.md](SECURITY.md)；
- 作者不对因使用本软件造成的任何直接或间接损失负责（MIT 的 “AS IS” 条款）。

## 13.5 参与贡献

欢迎 Issue / PR。请先读 [CONTRIBUTING.md](CONTRIBUTING.md) 的硬规则：
不加入隐蔽或对抗检测能力、不绕过 Android 权限、不破坏隐私默认值、不提交密钥。

## 13.6 发布前替换占位符

仓库里的徽章与 Issue 模板包含 `OWNER/REPO` 占位符，首次发布时替换为你的 GitHub 用户名与仓库名：

- `README.md` 顶部 4 个徽章；
- `.github/ISSUE_TEMPLATE/config.yml` 的两个链接。

也可直接运行 `docs/PUBLISHING.md` 第 0 节的检查命令确认没有遗漏。
>>>>>>> e021c90 (chore: initial public release v0.3.1)
#   w h a l e - c h a n  
 