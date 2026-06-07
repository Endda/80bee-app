# 80bee Android App Project Plan

## Overview
The 80bee Android App is a standalone, native application designed to manage Android devices. It serves as a comprehensive control panel that brings the capabilities of the original "Web USB Panel" directly to an Android environment. 

The application has a dual-purpose design:
1. **OTG Host Mode:** When connected to another Android device via an OTG cable, it uses the Android USB Host API to execute ADB (and eventually Fastboot) commands on the connected device.
2. **Local Mode (Standalone):** When run directly on a device without an OTG connection, it leverages the Shizuku API to execute privileged shell commands locally, allowing users to modify their own device's settings and configurations.

## Architecture & Technical Approach
*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material Design 3) to accurately mirror the intuitive, card-based grid layout of the original web app.
*   **Connection Layer (`ConnectionManager`):**
    *   `OtgConnectionManager`: Wraps the `android.hardware.usb.UsbManager` to communicate via ADB protocols over USB. Will utilize an open-source library (e.g., `libadb-android` or `ADB-OTG`) for ADB communication, and a Fastboot library (e.g., `fastboot-mobile` or `fastboot-java`) for bootloader interaction.
    *   `ShizukuConnectionManager`: Wraps `rikka.shizuku:api` to execute local root-level shell commands.

## Phased Implementation Plan

### Phase 1: Scaffolding & Core Architecture (Completed)
*   [x] Initialize new Android project.
*   [x] Setup Jetpack Compose architecture and themes.
*   [x] Implement `ConnectionManager` interface.
*   [x] Create `OtgConnectionManager` and `ShizukuConnectionManager`.
*   [x] Build Device Detection UI to handle Shizuku permissions and OTG connection status.

### Phase 2: MVP Features
*   **Boot Manager:** Reboot to system, bootloader, fastboot, and recovery.
*   **Speed Tweaker:** Adjust window/transition/animator scales and display density.
*   **DNS Toggle:** Switch between Off, Opportunistic, and Custom Private DNS hostnames.
*   **Battery Optimizer:** Toggle adaptive battery, Doze modes, and service data toggles.
*   *Goal:* Test bidirectional command execution over OTG and locally via Shizuku.

### Phase 3: Advanced App Management
*   **Debloater Manager:** Parse `pm list`, handle package uninstall/disable via shell.
*   **Sideload Bypass:** File picker integration + `pm install` via ADB/Shizuku, bypassing low target SDK blocks.

### Phase 4: Complex Flows
*   **Pixel Magisk Tool:** Requires a Fastboot protocol library implementation over USB Host to flash `init_boot`/`boot` images (OTG only).
*   **PiHole & HA Installers:** Automating Termux intents and inputting setup scripts.
*   **GCam Detector:** Hardware validation and automated package deployment.

## Future Work & Feature Ideas
Beyond the initial web conversion, the native app opens up possibilities for deeper device integration:
*   **Advanced File Manager:** Utilizing the ADB Sync protocol for fast, robust file transfers between devices.
*   **Screen Mirroring:** Implementing an internal scrcpy client to view and control the connected OTG device directly within the app.
*   **Batch XAPK Installer:** Drag-and-drop or multi-select installation of split APKs.
*   **Device Health & Telemetry Dashboard:** Real-time graphs for CPU, battery temperature, and memory usage pulled via `dumpsys`.
*   **App Profiler:** Identifying jank, battery hogs, and background wakelocks using shell diagnostics.