# 发布到 GitHub（维护者手册）

本文件是仓库发布的操作清单。仓库已经包含：`LICENSE`、`SECURITY.md`、`CONTRIBUTING.md`、
`CHANGELOG.md`、`THIRD_PARTY_LICENSES.md`、`.gitignore`、`.gitattributes`、
`.github/workflows/android.yml`、Issue/PR 模板。下面只讲 GitHub 上的操作。

---

## 0. 发布前 60 秒自查

```bash
cd whale-chan
git status --short          # 不应出现 local.properties / build/ / .gradle-home / *.apk
grep -rn "sk-\|api_key\|Bearer " --include="*.kt" --include="*.md" . | head
```

- 确认没有 API Key、个人 token、release keystore；
- `local.properties` 必须被忽略（本仓库已配置）；
- `keystore/debug.keystore` 是公开调试签名，提交无妨，但 `SECURITY.md` 已说明仅限 debug。

---

## 1. 创建仓库

### 方式 A：网页（推荐第一次用）

1. 打开 <https://github.com/new>；
2. Repository name：`whale-chan`；
3. Description：`端侧 LLM 手机代理：无障碍自动化 + 通知监听 + 独立伴侣端（Kotlin/Android）`；
4. 选择 **Public**；
5. **不要**勾选 “Add a README / .gitignore / license”——仓库里已经有，勾了会冲突；
6. Create repository。

### 方式 B：GitHub CLI

```bash
gh auth login
gh repo create whale-chan --public --source=. --remote=origin --push
```

### 方式 B 之后：替换占位符

仓库里有两处 `OWNER/REPO` 占位符，需要替换成你的用户名与仓库名：

- `README.md` 顶部的 4 个徽章 URL；
- `.github/ISSUE_TEMPLATE/config.yml` 里的安全报告与文档链接。

```bash
grep -rn "OWNER/REPO" README.md .github/
```

把这 6 处批量替换即可（例如 `OWNER` → `你的用户名`，`REPO` → `whale-chan`）。

---

## 2. 首次推送

如果本机还没配 Git 身份：

```bash
git config --global user.name  "你的名字"
git config --global user.email "你的邮箱"
```

```bash
cd whale-chan
git init
git add .
git status                 # 再确认一次没有敏感文件
git commit -m "chore: initial public release v0.4.0"
git branch -M main
git remote add origin https://github.com/<你的用户名>/whale-chan.git
git push -u origin main
```

### 认证方式（GitHub 不接受账号密码）

**HTTPS + PAT（简单）**

1. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens；
2. Repository access 选 `whale-chan`；Permissions：`Contents: Read and write`（如需改 Actions 再加 `Workflows`）；
3. `git push` 时用户名填 GitHub 用户名，密码粘贴 token；
4. 记住凭据：`git config --global credential.helper manager`（Git for Windows 自带）。

**SSH（长期推荐）**

```bash
ssh-keygen -t ed25519 -C "你的邮箱"
# 把 ~/.ssh/id_ed25519.pub 贴到 GitHub → Settings → SSH and GPG keys
ssh -T git@github.com
git remote set-url origin git@github.com:<你的用户名>/whale-chan.git
```

---

## 3. 打标签与发布 Release

APK 不要提交进 Git（`.gitignore` 已排除 `*.apk`），放 Release 附件：

```bash
git tag -a v0.4.0 -m "v0.4.0: 修复无限模式跨应用任务过早中断"
git push origin v0.4.0
```

GitHub → 仓库右侧 **Releases** → **Draft a new release**：

1. Choose a tag：`v0.4.0`；
2. Title：`v0.4.0`；
3. 描述：从 `CHANGELOG.md` 粘贴对应段落；
4. 附件上传：
   - `app/build/outputs/apk/debug/app-debug.apk`
   - `companion/build/outputs/apk/debug/companion-debug.apk`
5. Publish release。

> 建议同时说明：APK 为 debug 签名、需侧载、需手动授予无障碍/通知/电池优化，
> 且仅供个人设备使用。详细权限见 README。

---

## 4. 仓库设置

- **About**（右上齿轮）：
  - Description 同上；
  - Website 可留空；
  - Topics：`android` `kotlin` `accessibility-service` `llm` `ai-agent` `openai` `automation` `websocket` `room` `side-load`。
- **Features**：Issues ✅、Discussions（可选）、Wiki ❌、Projects（可选）。
- **Branches → Branch protection rules**（main）：
  - Require a pull request before merging；
  - Require status checks：`Build & Test`；
  - Do not allow force pushes。
- **Actions → General**：Workflow permissions 选 `Read repository contents`（与本仓库 `permissions: contents: read` 一致）。
- **Security**：
  - Private vulnerability reporting ✅；
  - Dependabot alerts ✅；
  - Dependabot security updates ✅；
  - Secret scanning（公开仓库默认开启）。

---

## 5. CI 说明

`.github/workflows/android.yml` 在 push / PR 时执行：

1. JDK 21 + Android SDK 35 + Build Tools 35；
2. `gradle :app:testDebugUnitTest`；
3. `gradle :app:lintDebug :companion:lintDebug`；
4. 构建两个 debug APK；
5. 上传 artifact：`whale-chan-debug-apks`（Actions → 某次运行 → Artifacts）。

首次运行会下载 SDK/依赖，约 5–10 分钟；`gradle-version` 固定 8.10.2，不依赖 wrapper 的下载地址。

---

## 6. 中国大陆网络提示

- `git push` 慢：可临时 `git config --global http.proxy http://127.0.0.1:7890`（按你的代理端口改），
  完成后 `git config --global --unset http.proxy`；
- `./gradlew` 默认走腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties` 注释里也给了官方源）；
- Release 上传大文件慢时，可先只推 tag，再用网页上传 APK。

---

## 7. 后续维护

- 版本号：`app/build.gradle.kts`、`companion/build.gradle.kts` 的 `versionCode/versionName`，
  以及 `protocol/.../Protocol.kt` 的 `APP_VERSION` 三处保持一致；
- 每次发版更新 `CHANGELOG.md` 并打 `vX.Y.Z` tag；
- 依赖升级走 PR，CI 必须绿；
- 收到安全报告走 Security Advisory，修复后再公开。

---

## 8. 开源合规 checklist

- [x] MIT `LICENSE`（把版权人改成你的名字/组织，如果不想用 “Whale Chan Contributors”）；
- [x] 第三方依赖清单 `THIRD_PARTY_LICENSES.md`；
- [x] 隐私与权限边界写在 `README.md` 与 `SECURITY.md`；
- [x] 明确“仅供个人设备、遵守各平台用户协议、不得用于骚扰/刷量/绕过风控”；
- [x] 不含逆向、Hook、对抗检测代码；
- [x] 不含 API Key、个人路径、聊天记录、截图。
