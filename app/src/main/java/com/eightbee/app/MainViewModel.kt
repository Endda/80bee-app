package com.eightbee.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.features.SideloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

data class BatteryOptimizerState(
    val maxHibernation: Boolean = false,
    val dozeZero: Boolean = false,
    val backgroundDrainMonitor: Boolean = false,
    val lockRefreshRate60: Boolean = false,
    val disableWindowBlurs: Boolean = false,
    val superDim: Boolean = false,
    val samsungCpuBoosterOff: Boolean = false,
    val samsungRamPlusOff: Boolean = false,
    val samsungMotionEngineOff: Boolean = false
)

enum class BootMode(val shellCommand: String, val label: String) {
    SYSTEM("reboot", "Rebooting to system..."),
    RECOVERY("reboot recovery", "Rebooting to recovery..."),
    BOOTLOADER("reboot bootloader", "Rebooting to bootloader..."),
    FASTBOOTD("reboot fastboot", "Rebooting to fastbootd...")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val shizukuManager = ShizukuConnectionManager()
    val otgManager = OtgConnectionManager(
        application.applicationContext,
        application.getSystemService(Context.USB_SERVICE) as UsbManager
    )

    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val executionMutex = Mutex()

    // Navigation State
    private val _currentScreen = MutableStateFlow(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()
    private val navStack = mutableListOf(Screen.Dashboard)

    private val debloaterController = DebloaterController(
        application = application,
        runExclusive = { status, notify, block ->
            runExclusive(status, notifySnackbar = notify, block = block)
        }
    )

    private val batteryOptimizerController = BatteryOptimizerController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        runExclusive = { status, notify, block ->
            runExclusive(status, notifySnackbar = notify, block = block)
        }
    )

