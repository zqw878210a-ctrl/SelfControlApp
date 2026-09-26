# 测试 / Testing

## 已有结果 / Existing results

| 证据 / Evidence | 结果 / Result | 边界 / Limits |
| --- | --- | --- |
| 维护者最近一次报告 / Latest maintainer report | 单元测试 97/97 通过 / 97/97 unit tests passed | 历史结果，本次未重跑 / Historical, not rerun for this task |
| 本地 `app/build/test-results/testDebugUnitTest/TEST-*.xml` / Existing local reports | 11 个测试类，共 97 项，失败 0、错误 0、跳过 0 / 11 classes, 97 tests, zero failures/errors/skips | 只读核对旧报告；报告被 `build/` 规则忽略，不随源码发布 / Read-only inspection of prior reports, excluded from source publication |
| 维护者 Release 真机报告 / Maintainer's Release device report | Mate70 / HarmonyOS 4.3.0：5/5 通过 / 5/5 passed | 未提供五项逐条步骤、时间和原始证据；不编造验收清单 / No itemized steps, timestamps or raw evidence supplied |

此前 RC 阶段曾报告 4 项通过、手动恢复按钮因未出现异常状态而未触发。后续 Release 汇总为 5/5，但未说明第五项内容，因此不能据此确认手动恢复异常路径已真机覆盖。RC-H-001 仍为已知限制。

An earlier RC report recorded four passes and an untriggered manual-recovery button because no abnormal state occurred. The later Release summary is 5/5, but its fifth item was not specified. Device coverage of that recovery path therefore remains unconfirmed. RC-H-001 remains open.

## 源码中的单元测试 / Unit tests in source

路径：`app/src/test/java/com/selfcontrol/app/`。按真实 `@Test` 注解计数共 97 项；不要将 Kotlin 的 `this@TestPreferences` 标签计作测试。

Location: `app/src/test/java/com/selfcontrol/app/`. There are 97 actual `@Test` annotations; the Kotlin label `this@TestPreferences` is not a test.

| 测试类 / Test class | 数量 / Count | 范围 / Scope |
| --- | ---: | --- |
| `AppPermissionGuideTest` | 1 | 缺失权限引导 / Missing-permission guidance |
| `FocusHomeExitStateTest` | 10 | 返回桌面、旧事件抑制、快速重新进入 / Home exit, stale events and reentry |
| `FocusMonitorStatusTest` | 6 | 专注页监控状态及权限优先 / Monitor status and permission precedence |
| `ForegroundInitializationTest` | 14 | UsageEvents 初始前台状态重建 / Initial foreground-state reconstruction |
| `MonitorHealthTest` | 8 | 心跳、轮询、读取和窗口状态 / Heartbeat, polling, reads and window state |
| `MonitorNotificationTest` | 6 | 专注及普通监控通知内容 / Focus and ordinary notification content |
| `MonitorServiceRestartTest` | 7 | 启动节流和 START_STICKY 返回策略 / Retry throttling and sticky return policy |
| `focus/FocusOverlayWindowStateTest` | 11 | 窗口复用与附着状态判定 / Window reuse and attachment predicates |
| `focus/FocusSessionStoreTest` | 27 | 持久化、截止结算、并发、旧数据和写入失败 / Persistence, expiry, concurrency, legacy data and write failures |
| `quota/QuotaConfigRefreshTest` | 3 | 配置变更导致快照失效 / Snapshot invalidation after configuration changes |
| `quota/QuotaExtraTimeLifecycleTest` | 4 | 授权保留、停用、次数限制和跨天 / Extra-time persistence, disabling, grant limits and date rollover |
| **合计 / Total** | **97** | |

使用 JUnit 4.13.2；部分存储测试使用内存 SharedPreferences 替身。Gradle 设置 `unitTests.isReturnDefaultValues=true`，因此通过不代表 Android 系统 API、真实存储或窗口已经真机验证。

Tests use JUnit 4.13.2 and in-memory SharedPreferences substitutes where needed. Gradle enables `unitTests.isReturnDefaultValues=true`; success does not establish real Android API, disk, or window behavior.

## 复现命令 / Reproduction

配置构建环境后，维护者可自行运行；本次没有执行这些命令。

After setting up the build environment, maintainers can run the following. These commands were not executed for the documentation task.

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

```sh
sh ./gradlew :app:testDebugUnitTest
```

HTML 报告位置 / HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`。

## 未验证事项 / Unverified areas

- 未见 `app/src/androidTest` 仪器测试；没有覆盖率报告，不能称为全面覆盖 / No instrumentation-test source or coverage report was found.
- 未复验真实触摸拦截、悬浮窗渲染、通知投递、权限撤销后的系统行为及 Room 真机迁移 / Rendering, touch interception, notification delivery, system permission revocation and on-device Room migrations were not revalidated.
- 未复验 RC-H-001 恢复、长时间待机、强制停止、重启、电池消耗和其他设备 / Recovery, prolonged idle behavior, force-stop, reboot, battery use and other devices were not revalidated.
- 未建立当前源码提交与已签名 APK 的可复现构建对应关系；本次未初始化 Git，也未构建或签名 / No reproducible source-commit-to-signed-APK mapping was established; no Git initialization, build or signing occurred.

公开测试记录应区分源码测试、旧报告、维护者真机报告和新执行结果。不要上传含个人信息的原始日志。

Public test records should distinguish source tests, historical reports, maintainer device reports, and newly executed results. Do not publish raw logs containing personal information.
