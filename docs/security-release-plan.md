# Security & Public Release Plan

This document outlines the necessary steps to transition the 80bee app from a private debug build to a secure, public-ready release suitable for sharing with a wider audience.

## 1. Eliminate Debug Vulnerabilities
*   **Action:** Change build variant to `release`.
*   **Why:** This automatically sets `android:debuggable="false"`, preventing unauthorized debuggers from attaching to the process and inspecting memory or RSA keys.

## 2. Code Obfuscation (R8/ProGuard)
*   **Action:** Enable `isMinifyEnabled = true` in `app/build.gradle.kts`.
*   **Why:** Shrinks the app size and obfuscates the code, making it significantly harder for bad actors to reverse-engineer your ADB/Fastboot logic or create malicious clones.

## 3. Secure RSA Key Management
*   **Action:** Ensure RSA private keys are stored only in internal storage (`context.filesDir`) and are never backed up to the cloud via `android:allowBackup="false"` unless encrypted.
*   **Why:** Prevents the "Target" device's trust from being compromised if the "Host" phone's data is synced to an insecure location.

## 4. Production Logging (Log Stripping)
*   **Action:** Integrate a logging wrapper like `Timber`.
*   **Why:** Ensures that verbose `Log.d` and `Log.v` calls—which may contain device IDs or sensitive handshake data—are stripped out of the final release build.

## 5. Developer Signing
*   **Action:** Create a dedicated `.jks` keystore and sign all public APKs.
*   **Why:** 
    *   Establishes "Proof of Origin" for your subscribers.
    *   Enables seamless over-the-air updates (Android prevents installing an update if the signature doesn't match the original).
    *   Reduces "Play Protect" warnings.

## 6. User Transparency & Education
*   **Action:** Provide a "Security & Privacy" section in the app or on the YouTube description.
*   **Why:** Explain why the app needs USB permissions and how it handles the target phone's data, building trust with your community.

---
**Status:** This plan is a prerequisite for sharing the 80bee app with YouTube subscribers or any public forum.
