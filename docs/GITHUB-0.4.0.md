# 把 GitHub 仓库主页更新到 v0.4.0（实操教程）

> 适用仓库：<https://github.com/Yowane-Hakudesu/Project_Cetacea>
> 本地路径：`E:\DSproject2\whale-chan`
> 目标：主页 README、About、Release 说明都变成 v0.4.0，并把 v0.4.0 代码真正推上去。

---

## 0. 先看清现状（很重要）

在动手前，先确认三件事。本仓库当前的真实状态是：

| 项目 | 现状 |
| :--- | :--- |
| 远程仓库 | 已从 `whale-chan` 改名为 **`Project_Cetacea`**（旧地址会 301 跳转） |
| 本地 HEAD | `0b4d353`（v0.3.1 初始提交） |
| 远程 main | `9a402c0`，比本地多 5 个提交（改 README、加测试文件、传 assets） |
| 本地工作区 | v0.4.0 代码**还没提交**（42+ 个文件改动/新增） |
| v0.4.0 标签 | **远程已存在**，指向 `9a402c0`（只是 README 提交，不含代码） |
| v0.4.0 Release | **已发布**，带 3 个附件：`0.4.0app-debug.apk`、`0.4.0companion-debug.apk`、`0.4.0README.md` |
| 主页 README | 仍是 v0.3.1 文案 |

先跑：

```powershell
cd E:\DSproject2\whale-chan
git status --short
git log --oneline -3
git remote -v
```

### 0.1 把远程地址改成新名字

```powershell
git remote set-url origin https://github.com/Yowane-Hakudesu/Project_Cetacea.git
git remote -v
```

> 不改也能用（GitHub 会跳转），但推送时容易弹旧地址的凭据，建议改掉。

### 0.2 清理临时文件

工作区里有几份重复的 README，直接提交会让主页很乱：

```powershell
# 如果 app/0.4.0README.md 还在，它只是 app/README.md 的副本，改名回去
if (Test-Path app\0.4.0README.md) { Move-Item app\0.4.0README.md app\README.md -Force }

# 这两个是临时草稿，删除
Remove-Item README_new.md, README_v01_style.md -Force
```

保留这些（有用）：

- `docs/v0.2.0-README.md`、`docs/v0.3.1-README.md`、`docs/companion-v0.3.0-README.md`：历史版本存档；
- `docs/icons/`：图标与社交预览素材；
- `app/README.md`、`companion/README.md`：v0.4.0 两份模块手册。

### 0.3 敏感信息自查

```powershell
git status --short | Select-String "local.properties|build/|\.apk$|\.aab$|release\.keystore"
```

应当没有任何输出。`keystore/debug.keystore` 是公开调试签名，可以提交。

---

## 1. 提交 v0.4.0 代码（在独立分支上做）

不要直接在主分支上堆 42 个文件，先开分支，出问题好回退。

```powershell
git checkout -b release/0.4.0
git add -A
git status --short
```

确认暂存列表里没有 `local.properties`、`build/`、`*.apk`，然后：

```powershell
git commit -m "feat: v0.4.0 social throttling, entity memory, segmented sending, relay"
```

提交信息建议遵循 Conventional Commits；这是给外人看的第一眼。

---

## 2. 同步远程 main（本地落后 5 个提交）

```powershell
git fetch origin
git rebase origin/main
```

大概率只在 `README.md` 冲突：远程把 README 换成了 v0.3.1 落地页，你本地改的是长规格文档。

**解决方式**：先把「你提交里的 README」拿回来，下一步再统一换成新主页：

```powershell
git checkout --theirs README.md
git add README.md
git rebase --continue
```

> rebase 过程中：`--ours` 是远程 main，`--theirs` 是正在重放的你的提交。
> `git rebase --continue` 可能弹出编辑器让你确认提交信息，保存退出即可。

> 如果提示其他文件冲突（例如 `.github/ISSUE_TEMPLATE/config.yml`），
> 先 `git diff` 看内容，保留 v0.4.0 的改动，`git add <file>` 后继续。

rebase 完成后确认：