    private val bootloaderWizardController = BootloaderWizardController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        onOutput = { _output.value = it }
    )

    private val sideloadController = SideloadController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value }
    )

    private val magiskPatcherController = MagiskPatcherController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value }
    )

    val debloatList: StateFlow<List<BloatInfo>> = debloaterController.debloatList
    val degoogleList: StateFlow<List<PresetPkg>> = debloaterController.degoogleList
    val desamsungList: StateFlow<List<PresetPkg>> = debloaterController.desamsungList
    val restoreList: StateFlow<List<PresetPkg>> = debloaterController.restoreList
    val snapshotFeedback: StateFlow<String?> = debloaterController.snapshotFeedback

    val batteryOptimizerState: StateFlow<BatteryOptimizerState> = batteryOptimizerController.state
    val bootloaderWizardState: StateFlow<BootloaderWizardState> = bootloaderWizardController.state
    val sideloadState: StateFlow<SideloadState> = sideloadController.state
    val patcherState: StateFlow<PixelMagiskPatcherState> = magiskPatcherController.state

    init {
        shizukuManager.startListening()
        viewModelScope.launch {
            activeConnection.collectLatest { conn ->
                if (conn is OtgConnectionManager) {
                    conn.connectionState.collect { state ->
                        magiskPatcherController.onConnectionStateChanged(state)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        shizukuManager.close()
        otgManager.close()
        val serviceConn = BackgroundOperationService.connectionManager
        if (serviceConn === otgManager || serviceConn === _activeConnection.value) {
            BackgroundOperationService.connectionManager = null
        }
    }

    fun navigateTo(screen: Screen) {
        if (navStack.lastOrNull() != screen) {
            navStack.add(screen)
            _currentScreen.value = screen
        }
    }

    fun navigateBack(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            _currentScreen.value = navStack.last()
            return true
        }
        return false
    }

    fun resetNavigation() {
        navStack.clear()
        navStack.add(Screen.Dashboard)
        _currentScreen.value = Screen.Dashboard
    }

    fun setActiveConnection(connection: ConnectionManager?) {
        _activeConnection.value = connection
        BackgroundOperationService.connectionManager = connection
        if (connection != null) {
            refreshSettings()
        } else {
            resetNavigation()
        }
    }

    private fun runExclusive(
        initialStatus: String,
        notifySnackbar: Boolean = false,
        refreshDeviceSettings: Boolean = false,
        block: suspend (ConnectionManager) -> String
    ) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            executionMutex.withLock {
                _isExecuting.value = true
                _output.value = initialStatus
                try {
                    val result = block(connection).ifBlank { "Command completed." }
                    _output.value = result
                    if (notifySnackbar) {
                        _snackbarEvents.tryEmit(summarizeForSnackbar(result))
                    }
                    if (refreshDeviceSettings) {
                        refreshSettingsInternal(connection)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val errorMsg = "Error: ${e.message ?: "Operation failed"}"
                    _output.value = errorMsg
                    if (notifySnackbar) {
                        _snackbarEvents.tryEmit(errorMsg)
                    }
                } finally {
                    _isExecuting.value = false
                }
            }
        }
    }

    private fun summarizeForSnackbar(result: String): String {
        val firstLine = result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length > 90) firstLine.take(87) + "..." else firstLine.ifEmpty { "Done." }
    }

    fun refreshSettings() {
        val connection = _activeConnection.value ?: return
        if (!connection.isAvailable()) return
        if (connection is OtgConnectionManager && connection.isFastboot()) return

        viewModelScope.launch(Dispatchers.IO) {
            refreshSettingsInternal(connection)
        }
    }

    private suspend fun refreshSettingsInternal(connection: ConnectionManager) {
        if (!connection.isAvailable()) return
        if (connection is OtgConnectionManager && connection.isFastboot()) return

        val dnsMode = connection.runShellCommand("settings get global private_dns_mode")
        val dnsHost = connection.runShellCommand("settings get global private_dns_specifier")
        val winScale = connection.runShellCommand("settings get global window_animation_scale")
        val transScale = connection.runShellCommand("settings get global transition_animation_scale")
        val animScale = connection.runShellCommand("settings get global animator_duration_scale")
        val adaptiveBattery = connection.runShellCommand("settings get global adaptive_battery_management_enabled")

        _settings.value = DeviceSettings(
            privateDnsMode = dnsMode.trim(),
            privateDnsHostname = dnsHost.trim(),
            windowAnimationScale = winScale.trim(),
            transitionAnimationScale = transScale.trim(),
            animatorDurationScale = animScale.trim(),
            adaptiveBatteryEnabled = adaptiveBattery.trim() == "1"
        )
    }

    // --- Typed Device Actions (Private DNS, UI Adjustments, Boot, Battery Diagnostics) ---
    fun setPrivateDnsOff() {
        runExclusive("Disabling Private DNS...", notifySnackbar = true, refreshDeviceSettings = true) { conn ->
            conn.runShellCommand("settings put global private_dns_mode off")
            "Private DNS turned off."
        }
    }

    fun setPrivateDnsAuto() {
        runExclusive("Setting Private DNS to automatic...", notifySnackbar = true, refreshDeviceSettings = true) { conn ->
            conn.runShellCommand("settings put global private_dns_mode opportunistic")
            "Private DNS set to Automatic."
        }
    }

    fun setPrivateDnsHostname(rawHost: String) {
        val host = rawHost.trim()
        if (!isValidPrivateDnsHost(host)) {
            val msg = "Invalid Private DNS hostname."
            _output.value = msg
            _snackbarEvents.tryEmit(msg)
            return
        }
        runExclusive("Applying Private DNS ($host)...", notifySnackbar = true, refreshDeviceSettings = true) { conn ->
            conn.runShellCommand("settings put global private_dns_mode hostname; settings put global private_dns_specifier '$host'")
            "Private DNS set to $host."
        }
    }

    fun setAnimationScale(scale: Float) {
        if (scale !in listOf(0.0f, 0.5f, 1.0f)) return
        val label = "${scale}x"
        runExclusive("Setting animation scales to $label...", notifySnackbar = true, refreshDeviceSettings = true) { conn ->
            conn.runShellCommand(
                "settings put global window_animation_scale $scale; " +
                    "settings put global transition_animation_scale $scale; " +
                    "settings put global animator_duration_scale $scale"
            )
            "Animation scales set to $label."
        }
    }

    fun setDisplayDensity(dpi: Int) {
        if (dpi !in 120..800) {
            val msg = "DPI must be between 120 and 800."
            _output.value = msg
            _snackbarEvents.tryEmit(msg)
            return
        }
        runExclusive("Applying display density $dpi...", notifySnackbar = true) { conn ->
            conn.runShellCommand("wm density $dpi")
            "Display density set to $dpi."
        }
    }

    fun resetDisplayDensity() {
        runExclusive("Resetting display density...", notifySnackbar = true) { conn ->
            conn.runShellCommand("wm density reset")
            "Display density reset to stock."
        }
    }

    fun rebootDevice(mode: BootMode) {
        runExclusive(mode.label, notifySnackbar = true) { conn ->
            val out = conn.runShellCommand(mode.shellCommand)
            out.ifBlank { "${mode.label} command sent." }
        }
    }

    fun setForceDoze(enabled: Boolean) {
        val status = if (enabled) "Forcing Doze standby..." else "Unforcing Doze standby..."
        val cmd = if (enabled) "dumpsys deviceidle force-idle" else "dumpsys deviceidle unforce"
        runExclusive(status, notifySnackbar = true) { conn ->
            conn.runShellCommand(cmd).ifBlank {
                if (enabled) "Forced Doze standby." else "Disabled forced Doze."
            }
        }
    }

    fun setAdaptiveBattery(enabled: Boolean) {
        val status = if (enabled) "Enabling Adaptive Battery..." else "Disabling Adaptive Battery..."
        val flag = if (enabled) "1" else "0"
        runExclusive(status, notifySnackbar = true, refreshDeviceSettings = true) { conn ->
            conn.runShellCommand("settings put global adaptive_battery_management_enabled $flag")
            if (enabled) "Adaptive Battery enabled." else "Adaptive Battery disabled."
        }
    }

    fun simulateBatteryUnplug() {
        runExclusive("Simulating battery unplug...", notifySnackbar = true) { conn ->
            conn.runShellCommand("dumpsys battery unplug").ifBlank { "Battery unplug simulation active." }
        }
    }

    fun resetBatterySimulation() {
        runExclusive("Resetting battery state...", notifySnackbar = true) { conn ->
            conn.runShellCommand("dumpsys battery reset").ifBlank { "Battery state reset to hardware reporting." }
        }
    }

    // --- Debloater & Snapshot Delegates ---
    fun scanBloatware(safetyLevel: String) = debloaterController.scanBloatware(safetyLevel)
    fun scanDegoogle(maxTier: Int) = debloaterController.scanDegoogle(maxTier)
    fun scanDesamsung(maxTier: Int) = debloaterController.scanDesamsung(maxTier)
    fun disablePackages(packages: List<String>, isUninstall: Boolean) = debloaterController.disablePackages(packages, isUninstall)
    fun restorePackages(packages: List<String>) = debloaterController.restorePackages(packages)
    fun scanDisabledPackages() = debloaterController.scanDisabledPackages()
    fun exportPackageSnapshot(uri: Uri) = debloaterController.exportSnapshotToUri(uri)
    fun importPackageSnapshot(uri: Uri) = debloaterController.importSnapshotFromUri(uri)

    // --- Bluetooth Repair Logic ---
    fun executeBluetoothRepair(oemOverride: String, isAdvanced: Boolean) {
        runExclusive("Starting Bluetooth Repair...", notifySnackbar = true) { conn ->
            _output.value = "Disabling Bluetooth..."
            conn.runShellCommand("cmd bluetooth_manager disable || svc bluetooth disable")
            delay(2000)

            val packages = mutableListOf(
                "com.android.bluetooth",
                "com.android.bluetoothmidiservice",
                "com.google.android.bluetooth"
            )
            if (isAdvanced) {
                packages.add("com.android.networkstack")
            }
            when (oemOverride) {
                "samsung" -> packages.add("com.samsung.android.bluetooth")
                "xiaomi" -> packages.addAll(listOf("com.miui.bluetooth", "com.xiaomi.bluetooth", "com.xiaomi.bluetooth.overlay"))
                "oneplus" -> packages.addAll(listOf("com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"))
                "motorola" -> packages.addAll(listOf("com.motorola.bluetooth", "com.motorola.bt"))
            }

            for (pkg in packages) {
                if (isAdvanced) {
                    _output.value = "Clearing data for $pkg..."
                    conn.runShellCommand("pm clear $pkg")
                } else {
                    _output.value = "Restarting $pkg..."
                    conn.runShellCommand("am force-stop $pkg")
                }
            }

            if (isAdvanced) {
                val sdkInt = conn.runShellCommand("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
                if (sdkInt >= Build.VERSION_CODES.R) {
                    _output.value = "Cycling radios via Airplane Mode..."
                    val originalApState = conn.runShellCommand("settings get global airplane_mode_on").trim().toIntOrNull() ?: 0
                    if (originalApState == 1) {
                        conn.runShellCommand("cmd connectivity airplane-mode disable")
                        delay(4000)
                        conn.runShellCommand("cmd connectivity airplane-mode enable")
                        delay(4000)
                    } else {
                        conn.runShellCommand("cmd connectivity airplane-mode enable")
                        delay(4000)
                        conn.runShellCommand("cmd connectivity airplane-mode disable")
                        delay(4000)
                    }
                } else {
                    _output.value = "Skipping Airplane Mode radio cycle (cmd connectivity requires Android 11+; target SDK is $sdkInt)."
                }
            }

            _output.value = "Enabling Bluetooth..."
            conn.runShellCommand("cmd bluetooth_manager enable || svc bluetooth enable")
            delay(3000)

            _output.value = "Checking Bluetooth state..."
            val dump = conn.runShellCommand("dumpsys bluetooth_manager")
            "Repair complete!\n\n$dump"
        }
    }

    fun applyBluetoothQuirk(oem: String) {
        val targetPackages = when (oem) {
            "xiaomi" -> listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.miui.bluetooth")
            "oneplus" -> listOf("com.android.bluetooth", "com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth")
            else -> return
        }

        runExclusive("Applying ${oem.uppercase()} Bluetooth Doze & background exemptions...", notifySnackbar = true) { conn ->
            val exempted = mutableListOf<String>()
            for (pkg in targetPackages) {
                val pathOutput = conn.runShellCommand("pm path $pkg")
                if (pathOutput.contains("package:")) {
                    conn.runShellCommand("dumpsys deviceidle whitelist +$pkg")
                    conn.runShellCommand("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
                    exempted.add(pkg)
                }
            }
            if (exempted.isNotEmpty()) {
                "Exempted ${exempted.size} Bluetooth package(s):\n${exempted.joinToString("\n")}"
            } else {
                "No matching Bluetooth packages found for ${oem.uppercase()}."
            }
        }
    }

    // --- Audio Mods ---
    fun executeAudioBypass() {
        runExclusive("Applying Audio Safety Bypass...", notifySnackbar = true) { conn ->
            val targetApiLevel = conn.runShellCommand("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
            if (targetApiLevel >= 34) {
                conn.runShellCommand("settings put global audio_safe_csd_next_warning 999999.0")
            }
            conn.runShellCommand("settings put global safe_media_volume_enabled 0")
            conn.runShellCommand("settings put global audio_safe_volume_state 3")
            "Bypass applied successfully! Rebooting the device is highly recommended."
        }
    }

    fun restoreAudioDefaults() {
        runExclusive("Restoring Audio Safety Defaults...", notifySnackbar = true) { conn ->
            conn.runShellCommand("settings delete global audio_safe_csd_next_warning")
            conn.runShellCommand("settings put global safe_media_volume_enabled 1")
            conn.runShellCommand("settings put global audio_safe_volume_state 1")
            "Defaults restored! Reboot recommended."
        }
    }

    // --- Battery Optimizer Delegates ---
    fun refreshBatteryOptimizerState() = batteryOptimizerController.refreshState()
    fun applyMaxHibernation() = batteryOptimizerController.applyMaxHibernation()
    fun restoreMaxHibernation() = batteryOptimizerController.restoreMaxHibernation()
    fun applyDozeZero() = batteryOptimizerController.applyDozeZero()
    fun restoreDozeZero() = batteryOptimizerController.restoreDozeZero()
    fun setBackgroundDrainMonitor(enabled: Boolean) = batteryOptimizerController.setBackgroundDrainMonitor(enabled)
    fun setLockRefreshRate60(enabled: Boolean) = batteryOptimizerController.setLockRefreshRate60(enabled)
    fun setDisableWindowBlurs(enabled: Boolean) = batteryOptimizerController.setDisableWindowBlurs(enabled)
    fun setSamsungCpuResponsiveness(enabled: Boolean) = batteryOptimizerController.setSamsungCpuResponsiveness(enabled)
    fun setSamsungRamPlusOff(disabled: Boolean) = batteryOptimizerController.setSamsungRamPlusOff(disabled)
    fun setSamsungMotionEngineOff(disabled: Boolean) = batteryOptimizerController.setSamsungMotionEngineOff(disabled)
    fun applySuperDim() = batteryOptimizerController.applySuperDim()
    fun restoreSuperDim() = batteryOptimizerController.restoreSuperDim()

    // --- Foreground Service Controllers ---
    fun startArtCompilation() {
        val app = getApplication<Application>()
        val intent = Intent(app, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_START_COMPILE
        }
        app.startForegroundService(intent)
    }

    fun stopArtCompilation() {
        val app = getApplication<Application>()
        val intent = Intent(app, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_STOP_COMPILE
        }
        app.startService(intent)
    }

    fun startBatteryMonitoring() {
        val app = getApplication<Application>()
        val intent = Intent(app, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_START_BATTERY_MONITOR
        }
        app.startForegroundService(intent)
    }

    fun stopBatteryMonitoring() {
        val app = getApplication<Application>()
        val intent = Intent(app, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_STOP_BATTERY_MONITOR
        }
        app.startService(intent)
    }

    // --- Home Assistant Installer Logic ---
    fun prepareHaSystem() {
        runExclusive("Preparing system for Home Assistant...", notifySnackbar = true) { conn ->
            conn.runShellCommand("device_config set_sync_disabled_for_tests persistent")
            conn.runShellCommand("device_config put activity_manager max_phantom_processes 2147483647")
            conn.runShellCommand("settings put global settings_enable_monitor_phantom_procs false")
            conn.runShellCommand("appops set com.termux SYSTEM_ALERT_WINDOW allow")
            conn.runShellCommand("dumpsys deviceidle whitelist +com.termux")
            "System preparation complete. Phantom Process Killer disabled and Termux whitelisted."
        }
    }

    // /sdcard/Download/setup_ha.sh is pushed onto the remote ADB target, not read on the local host.
    @SuppressLint("SdCardPath")
    fun deployHomeAssistant() {
        val app = getApplication<Application>()
        runExclusive("Checking Termux installation...", notifySnackbar = true) { conn ->
            val termuxPath = conn.runShellCommand("pm path com.termux").trim()
            if (!termuxPath.startsWith("package:")) {
                return@runExclusive "Error: Termux (com.termux) is not installed on the target device."
            }

            val isShizuku = conn is ShizukuConnectionManager
            _output.value = "Generating installation script..."
            // Configure ~/.termux/termux.properties inside Termux itself because shell UID 2000
            // cannot write to /data/data/com.termux under Android's app sandbox.
            val setupScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                echo "=== Configuring Termux permissions ==="
                mkdir -p "${'$'}HOME/.termux"
                chmod 700 "${'$'}HOME/.termux"
                PROPERTIES_FILE="${'$'}HOME/.termux/termux.properties"
                if grep -q "allow-external-apps" "${'$'}PROPERTIES_FILE" 2>/dev/null; then
                    sed -i 's/^#* *allow-external-apps *=.*/allow-external-apps=true/' "${'$'}PROPERTIES_FILE"
                else
                    echo "allow-external-apps=true" >> "${'$'}PROPERTIES_FILE"
                fi
                echo "=== Starting Home Assistant Core installation ==="
                pkg update -y && pkg upgrade -y
                pkg install python rust libjpeg-turbo libffi binutils openssl -y
                pip install --upgrade pip
                pip install homeassistant
                echo "=== Home Assistant Core installation finished ==="
                echo "Run 'hass' command to start server."
            """.trimIndent()

            val tempFile = File(app.cacheDir, "setup_ha.sh")
            try {
                tempFile.writeText(setupScript)
                tempFile.inputStream().use { inputStream ->
                    if (conn is OtgConnectionManager) {
                        conn.pushFile(inputStream, tempFile.length(), "/sdcard/Download/setup_ha.sh")
                    } else {
                        conn.runShellCommandWithInput("cat > /sdcard/Download/setup_ha.sh", inputStream)
                    }
                }
            } finally {
                tempFile.delete()
            }

            val verifyOut = conn.runShellCommand("test -s /sdcard/Download/setup_ha.sh && echo OK").trim()
            if (verifyOut != "OK") {
                return@runExclusive "Error: Failed to stage /sdcard/Download/setup_ha.sh on target device."
            }

            _output.value = "Launching Termux and running installer..."
            conn.runShellCommand("am start -n com.termux/com.termux.app.TermuxActivity")
            delay(2000)

            if (isShizuku) {
                _output.value = "[Shizuku Guard] Keystroke injection may fail depending on OEM security settings. If it fails, open Termux and run: bash /sdcard/Download/setup_ha.sh"
            }
            conn.runShellCommand("input text bash")
            conn.runShellCommand("input keyevent 62")
            conn.runShellCommand("input text /sdcard/Download/setup_ha.sh")
            conn.runShellCommand("input keyevent 66")

            "Deployment commands sent! Check Termux on your device."
        }
    }

    // --- Bootloader Wizard Delegates ---
    fun startBootloaderWizard(isUnlock: Boolean) = bootloaderWizardController.start(isUnlock)
    fun clearBootloaderWizardState() = bootloaderWizardController.clear()
    fun proceedFromWarnings() = bootloaderWizardController.proceedFromWarnings()
    fun transitionToCommandSelection() = bootloaderWizardController.transitionToCommandSelection()
    fun proceedFromCommandSelection(command: String) = bootloaderWizardController.proceedFromCommandSelection(command)
    fun goBackToCommandSelection() = bootloaderWizardController.goBackToCommandSelection()
    fun confirmAndExecuteBootloaderCommand() = bootloaderWizardController.confirmAndExecute()
    fun completePhysicalConfirmation() = bootloaderWizardController.completePhysicalConfirmation()

    // --- Sideload Delegates ---
    fun startSideload(uri: Uri) = sideloadController.startSideload(uri)
    fun executeSideloadInstall(
        config: SideloadState.ConfigReady,
        bypassLowTargetSdk: Boolean,
        grantAllPermissions: Boolean,
        allowDowngrade: Boolean,
        testOnly: Boolean,
        installToSdCard: Boolean
    ) = sideloadController.executeInstall(
        config = config,
        bypassLowTargetSdk = bypassLowTargetSdk,
        grantAllPermissions = grantAllPermissions,
        allowDowngrade = allowDowngrade,
        testOnly = testOnly,
        installToSdCard = installToSdCard
    )
    fun clearSideloadState() = sideloadController.clear()

    // --- Pixel Magisk Patcher Delegates ---
    fun startPatcher() = magiskPatcherController.startPatcher()
    fun clearPatcherState() = magiskPatcherController.clearPatcherState()
    fun goToPatcherStep(step: Int) = magiskPatcherController.goToStep(step)
    fun startMagiskRecon() = magiskPatcherController.startRecon()
    fun handleMagiskBootFileSelected(uri: Uri) = magiskPatcherController.handleBootFileSelected(uri)
    fun handleMagiskApkSelected(uri: Uri) = magiskPatcherController.handleMagiskApkSelected(uri)
    fun startMagiskPatching() = magiskPatcherController.startPatching()
    fun rebootMagiskToBootloader() = magiskPatcherController.rebootToBootloader()
    fun flashMagiskPatchedImage() = magiskPatcherController.flashPatchedImage()
    fun installMagiskApkFromPatcher() = magiskPatcherController.installMagiskApk()

    companion object {
        // Dot-separated labels of letters, digits and hyphens, e.g. dns.quad9.net.
        private val PRIVATE_DNS_HOST_REGEX =
            Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

        fun isValidPrivateDnsHost(host: String): Boolean = PRIVATE_DNS_HOST_REGEX.matches(host.trim())
    }
}
