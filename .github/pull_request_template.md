## 改动内容

<!-- 一句话说明这个 PR 做了什么 -->

## 关联 Issue

Closes #

## 验证方式

- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] `./gradlew :app:assembleDebug :companion:assembleDebug` 通过
- [ ] `./gradlew :app:lintDebug :companion:lintDebug` 无 error
- [ ] 真机验证（机型 / Android 版本 / 场景）：

## 风险与回滚

<!-- 影响范围、可能的回归、如何回滚 -->

## 合规检查

- [ ] 未加入隐藏应用、对抗检测、绕过 Android 权限的能力
- [ ] 未新增遥测，未把数据发到用户未配置的端点
- [ ] 未提交 API Key / release keystore / local.properties
- [ ] 新增权限已在 README 权限表补充“用途 / 缺失后果”
- [ ] 新增动作已写结构化日志并带 `ErrorCategory`
