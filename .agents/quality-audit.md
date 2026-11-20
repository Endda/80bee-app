# Quality audit
Generated: 2026-11-20 · Base commit: c03bab5
Verify: `./gradlew testDebugUnitTest` · `./gradlew lintDebug` · `./gradlew assembleDebug`
Baseline: `:adblib:testDebugUnitTest`, `:app:testDebugUnitTest`, `:adblib:lintDebug`, `:app:lintDebug`, and `:app:assembleDebug` all pass (`BUILD SUCCESSFUL`). Note: working tree at plan generation contains pre-existing uncommitted changes; Phase 2 branches must start from a clean working tree.

| # | Status | Target | Problem | Move | Risk |
|---|--------|--------|---------|------|------|
| 1 | done | app/src/main/java/com/eightbee/app/MagiskPatcherHelper.kt (450 -> 421 lines) | Duplicates inner/outer zip extraction, codename/build mismatch checks, and `copyBounded` loops | Collapse inner/outer extraction and build checks into shared helpers; reuse `FeatureUtils.copyBounded` | low |
| 2 | todo | app/src/main/java/com/eightbee/app/ui/DebloaterScreen.kt (765 lines), DebloaterController.kt (258 lines) | String-sniffs English error text across boundary, repeats 4 item mappings + 3 selection filters + 3 restore cards | Use typed `SnapshotFeedback`, unify tab data projection & selection, extract restore card & subviews | low-medium |
| 3 | todo | app/src/main/java/com/eightbee/app/ui/BatteryOptimizerScreen.kt (371 lines) | Copy-pastes 9 identical title/description/`Switch` rows (~220 lines of UI boilerplate) | Replace repeated switch blocks with a data-driven `OptimizerToggleRow` list | low |
| 4 | todo | app/src/main/java/com/eightbee/app/features/SideloadController.kt (286 lines), ui/SideloadBottomSheet.kt (589 lines), SideloadState.kt (58 lines) | Repeats `apkFiles` deletion 10 times, passes 5 booleans positionally, duplicates 6 option rows, keeps unused `getDisplayName()` | Guard cleanup in one `try/finally`, bundle `InstallOptions`, drive checkboxes from descriptors, drop dead method | medium |
| 5 | todo | app/src/main/java/com/eightbee/app/ui/BootloaderWizardBottomSheet.kt (781 lines) | 781-line file with repeated checkbox/radio/warning/footer chrome across 8 step composables and duplicate custom-command validation | Split steps into `ui/bootloader/`, extract shared step chrome, align validation with controller | low-medium |
| 6 | todo | app/src/main/java/com/eightbee/app/features/MagiskPatcherController.kt (645 lines) | Nested try/catch fastboot slot fallback, 7 manual `Job?` fields, repeated stale-file cleanup and bounded URI copy | Flatten fastboot slot fallback into candidate loop, extract file-swap/copy helpers & job registry | medium |
| 7 | todo | app/src/main/java/com/eightbee/app/connection/OtgConnectionManager.kt (518 lines) | Repeats `withContext(Dispatchers.IO)` + `CancellationException`/`IOException` wrapping 5 times, stream cleanup, and chunked write loops | Extract `withAdbStream`, `wrapIoError`, `writeStreamToAdb`, and `clearConnectingDevice` helpers | medium-high |
| 8 | todo | Cross-cutting: ui/patcher/Step*.kt, MainActivity.kt (212 lines), BackgroundOperationService.kt (298 lines) | Duplicated alert cards across patcher steps, duplicated Android 13 `getParcelableExtra` compat code, global service singleton & FGS state | Extract `PatcherAlertCard`, `Intent.parcelableExtraCompat`, and unify `BackgroundOperationService` lifecycle/state | medium |

