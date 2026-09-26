# 第三方说明 / Third-party notices

项目 [MIT 许可证](LICENSE)用于项目原创部分，不改变第三方文件、依赖及其作者的权利。MIT 标准文本参考 [Open Source Initiative](https://opensource.org/license/mit)。项目版权持有人已确认：zqw878210a-ctrl。

The project [MIT License](LICENSE) applies to original project material, not as a replacement for third-party terms. The standard MIT text is available from the [Open Source Initiative](https://opensource.org/license/mit). The confirmed project copyright holder is zqw878210a-ctrl.

## 随源码分发的文件 / Files bundled with source

`gradlew` 和 `gradlew.bat` 保留原有 Gradle 作者版权及 Apache-2.0 头部。`gradle/wrapper/gradle-wrapper.jar` 包含 `META-INF/LICENSE`；其原文另存于 [licenses/Gradle-Wrapper-LICENSE.txt](licenses/Gradle-Wrapper-LICENSE.txt)，随源码保留。JAR 校验与版本说明见 [BUILD](docs/BUILD.md)。这些文件是构建工具，不是本项目原创业务代码。

`gradlew` and `gradlew.bat` retain their Gradle-author copyright and Apache-2.0 headers. The wrapper JAR contains `META-INF/LICENSE`; an unchanged copy is included as [licenses/Gradle-Wrapper-LICENSE.txt](licenses/Gradle-Wrapper-LICENSE.txt). See [Build](docs/BUILD.md) for its checksum and version distinction. These are third-party build tooling, not original application code.

## 构建声明的组件 / Declared components

| 组件 / Component | 来源与许可证信息 / Upstream and license information |
| --- | --- |
| Gradle / Android Gradle Plugin / AndroidX (Core, Lifecycle, Activity, Compose, Room) | 通过配置仓库解析，遵循各组件的许可证与归属声明；[Android 许可说明](https://source.android.com/docs/setup/contribute/licenses) / Resolved from configured repositories; retain each component's terms and notices |
| Kotlin 2.0.21 | [上游许可证 / Upstream license](https://github.com/JetBrains/kotlin/blob/v2.0.21/license/LICENSE.txt)，Apache-2.0 |
| JUnit 4.13.2（仅测试 / test only） | [JUnit 许可说明 / License](https://junit.org/junit4/license.html)，EPL-1.0 |

该表是直接组件清单，不是完整的传递依赖 SBOM 或 APK 许可证审计。当前工作不分发 Gradle 缓存或 APK，也未解析所有传递依赖的最终版本与 NOTICE；将来分发二进制时需另行保留对应许可材料。

This is a direct-component inventory, not a complete transitive SBOM or APK license audit. This task does not distribute Gradle caches or APKs and did not resolve every transitive version or NOTICE. Binary distribution needs its corresponding license materials.

## 资源与来源待确认 / Assets and provenance

当前公开候选文件未发现捆绑的第三方图片、字体、音频、视频或真实用户数据；资源目录仅包含主题 XML。界面引用系统图标及运行时应用名称，不包含所管理应用的安装包。源码检查无法证明每段代码的原创性或作者授权，维护者仍需确认原创权属及任何未标注的外部代码来源。

No bundled third-party images, fonts, audio, video, or real user data were found among publication candidates; the resource directory contains a theme XML. The UI references system icons and runtime app names, not other apps' installation packages. Source inspection cannot prove authorship or permission for every passage of code; maintainers must confirm provenance and any unattributed external material.
