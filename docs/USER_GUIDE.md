# 用户指南 / User guide

V1.0 界面主要为中文；以下英文用于解释现有按钮。先阅读[兼容性](COMPATIBILITY.md)，尤其是 Android 8.0 以下的悬浮窗限制。

The V1.0 UI is primarily Chinese. English text here explains the existing controls. Read [Compatibility](COMPATIBILITY.md), particularly the overlay limitation below Android 8.0.

## 1. 授权与选择应用 / Permissions and app selection

打开应用，按引导开启“使用情况访问”和“悬浮窗权限”，再返回应用检查状态。进入“受控App管理”，从系统可见的启动器应用中选择受控应用；首次建库已有抖音配置，可停用。Android 13 及以上启动监控时可能请求通知权限，拒绝会影响通知显示，代码仍会尝试启动监控。

Grant usage access and overlay permission in system settings, then return to recheck status. Open “受控App管理” to select from visible launcher apps. The initial Douyin configuration can be disabled. On Android 13+, starting monitoring may request notification permission; denial affects notifications, while the code still attempts to start monitoring.

| 权限 / Manifest permission | 用途 / Purpose |
| --- | --- |
| `PACKAGE_USAGE_STATS` | 系统使用事件及使用时长；需设置授权 / Usage events and duration; requires settings approval |
| `SYSTEM_ALERT_WINDOW` | 应用限制悬浮窗；需设置授权 / Restriction overlays; requires settings approval |
| `FOREGROUND_SERVICE` | 运行前台监控服务 / Foreground monitoring service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 声明监控服务的 specialUse 类型 / Declared specialUse service type |
| `POST_NOTIFICATIONS` | 监控、专注与额度通知 / Monitor, Focus and quota notifications |

源 Manifest 未声明无障碍服务或设备管理员组件。

The source Manifest declares neither an accessibility service nor a device-admin component.

## 2. 启动监控 / Start monitoring

在管理页点击“启动实时监控”，确认状态可用。服务运行不一定表示监控可用。专注期间首页另显示“正常 / 正在检查 / 不可用 / 需要设置”；权限不足时使用“去设置”，不可用时可点击“尝试恢复监控”。点按后仍应检查状态，不应假定恢复成功。

Use “启动实时监控” in management and check availability. A running service alone is insufficient. During Focus, home shows normal / checking / unavailable / settings required. Use “去设置” for missing permissions or “尝试恢复监控” when unavailable, then verify the result.

“停止实时监控”会停止服务，但活跃专注期间的恢复机制仍可能尝试重新启动监控；如需结束计划，请使用明确的提前结束入口。

“停止实时监控” stops the service, but an active Focus plan can trigger recovery attempts. Use the explicit early-end action to end the plan.

## 3. 专注 / Focus

启用至少一个受控应用后，选择 25 / 45 / 60 分钟或输入 1～1440 的整数，点击“开始专注”。专注期间受控应用显示限制窗口。“结束使用”尝试返回桌面，不会结束专注。首页“提前结束专注”需等待 5 秒后确认；等待期间倒计时继续，到期按自然完成记录。

Enable at least one controlled app, choose a preset or enter 1–1440 whole minutes, and tap “开始专注”. A restriction window appears over controlled apps during Focus. “结束使用” attempts to return Home without ending Focus. To end the plan early, use “提前结束专注”, wait five seconds, then confirm. The timer continues during confirmation; expiry is recorded as completion.

“查看专注报告”展示记录数、完成和提前结束次数、按时间戳计算的累计时长及最近 10 条记录。这些时长不测量实际注意力，也不扣除监控中断时间。点击专注通知可返回首页，不会创建新的专注计划。

“查看专注报告” shows totals, outcomes, timestamp-derived duration, and the latest ten records. It does not measure attention or subtract monitoring interruptions. The Focus notification returns to home without starting a new plan.

## 4. 额度与额外时间 / Quotas and extra time

在受控应用卡片点击“编辑规则”，输入 1～1440 分钟并保存；留空保存可取消额度。设置并保存中度或严格模式。额度依据系统每日使用统计，并非应用内独立秒表；数据不可用时应刷新或检查权限。

Open “编辑规则” on an app card and save a daily limit of 1–1440 minutes; saving a blank value removes the quota. Save either moderate or strict mode. Usage comes from system daily statistics, not an independent per-second meter. Refresh or check permissions when data is unavailable.

中度模式额度用完后可申请额外时间：等待 30 秒，再选择 5 或 10 分钟；每个应用每天最多一次。严格模式不提供新的额外时间申请。修改额度或切换模式不会重置当日申请次数；已获得的额外时间不会仅因切换严格模式而撤销。停用该应用会清除额外分钟数但保留当日已申请标志，跨本地日期后重新计算资格。额外时间仍不能越过 Focus。

Moderate mode offers one extra-time grant per app per local day: wait 30 seconds, then choose five or ten minutes. Strict mode offers no new grant. Editing the quota or changing mode does not reset eligibility; switching to strict mode alone does not revoke an existing grant. Disabling the app clears its extra minutes while retaining the day's used flag. Eligibility resets on the next local date. Extra time never overrides Focus.

## 5. 使用意图 / Intent Gate

当 Focus 和 Quota 未拦截且没有可复用 Session 时，Gate 要求选择原因后“继续使用”，也可“放弃”并尝试返回桌面。继续使用建立内存中的应用会话，离开不超过 5 分钟可复用确认；它不延长额度。

When Focus and Quota permit access and no reusable app session exists, select a reason and choose “继续使用”, or choose “放弃” to attempt returning Home. Continuing creates an in-memory approval reusable within five minutes of leaving; it does not extend a quota.

## 6. 使用痕迹与反馈 / Usage traces and reporting

本地数据库、偏好和运行日志可能反映使用习惯。分享问题时先删去包名、时间、个人路径及其他不愿公开的信息，不要附密钥、密码、原始日志或设备数据。当前源码没有用户数据导出或一键清理界面，文档不承诺相关功能。

Local databases, preferences, and runtime logs may reveal usage patterns. Redact package names, timestamps, personal paths and other private details before reporting issues; do not attach keys, passwords, raw logs, or device data. No user-facing data export or one-click purge is implemented in the reviewed source.
