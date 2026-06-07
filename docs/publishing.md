# 80bee Publishing & Distribution Workflows

This document outlines the build and release processes for distributing 80bee across its three primary platforms: **Google Play Store**, **GitHub Releases**, and **F-Droid**.

---

## 1. Google Play Store Distribution (AAB)

To distribute updates to the Google Play Store, you must generate an **Android App Bundle (`.aab`)**. Play App Signing then splits and optimizes this bundle into individual device-specific APKs for users.

### Prerequisites (Local Only)
*   You must have the `release.keystore` file in the project root directory.
*   You must have `keystore.properties` in the project root containing the signing credentials (alias, passwords).
*   *Note: Both files are ignored in `.gitignore` to prevent leaking passwords/keys.*

### Build Process
Open your terminal in the project root and run:
```powershell
./gradlew :app:bundleRelease
```
*   **Output File:** `app/build/outputs/bundle/release/app-release.aab`
*   **Signature:** Signed with your local release signing key.

### Publishing Steps
1.  Log in to the **Google Play Console**.
2.  Navigate to your application -> **Production** (or test track).
3.  Create a new release.
4.  Upload the compiled `app-release.aab` file.
5.  Roll out the release to production.

---

## 2. GitHub Releases (Automated APK)

To distribute 80bee on GitHub, we distribute a standalone **Universal APK** containing all target CPU architectures and resources. This process is fully automated using GitHub Actions.

### Infrastructure Configured
*   **Workflow file:** `.github/workflows/release.yml`
*   **Encryption Secret:** Base64-encoded keystore stored in GitHub Secrets.

### Automated Release Steps
Whenever you are ready to publish a new version on GitHub:

1.  **Update Version Codes:**
    Increment the `versionCode` and `versionName` inside [app/build.gradle.kts](file:///E:/dev/80bee/app/build.gradle.kts):
    ```kotlin
    versionCode = 2
    versionName = "1.0.1"
    ```
    *Ensure you commit and push these changes to `main` first.*

2.  **Tag and Push:**
    Create a version tag matching the version name (prefixed with `v`) and push it to GitHub:
    ```powershell
    # Tag the current commit
    git tag -a v1.0.1 -m "Release version 1.0.1"
    
    # Push the tag to GitHub (triggers the runner)
    git push origin v1.0.1
    ```

3.  **Automatic Build & Upload:**
    *   GitHub Actions detects the tag and runs the build script.
    *   It retrieves the stored credentials from repository secrets (`RELEASE_KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`).
    *   It decodes your keystore, compiles the signed release APK, and attaches the `.apk` file directly to a new Draft Release page.
    *   You only need to visit the release page on GitHub to write the release notes and hit publish!

---

## 3. F-Droid Submission Workflow

F-Droid is an independent catalog of Free and Open Source Software (FOSS). Unlike Play Store or GitHub, **F-Droid compiles your app from your source code on their own servers** and signs it with their own signing key.

### Prerequisites Completed
*   **Public Repository:** The source code is publicly hosted on GitHub at `https://github.com/Endda/80bee-app` (Required by F-Droid).
*   **Open-Source License:** An Apache-2.0 [LICENSE](file:///E:/dev/80bee/LICENSE) has been added to the root directory (Required by F-Droid).
*   **FOSS Dependencies:** An audit of [app/build.gradle.kts](file:///E:/dev/80bee/app/build.gradle.kts) confirmed all dependencies (Compose, Shizuku, libsu, fastboot-java) are open-source and free of proprietary tracking/analytics (Required by F-Droid).

### What's Left to Submit to F-Droid
To get 80bee listed in the official F-Droid repository, you need to submit a metadata build recipe:

1.  **Fork F-Droid Data:**
    Fork the main F-Droid recipes repository on GitLab: [gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).

2.  **Create your Recipe file:**
    Add a new recipe file inside `templates/com.eightybee.app.yml` (replace with your exact application ID if it differs). Use the following recipe structure:
    ```yaml
    Categories:
      - System
      - Developer
    License: Apache-2.0
    WebSite: https://github.com/Endda/80bee-app
    SourceCode: https://github.com/Endda/80bee-app
    IssueTracker: https://github.com/Endda/80bee-app/issues

    RepoType: git
    Repo: https://github.com/Endda/80bee-app

    Builds:
      - versionName: '1.0.0'
        versionCode: 1
        commit: v1.0.0
        subdir: app
        gradle:
          - yes
    ```

3.  **Submit a Merge Request:**
    Submit a Merge Request on GitLab to merge your recipe file into `fdroiddata`. F-Droid's integration system will test-build your app from your tag.

4.  **Automatic Polling:**
    Once merged, you do not need to submit anything to F-Droid for future updates. F-Droid's build servers will periodically scan your GitHub tags, find any new release tags (like `v1.0.1`), compile them automatically, and update the app inside the F-Droid store.