```powershell
git log --oneline -5
git status
```

应当看到你的提交在 `origin/main` 之上，工作区干净。

---

## 3. 用 v0.4.0 主页替换根 README

仓库主页 = 根 `README.md`。本次已经准备好一份落地页：

`docs/HOMEPAGE-0.4.0.md`

它包含：徽章、v0.4.0 新特性表、两个 App 对比、快速开始、中继连接、架构图、文档导航、FAQ。

```powershell
Copy-Item docs\HOMEPAGE-0.4.0.md README.md -Force
git add README.md docs/HOMEPAGE-0.4.0.md
git commit -m "docs: refresh homepage for v0.4.0"
```

### 3.1 检查主页里的链接

主页会链接到：

- `app/README.md`（手机端手册）
- `companion/README.md`（遥控端手册）
- `relay/README.md`（中继部署）

这三个文件必须一起推上去，否则 GitHub 上是 404。

```powershell
Test-Path app\README.md, companion\README.md, relay\README.md
```

### 3.2 徽章说明

- CI 徽章已写成真实仓库：
  `https://github.com/Yowane-Hakudesu/Project_Cetacea/actions/workflows/android.yml/badge.svg`
- License / minSdk / Kotlin / version 是 shields.io 静态徽章，不需要 token；
- 首次推送后 CI 跑完，CI 徽章才会变绿。

### 3.3 标题图片

主页引用了 `./assets/500b7eb8ba0a9cda48c82fefddef38b7.png`。
这个文件在远程 `main` 上已存在；rebase 后本地也会有。若本地没有，先从远程取：

```powershell
git checkout origin/main -- assets
```

---

## 4. 推送代码

### 方式 A：直接快进到 main（个人仓库最省事）

```powershell
git push origin release/0.4.0:main
```

因为分支是基于 `origin/main` 的，这是一次 fast-forward，不会覆盖别人的提交。

### 方式 B：走 PR（推荐以后多人协作时）

```powershell
git push -u origin release/0.4.0
```

然后到 GitHub 开 PR → 等 CI 绿 → Squash merge。

推送后确认：

```powershell
git fetch origin
git log --oneline origin/main -3
```

---

## 5. 处理已经存在的 v0.4.0 标签 / Release

这是本仓库最特殊的一点：**v0.4.0 标签和 Release 已经在代码推送前建好了**，
标签指向 `9a402c0`（只改了 README），但 Release 里的 APK 是真实的。

### 5.1 推荐：不动标签，只补 Release 说明

Release 页面下载的是附件 APK，源码 zip 指向旧提交对普通用户影响不大。
直接编辑 Release 说明即可：

1. 打开 <https://github.com/Yowane-Hakudesu/Project_Cetacea/releases/tag/v0.4.0>
2. 点 **Edit release**
3. 标题保持 `Project_Cetacea v0.4.0`
4. 说明粘贴 `CHANGELOG.md` 的 `[0.4.0]` 段落（新增/变更/修复）
5. 勾选 **Set as the latest release**
6. Update release

> 附件已经是 v0.4.0 的 APK，不需要重传；若想换成最新构建，删掉旧附件再上传
> `app/build/outputs/apk/debug/app-debug.apk` 与
> `companion/build/outputs/apk/debug/companion-debug.apk`。

### 5.2 可选：把标签移到新提交（有风险）

只有确认**没人下载过、也没有外部链接引用**时才做：

1. 先删除 Release（GitHub 会保留 tag）；
2. 删除远程标签：

   ```powershell
   git push origin :refs/tags/v0.4.0
   git tag -d v0.4.0
   ```

3. 在新提交上重打并推送：

   ```powershell
   git tag -a v0.4.0 -m "v0.4.0: social throttling, entity memory, segmented sending, relay"
   git push origin v0.4.0
   ```

4. 重新创建 Release 并上传两个 APK。

**更稳妥的做法**：保留 v0.4.0，把下一次代码发布叫 `v0.4.1`。语义化版本比“移动标签”更安全。

---

## 6. 更新仓库主页的「About」区域

主页 README 之外，仓库右上角 About 也是门面。

