# SelfControlApp

[简体中文](README.md)

SelfControlApp is an Android self-control app built with Kotlin and Jetpack Compose. It uses usage access, a foreground service, and overlay windows to help users reflect before opening selected apps, manage daily limits, and follow a focus plan.

These documents describe **v1.0.0**. The app declares version `1.0`, `versionCode=1`, and package `com.selfcontrol.app`. Source repository: [zqw878210a-ctrl/SelfControlApp](https://github.com/zqw878210a-ctrl/SelfControlApp). This source archive does not include an APK download or a GitHub Release.

## Features

| Feature | Current implementation |
| --- | --- |
| Focus | 25 / 45 / 60 minute presets and custom durations of 1–1440 minutes; restricts enabled controlled apps and persists timestamps; ending early requires a five-second wait and confirmation |
| Daily quota | Per-app limits of 1–1440 minutes, or no quota; reads today's system usage statistics and displays remaining time and quota state; supports 50% / 80% / 90% / 100% threshold notifications |
| Extra time | In moderate mode, wait 30 seconds after exhausting a quota, then choose 5 or 10 extra minutes; at most once per app per day; strict mode does not offer a new grant |
| Intent Gate | Select a reason before continuing into a controlled app, or abandon the attempt; formal Gate outcomes are stored locally |
| App session | Reuses approval when returning within five minutes of leaving; Focus and Quota still take precedence; approval is not guaranteed to survive service recreation |
| Home and management | Controlled-app selection, permission guidance, Focus notifications, and Focus history with a summary and the latest ten entries |
| Monitor status | Separate normal, checking, unavailable, and settings-required states, with a settings or recovery action when appropriate |

Rule priority: **Focus > Quota > Session > Gate**. Restrictions depend on permissions, system usage events, a functioning service, and overlays. This is not an operating-system enforcement boundary. A running timer or recorded focus session does not prove uninterrupted monitoring.

## Requirements and limitations

- Gradle declares `minSdk=23` (Android 6.0), `compileSdk=35`, and `targetSdk=35`.
- **Restriction overlays require Android 8.0 / API 26 or later in the current implementation.** They skip display on API 23–25. The declared minimum does not establish full feature compatibility.
- Usage access and overlay permission are required for monitoring and restrictions; notification permission affects notification visibility.
- **RC-H-001: clearing recent tasks on Mate70 / HarmonyOS 4.3.0 may interrupt monitoring.** This remains a known V1.0 limitation. Avoid clearing the app, review system background settings, and check monitor status again; recovery is not guaranteed.
- The app UI is primarily Chinese. English documentation does not imply an English UI.

The maintainer reports **97/97 unit tests passed** and **5/5 Mate70 Release device acceptance checks passed**. Existing local XML reports also total 97 tests with no failures. Tests were not rerun for this documentation task. See [Testing](docs/TESTING.md) for evidence limits and [Compatibility](docs/COMPATIBILITY.md) for device scope.

## Getting started

Use JDK 17 and Android SDK Platform 35. Open the project root in Android Studio and sync Gradle. Build a Debug APK on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

After installing your build, grant the requested access, select apps under “受控App管理”, start monitoring, and configure a quota or start Focus. A new database seeds an enabled Douyin entry, which can be changed in management. See the [Build guide](docs/BUILD.md) and [User guide](docs/USER_GUIDE.md).

## Data and privacy facts

The current application source contains no identified network requests, accounts, cloud sync, or analytics implementation, and its source Manifest does not declare `INTERNET`. Gradle downloads tools and dependencies during setup; this does not establish an offline build.

Room stores controlled-app settings and Gate events locally. SharedPreferences stores Focus state and history, extra time, and quota reminder state. Runtime logs may include package names, timestamps, and control state. The Manifest sets `allowBackup=true`; these documents therefore make no promise that system backup or device migration cannot transfer data, or that logs contain no usage traces. These are source observations, not a formal privacy policy.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [User guide](docs/USER_GUIDE.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Testing](docs/TESTING.md)
- [Build](docs/BUILD.md)
- [Changelog](CHANGELOG.md)
- [Pre-publication review](docs/SECURITY_REVIEW.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Original project code is licensed under the [MIT License](LICENSE). Copyright (c) 2026 zqw878210a-ctrl. Third-party components retain their own licenses.