## 1. Unify zip extraction and build validation in `MagiskPatcherHelper`
- **Target:** `app/src/main/java/com/eightbee/app/MagiskPatcherHelper.kt`, `app/src/main/java/com/eightbee/app/features/FeatureUtils.kt`
- **Problem:** `extractBootImageFromStream` duplicates the exact "extract entry to cache file + choose primary vs `fallbackBootFile` + delete stale fallback" block for inner `image-*.zip` entries (`MagiskPatcherHelper.kt:203-218`) and outer entries (`MagiskPatcherHelper.kt:223-237`). `checkInnerZipBuildMismatch` (`:333-356`) and `checkArchiveFilenameBuildMismatch` (`:358-380`) duplicate the same codename-mismatch throw and build-ID mismatch warning logic. `copyEntryToFile` (`:273-302`) and `extractMagiskComponents` (`:420-434`) re-implement bounded stream copying already provided by `copyBounded` (`FeatureUtils.kt:19-35`).
- **Move:** Extract a shared helper for extracting a candidate boot entry (returning primary vs fallback), extract a shared `validateCodenameAndBuild` helper used by both inner zip and outer filename checks, and delegate bounded entry copying to `FeatureUtils.copyBounded` (parameterized with buffer size or standard 64 KB).
- **Safety net:** Covered by `MagiskPatcherHelperTest.kt` (`validateBootImageForPartition_distinguishesBootAndInitBoot`, `extractBootImageFromStream_streamsNestedZipAndValidatesBoardAndBuild`, `extractBootImageFromStream_cleansUpOnPartitionHeaderMismatch`, `resolveReconTarget_choosesPartitionAndRejectsMissingApiLevel`).
- **Notes:** Branch `quality/1-magisk-patcher-helper` (from `main` `22e1cae`). Commits: `29035d8` (characterization tests in `MagiskPatcherHelperTest.kt`), `7dbfd7d` (refactor). `MagiskPatcherHelper.kt` `450 -> 421` lines; `FeatureUtils.kt` `141 -> 144` lines. Instead of extracting separate entry-extraction helpers with parameter plumbing, unified the inner and outer zip loops directly via a local `scanZip` function inside `extractBootImageFromStream`, added `validateCodenameAndBuild`, and extended `FeatureUtils.copyBounded` with optional `bufferSize` and `onBytesCopied`. Verified `./gradlew testDebugUnitTest lintDebug assembleDebug` (`BUILD SUCCESSFUL`). No bugs logged or follow-up items added.

## 2. Decompose `DebloaterScreen` and type the snapshot feedback boundary
- **Target:** `app/src/main/java/com/eightbee/app/ui/DebloaterScreen.kt`, `app/src/main/java/com/eightbee/app/features/DebloaterController.kt`
- **Problem:** `DebloaterScreen.kt:653` sniffs English prose (`feedback.startsWith("Failed") || feedback.contains("Ignored")`) from `DebloaterController.kt:221,243,252` to decide error styling. In `DebloaterScreen.kt:75-130`, three identical `PresetPkg` mappings and four `remember` lists feed `activeItems`, while selected package filtering (`visibleItems.map { it.pkg }.filter { checkedPackages[it] == true }`) is duplicated at `:327`, `:339`, and `:393`. `RestoreControlsTab` (`:597-688`) repeats 3 identical `OutlinedCard` action blocks, pushing the file to 765 lines.
- **Move:** Replace `StateFlow<String?>` `snapshotFeedback` in `DebloaterController` with a small `SnapshotFeedback(val message: String, val isError: Boolean)` model. Unify `PresetPkg` / `BloatInfo` tab projection and selected-package lookup in `DebloaterScreen`, replace the 3 `OutlinedCard` blocks in `RestoreControlsTab` with a shared `RestoreActionCard`, and extract tab/dialog subcomponents into `ui/debloater/`.
- **Safety net:** `FeatureControllersTest.kt` covers `DebloaterController` scans and disable/restore state sync; add a characterization test in `FeatureControllersTest.kt` for `exportSnapshotToUri` / `importSnapshotFromUri` error and warning feedback before changing `snapshotFeedback`.
- **Notes:**

