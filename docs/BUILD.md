# 构建 / Build

## 已检查的配置 / Inspected configuration

| 配置 / Configuration | 值 / Value |
| --- | --- |
| Module | `app` |
| Application ID / namespace | `com.selfcontrol.app` |
| Version | `versionName=1.0`, `versionCode=1`；文档版本 / documentation version `v1.0.0` |
| SDK | min 23, compile 35, target 35 |
| Java compile compatibility | 17 |
| Gradle distribution | 8.7，来自 / from `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.6.1 |
| Kotlin / Compose plugin / kapt | 2.0.21 |
| Compose BOM | 2024.12.01 |
| Room | 2.6.1 |
| JUnit | 4.13.2 |

依赖版本以 `gradle/libs.versions.toml` 和 `app/build.gradle.kts` 为准。不要把 Compose BOM 版本当作每一个 Compose 库的版本。

Dependency versions are defined in `gradle/libs.versions.toml` and `app/build.gradle.kts`. The Compose BOM version is not the version of each individual Compose library.

## 环境与 Debug 构建 / Environment and Debug build

1. 安装 JDK 17，准备 Android SDK Platform 35 和 SDK 构建工具。使用支持本项目 AGP 版本的 Android Studio，或配置命令行 SDK 环境。
2. 在 Android Studio 中打开项目根目录；由 IDE 配置本机 SDK 路径，或在已忽略的 `local.properties` 中设置本地 `sdk.dir`。不要提交个人绝对路径。
3. 使用项目 Wrapper 同步和构建。初次运行需要从 Gradle、Google Maven、Maven Central 及插件仓库下载相应工具和依赖。

Use JDK 17, Android SDK Platform 35 and SDK build tools. Open the root folder with an Android Studio version supporting the declared AGP, or configure command-line SDK tools. Keep the local SDK path in ignored `local.properties`. Initial setup downloads tools and dependencies from the configured repositories.

```powershell
.\gradlew.bat :app:assembleDebug
```

```sh
sh ./gradlew :app:assembleDebug
```

默认 Debug 输出 / Default Debug output: `app/build/outputs/apk/debug/app-debug.apk`。

这些命令是构建说明，本次未执行。系统运行限制见[兼容性](COMPATIBILITY.md)；声明 `minSdk=23` 不代表 API 23～25 的限制悬浮窗可用。

These are build instructions, not commands executed for this task. See [Compatibility](COMPATIBILITY.md): restriction overlays do not run on API 23–25 despite `minSdk=23`.

## Release 与签名 / Release and signing

当前 Gradle 文件没有正式签名配置。`assembleRelease` 不应被描述成自动生成可分发的正式签名包。维护者使用 Android Studio 的签名 APK 工作流，选择自己的项目外密钥和 `release` 构建；仓库不分发正式密钥或密码，也不要求贡献者取得维护者的密钥。

The Gradle files contain no production signing configuration. Do not describe `assembleRelease` as automatically producing a production-signed distribution. Maintainers use Android Studio's signed-APK workflow with their own external key and the `release` variant. Production keys and passwords are not distributed or required for contributor Debug builds.

发布前应针对实际生成路径验证签名、包名、版本和 `debuggable`，记录 SHA-256。文件名和目录名本身不能证明构建类型。正式签名与 Debug 签名不同的包不能直接覆盖安装；本任务不执行安装或卸载。

For an actual release artifact, verify its signature, package, version and `debuggable` flag and record its SHA-256. A filename or directory does not prove its build type. A production package signed differently from an installed Debug package cannot directly replace it. No installation or removal was performed here.

## Wrapper 来源说明 / Wrapper provenance

现有 `gradle-wrapper.jar` 的 SHA-256 是 `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`，与 [Gradle 官方校验表](https://gradle.org/release-checksums/)中的 9.3.0 / 9.3.1 Wrapper 一致；它并不是官方 8.7 Wrapper 的校验值。实际下载的 Gradle 版本仍由 `distributionUrl` 指向 8.7。本次未替换 Wrapper，也未验证干净环境重建。

The bundled wrapper JAR matches the official 9.3.0 / 9.3.1 wrapper hash, rather than the 8.7 wrapper hash. The configured distribution remains Gradle 8.7. The wrapper was not replaced, and a clean-environment rebuild was not performed. See the [official checksum reference](https://gradle.org/release-checksums/).

## 文件边界 / Repository boundaries

保留源码、测试、Gradle 脚本、版本目录、Wrapper 脚本及 JAR、文档和许可证。`.gitignore` 排除本机配置、密钥、APK/AAB、日志、构建缓存和 `app/release/`。不要把整个工作目录直接打包公开；忽略规则不会自动清理 ZIP，也不能阻止强制添加或保护写在普通源码中的密码。

Keep source, tests, Gradle scripts and catalogs, wrapper scripts/JAR, documentation and licenses. Ignore local configuration, keys, APK/AAB files, logs, build caches and `app/release/`. Do not publish a raw working-directory ZIP: ignore rules do not sanitize archives, prevent forced additions, or protect secrets embedded in ordinary source files.
