# Changelog

All notable changes to **80bee** are documented in this file.

## [1.5] - 2026-11-19 (`versionCode 5`)

### Compatibility & Platform
- Made `android.hardware.usb.host` optional (`android:required="false"`) so Shizuku-only devices without USB OTG Host support can install from Google Play.
- Updated `compileSdk` and `targetSdk` to API 37 (Android 17).
- Upgraded Android Gradle Plugin (`9.2.0`), Gradle (`9.9.1`), and core AndroidX/Lifecycle/Activity dependencies.

### UI & Assets
- Moved adaptive launcher icons to `mipmap-anydpi/`, added `<monochrome>` themed icon support, and removed legacy pre-API-26 PNG launcher icons.
- Extracted hardcoded UI strings into `strings.xml` and standardized theme colors.

### Architecture, Security & Stability
- Extracted feature controllers (`BatteryOptimizerController`, `BootloaderWizardController`, `DebloaterController`, `MagiskPatcherController`, `SideloadController`) and Pixel Magisk Patcher step composables out of `MainViewModel` and `PixelMagiskPatcherScreen`.
- Hardened `BackgroundOperationService` foreground lifecycle/timeout handling, `OtgConnectionManager` thread safety, and `ShizukuConnectionManager` listener lifecycle.
- Protected `ShizukuProvider` with `android.permission.INTERACT_ACROSS_USERS_FULL` and disabled automatic backups (`android:allowBackup="false"`) with explicit exclusion rules.
- Deduplicated `app/src/main/assets/80bee_ultimate_db.json`, aligned GPL-3.0 licensing with `adblib` BSD-3-Clause notices, migrated `:adblib` to Kotlin DSL, and added GitHub Actions CI + unit tests.

## [1.3] - 2026-11-18 (`versionCode 4`)

### Features
- Added the 6-step **Pixel Magisk Patcher** workflow (`init_boot`/`boot` extraction, patching, and flashing).
- Replaced `fastboot-java` with a custom `FastbootUsbClient` built directly on Android's USB Host API.
- Added full feature parity screens: **Boot Manager**, **UI Adjustments**, **Private DNS Toggle**, **Battery Optimizer & Diagnostics** (with `BackgroundOperationService` for ART compilation and live telemetry), **Debloater Manager** (with `80bee_ultimate_db.json`, `degoogle_db.json`, `samsung_db.json`, and snapshot backup/restore), **Audio Mods**, **Bluetooth Repair**, **Bootloader Utilities**, and **Home Assistant Installer**.

### Security & Reliability
- Fixed USB permission `PendingIntent.FLAG_MUTABLE` handling on API 31+ and streamed `OtgConnectionManager.pullFile` directly to disk.
- Bounded split-APK archive extraction (200 APK entries / 4 GiB unpacked max), validated custom Private DNS hostnames, validated Magisk recon targets and patched images, and bounded `adblib` header/payload allocations.
- Stripped verbose/debug/info logs in release builds via R8, added `SECURITY.md`, and added unit test gating to CI/release workflows.

## [1.2] - 2026-06-12 (`versionCode 3`)

- Implemented the guided **Bootloader Lock/Unlock Wizard** (`BootloaderWizardBottomSheet`).
- Fixed Samsung USB OTG compatibility in `OtgConnectionManager`.

## [1.1] - 2026-06-09

- Implemented the interactive **APK Sideload Installer** (`SideloadBottomSheet`) with `.apkm`, `.xapk`, and `.apks` split-APK archive support.

## [1.0.0] - 2026-06-07

- Initial release with Local Mode (Shizuku), OTG Host Mode (`adblib`), and automated release build workflow.
