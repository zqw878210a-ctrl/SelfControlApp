# 变更记录 / Changelog

## v1.0.0

V1.0 首次源码归档日期：2026-09-27。本文归纳当前源码与维护者已报告的验收状态；本次归档不包含 GitHub Release 或 APK 下载。

Initial V1.0 source archive date: 2026-09-27. This entry summarizes current source and maintainer-reported acceptance; the archive does not include a GitHub Release or an APK download.

### 功能 / Features

- Focus 专注时长、自定义计划、限制窗口、通知、提前结束确认和本地历史 / Focus durations, restriction overlays, notifications, early-end confirmation and local history.
- 每日 Quota、额度阈值提醒、中度/严格模式，以及每日一次的 ExtraTime / Daily quotas, threshold notifications, moderate/strict modes and once-per-day extra time.
- Intent Gate 原因选择和结果记录、五分钟离开宽限内的 Session 复用 / Intent selection and event storage, with app-session reuse within a five-minute absence.
- 首页、受控应用管理、权限引导、独立监控健康状态及恢复入口 / Home, app management, permission guidance, separate monitor health and recovery controls.
- 规则顺序 / Rule priority: **Focus > Quota > Session > Gate**.

### 验证 / Validation

维护者报告单元测试 97/97、Mate70 Release 真机验收 5/5 通过；本次文档准备只读核对了已有 97 项成功的 XML 报告，未重跑测试或真机验收。范围见 [TESTING](docs/TESTING.md)。

The maintainer reports 97/97 unit tests and 5/5 Mate70 Release checks passed. Documentation preparation only inspected existing successful XML reports; it did not rerun tests or device checks. See [Testing](docs/TESTING.md).

### 已知限制 / Known limitations

- **RC-H-001:** Mate70 / HarmonyOS 4.3.0 清理最近任务后监控可能中断，V1.0 保留未修复 / Monitoring may stop after clearing recent tasks; unresolved in V1.0.
- `minSdk=23`，但限制悬浮窗仅在 API 26 及以上尝试显示 / Restriction overlays only attempt display on API 26+, despite `minSdk=23`.
- 其他设备、长期后台表现及当前源码到已签名 APK 的可复现对应关系尚未确认 / Other devices, prolonged background operation and reproducible source-to-signed-APK correspondence remain unconfirmed.