## 3. Data-drive toggle rows in `BatteryOptimizerScreen`
- **Target:** `app/src/main/java/com/eightbee/app/ui/BatteryOptimizerScreen.kt`
- **Problem:** `BatteryOptimizerScreen.kt:106-325` repeats 9 near-identical `Row { Column(Modifier.weight(1f)) { Text(...); Text(...) }; Switch(...) }` blocks (~220 lines of copy-pasted UI layout).
- **Move:** Extract an `OptimizerToggleRow(titleRes, descRes, checked, onCheckedChange)` composable and render the Deep System and Samsung OEM toggle groups from concise descriptor lists, cutting ~150 lines of boilerplate without changing state or controller behavior.
- **Safety net:** `BatteryOptimizerController` is covered by `FeatureControllersTest.kt`; UI verified via Kotlin compilation, `lintDebug`, and `assembleDebug`.
- **Notes:**

## 4. Centralize `SideloadController` cleanup and simplify `SideloadBottomSheet`
- **Target:** `app/src/main/java/com/eightbee/app/features/SideloadController.kt`, `app/src/main/java/com/eightbee/app/ui/SideloadBottomSheet.kt`, `app/src/main/java/com/eightbee/app/SideloadState.kt`
- **Problem:** `SideloadController.executeInstall` manually repeats `config.apkFiles.forEach { it.delete() }` 10 times across every early return and catch block (`SideloadController.kt:178,200,207,212,219,242,247,255,258,265`). `ConfigView` passes 5 positional booleans through `(Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit` (`SideloadBottomSheet.kt:70,136`) and duplicates 6 `Row { Checkbox; Column { title; desc } }` blocks (`:243-376`). `InstallStep.getDisplayName()` (`SideloadState.kt:50-57`) is unused alongside `labelResId()`.
- **Move:** Add characterization unit tests for `SideloadController.executeInstall` first. Then wrap `executeInstall` temp-file deletion in a single `try { ... } finally`, introduce a `SideloadInstallOptions` data class to replace the 5-boolean signature, render `ConfigView` checkboxes via a shared `SideloadOptionRow`, and remove unused `getDisplayName()`.
- **Safety net:** None currently for `SideloadController`; add unit tests in `FeatureControllersTest.kt` (covering install flags, streaming failure/abandon, and temp-file cleanup) in their own commit before refactoring.
- **Notes:**

## 5. Decompose `BootloaderWizardBottomSheet` into shared step chrome and step files
- **Target:** `app/src/main/java/com/eightbee/app/ui/BootloaderWizardBottomSheet.kt`, `app/src/main/java/com/eightbee/app/features/BootloaderWizardController.kt`
- **Problem:** `BootloaderWizardBottomSheet.kt` is 781 lines (largest Kotlin file) with duplicated icon+headline headers, warning `Card`s (`:165-176, 513-525`), `Row { Checkbox; Text }` blocks (`:184-232, 529-542`), `Row(selectable) { RadioButton; Column }` blocks (`:347-415`), and 2-button footers (`:236-254, 434-460, 546-564`). `CommandSelectionView` (`:325-333`) also mixes UI-side `FastbootCommandPolicy.validateWizardCustomCommand` with `controllerError` from `BootloaderWizardController.kt:78`.
- **Move:** Extract shared wizard chrome (`WizardWarningBanner`, `WizardCheckboxRow`, `WizardActionRow`, `WizardStepHeader`) and split step views into focused files under `ui/bootloader/` (or a clean two-file split), keeping `BootloaderWizardBottomSheet.kt` well under ~250 lines and clarifying custom-command validation ownership.
- **Safety net:** `BootloaderWizardController` transitions and policy validation are covered by `FeatureControllersTest.kt`; UI verified via compilation, `lintDebug`, and `assembleDebug`.
- **Notes:**