1. 仓库首页 → 右侧 **About** 旁的 ⚙️；
2. **Description** 建议：

   ```text
   端侧 LLM 手机代理：无障碍自动化 + 社交节流 + 实体记忆 + 公网中继（Kotlin/Android）
   ```

3. **Website** 可留空，或填使用文档；
4. **Topics** 建议（逐个添加）：

   ```text
   android  kotlin  accessibility-service  llm  ai-agent  automation
   websocket  relay  room  openai  side-load
   ```

5. 保存。

### 6.1 社交预览图（可选但推荐）

仓库 Settings → **Social preview** → Upload an image。
建议 1280×640 的 PNG；素材在 `docs/icons/`（SVG 需先转 PNG，可用浏览器截图或 Inkscape）。

### 6.2 固定仓库（可选）

个人主页 → Customize your pins → 勾选 `Project_Cetacea`，访客第一眼能看到。

---

## 7. 验收清单

推送后逐项确认：

- [ ] 仓库首页标题是 **v0.4.0**，不再出现“当前版本 v0.3.1”
- [ ] CI 徽章显示绿色（Actions → Android CI 最近一次成功）
- [ ] `app/README.md`、`companion/README.md`、`relay/README.md` 都能打开
- [ ] 主页里的相对链接不 404（尤其 `./assets/...png`）
- [ ] Release `v0.4.0` 附件可下载，说明不为空
- [ ] About 的 Description / Topics 已填
- [ ] `git status` 干净，`git log origin/main -1` 是本次提交

命令行辅助：

```powershell
git status
git log --oneline origin/main -3
git ls-tree --name-only origin/main | Select-String "app|companion|relay|docs"
```

---

## 8. 回滚方案

### 8.1 只回滚主页

```powershell
git revert <commit-hash>
git push origin main
```

### 8.2 Release 发错了

- 编辑 Release 改说明/换附件；
- 或删除 Release（不影响 tag），再重建；
- 不要 `git push --force` 主分支——会破坏别人的 fork/PR。

### 8.3 标签打错

按 5.2 的步骤删标签重打；已经有人下载时，改用新版本号（v0.4.1）。

---

## 9. 常见问题

| 现象 | 处理 |
| :--- | :--- |
| `rebase` 冲突太多 | `git rebase --abort`，改用 `git merge origin/main` 手工解 |
| push 要求认证 | 用 PAT（Contents: Read and write）或 SSH；GitHub 不接受账号密码 |
| CI 红了 | 本地先跑 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :companion:lintDebug` |
| 主页图片 404 | `git checkout origin/main -- assets` 后重新提交 |
| Release 附件 404 | 重新上传 APK；单文件 < 2GB |
| 主页还是旧内容 | 确认改的是根 `README.md`，而不是 `docs/` 下的副本；等 1~2 分钟 CDN 刷新 |

---

## 10. 一条龙命令（熟练后可直接用）

```powershell
cd E:\DSproject2\whale-chan

# 0. 远程改名 + 清理
git remote set-url origin https://github.com/Yowane-Hakudesu/Project_Cetacea.git
if (Test-Path app\0.4.0README.md) { Move-Item app\0.4.0README.md app\README.md -Force }
Remove-Item README_new.md, README_v01_style.md -Force

# 1. 提交 v0.4.0
git checkout -b release/0.4.0
git add -A
git commit -m "feat: v0.4.0 social throttling, entity memory, segmented sending, relay"

# 2. 同步远程并解决 README 冲突
git fetch origin
git rebase origin/main
git checkout --theirs README.md
git add README.md
git rebase --continue

# 3. 用 v0.4.0 主页覆盖并提交
Copy-Item docs\HOMEPAGE-0.4.0.md README.md -Force
git add README.md docs\HOMEPAGE-0.4.0.md
git commit -m "docs: refresh homepage for v0.4.0"

# 4. 推送
git push origin release/0.4.0:main

# 5. 验收
git fetch origin
git log --oneline origin/main -3
git status
```

推完后去网页完成第 5、6 节：编辑 Release 说明、填 About/Topics、上传社交预览图。
