# 安全策略 / Security Policy

## 支持版本

| 版本 | 支持 |
|---|---|
| 0.3.x | ✅ |
| ≤ 0.2.x | ❌ 请升级 |

## 报告安全问题

**不要开公开 issue。** 请使用 GitHub 的
[Private vulnerability reporting](https://docs.github.com/zh/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
（仓库 → Security → Report a vulnerability），或直接联系维护者。

请在报告里说明：影响版本、复现步骤、设备/Android 版本、是否需要用户已授予无障碍权限。
我们会在 7 天内确认，并在修复后致谢（除非你要求匿名）。

## 这个项目是什么（威胁模型）

这是一个**运行在用户自己手机上的端侧 LLM 代理**：它通过无障碍服务读屏、点击、滑动，
通过通知监听读取 QQ 消息，通过 WebSocket 接受局域网内伴侣端的指令。

它**不是**恶意软件，也**没有**下面这些能力（我们明确拒绝合入）：

- 隐藏自身、伪装成其他应用、规避系统权限模型；
- 对抗平台自动化检测（随机轨迹伪装、篡改包名、Hook/Xposed）；
- 在用户不知情时把屏幕/通知发到第三方端点（只会发到用户自己配置的 LLM 端点）；
- 后台静默安装应用、提权、读取其他应用私有数据。

## 需要用户知情的风险

1. **无障碍权限范围很大**：可读取当前界面的节点文本。请只在自己的设备上使用。
2. **通知使用权覆盖全部应用**：代码只处理 QQ，但权限本身是全局的。
3. **`FULL_TRUST` 完全信任模式**：会关闭所有操作前确认（支付、授权、删除、系统设置）。
   误操作不可撤销，只保留事后日志。
4. **`allowCredentialEntry` 允许代填凭据**：默认关闭，即使 `FULL_TRUST` 也不会自动打开。
5. **API Key**：使用 Android Keystore AES-GCM 加密存储，但任何能在此应用进程内执行代码的人
   都能调用解密接口；请勿在已 root 且不可信的设备上使用。
6. **局域网伴侣端无 TLS**：token 与消息在局域网内明文传输，公共 Wi-Fi 下不要开启。

## 调试签名

仓库内 `keystore/debug.keystore` 是公开的 Android 调试签名（密码 `android`），
**只能用于 debug 构建**。发布请自建 release keystore，且不要提交到仓库。

## 依赖与供应链

依赖版本固定在 `app/build.gradle.kts` / `protocol/build.gradle.kts` / `companion/build.gradle.kts`。
欢迎提交依赖升级 PR（需通过 CI）。