## 6. Simplify job, file-swap, and fastboot slot-retry logic in `MagiskPatcherController`
- **Target:** `app/src/main/java/com/eightbee/app/features/MagiskPatcherController.kt`
- **Problem:** `MagiskPatcherController` manages 7 separate nullable `Job?` fields (`:40-65`), repeats "delete previous file if path changed" 3 times (`:271-275, 347-351, 476-480`), repeats URI-to-cache bounded copy with cleanup (`:253-266, 320-335`), and nests two `try/catch` blocks inside an outer `try/catch` for `flashPatchedImage` slot-suffix fallback (`:536-573`).
- **Move:** Add characterization tests for `MagiskPatcherController` first (recon parsing, step bounds, `flashPatchedImage` primary and slotted retry). Then flatten `flashPatchedImage` into a loop over candidate partitions (`listOfNotNull(targetPartition, slottedIfDifferent)`), extract `replaceCacheFile` / `copyUriToCacheFile` helpers, and replace the 7 manual `Job?` fields with a small job map/helper.
- **Safety net:** None currently for `MagiskPatcherController` (only `MagiskPatcherHelper` is tested); add `MagiskPatcherController` unit tests first before refactoring.
- **Notes:**

## 7. Deduplicate stream lifecycle and error wrapping in `OtgConnectionManager`
- **Target:** `app/src/main/java/com/eightbee/app/connection/OtgConnectionManager.kt`
- **Problem:** Five `override suspend fun ... = withContext(Dispatchers.IO)` methods (`:285-414`) repeat identical `CancellationException`/`IOException`/generic `Exception` wrapping. Four ADB methods (`:285-383, 426-517`) repeat `adbConnection ?: throw IOException(...)`, `stream?.close()` cleanup, and chunked `ByteArray(4096)` stream writing (`:317-323, 440-448`). `connectDevice()` repeats the same `synchronized(stateLock)` reset block at `:163-168` and `:180-185`.
- **Move:** Add characterization tests around `OtgConnectionManager` stream read/write/error behavior (introducing a thin internal seam or testing extracted stream helpers if needed) first. Then extract `withAdbStream`, `wrapIoError`, `writeStreamToAdb`, and `resetConnectingDeviceIfCurrent`, cutting ~90 lines of duplicate error/cleanup plumbing.
- **Safety net:** None currently for `OtgConnectionManager` (`FastbootUsbClient` and `AdbStream` are tested separately); add unit tests before refactoring.
- **Notes:**

## 8. Clean up cross-cutting UI cards, `MainActivity` compat helpers, and `BackgroundOperationService` boundary
- **Target:** `app/src/main/java/com/eightbee/app/ui/patcher/StepConnectionRecon.kt`, `StepFirmwareHandoff.kt`, `StepFastbootFlash.kt`, `app/src/main/java/com/eightbee/app/MainActivity.kt`, `app/src/main/java/com/eightbee/app/BackgroundOperationService.kt`
- **Problem:** Patcher steps duplicate identical error/info `Card { Row { Icon; Text } }` banners (`StepConnectionRecon.kt:84-105,181-229`, `StepFirmwareHandoff.kt:200-245`, `StepFastbootFlash.kt:49-68`). `MainActivity.kt` duplicates the Android 13 `getParcelableExtra` version check (`:37-42, 188-193`) and `ACTION_USB_DEVICE_ATTACHED` dispatch (`:50-52, 187-195`). `BackgroundOperationService.kt` exposes a global mutable `companion object { var connectionManager: ConnectionManager? }` (`:31-32`) and repeats foreground start/stop/timeout state transitions across compile and battery monitor tasks (`:87-213`).
- **Move:** Extract `PatcherAlertCard` into `PatcherSharedComponents.kt`, add an `Intent.parcelableExtraCompat<T>` helper in `MainActivity.kt`, and extract a small task-state coordinator inside `BackgroundOperationService.kt` (keeping public `StateFlow` API compatible or cleanly bridged).
- **Safety net:** Compile/lint/assemble checks; add unit tests for any extracted pure `BackgroundOperationService` state logic.
- **Notes:**
