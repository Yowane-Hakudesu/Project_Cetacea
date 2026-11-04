# 贡献指南 / Contributing

感谢你愿意一起把「鲸鱼娘」做得更好。提交前请先读一遍下面的硬规则。

## 环境

| 组件 | 版本 |
|---|---|
| JDK | 17–21（CI 用 21） |
| Gradle | 8.10.2（wrapper 自带） |
| Android SDK Platform | android-35 |
| Build Tools | 35.0.0 |
| AGP / Kotlin | 8.7.3 / 2.0.21 |

首次构建前创建 `local.properties`（不要提交）：

```properties
sdk.dir=/path/to/Android/Sdk
```

或在 Android Studio 里打开工程自动生成。

## 构建与自检

```bash
./gradlew :app:assembleDebug          # 手机端
./gradlew :companion:assembleDebug    # 独立伴侣端
./gradlew :app:testDebugUnitTest      # JVM 单测
./gradlew :app:lintDebug :companion:lintDebug
```

- 主分支要求：单测通过、lint 0 error、两个 APK 都能构建；
- 改 Agent 循环 / 执行器 / 安全策略时，请在 PR 描述里写清楚回归步骤；
- 涉及真机行为的改动（无障碍、通知、前台服务）请注明测试机型与 Android 版本。

## 工程结构

```
protocol/   两端共享：JSON 协议、WebSocket 客户端、mDNS 发现
app/        被操控端：无障碍、通知监听、Agent、Room 日志、WebSocket 服务端
companion/  独立伴侣端：聊天 UI、背景、图片/文件发送
docs/       规格与发布文档
```

## 代码约定

- Kotlin，4 空格缩进；公共 API 写 KDoc，复杂决策写清楚“为什么”；
- 用户可见文案用中文；协议字段用英文；
- 新增依赖需说明用途与许可证，并更新 `THIRD_PARTY_LICENSES.md`；
- 新增权限必须在 README 的权限表里补充“用途 / 缺失后果”。

## 硬规则（违反会被直接关闭）

1. **不加入任何隐蔽/对抗能力**：不隐藏应用、不伪装系统弹窗、不规避 Android 权限模型、
   不做对抗平台自动化检测的“反检测”逻辑。
2. **不破坏隐私默认值**：不把屏幕、通知、聊天内容发送到用户未配置的端点；不新增遥测。
3. **不提交密钥**：API Key、release keystore、个人 token、`local.properties` 一律不要提交。
4. **安全默认**：涉及支付、权限、删除、凭据输入的新能力，默认必须需要确认或默认关闭。
5. **可观测**：新增动作必须写结构化日志（`StepLogEntity`），失败要带 `ErrorCategory`。

## 提交 PR

1. Fork → 新建分支 `feat/xxx` 或 `fix/xxx`；
2. 提交信息建议：`fix(agent): 冷启动时等待目标包进入前台`；
3. PR 模板会要求填写：改动点、复现/验证步骤、风险与回滚方式；
4. CI 通过后由维护者 review。

## 报告 Bug

请使用 Issue 模板，附上：
- 版本号（`versionName`）、机型、Android 版本；
- 复现步骤、预期 vs 实际；
- 相关日志（设置页/伴侣端时间线里的 `errorCategory` 与 `errorDetail`，**打码个人信息**）。
