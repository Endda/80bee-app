# Objective
Fix the OTG USB Host mode issues where the UI does not update when a device is connected, and the app freezes while waiting for USB authorization.

# Key Files & Context
- `app/src/main/java/com/eightbee/app/connection/ConnectionManager.kt`
- `app/src/main/java/com/eightbee/app/connection/OtgConnectionManager.kt`
- `app/src/main/java/com/eightbee/app/connection/ShizukuConnectionManager.kt`
- `app/src/main/java/com/eightbee/app/ui/DeviceDetectionScreen.kt`

# Implementation Steps

1. **Update ConnectionManager Interface:**
   - Add a `val isAvailableFlow: StateFlow<Boolean>` property to `ConnectionManager` to allow reactive UI updates.

2. **Fix `OtgConnectionManager.kt` (Threading & Reactivity):**
   - Add a private `MutableStateFlow<Boolean>` and implement `isAvailableFlow`.
   - Update `connectDevice()` to execute inside a background coroutine (`CoroutineScope(Dispatchers.IO).launch`) so `adbConnection?.connect()` doesn't block the main thread.
   - Update the state flow to `true` when the connection succeeds, and `false` when disconnected.

3. **Update `ShizukuConnectionManager.kt`:**
   - Implement `isAvailableFlow` backed by a `MutableStateFlow` that is initialized with `isAvailable()` and updated when permission is requested.

4. **Fix `DeviceDetectionScreen.kt` UI:**
   - Replace the static `LaunchedEffect` check with `collectAsState()` on the new `isAvailableFlow` properties for both managers, so the UI updates automatically.

# Verification & Testing
- Build and run the app.
- Connect an OTG device without authorizing it immediately. Verify the app does not freeze (ANR).
- Authorize the device. Verify the UI automatically updates to "Device Connected" without requiring a restart.