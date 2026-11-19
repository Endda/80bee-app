# 80bee 🐝

[![Google Play Store](https://img.shields.io/badge/Google_Play-Approved-3DDC84?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.eightybee.app)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Endda/80bee-app?color=007ACC&logo=github)](https://github.com/Endda/80bee-app/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-229900?logo=android&logoColor=white)](https://developer.android.com/about/versions)

**80bee** is a native, standalone Android utility designed for complete, root-free device management. It brings the power of developer tools like ADB (Android Debug Bridge) and Fastboot directly into a clean, modern, Material Design 3 interface.

The application operates in two modes:
1. **Local Mode (Standalone):** Runs on your own device, utilizing the [Shizuku API](https://shizuku.rikka.app/) to execute system-level commands and modify device configurations without requiring root access.
2. **OTG Host Mode (Device-to-Device):** When connected to another Android device via a USB OTG cable, it turns your device into an ADB/Fastboot host to debug, configure, or flash the connected client device.

**Note**: I have been hand-coding websites for over 25 years, but building a native Android app is outside of my skillset. 
So while I'm learning the ropes, this APK file is being developed with LLM editors (Antigravity). 
I research, test, and implement the features on the 80bee.com website. . .then those features get ported to the native app.

---

## 🚀 Key Features

*   **⚡ Boot Manager:** Reboot any device (local or connected via OTG) to System, Bootloader, Fastboot, or Recovery modes.
*   **📱 UI Adjustments:** Modify window/transition/animator scales or adjust display density (DPI) dynamically.
*   **🔒 Private DNS Toggle:** Configure system-wide Private DNS settings (Automatic, Custom Hostnames, or Off).
*   **🔋 Battery Optimizer & Diagnostics:** Manage Adaptive Battery, Doze policies, ART AOT speed compilation, live battery telemetry, and unplug/level simulation.
*   **📦 Debloater Manager:** Scan, disable, or uninstall pre-installed system apps with snapshot backup/restore and OEM/safety filtering.
*   **📥 APK / Split-APK Sideloader:** Install single APKs, `.apkm`/`.xapk`/`.apks` bundles with optional legacy target-SDK bypass.
*   **🎧 Audio Mods & Bluetooth Repair:** Adjust safe-volume/CSD warning thresholds, restart/clear Bluetooth stacks, and apply OEM Doze exemptions.
*   **🛠️ Bootloader & Pixel Magisk Patcher:** Guided bootloader lock/unlock wizard, OTG Fastboot utilities, and a 6-step Pixel `init_boot`/`boot` Magisk patching workflow.
*   **🏠 Home Assistant Installer:** Deploy Home Assistant Core inside Termux via ADB.

---

## 📐 Architecture & Libraries

80bee is written in **Kotlin** with **Jetpack Compose** (`minSdk 26` / Android 8.0+, `targetSdk 37` / Android 17, with themed monochrome launcher icon support) and leverages:
*   **[Shizuku API](https://github.com/RikkaApps/Shizuku-API):** Communicates with the local Shizuku service to run privileged shell commands root-free.
*   **[adblib](adblib/LICENSE):** Vendored Java ADB protocol implementation (based on `cgutman/AdbLib` with USB OTG channel extensions) for device-to-device ADB over USB Host.
*   **Custom `FastbootUsbClient`:** Direct USB bulk-transfer Fastboot protocol client built on Android's USB Host API for bootloader inspection and flashing.
*   **Android USB Host API:** Interacts with the USB controller directly for OTG ADB and Fastboot communication (`android.hardware.usb.host` is optional, so non-OTG devices can still run Local Mode).

---

## ⚙️ Prerequisites

### For Local Mode (Standalone)
To run commands on your own device (works even on devices without USB OTG Host hardware):
1. Download **Shizuku** from Google Play, F-Droid, or GitHub.
2. Open Shizuku and follow the setup instructions (utilizing **Wireless Debugging** in Android Developer Options).
3. Start Shizuku, open **80bee**, and authorize it when prompted.

### For OTG Host Mode
To control another device:
1. Enable **USB Debugging** on the target/client device (Settings > Developer Options > USB Debugging).
2. Connect both devices using a **USB OTG** cable/adapter.
3. Open **80bee** on the host device and grant USB permissions when prompted.

---

## 🛠️ Building from Source

You can build 80bee locally using Android Studio or the command line.

### Command Line Build
To compile the debug APK locally:
```powershell
# Clone the repository
git clone https://github.com/Endda/80bee-app.git
cd 80bee-app

# Compile the debug APK
./gradlew :app:assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`. (Release builds require a local `keystore.properties` signing configuration.)

---

## ⚠️ Disclaimer

80bee is a powerful utility designed for developers and power users.
*   Modifying system settings, disabling critical packages, or flashing boot partitions via Fastboot can cause bootloops or system instability.
*   Use this application at your own risk. The developer is not responsible for any bricked devices or loss of data.

---

## 📄 License & Attributions

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** - see the [LICENSE](LICENSE) file for details. See [CHANGELOG.md](CHANGELOG.md) for release history.

### Third-Party Notices
*   **[adblib](adblib/LICENSE):** Based on [cgutman/AdbLib](https://github.com/cgutman/AdbLib) (Copyright (c) 2013, Cameron Gutman, BSD 3-Clause License) with USB OTG channel extensions by xudong.
*   **[Universal Android Debloater Next Generation (UAD-NG)](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)** & **[BloatwareHatao](https://github.com/ImKKingshuk/BloatwareHatao):** Package metadata and descriptions in `app/src/main/assets/80bee_ultimate_db.json` are derived from UAD-NG and BloatwareHatao (GPL-3.0).
