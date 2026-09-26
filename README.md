# SelfControlApp

[English](README_EN.md)

SelfControlApp 是使用 Kotlin 和 Jetpack Compose 开发的 Android 自律辅助应用。它通过系统使用情况访问、前台服务和悬浮窗，帮助用户在打开受控应用前确认使用意图、管理每日额度并执行专注计划。

本文档对应 **v1.0.0**；应用版本为 `1.0`，`versionCode=1`，包名为 `com.selfcontrol.app`。源码仓库：[zqw878210a-ctrl/SelfControlApp](https://github.com/zqw878210a-ctrl/SelfControlApp)。本次源码归档不包含 APK 下载或 GitHub Release。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| Focus 专注 | 25 / 45 / 60 分钟预设及 1～1440 分钟自定义；限制已启用受控应用，保存开始和结束时间；提前结束需等待 5 秒并确认 |
| Quota 每日额度 | 按应用设置 1～1440 分钟额度，留空取消；读取系统今日使用统计，显示剩余时间及额度状态，并支持 50% / 80% / 90% / 100% 阈值提醒 |
| ExtraTime 额外时间 | 中度模式下，额度用完后等待 30 秒，可选增加 5 或 10 分钟；每个应用每天最多一次；严格模式不提供新申请 |
| Intent Gate 意图确认 | 打开受控应用时选择使用原因，再继续使用或放弃；正式 Gate 结果记录到本地数据库 |
| Session 使用会话 | 已确认应用离开不超过 5 分钟时可复用确认；仍受 Focus 和 Quota 约束，服务重建后不保证保留 |
| 首页与管理 | 管理受控应用、权限引导、专注通知、专注历史概览和最近 10 条记录 |
| Monitor 监控状态 | 显示正常、正在检查、不可用或需要设置；异常时提供设置或恢复入口 |

规则优先级：**Focus > Quota > Session > Gate**。这些限制依赖权限、系统使用事件、服务和悬浮窗正常工作，不是系统级强制封锁。专注计时或历史记录不证明整个时段内监控持续有效。

## 运行条件与已知限制

- Gradle 声明 `minSdk=23`（Android 6.0）、`compileSdk=35`、`targetSdk=35`。
- **限制悬浮窗的实现要求 Android 8.0 / API 26 及以上**；API 23～25 会跳过显示。声明的最低版本不等于完整功能兼容性。
- 需要使用情况访问及悬浮窗权限；通知权限影响通知显示。
- **RC-H-001：Mate70 / HarmonyOS 4.3.0 清理最近任务后，监控可能中断。** V1.0 保留此已知限制。避免清理应用、查看系统后台设置并重新检查监控状态；这些操作不保证恢复。
- 应用界面目前以中文为主；英文文档不代表应用已提供英文界面。

维护者报告最近一次单元测试 **97/97 通过**、Mate70 Release 真机验收 **5/5 通过**。本地已有 XML 测试报告也合计 97 项且无失败；本次文档工作没有重新运行测试。具体证据边界见[测试说明](docs/TESTING.md)，兼容性见[兼容性说明](docs/COMPATIBILITY.md)。

## 快速开始

使用 JDK 17 和 Android SDK Platform 35，在 Android Studio 中打开项目根目录并同步 Gradle。Windows 下构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

安装自行构建的应用后，按引导授权，进入“受控App管理”选择应用并启动实时监控，再设置额度或开始专注。首次创建数据库时会加入已启用的抖音配置，可在管理页调整。构建和签名边界见[构建说明](docs/BUILD.md)，操作步骤见[用户指南](docs/USER_GUIDE.md)。

## 数据与隐私事实

当前应用源码未发现网络请求、账号、云同步或分析上报实现；源 Manifest 未声明 `INTERNET`。构建时 Gradle 会下载工具和依赖，不能据此宣称构建离线。

应用在本地 Room 数据库保存受控应用配置和 Gate 事件，在 SharedPreferences 保存专注状态、历史及额度相关状态。运行日志可包含包名、时间及控制状态。Manifest 设置 `allowBackup=true`，因此这里不承诺数据绝不会被系统备份或迁移，也不承诺日志无使用痕迹。上述为源码事实，不代替正式隐私政策。

## 文档

- [架构 / Architecture](docs/ARCHITECTURE.md)
- [用户指南 / User guide](docs/USER_GUIDE.md)
- [兼容性 / Compatibility](docs/COMPATIBILITY.md)
- [测试 / Testing](docs/TESTING.md)
- [构建 / Build](docs/BUILD.md)
- [变更记录 / Changelog](CHANGELOG.md)
- [公开前审核 / Pre-publication review](docs/SECURITY_REVIEW.md)
- [第三方说明 / Third-party notices](THIRD_PARTY_NOTICES.md)

## 许可证

项目原创代码采用 [MIT License](LICENSE)。Copyright (c) 2026 zqw878210a-ctrl。第三方组件保留各自许可证，不因项目采用 MIT 而改变。
