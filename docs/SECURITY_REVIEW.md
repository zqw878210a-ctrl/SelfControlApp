# 公开前审核 / Pre-publication review

审核日期 / Review date: **2026-09-27**。范围为当前工作目录的源码公开准备，不是渗透测试、完整供应链审计或法律权属认证。

Scope: preparation of the current working tree for source publication, not penetration testing, a complete supply-chain audit, or legal ownership certification.

## 方法与结果 / Method and findings

使用遵循现有忽略规则的文件清单、敏感关键词/常见令牌格式扫描、文件类型与大小检查，并阅读源码、Manifest、Gradle、资源和测试。扫描结果只报告位置和类型，不输出敏感值。文档创建前共有 47 个公开候选文件。

The review combined an ignore-aware candidate list, credential-keyword/common-token-format scanning, file-type/size checks, and inspection of source, Manifest, Gradle, resources and tests. Sensitive values are not printed. There were 47 publication candidates before documentation was added.

| 检查 / Check | 结果 / Finding |
| --- | --- |
| 密钥、密码、令牌 / Keys, passwords, tokens | 公开候选中未发现；唯一初始关键词命中是 `.gitignore` 的排除文件名 / None identified in candidates; the initial keyword match was an exclusion filename in `.gitignore` |
| 本机私密配置 / Local private settings | `local.properties`、IDE 配置和本地缓存存在并已忽略；未复制其内容 / Present and excluded; contents were not copied |
| 日志 / Logs | 根目录存在 `focus-home-huawei.log`，由 `*.log` 忽略；未公开或复制其内容 / An existing local log is excluded and its contents were not published |
| APK 与构建输出 / APKs and build output | 已有 Release APK 和构建目录被忽略；未删除、签名或纳入 Git / Existing packages and build output are excluded; not deleted, signed or added to Git |
| 用户数据 / User data | 候选文件未见数据库、用户导出或真实用户数据文件；测试使用合成记录 / No database/user-export files identified in candidates; tests use synthetic records |
| 大文件 / Large files | 初始候选无超过 5 MiB 文件；Wrapper JAR 是应保留的构建工具 / No initial candidate exceeded 5 MiB; keep the wrapper JAR as build tooling |
| 第三方资源 / Third-party material | 未发现捆绑媒体资源；Gradle Wrapper 保留原许可，依赖与权属边界见第三方说明 / No bundled media found; wrapper licensing preserved, with dependency/provenance limits documented |

正式密钥位于项目外是维护者提供的信息，本次没有查找或读取项目外密钥。未发现疑似敏感内容不等于形式化证明；编码、未知格式或未标注的私有素材可能不被静态检查识别。

The maintainer states that production keys are outside the project. External keys were not searched or read. Static inspection is not proof that encoded values, unknown formats, or unattributed private material are absent.

## 忽略规则 / Ignore rules

已检查 `.gitignore`：排除 `*.jks`、`*.keystore`、`*.apk`、`*.aab`、`.env`、`.env.*`、`*.log`、`.kotlin/`、`.gradle/`、`build/`、`app/release/`、`local.properties`、IDE 文件，以及常见 signing/keystore/key/secrets/credentials properties 和 `*.local.properties`、`*.private.properties`。

The existing rules exclude keys, packages, environment files, logs, caches, release output, machine configuration, IDE files, and common local signing/credential properties. Required Gradle scripts, `gradle.properties`, version catalogs and wrapper files remain candidates.

普通 `gradle.properties` 仍需逐次检查，不应整体忽略必需配置。规则不覆盖任意私密文件名，也不清理手动 ZIP、历史提交或强制添加。公开时应依据候选清单选择文件。

Ordinary `gradle.properties` remains reviewable project configuration. Ignore rules cannot cover arbitrary private filenames, sanitize ZIPs, remove historical commits, or prevent forced additions. Publish a reviewed file selection.

## 功能、数据与公开前待确认 / Claims, data and pending confirmation

- 文档依据实际实现记录 Focus、Quota、Session、Gate、历史和监控健康，不承诺不可绕过或永久后台运行 / Feature descriptions follow implementation and do not promise tamper-proof or permanent background enforcement.
- 明确记录 API 23 最低声明与 API 26 悬浮窗门槛的差异，以及未修复的 RC-H-001 / The API 23 declaration, API 26 overlay gate and unresolved RC-H-001 are documented.
- 未见应用网络实现或 `INTERNET` 声明，但 `allowBackup=true` 且源码有运行日志；不写“绝不上传、绝不备份、零使用痕迹”式隐私保证 / No application networking or INTERNET declaration was identified; backup is enabled and runtime logging exists, so absolute privacy claims are not made.
- MIT 版权持有人已由维护者确认并填写为 zqw878210a-ctrl；所有原创代码授权及未标注外部代码来源无法仅从源码证实 / The maintainer confirmed zqw878210a-ctrl as the MIT copyright holder; source inspection alone cannot establish original-code rights or external code provenance.
- 真机 5/5 的逐项记录、手动恢复异常路径、其他设备及当前源码到签名包的对应关系未证实 / Itemized device acceptance, abnormal manual recovery, other devices and source-to-signed-APK correspondence are unconfirmed.

文档准备阶段仅准备文档和许可材料，未初始化 Git、建立标签或上传。首次归档前复查了包含文档的 59 个候选文件，未发现疑似凭据、私密文件或构建输出；实际 Git 历史仍需在提交和上传时再次核验。业务逻辑、已有 Release APK 和签名密钥未改动，也未重跑业务测试。归档源码与已签名 APK 未经过二进制可复现性验证，不声称完全一致。

The documentation-preparation phase did not initialize Git, tag or upload. A pre-archive review of all 59 candidates, including documentation, identified no suspected credentials, private files or build output; actual Git history must also be checked during commit and upload. Business logic, the existing Release APK and signing keys were not changed, and business tests were not rerun. Binary reproducibility between the archived source and signed APK has not been verified; exact correspondence is not claimed.
