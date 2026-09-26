# 兼容性 / Compatibility

## 配置与实际能力 / Configuration versus behavior

| 项目 / Item | 已知事实 / Evidence |
| --- | --- |
| 声明最低版本 / Declared minimum | Android 6.0 / API 23 (`minSdk=23`) |
| 编译及目标 / Compile and target | API 35 / API 35 |
| 限制悬浮窗 / Restriction overlays | `OverlayController`、Focus、Quota 耗尽及冷静期窗口均在 API < 26 时跳过显示 / All four overlay controllers skip display below API 26 |
| 已报告真机 / Reported device | Mate70 / HarmonyOS 4.3.0，Release 验收 5/5，由维护者报告 / Maintainer-reported Release acceptance: 5/5 |
| 其他设备和系统 / Other devices and systems | 未提供验收证据，不作兼容承诺 / No acceptance evidence supplied |

因此不能将 `minSdk=23` 写成“Android 6.0 起完整支持所有功能”。API 26 及以上也只是窗口代码的版本条件，不代表每台设备均已验证。

`minSdk=23` must not be advertised as full functionality from Android 6.0 onward. API 26+ satisfies the overlay version gate; it is not proof of compatibility on every device.

## RC-H-001 — 清理最近任务后监控可能中断 / Monitoring may stop after clearing recent tasks

- **环境 / Environment:** Mate70 / HarmonyOS 4.3.0。
- **触发条件 / Trigger:** 清理最近任务，应用或监控进程可能被系统终止 / Clearing recent tasks may terminate the application or monitoring process.
- **影响 / Impact:** Focus 计时状态可能保留，但应用限制不保证继续有效 / Focus timestamps may persist while app restrictions cease to be effective.
- **状态 / Status:** V1.0 已知兼容限制，保留未修复；5/5 Release 验收不表示此问题已解决 / Known, unresolved V1.0 limitation; the reported 5/5 acceptance result does not resolve it.
- **使用建议 / Mitigation:** 专注期间避免清理 SelfControlApp，查看系统应用启动管理、后台活动及电池优化设置；重新打开后检查权限与监控状态，必要时尝试恢复 / Avoid clearing SelfControlApp during Focus, review app-launch/background/battery settings, and recheck permissions and monitor status after reopening; attempt recovery if needed.
- **保证边界 / Limits:** 系统设置入口因版本而异，以上操作及 `START_STICKY`、进程内恢复任务均不保证系统杀进程后的自动恢复 / Settings vary; these actions, `START_STICKY`, and in-process recovery do not guarantee restart after process termination.

## 尚未确认 / Not established

未确认其他厂商、其他 HarmonyOS 版本、API 23～25 真机运行、长时间待机、强制停止或重启后的自动恢复、多窗口/多用户/工作资料场景，以及系统统计延迟和耗电表现。未对不支持 Android APK 的系统作出支持声明。

No evidence establishes other vendors or HarmonyOS versions, device behavior on API 23–25, prolonged idle operation, automatic recovery after force-stop or reboot, multi-window/multi-user/work-profile behavior, usage-statistics latency, or battery impact. No support is claimed for systems unable to run Android APKs.

单元测试验证局部逻辑，不能代替上述系统场景。见[测试说明](TESTING.md)。

Unit tests verify local logic, not these system scenarios. See [Testing](TESTING.md).
