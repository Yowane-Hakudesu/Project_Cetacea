# 📱 鲸鱼娘·遥控（WhaleChan Remote）— v0.4.0

![Android CI](https://img.shields.io/badge/Android%20CI-passing-brightgreen)
![License](https://img.shields.io/badge/License-MIT-blue)
![minSdk](https://img.shields.io/badge/minSdk-26-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple)
![version](https://img.shields.io/badge/version-0.4.0-blueviolet)

> “Master，我在另一台手机上也能使唤你了哦～”

**鲸鱼娘·遥控** 是 **鲸鱼娘** 的独立遥控端：用另一台手机或平板跟她聊天、派任务、
发图片和文件、看她在做什么、随时叫停。

它**不执行任何自动化**，只需要网络权限；真正干活的永远是手机端
[鲸鱼娘（WhaleChan Agent）](../app/README.md)。

| | 鲸鱼娘（手机端） | 鲸鱼娘·遥控（遥控端） |
| :--- | :--- | :--- |
| 安装包 | `app-debug.apk` | `companion-debug.apk` |
| 包名 | `com.whalechan.agent` | `com.whalechan.companion` |
| 角色 | 被操控端，执行任务 | 遥控端，发指令/看状态 |
| 权限 | 无障碍、通知监听、电池白名单 | 仅网络 |
| 是否必需 | 必需 | 可选 |

当前版本：**v0.4.0**（versionCode 5，协议 v2）

---

## ✨ 功能

- [x] **聊天式界面**：左右气泡、时间分隔、系统提示，接近主流 IM。
- [x] **两种模式**：聊天只聊天；任务模式发送 `COMMAND`，手机端直接 `startTask()`。
- [x] **实时状态**：`THINKING`（思考中）/ `EXECUTING_ACTION`（正在操作）/ `WAITING`（等待确认）/ `IDLE`（空闲），并显示当前步数。
- [x] **错误可见**：手机端每一步的 `errorCategory + errorDetail` 会以系统气泡显示，不再只看到“没做成”。
- [x] **图片 / 文件**：≤4 MB；图片自动压缩到 ≤1600px；发到手机端后交给 Agent 上下文。
- [x] **远程确认 / 叫停**：危险动作弹窗确认；随时停止当前任务。
- [x] **自定义聊天背景**：从相册选择，复制到私有目录，可恢复默认。
- [x] **两种连接**：
  - 公网中继（推荐，校园网/蜂窝网可用）；
  - 局域网直连（同一 Wi-Fi，低延迟）。

---

## 📲 安装与权限

1. 安装 `companion-debug.apk`（Android 8.0+）；
2. 首次打开允许通知权限（用于连接状态与后台提醒，可选）；
3. 其余不需要：**没有无障碍、没有通知监听、没有电池白名单**。

| 权限 | 用途 |
| :--- | :--- |
| `INTERNET` | 连接手机端 / 中继 |
| `ACCESS_NETWORK_STATE` | 判断网络是否可用 |
| `CHANGE_WIFI_MULTICAST_STATE` | 可选的局域网 mDNS 发现（直连模式用） |

---

## 🔌 连接方式

### 方式 A：公网中继（推荐）

适合校园网、公司网、蜂窝网，或两台设备不在同一网络。

**手机端（鲸鱼娘）先做：**

1. 设置页 ⑥ → 打开「公网中继」；
2. 填中继地址 `wss://你的服务器/ws` 与 `RELAY_TOKEN`；
3. 记下 6 位「中继配对码」。

**遥控端：**

1. 点顶部状态栏（「未连接 · 点这里设置」）；
2. 地址填 `wss://你的服务器/ws`；
3. 令牌填与服务端一致的 `RELAY_TOKEN`；
4. 配对码填手机端显示的 6 位；
5. 点「连接」。

连上后状态栏显示「已连接（中继）· 配对码 XXXXXX」。

> 中继部署见 [relay/README.md](../relay/README.md)。中继只转发，不落盘；
> 但没有 TLS 的 `ws://` 只适合本地测试，公网必须 `wss://`。

### 方式 B：局域网直连

适合两台设备连同一个 Wi-Fi、且路由器没有开 AP 隔离。

1. 手机端设置页 ⑥ 打开「WebSocket 服务端」，端口默认 `8787`；
2. 记下「地址」里的 `192.168.x.x:8787` 与「配对码」；
3. 遥控端地址填 `192.168.x.x:8787`，令牌填**手机配对码**，**中继配对码留空**；
4. 点「连接」（也可点「查找局域网设备」自动发现）。

---

## 🖥️ 界面说明

```text
┌─────────────────────────────────────┐
│ 🐋 已连接 · 思考中 · 第 3/∞ 步   ⋮ │  ← 状态栏：点击可重新连接
├─────────────────────────────────────┤
│  鲸鱼娘：晚安，别熬太晚       15:27 │  ← 左侧白气泡 + 头像
│                   好的，这就睡 15:28 │  ← 右侧蓝气泡（我）
│          ⚠️ NODE_NOT_FOUND：…        │  ← 系统气泡：真实错误
├─────────────────────────────────────┤
│ 📎 [ 跟鲸鱼娘说点什么… ]  [聊天] ➤ │
└─────────────────────────────────────┘
```

| 元素 | 说明 |
| :--- | :--- |
| 顶部状态 | 连接状态 + Agent 当前动作 + 步数；点击打开连接弹窗 |
| 右侧菜单 | 连接 / 更换背景 / 恢复默认背景 / 断开 |
| 回形针 | 发送图片或文件（≤4 MB） |
| 聊天 / 任务 | 切换发送意图：聊天 = 只聊天；任务 = `COMMAND` |
| 发送 | 任务模式下手机端立即执行，不等“允许我动手” |
| 弹窗 | 手机端需要确认时出现；可「确认执行 / 不要」 |

### 状态含义

| 状态 | 含义 |
| :--- | :--- |
| `THINKING` | 正在调 LLM 规划下一步 |
| `EXECUTING_ACTION` | 正在执行某个动作（点击/滑动/发送） |
| `WAITING` | 等你确认，或步数上限暂停 |
| `IDLE` | 空闲，没有任务在跑 |

> 状态旁边的 `detail` 会显示最近一步的动作或错误摘要。

### 图片 / 文件

- 图片：相册选择 → 采样到 ≤1600px → JPEG 压缩 → base64 发送；
- 文件：系统文件选择器，支持任意类型；
- 超过 4 MB 直接拒绝（协议单帧上限 8 MB）；
- 手机端会保存到 `files/received/`，并在下一轮任务里作为附件上下文；
- 开启视觉模型时，图片会作为该轮视觉输入（只发一次）。

### 聊天背景

右上菜单 →「更换聊天背景」→ 选图；图片复制到应用私有目录并持久化。
「恢复默认背景」删除旧图并回到渐变背景。不需要相册权限（走 SAF）。

---

## 📡 协议（协议 v2）

### 注册（中继）

```json
C→S {"type":"register","role":"companion","deviceId":"...","pairCode":"K7R8U8","token":"..."}
S→C {"type":"registered","ok":true,"role":"companion","pairCode":"K7R8U8"}
```

### 发任务 / 聊天

```json
{"type":"toPhone","payload":{"type":"COMMAND","text":"去QQ找张三说晚安","id":"uuid"}}
{"type":"toPhone","payload":{"type":"chat","mode":"CHAT_ONLY","text":"在吗"}}
```

### 手机回传

```json
{"type":"fromPhone","payload":{"type":"state","status":"THINKING","step":3,"maxSteps":0,"detail":"..."}}
{"type":"fromPhone","payload":{"type":"step","record":{"stepIndex":3,"result":"fail","errorCategory":"NODE_NOT_FOUND","errorDetail":"..."}}}
{"type":"fromPhone","payload":{"type":"task_end","ok":false,"summary":"..."}}
```

### 附件

```json
{"type":"toPhone","payload":{"type":"attachment","msgId":"...","kind":"image","name":"IMG_1.jpg","mime":"image/jpeg","size":482313,"data":"<base64>"}}
```

---

## 🐛 连不上怎么办

| 现象 | 原因 | 处理 |
| :--- | :--- | :--- |
| `Host unreachable` | 两台设备不在同一网络，或填了蜂窝网 `10.x` | 改用中继；直连要同一 Wi-Fi + `192.168.x.x` |
| `Connection refused` | 手机端没开 WebSocket 服务端，或端口错 | 手机端设置页 ⑥ 打开服务端 |
| `timeout` | 防火墙 / AP 隔离 / 跨网段 | 用中继，或手机开热点（填热点网关） |
| `NO_PHONE` | 中继连上了，但手机端不在房间 | 确认手机端「中继：ONLINE」、配对码一致 |
| `BAD_TOKEN` | 中继令牌错 | 两端填同一个 `RELAY_TOKEN` |
| 连上但没反应 | 手机端 Agent 正忙 | 指令会进 `PendingInbox`，当前任务结束自动处理 |
| 图片发送失败 | 超过 4 MB | 压缩或换小文件 |

### 校园网提示

校园网常开 **AP 隔离**，同一 SSID 下设备也互不可达。优先用公网中继；
临时方案：手机开热点，遥控端连热点，地址填热点网关（通常 `192.168.43.1:8787`）。

---

## 🔨 从源码构建

```bash
cd whale-chan
./gradlew :companion:assembleDebug     # companion-debug.apk
./gradlew :companion:lintDebug         # 0 errors
```

产物：`companion/build/outputs/apk/debug/companion-debug.apk`（约 5.6 MB）。
依赖 `:protocol` 模块（共享 Protocol / CompanionClient / NsdDiscovery）。

---

## 🔐 安全与限制

- 遥控端没有 TLS 终结能力：直连是明文 `ws://`，只在可信局域网使用；
- 中继模式必须用 `wss://`，并设置 `RELAY_TOKEN`；配对码只负责路由，不能替代令牌；
- 消息不持久化：进程重启后聊天记录清空（手机端也不保存历史）；
- 附件走 base64 单帧，4 MB 是内存安全线；
- 没有多设备会话管理：同一配对码下多个遥控端会同时收到状态；
- 本项目仅供个人设备学习研究，MIT “AS IS”。

## License: MIT
## Built with ❤️ by [Yowane-Hakudesu]
