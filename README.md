# 80bee 🐝

[![Google Play Store](https://img.shields.io/badge/Google_Play-Approved-3DDC84?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.eightybee.app)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Endda/80bee-app?color=007ACC&logo=github)](https://github.com/Endda/80bee-app/releases)
[![License](https://img.shields.io/badge/License-Apache_2.0-orange.svg?logo=apache)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-229900?logo=android&logoColor=white)](https://developer.android.com/about/versions)

**80bee** is a native, standalone Android utility designed for complete, root-free device management. It brings the power of developer tools like ADB (Android Debug Bridge) and Fastboot directly into a clean, modern, Material Design 3 interface.

The application operates in two modes:
1. **Local Mode (Standalone):** Runs on your own device, utilizing the [Shizuku API](https://shizuku.rikka.app/) to execute system-level commands and modify device configurations without requiring root access.
2. **OTG Host Mode (Device-to-Device):** When connected to another Android device via a USB OTG cable, it turns your device into an ADB/Fastboot host to debug, configure, or flash the connected client device.

---

## 🚀 Key Features

*   **⚡ Boot Manager:** Reboot any device (local or connected via OTG) to System, Bootloader, Fastboot, or Recovery modes.
*   **📱 Speed Tweaker:** Modify window/transition/animator scaling values or adjust the display density (DPI) dynamically.
*   **🔒 DNS Toggle:** Configure system-wide Private DNS settings (Opportunistic, Custom Hostnames, or Off).
*   **🔋 Battery Optimizer:** Manage adaptive battery status, toggle deep Doze modes, and adjust background service configurations.
*   **📦 Debloater Manager:** Uninstall, disable, or re-enable pre-installed system apps and bloatware (`pm disable` / `pm uninstall`).
*   **📥 Sideload Bypass:** Sideload APK files and bypass Android's target SDK limitations (e.g., installing legacy apps on Android 14+).
*   **🛠️ Pixel Magisk Tool (OTG only):** Fastboot interface for Google Pixel devices to flash `init_boot` and `boot` images for easy patching.

---

## 📐 Architecture & Libraries

80bee is written in **Kotlin** with **Jetpack Compose** and leverages:
*   **[Shizuku API](https://github.com/RikkaApps/Shizuku-API):** Communicates with the local Shizuku service to run privileged shell commands root-free.
*   **[libsu](https://github.com/topjohnwu/libsu):** Handles root/shell script execution interfaces safely.
*   **[fastboot-java](https://github.com/rohitverma882/fastboot-java):** Fastboot protocol implementation over USB host for bootloader interaction.
*   **Android USB Host API:** Interacts with the USB controller directly to communicate via raw USB protocols for OTG debugging.

---

## ⚙️ Prerequisites

### For Local Mode (Standalone)
To run commands on your own device, you need the **Shizuku** app installed and running:
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
To compile the universal release APK:
```powershell
# Clone the repository
git clone https://github.com/Endda/80bee-app.git
cd 80bee-app

# Compile and sign the release APK
./gradlew :app:assembleRelease
```
The output signed APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## ⚠️ Disclaimer

80bee is a powerful utility designed for developers and power users.
*   Modifying system settings, disabling critical package packages, or flashing boot partitions via Fastboot can cause bootloops or system instability.
*   Use this application at your own risk. The developer is not responsible for any bricked devices or loss of data.

---

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
