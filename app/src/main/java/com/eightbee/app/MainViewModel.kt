package com.eightbee.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

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

    private val _batteryOptimizerState = MutableStateFlow(BatteryOptimizerState())
    val batteryOptimizerState: StateFlow<BatteryOptimizerState> = _batteryOptimizerState.asStateFlow()

    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // Debloat list states
    private val _debloatList = MutableStateFlow<List<BloatInfo>>(emptyList())
    val debloatList: StateFlow<List<BloatInfo>> = _debloatList.asStateFlow()

    private val _degoogleList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val degoogleList: StateFlow<List<PresetPkg>> = _degoogleList.asStateFlow()

    private val _desamsungList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val desamsungList: StateFlow<List<PresetPkg>> = _desamsungList.asStateFlow()

    // Navigation State
    private val _currentScreen = MutableStateFlow(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()
    private val navStack = mutableListOf(Screen.Dashboard)

    // Pixel Magisk Patcher States
    private val _patcherState = MutableStateFlow(PixelMagiskPatcherState())
    val patcherState: StateFlow<PixelMagiskPatcherState> = _patcherState.asStateFlow()

    private val _patcherExtractLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val patcherExtractLogs: StateFlow<List<LogEntry>> = _patcherExtractLogs.asStateFlow()

    private val _patcherPatchLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val patcherPatchLogs: StateFlow<List<LogEntry>> = _patcherPatchLogs.asStateFlow()

    init {
        viewModelScope.launch {
            activeConnection.collectLatest { conn ->
                if (conn is OtgConnectionManager) {
                    conn.connectionState.collect { state ->
                        var shouldStartRecon = false
                        _patcherState.update { currentPatcherState ->
                            if (!currentPatcherState.isActive) {
                                currentPatcherState
                            } else {
                                when (state) {
                                    ConnectionState.FastbootConnected -> currentPatcherState.copy(
                                        isFastbootConnected = true,
                                        fastbootStatus = "STATUS: FASTBOOT_CONNECTED",
                                        flashStep = 3
                                    )
                                    ConnectionState.AdbConnected -> {
                                        if (currentPatcherState.currentStep == 1) {
                                            shouldStartRecon = true
                                        }
                                        currentPatcherState.copy(isFastbootConnected = false)
                                    }
                                    else -> currentPatcherState.copy(isFastbootConnected = false)
                                }
                            }
                        }
                        if (shouldStartRecon) {
                            startMagiskRecon()
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        otgManager.disconnect()
        if (BackgroundOperationService.connectionManager === otgManager) {
            BackgroundOperationService.connectionManager = null
        }
    }

    fun navigateTo(screen: Screen) {
        navStack.add(screen)
        _currentScreen.value = screen
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

    fun executeCommand(command: String) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing: $command..."
            val result = connection.runShellCommand(command)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            // Refresh settings after potentially changing them
            refreshSettings()
        }
    }

    fun executeCommandWithInput(command: String, input: InputStream) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing command with input..."
            val result = connection.runShellCommandWithInput(command, input)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            refreshSettings()
        }
    }

    fun refreshSettings() {
        val connection = _activeConnection.value ?: return
        if (!connection.isAvailable()) return

        viewModelScope.launch {
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
    }

    // Debloat and preset scan logic
    fun scanBloatware(context: Context, safetyLevel: String) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Scanning for bloatware..."
            try {
                val manufacturer = conn.runShellCommand("getprop ro.product.manufacturer").trim()
                val brand = conn.runShellCommand("getprop ro.product.brand").trim()

                val listOutput = conn.runShellCommand("pm list packages")
                val installed = listOutput.split("\n")
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:") }
                    .filter { it.isNotEmpty() }
                    .toSet()

                val db = PackageDatabase.loadUltimateDb(context)
                val allowedLevels = PackageDatabase.allowedSafetyLevels(safetyLevel)

                val filtered = db.values.filter { bloat ->
                    installed.contains(bloat.packageName) &&
                    allowedLevels.contains(bloat.safety.lowercase()) &&
                    PackageDatabase.matchesOem(bloat.oems, manufacturer, brand)
                }.sortedBy { it.label }

                _debloatList.value = filtered
                _output.value = "Scan complete. Found ${filtered.size} bloatware packages."
            } catch (e: Exception) {
                _output.value = "Scan failed: ${e.message}"
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun scanDegoogle(context: Context, maxTier: Int) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Scanning for Google packages..."
            try {
                val listOutput = conn.runShellCommand("pm list packages")
                val installed = listOutput.split("\n")
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:") }
                    .filter { it.isNotEmpty() }
                    .toSet()

                val db = PackageDatabase.loadDegoogleDb(context)
                val filtered = db.filter { preset ->
                    installed.contains(preset.id) && preset.tier <= maxTier
                }
                _degoogleList.value = filtered
                _output.value = "Scan complete. Found ${filtered.size} Google packages."
            } catch (e: Exception) {
                _output.value = "Scan failed: ${e.message}"
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun scanDesamsung(context: Context, maxTier: Int) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Scanning for Samsung packages..."
            try {
                val listOutput = conn.runShellCommand("pm list packages")
                val installed = listOutput.split("\n")
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:") }
                    .filter { it.isNotEmpty() }
                    .toSet()

                val db = PackageDatabase.loadSamsungDb(context)
                val filtered = db.filter { preset ->
                    installed.contains(preset.id) && preset.tier <= maxTier
                }
                _desamsungList.value = filtered
                _output.value = "Scan complete. Found ${filtered.size} Samsung packages."
            } catch (e: Exception) {
                _output.value = "Scan failed: ${e.message}"
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun disablePackages(packages: List<String>, isUninstall: Boolean) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Processing package removal..."
            var success = 0
            var fail = 0
            for (pkg in packages) {
                if (!PackageDatabase.isValidPackageName(pkg)) {
                    fail++
                    _output.value = "Skipped invalid package name: $pkg"
                    continue
                }
                val cmd = if (isUninstall) {
                    "pm uninstall -k --user 0 $pkg"
                } else {
                    "pm disable-user --user 0 $pkg"
                }
                val out = conn.runShellCommand(cmd)
                if (out.lowercase().contains("success") || out.lowercase().contains("new state: disabled-user")) {
                    success++
                } else {
                    fail++
                    _output.value = "Failed on $pkg: $out"
                }
            }
            _output.value = "Removal complete. Success: $success, Failed: $fail"
            _isExecuting.value = false
        }
    }

    fun restorePackages(packages: List<String>) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Restoring packages..."
            var success = 0
            var fail = 0
            for (pkg in packages) {
                if (!PackageDatabase.isValidPackageName(pkg)) {
                    fail++
                    _output.value = "Skipped invalid package name: $pkg"
                    continue
                }
                var out = conn.runShellCommand("pm enable $pkg")
                if (out.lowercase().contains("new state: enabled") || out.lowercase().contains("success")) {
                    success++
                } else {
                    out = conn.runShellCommand("cmd package install-existing --user 0 $pkg")
                    if (out.lowercase().contains("installed") || out.lowercase().contains("success")) {
                        success++
                    } else {
                        fail++
                        _output.value = "Failed on $pkg: $out"
                    }
                }
            }
            _output.value = "Restore complete. Success: $success, Failed: $fail"
            _isExecuting.value = false
        }
    }

    // Bluetooth Repair Logic
    fun executeBluetoothRepair(oemOverride: String, isAdvanced: Boolean) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Starting Bluetooth Repair..."

            _output.value = "Disabling Bluetooth..."
            conn.runShellCommand("cmd bluetooth_manager disable || svc bluetooth disable")
            delay(2000)

            val list = mutableListOf(
                "com.android.bluetooth",
                "com.android.bluetoothmidiservice",
                "com.google.android.bluetooth"
            )
            if (isAdvanced) {
                list.add("com.android.networkstack")
            }
            when (oemOverride) {
                "samsung" -> list.addAll(listOf("com.samsung.android.bluetooth", "com.sec.android.app.samsungapps"))
                "xiaomi" -> list.addAll(listOf("com.miui.bluetooth", "com.xiaomi.bluetooth", "com.xiaomi.bluetooth.overlay"))
                "oneplus" -> list.addAll(listOf("com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"))
                "motorola" -> list.addAll(listOf("com.motorola.bluetooth", "com.motorola.bt"))
            }

            for (pkg in list) {
                _output.value = "Clearing data for $pkg..."
                conn.runShellCommand("pm clear $pkg")
            }

            if (isAdvanced) {
                val sdkInt = conn.runShellCommand("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
                if (sdkInt >= Build.VERSION_CODES.R) {
                    _output.value = "Cycling radios via Airplane Mode..."
                    val apStateStr = conn.runShellCommand("settings get global airplane_mode_on").trim()
                    val originalApState = apStateStr.toIntOrNull() ?: 0

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
            _output.value = "Repair complete!\n\n$dump"
            _isExecuting.value = false
        }
    }

    fun applyBluetoothQuirk(oem: String) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            if (oem == "xiaomi") {
                _output.value = "Disabling Doze globally..."
                conn.runShellCommand("dumpsys deviceidle disable")
                _output.value = "Xiaomi Quirk Applied: Doze disabled. Will reset on reboot."
            } else if (oem == "oneplus") {
                _output.value = "Clearing battery app data..."
                conn.runShellCommand("pm clear com.oplus.battery")
                conn.runShellCommand("pm clear com.coloros.battery")
                _output.value = "OnePlus Quirk Applied: Battery app cleared."
            }
            _isExecuting.value = false
        }
    }

    // Audio Mods
    fun executeAudioBypass() {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Applying Audio Safety Bypass..."
            val targetApiLevel = conn.runShellCommand("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
            if (targetApiLevel >= 34) {
                conn.runShellCommand("settings put global audio_safe_csd_next_warning 999999.0")
                conn.runShellCommand("settings put global safe_media_volume_enabled 0")
                conn.runShellCommand("settings put global audio_safe_volume_state 3")
            } else {
                conn.runShellCommand("settings put global safe_media_volume_enabled 0")
                conn.runShellCommand("settings put global audio_safe_volume_state 3")
            }
            _output.value = "Bypass applied successfully! Rebooting the device is highly recommended."
            _isExecuting.value = false
        }
    }

    fun restoreAudioDefaults() {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Restoring Audio Safety Defaults..."
            conn.runShellCommand("settings delete global audio_safe_csd_next_warning")
            conn.runShellCommand("settings put global safe_media_volume_enabled 1")
            conn.runShellCommand("settings put global audio_safe_volume_state 1")
            _output.value = "Defaults restored! Reboot recommended."
            _isExecuting.value = false
        }
    }

    // Battery Optimizer Extra Toggles
    fun refreshBatteryOptimizerState() {
        val conn = _activeConnection.value ?: return
        if (!conn.isAvailable()) return
        viewModelScope.launch(Dispatchers.IO) {
            val lightAfterInactive = conn.runShellCommand("device_config get device_idle light_after_inactive_to").trim()
            val inactiveTo = conn.runShellCommand("device_config get device_idle inactive_to").trim()
            val lightMaintMin = conn.runShellCommand("device_config get device_idle light_idle_maintenance_min_budget").trim()
            val deepMaintTime = conn.runShellCommand("device_config get device_idle min_deep_maintenance_time").trim()
            val bgDrain = conn.runShellCommand("device_config get background_install_control bg_current_drain_monitor_enable").trim()
            val peakRefresh = conn.runShellCommand("settings get system peak_refresh_rate").trim().toFloatOrNull()
            val minRefresh = conn.runShellCommand("settings get system min_refresh_rate").trim().toFloatOrNull()
            val windowBlurs = conn.runShellCommand("settings get global disable_window_blurs").trim()
            val colorMatrix = conn.runShellCommand("settings get secure accessibility_display_color_matrix").trim()
            val samsungCpu = conn.runShellCommand("settings get global sem_enhanced_cpu_responsiveness").trim()
            val samsungRamPlus = conn.runShellCommand("settings get global ram_expand_size_list").trim()
            val samsungMotion = conn.runShellCommand("settings get system motion_engine").trim()

            _batteryOptimizerState.value = BatteryOptimizerState(
                maxHibernation = lightAfterInactive == "0" && inactiveTo == "0",
                dozeZero = lightMaintMin == "0" && deepMaintTime == "0",
                backgroundDrainMonitor = bgDrain.equals("true", ignoreCase = true),
                lockRefreshRate60 = peakRefresh == 60.0f && minRefresh == 60.0f,
                disableWindowBlurs = windowBlurs == "1",
                superDim = colorMatrix == "0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0",
                samsungCpuBoosterOff = samsungCpu == "0",
                samsungRamPlusOff = samsungRamPlus == "0",
                samsungMotionEngineOff = samsungMotion == "0"
            )
        }
    }

    fun applyMaxHibernation() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(maxHibernation = true) }
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Applying Maximum Hibernation Doze Mode..."
            conn.runShellCommand("device_config put device_idle light_after_inactive_to 0")
            conn.runShellCommand("device_config put device_idle inactive_to 0")
            conn.runShellCommand("device_config put device_idle sensing_to 0")
            conn.runShellCommand("device_config put device_idle locating_to 0")
            conn.runShellCommand("device_config put device_idle motion_inactive_to 0")
            conn.runShellCommand("device_config put device_idle min_time_to_alarm +365d0h0m0s0ms")
            _output.value = "Max Hibernation applied."
            _isExecuting.value = false
        }
    }

    fun restoreMaxHibernation() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(maxHibernation = false) }
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Restoring Hibernation defaults..."
            conn.runShellCommand("device_config delete device_idle light_after_inactive_to")
            conn.runShellCommand("device_config delete device_idle inactive_to")
            conn.runShellCommand("device_config delete device_idle sensing_to")
            conn.runShellCommand("device_config delete device_idle locating_to")
            conn.runShellCommand("device_config delete device_idle motion_inactive_to")
            conn.runShellCommand("device_config delete device_idle min_time_to_alarm")
            _output.value = "Hibernation defaults restored."
            _isExecuting.value = false
        }
    }

    fun applyDozeZero() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(dozeZero = true) }
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Applying Zero Maintenance Standby..."
            conn.runShellCommand("device_config put device_idle light_idle_maintenance_min_budget 0")
            conn.runShellCommand("device_config put device_idle light_idle_maintenance_max_budget 0")
            conn.runShellCommand("device_config put device_idle min_deep_maintenance_time 0")
            conn.runShellCommand("device_config put device_idle min_time_to_alarm +365d0h0m0s0ms")
            _output.value = "Zero Maintenance applied."
            _isExecuting.value = false
        }
    }

    fun restoreDozeZero() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(dozeZero = false) }
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Restoring Zero Maintenance defaults..."
            conn.runShellCommand("device_config delete device_idle light_idle_maintenance_min_budget")
            conn.runShellCommand("device_config delete device_idle light_idle_maintenance_max_budget")
            conn.runShellCommand("device_config delete device_idle min_deep_maintenance_time")
            conn.runShellCommand("device_config delete device_idle min_time_to_alarm")
            _output.value = "Zero Maintenance defaults restored."
            _isExecuting.value = false
        }
    }

    fun setBackgroundDrainMonitor(enabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(backgroundDrainMonitor = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                conn.runShellCommand("device_config put background_install_control bg_current_drain_monitor_enable true")
                _output.value = "Background current drain monitor enabled."
            } else {
                conn.runShellCommand("device_config delete background_install_control bg_current_drain_monitor_enable")
                _output.value = "Background current drain monitor restored."
            }
        }
    }

    fun setLockRefreshRate60(enabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(lockRefreshRate60 = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                conn.runShellCommand("settings put system peak_refresh_rate 60.0")
                conn.runShellCommand("settings put system min_refresh_rate 60.0")
                _output.value = "Locked refresh rate at 60Hz."
            } else {
                conn.runShellCommand("settings delete system peak_refresh_rate")
                conn.runShellCommand("settings delete system min_refresh_rate")
                _output.value = "Refresh rate settings restored."
            }
        }
    }

    fun setDisableWindowBlurs(enabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(disableWindowBlurs = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global disable_window_blurs $valStr")
            _output.value = if (enabled) "Disabled window blurs." else "Enabled window blurs."
        }
    }

    fun setSamsungCpuResponsiveness(enabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(samsungCpuBoosterOff = !enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global sem_enhanced_cpu_responsiveness $valStr")
            _output.value = if (enabled) "Enabled Samsung CPU responsiveness." else "Disabled Samsung CPU responsiveness."
        }
    }

    fun setSamsungRamPlusOff(disabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(samsungRamPlusOff = disabled) }
        viewModelScope.launch(Dispatchers.IO) {
            if (disabled) {
                conn.runShellCommand("settings put global ram_expand_size_list 0")
                _output.value = "Disabled Samsung RAM Plus."
            } else {
                conn.runShellCommand("settings delete global ram_expand_size_list")
                _output.value = "Restored Samsung RAM Plus defaults."
            }
        }
    }

    fun setSamsungMotionEngineOff(disabled: Boolean) {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(samsungMotionEngineOff = disabled) }
        viewModelScope.launch(Dispatchers.IO) {
            if (disabled) {
                conn.runShellCommand("settings put system motion_engine 0")
                _output.value = "Disabled Samsung Motion Engine."
            } else {
                conn.runShellCommand("settings delete system motion_engine")
                _output.value = "Restored Samsung Motion Engine defaults."
            }
        }
    }

    fun applySuperDim() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(superDim = true) }
        viewModelScope.launch(Dispatchers.IO) {
            conn.runShellCommand("settings put secure accessibility_display_color_matrix 0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0")
            _output.value = "Super Dim color matrix applied."
        }
    }

    fun restoreSuperDim() {
        val conn = _activeConnection.value ?: return
        _batteryOptimizerState.update { it.copy(superDim = false) }
        viewModelScope.launch(Dispatchers.IO) {
            conn.runShellCommand("settings delete secure accessibility_display_color_matrix")
            _output.value = "Super Dim color matrix restored."
        }
    }

    // Foreground service controllers
    fun startArtCompilation(context: Context) {
        val intent = Intent(context, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_START_COMPILE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopArtCompilation(context: Context) {
        val intent = Intent(context, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_STOP_COMPILE
        }
        context.startService(intent)
    }

    fun startBatteryMonitoring(context: Context) {
        val intent = Intent(context, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_START_BATTERY_MONITOR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopBatteryMonitoring(context: Context) {
        val intent = Intent(context, BackgroundOperationService::class.java).apply {
            action = BackgroundOperationService.ACTION_STOP_BATTERY_MONITOR
        }
        context.startService(intent)
    }

    // Home Assistant Installer Logic
    fun prepareHaSystem() {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Preparing system for Home Assistant..."
            conn.runShellCommand("device_config set_sync_disabled_for_tests persistent")
            conn.runShellCommand("device_config put activity_manager max_phantom_processes 2147483647")
            conn.runShellCommand("settings put global settings_enable_monitor_phantom_procs false")
            conn.runShellCommand("appops set com.termux SYSTEM_ALERT_WINDOW allow")
            conn.runShellCommand("dumpsys deviceidle whitelist +com.termux")
            _output.value = "System preparation complete. Phantom Process Killer disabled and Termux whitelisted."
            _isExecuting.value = false
        }
    }

    fun deployHomeAssistant(context: Context) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            try {
                _output.value = "Checking Termux installation..."
                val termuxPath = conn.runShellCommand("pm path com.termux").trim()
                if (!termuxPath.startsWith("package:")) {
                    _output.value = "Error: Termux (com.termux) is not installed on the target device."
                    return@launch
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

                val tempFile = File(context.cacheDir, "setup_ha.sh")
                try {
                    tempFile.writeText(setupScript)
                    if (conn is OtgConnectionManager) {
                        conn.pushFile(tempFile.inputStream(), tempFile.length(), "/sdcard/Download/setup_ha.sh")
                    } else {
                        tempFile.inputStream().use { inputStream ->
                            conn.runShellCommandWithInput("cat > /sdcard/Download/setup_ha.sh", inputStream)
                        }
                    }
                } finally {
                    tempFile.delete()
                }

                val verifyOut = conn.runShellCommand("test -s /sdcard/Download/setup_ha.sh && echo OK").trim()
                if (verifyOut != "OK") {
                    _output.value = "Error: Failed to stage /sdcard/Download/setup_ha.sh on target device."
                    return@launch
                }

                _output.value = "Installation script staged at /sdcard/Download/setup_ha.sh"

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

                _output.value = "Deployment commands sent! Check Termux on your device."
            } catch (e: Exception) {
                _output.value = "Home Assistant deploy failed: ${e.message}"
            } finally {
                _isExecuting.value = false
            }
        }
    }

    private val _bootloaderWizardState = MutableStateFlow<BootloaderWizardState>(BootloaderWizardState.Idle)
    val bootloaderWizardState: StateFlow<BootloaderWizardState> = _bootloaderWizardState.asStateFlow()

    fun startBootloaderWizard(isUnlock: Boolean) {
        _bootloaderWizardState.value = BootloaderWizardState.ConfirmingWarnings(isUnlock)
    }

    fun clearBootloaderWizardState() {
        _bootloaderWizardState.value = BootloaderWizardState.Idle
    }

    fun proceedFromWarnings() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.ConfirmingWarnings ?: return
        val isUnlock = currentState.isUnlock
        _bootloaderWizardState.value = BootloaderWizardState.Rebooting(isUnlock)

        viewModelScope.launch {
            _output.value = "Sending reboot to bootloader command..."
            val connection = _activeConnection.value
            if (connection != null && connection.isAvailable()) {
                val result = connection.runShellCommand("reboot bootloader")
                _output.value = "Reboot command output: $result"
            }
            _bootloaderWizardState.value = BootloaderWizardState.WaitingForFastboot(isUnlock)
        }
    }

    fun transitionToCommandSelection() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.WaitingForFastboot ?: return
        val isUnlock = currentState.isUnlock
        val options = if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
        _bootloaderWizardState.value = BootloaderWizardState.CommandSelection(
            isUnlock = isUnlock,
            commandOptions = options,
            customCommand = ""
        )
    }

    fun proceedFromCommandSelection(command: String) {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.CommandSelection ?: return
        _bootloaderWizardState.value = BootloaderWizardState.ConfirmingExecution(
            isUnlock = currentState.isUnlock,
            command = command
        )
    }

    fun goBackToCommandSelection() {
        val currentState = _bootloaderWizardState.value
        val isUnlock = when (currentState) {
            is BootloaderWizardState.ConfirmingExecution -> currentState.isUnlock
            is BootloaderWizardState.ExecutingCommand -> currentState.isUnlock
            else -> return
        }
        val options = if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
        _bootloaderWizardState.value = BootloaderWizardState.CommandSelection(
            isUnlock = isUnlock,
            commandOptions = options,
            customCommand = ""
        )
    }

    fun confirmAndExecuteBootloaderCommand() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.ConfirmingExecution ?: return
        val isUnlock = currentState.isUnlock
        val command = currentState.command
        _bootloaderWizardState.value = BootloaderWizardState.ExecutingCommand(isUnlock, command)

        viewModelScope.launch {
            val connection = _activeConnection.value
            if (connection == null) {
                _bootloaderWizardState.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Error: Connection lost. Ensure device is plugged in."
                )
                return@launch
            }

            _output.value = "Executing fastboot command: $command..."
            val result = connection.sendFastbootCommand(command)
            _output.value = "Fastboot output: $result"

            if (result.contains("OKAY", ignoreCase = true)) {
                _bootloaderWizardState.value = BootloaderWizardState.PhysicalConfirmationPrompt(
                    isUnlock = isUnlock,
                    command = command,
                    responseText = result
                )
            } else {
                _bootloaderWizardState.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Fastboot execution failed: $result"
                )
            }
        }
    }

    fun completePhysicalConfirmation() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.PhysicalConfirmationPrompt ?: return
        _bootloaderWizardState.value = BootloaderWizardState.Finished(
            isUnlock = currentState.isUnlock,
            success = true,
            message = "Command confirmed successfully."
        )
    }

    private val _sideloadState = MutableStateFlow<SideloadState>(SideloadState.Idle)
    val sideloadState: StateFlow<SideloadState> = _sideloadState.asStateFlow()

    // Archives are untrusted, so cap how much a single sideload can unpack.
    private val maxSideloadApkEntries = 200
    private val maxSideloadBytes = 4L * 1024 * 1024 * 1024

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, budget: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > budget) throw java.io.IOException("Archive is too large to sideload.")
            output.write(buffer, 0, read)
        }
        return copied
    }

    fun startSideload(context: Context, uri: Uri) {
        val prevState = _sideloadState.value
        if (prevState is SideloadState.ConfigReady) {
            prevState.apkFiles.forEach { it.delete() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _sideloadState.value = SideloadState.CopyingAndParsing
            val tempFiles = mutableListOf<File>()
            try {
                val contentResolver = context.contentResolver
                val fileName = getFileName(context, uri)
                val isArchive = fileName.endsWith(".zip", true) ||
                                fileName.endsWith(".apks", true) ||
                                fileName.endsWith(".xapk", true)

                var explicitBaseApk: File? = null
                if (isArchive) {
                    val archiveStream = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Unable to open selected archive.")
                    archiveStream.use { inputStream ->
                        val zipInputStream = ZipInputStream(inputStream)
                        var entry = zipInputStream.nextEntry
                        var bytesLeft = maxSideloadBytes
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                if (tempFiles.size >= maxSideloadApkEntries) {
                                    throw java.io.IOException("Archive contains too many APK files.")
                                }
                                val baseName = File(entry.name).name
                                val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_$baseName")
                                tempFiles.add(tempFile)
                                if (baseName.equals("base.apk", ignoreCase = true) && explicitBaseApk == null) {
                                    explicitBaseApk = tempFile
                                }
                                tempFile.outputStream().use { outputStream ->
                                    bytesLeft -= copyBounded(zipInputStream, outputStream, bytesLeft)
                                }
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                        zipInputStream.close()
                    }
                } else {
                    val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_${File(fileName).name}")
                    tempFiles.add(tempFile)
                    val apkStream = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Unable to open selected APK file.")
                    apkStream.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            copyBounded(inputStream, outputStream, maxSideloadBytes)
                        }
                    }
                }

                if (tempFiles.isEmpty()) {
                    _sideloadState.value = SideloadState.Error("No APK files found in the selected file.", InstallStep.CHECK_CONNECTION)
                    return@launch
                }

                val baseApk = explicitBaseApk
                    ?: tempFiles.maxByOrNull { it.length() }
                    ?: tempFiles.first()
                
                val packageManager = context.packageManager
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)
                }

                val appLabel = if (packageInfo != null && packageInfo.applicationInfo != null) {
                    val appInfo = packageInfo.applicationInfo!!
                    appInfo.sourceDir = baseApk.absolutePath
                    appInfo.publicSourceDir = baseApk.absolutePath
                    appInfo.loadLabel(packageManager).toString()
                } else {
                    fileName.substringBeforeLast(".")
                }

                val packageName = packageInfo?.packageName ?: "unknown"
                val versionName = packageInfo?.versionName ?: "1.0"
                
                val appIcon = if (packageInfo != null && packageInfo.applicationInfo != null) {
                    try {
                        val appInfo = packageInfo.applicationInfo!!
                        appInfo.sourceDir = baseApk.absolutePath
                        appInfo.publicSourceDir = baseApk.absolutePath
                        val drawable = appInfo.loadIcon(packageManager)
                        drawable.toBitmap()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                val totalSize = tempFiles.sumOf { it.length() }

                _sideloadState.value = SideloadState.ConfigReady(
                    appLabel = appLabel,
                    packageName = packageName,
                    versionName = versionName,
                    appIcon = appIcon,
                    totalSize = totalSize,
                    apkFiles = tempFiles,
                    isArchive = isArchive,
                    archiveName = fileName
                )
            } catch (e: Exception) {
                tempFiles.forEach { it.delete() }
                _sideloadState.value = SideloadState.Error("Failed to parse APK: ${e.localizedMessage ?: e.message}", InstallStep.CHECK_CONNECTION)
            }
        }
    }

    fun executeSideloadInstall(
        config: SideloadState.ConfigReady,
        bypassLowTargetSdk: Boolean,
        grantAllPermissions: Boolean,
        allowDowngrade: Boolean,
        testOnly: Boolean,
        installToSdCard: Boolean
    ) {
        val connection = _activeConnection.value
        if (connection == null || !connection.isAvailable()) {
            _sideloadState.value = SideloadState.Error(
                "Device connection not active or unauthorized.",
                InstallStep.CHECK_CONNECTION
            )
            config.apkFiles.forEach { it.delete() }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val completedSteps = mutableSetOf<InstallStep>()
            _sideloadState.value = SideloadState.Installing(InstallStep.CHECK_CONNECTION, completedSteps)
            
            completedSteps.add(InstallStep.CHECK_CONNECTION)
            
            _sideloadState.value = SideloadState.Installing(InstallStep.CREATE_SESSION, completedSteps)
            val commandBuilder = java.lang.StringBuilder("cmd package install-create")
            commandBuilder.append(" -r")
            if (bypassLowTargetSdk) {
                commandBuilder.append(" --bypass-low-target-sdk-block")
            }
            if (grantAllPermissions) {
                commandBuilder.append(" -g")
            }
            if (allowDowngrade) {
                commandBuilder.append(" -d")
            }
            if (testOnly) {
                commandBuilder.append(" -t")
            }
            if (installToSdCard) {
                commandBuilder.append(" -s")
            }
            
            val createResult = connection.runShellCommand(commandBuilder.toString())
            if (!createResult.contains("Success", ignoreCase = true)) {
                _sideloadState.value = SideloadState.Error("Failed to create install session: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            val match = Regex("\\[(\\d+)\\]").find(createResult)
            val sessionId = match?.groupValues?.get(1)
            if (sessionId == null) {
                _sideloadState.value = SideloadState.Error("Could not parse session ID from: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            completedSteps.add(InstallStep.CREATE_SESSION)
            
            _sideloadState.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps)
            
            try {
                config.apkFiles.forEachIndexed { index, file ->
                    val progressText = "Streaming ${file.name} (${file.length() / 1024 / 1024} MB)... [${index + 1}/${config.apkFiles.size}]"
                    _sideloadState.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps, progressText)
                    
                    FileInputStream(file).use { fis ->
                        val remoteSplitName = "split_$index.apk"
                        val writeCommand = "cmd package install-write -S ${file.length()} $sessionId $remoteSplitName -"
                        val writeResult = connection.runShellCommandWithInput(writeCommand, fis)
                        
                        if (!writeResult.contains("Success", ignoreCase = true) || writeResult.contains("Failure", ignoreCase = true)) {
                            connection.runShellCommand("cmd package install-abandon $sessionId")
                            throw Exception("Failed to write ${file.name}: $writeResult")
                        }
                    }
                }
                completedSteps.add(InstallStep.STREAM_APKS)
            } catch (e: Exception) {
                runCatching { connection.runShellCommand("cmd package install-abandon $sessionId") }
                _sideloadState.value = SideloadState.Error(e.message ?: "Failed to stream APKs", InstallStep.STREAM_APKS)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            _sideloadState.value = SideloadState.Installing(InstallStep.COMMIT_INSTALL, completedSteps)
            
            val commitResult = connection.runShellCommand("cmd package install-commit $sessionId")
            
            config.apkFiles.forEach { it.delete() }
            
            if (commitResult.contains("Success", ignoreCase = true) || commitResult.isBlank()) {
                completedSteps.add(InstallStep.COMMIT_INSTALL)
                _sideloadState.value = SideloadState.Success(config.appLabel)
            } else {
                _sideloadState.value = SideloadState.Error("Installation failed: $commitResult", InstallStep.COMMIT_INSTALL)
            }
        }
    }

    fun clearSideloadState() {
        val state = _sideloadState.value
        if (state is SideloadState.ConfigReady) {
            state.apkFiles.forEach { it.delete() }
        }
        _sideloadState.value = SideloadState.Idle
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            val path = uri.path
            if (path != null) {
                val cut = path.lastIndexOf('/')
                result = if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result ?: "unknown"
    }

    // --- Pixel Magisk Patcher Methods ---
    fun startPatcher() {
        _patcherState.value = PixelMagiskPatcherState(isActive = true, currentStep = 1)
        _patcherExtractLogs.value = emptyList()
        _patcherPatchLogs.value = emptyList()
        val connection = _activeConnection.value
        if (connection is OtgConnectionManager && connection.isAvailable() && !connection.isFastboot()) {
            startMagiskRecon()
        }
    }

    fun clearPatcherState() {
        val state = _patcherState.value
        state.bootFile?.delete()
        state.magiskApkFile?.delete()
        state.patchedFile?.delete()
        File(getApplication<Application>().cacheDir, "magisk_components").deleteRecursively()
        _patcherState.value = PixelMagiskPatcherState(isActive = false)
        _patcherExtractLogs.value = emptyList()
        _patcherPatchLogs.value = emptyList()
    }

    fun goToPatcherStep(step: Int) {
        if (step in 1..6) {
            _patcherState.update { it.copy(currentStep = step) }
            // Auto trigger Phase 4 setup
            if (step == 4) {
                _patcherPatchLogs.value = emptyList()
                _patcherState.update { it.copy(patchLogs = emptyList()) }
                addPatcherPatchLog("Awaiting execution trigger...", "INFO")
            }
        }
    }

    fun startMagiskRecon() {
        val connection = _activeConnection.value as? OtgConnectionManager ?: return
        var started = false
        _patcherState.update { state ->
            if (state.isReconning) {
                state
            } else {
                started = true
                state.copy(isReconning = true, integrityStatus = "GATHERING_DATA")
            }
        }
        if (!started) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!connection.isAvailable()) {
                    throw Exception("ADB Connection is still initializing or unauthorized. Please ensure target device is unlocked and authorize the USB debugging prompt on the target device screen.")
                }

                suspend fun readProp(prop: String): String {
                    val value = connection.runShellCommand("getprop $prop").trim()
                    if (value.startsWith("Error", ignoreCase = true)) {
                        throw Exception(value)
                    }
                    return value
                }

                val codename = readProp("ro.product.device")
                val buildId = readProp("ro.build.display.id")
                val apiLevelStr = readProp("ro.product.first_api_level")
                val boardApiLevelStr = if (apiLevelStr.isEmpty()) readProp("ro.board.first_api_level") else ""
                val vendorApiLevelStr = if (apiLevelStr.isEmpty() && boardApiLevelStr.isEmpty()) readProp("ro.vendor.api_level") else ""
                val previewSdkStr = readProp("ro.build.version.preview_sdk")
                val previewSdk = previewSdkStr.toIntOrNull() ?: 0
                val buildCodename = readProp("ro.build.version.codename")
                val abi = readProp("ro.product.cpu.abi")
                val oemAllowed = readProp("sys.oem_unlock_allowed") == "1"
                val currentSlot = readProp("ro.boot.slot_suffix")

                val flashLocked = readProp("ro.boot.flash.locked")
                val vbState = readProp("ro.boot.verifiedbootstate")
                val isUnlocked = flashLocked == "0" || vbState == "orange"

                val isBeta = previewSdk > 0 || buildCodename != "REL" || buildId.contains("beta", ignoreCase = true) || buildId.contains("preview", ignoreCase = true)

                val resolution = MagiskPatcherHelper.resolveReconTarget(
                    codename = codename,
                    abi = abi,
                    firstApiLevelRaw = apiLevelStr,
                    boardApiLevelRaw = boardApiLevelStr,
                    vendorApiLevelRaw = vendorApiLevelStr
                )

                val deviceData = MagiskDeviceData(
                    codename = codename,
                    buildId = buildId,
                    apiLevel = resolution.apiLevel,
                    previewSdk = previewSdk,
                    isBeta = isBeta,
                    abi = abi,
                    oemAllowed = oemAllowed,
                    currentSlot = currentSlot,
                    isUnlocked = isUnlocked,
                    targetPartition = resolution.targetPartition
                )
                
                _patcherState.update {
                    it.copy(
                        isReconning = false,
                        deviceData = deviceData,
                        showUnlockWarning = !isUnlocked,
                        integrityStatus = "READY_FOR_IMAGE"
                    )
                }
            } catch (e: Exception) {
                _patcherState.update {
                    it.copy(
                        isReconning = false,
                        integrityStatus = "RECON_FAILED: ${e.message}"
                    )
                }
                addPatcherPatchLog("Recon failed: ${e.message}", "ERROR")
            }
        }
    }

    fun handleMagiskBootFileSelected(context: Context, uri: Uri) {
        _patcherState.update {
            it.copy(
                integrityStatus = "PROCESSING",
                integrityProgress = 0.05f,
                imageWarning = null,
                imageError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            var copiedRawFile: File? = null
            try {
                val fileName = getFileName(context, uri)
                val deviceData = _patcherState.value.deviceData
                val totalBytes = getUriSize(context, uri)

                val isZip = fileName.endsWith(".zip", ignoreCase = true) || run {
                    val probe = ByteArray(4)
                    val read = context.contentResolver.openInputStream(uri)?.use { it.read(probe) } ?: 0
                    MagiskPatcherHelper.isZipMagic(probe, read)
                }

                val extractionResult: BootExtractionResult = if (isZip) {
                    _patcherState.update {
                        it.copy(
                            integrityStatus = "SCANNING_ARCHIVE",
                            integrityProgress = 0.1f
                        )
                    }
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected archive.")
                    inputStream.use { stream ->
                        MagiskPatcherHelper.extractBootImageFromStream(
                            inputStream = stream,
                            cacheDir = context.cacheDir,
                            targetPartition = deviceData?.targetPartition,
                            expectedCodename = deviceData?.codename,
                            expectedBuildId = deviceData?.buildId,
                            totalInputBytes = totalBytes,
                            sourceFileName = fileName
                        ) { status, progress ->
                            _patcherState.update {
                                it.copy(
                                    integrityStatus = status,
                                    integrityProgress = progress
                                )
                            }
                        }
                    }
                } else {
                    val tempImg = File(context.cacheDir, "boot_${System.currentTimeMillis()}.img")
                    copiedRawFile = tempImg
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected image file.")
                    inputStream.use { input ->
                        tempImg.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    MagiskPatcherHelper.validateBootImageForPartition(tempImg, deviceData?.targetPartition)
                    BootExtractionResult(tempImg, buildWarning = null)
                }

                _patcherState.value.bootFile?.let { oldFile ->
                    if (oldFile.absolutePath != extractionResult.bootFile.absolutePath) {
                        oldFile.delete()
                    }
                }

                _patcherState.update {
                    it.copy(
                        bootFile = extractionResult.bootFile,
                        bootFileName = extractionResult.bootFile.name,
                        bootFileSize = String.format("%.1f MB", extractionResult.bootFile.length().toFloat() / (1024 * 1024)),
                        integrityStatus = "IMAGE_LOADED",
                        integrityProgress = 1f,
                        imageWarning = extractionResult.buildWarning,
                        imageError = null
                    )
                }
            } catch (e: Exception) {
                copiedRawFile?.delete()
                _patcherState.update {
                    it.copy(
                        bootFile = null,
                        bootFileName = "",
                        bootFileSize = "",
                        integrityStatus = "LOAD_FAILED",
                        integrityProgress = 0f,
                        imageWarning = null,
                        imageError = e.message ?: "Failed to load firmware image."
                    )
                }
                addPatcherPatchLog("Load boot file failed: ${e.message}", "ERROR")
            }
        }
    }

    private fun getUriSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx != -1 && !cursor.isNull(idx)) {
                        return cursor.getLong(idx)
                    }
                }
            }
        }
        return -1L
    }

    fun handleMagiskApkSelected(context: Context, uri: Uri) {
        _patcherState.update {
            it.copy(
                appStatus = "STATUS: DECOMPRESSING",
                extractProgress = 0.1f,
                extractLogs = emptyList()
            )
        }
        _patcherExtractLogs.value = emptyList()
        addPatcherExtractLog("Scanning application: " + getFileName(context, uri))
        
        viewModelScope.launch(Dispatchers.IO) {
            val tempApkFile = File(context.cacheDir, "magisk_temp_${System.currentTimeMillis()}.apk")
            val componentsDir = File(context.cacheDir, "magisk_components")
            try {
                val apkStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open selected Magisk APK.")
                apkStream.use { input ->
                    tempApkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                addPatcherExtractLog("Processing archive entries...")

                componentsDir.deleteRecursively()
                componentsDir.mkdirs()
                val components = MagiskPatcherHelper.extractMagiskComponents(tempApkFile, componentsDir)

                for (name in components.keys) {
                    addPatcherExtractLog("Decompressing $name...")
                }

                _patcherState.value.magiskApkFile?.let { oldApk ->
                    if (oldApk.absolutePath != tempApkFile.absolutePath) {
                        oldApk.delete()
                    }
                }

                addPatcherExtractLog("Extraction complete. Environment ready.")

                _patcherState.update {
                    it.copy(
                        magiskApkFile = tempApkFile,
                        appStatus = "STATUS: DECOMPRESS_SUCCESS",
                        extractProgress = 1f,
                        magiskComponentsReady = true
                    )
                }
            } catch (e: Exception) {
                tempApkFile.delete()
                componentsDir.deleteRecursively()
                _patcherState.update {
                    it.copy(
                        appStatus = "STATUS: EXTRACTION_FAILED",
                        extractProgress = 0f,
                        magiskComponentsReady = false
                    )
                }
                addPatcherExtractLog("Fatal error: ${e.message}")
            }
        }
    }

    fun startMagiskPatching(context: Context) {
        val connection = _activeConnection.value as? OtgConnectionManager ?: return
        val state = _patcherState.value
        val bootFile = state.bootFile ?: return
        val apkFile = state.magiskApkFile ?: return
        
        _patcherState.update {
            it.copy(
                patchStatus = "EXECUTING_PATCH",
                patchProgress = 0.05f,
                patchProgressLabel = "Initializing scoped workspace...",
                patchLogs = emptyList()
            )
        }
        _patcherPatchLogs.value = emptyList()
        addPatcherPatchLog("Initializing scoped workspace...", "INFO")
        
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = "/data/local/tmp/80bee_patcher"
            try {
                // 0. Clean & create workspace
                connection.runShellCommand("rm -rf $workspace && mkdir -p $workspace")
                
                // 1. Push boot image
                addPatcherPatchLog("Pushing boot image...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.1f, patchProgressLabel = "Uploading boot image...") }
                val remoteBootPath = "$workspace/boot.img"
                connection.pushFile(FileInputStream(bootFile), bootFile.length(), remoteBootPath) { sent, total ->
                    val progress = 0.1f + (sent.toFloat() / total) * 0.4f
                    _patcherState.update {
                        it.copy(
                            patchProgress = progress,
                            patchProgressLabel = "Uploading boot image (${String.format("%.1f MB", sent.toFloat() / (1024 * 1024))})..."
                        )
                    }
                }
                
                // 2. Push Magisk components
                addPatcherPatchLog("Pushing Magisk components...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.5f, patchProgressLabel = "Pushing Magisk binaries...") }

                val componentsDir = File(context.cacheDir, "magisk_components")
                componentsDir.deleteRecursively()
                componentsDir.mkdirs()
                try {
                    val extractedFiles = MagiskPatcherHelper.extractMagiskComponents(apkFile, componentsDir)
                    val totalComp = extractedFiles.size
                    var compIdx = 0
                    for ((name, file) in extractedFiles) {
                        connection.pushFile(FileInputStream(file), file.length(), "$workspace/$name")
                        compIdx++
                        val progress = 0.5f + (compIdx.toFloat() / totalComp) * 0.15f
                        _patcherState.update {
                            it.copy(
                                patchProgress = progress,
                                patchProgressLabel = "Pushed $name"
                            )
                        }
                    }
                } finally {
                    componentsDir.deleteRecursively()
                }
                
                // 3. Set env and execute
                addPatcherPatchLog("Executing boot_patch.sh...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.7f, patchProgressLabel = "Setting permissions...") }
                connection.runShellCommand("chmod +x $workspace/*")
                
                _patcherState.update { it.copy(patchProgress = 0.75f, patchProgressLabel = "Patching kernel image...") }
                val patchCmd = "cd $workspace && export KEEPVERITY=true; export KEEPFORCEENCRYPT=true; export PATCHVBMETAFLAG=false; sh boot_patch.sh $remoteBootPath"
                
                connection.runShellCommandLive(patchCmd) { line ->
                    addPatcherPatchLog(line, "PROMPT")
                }
                
                // 4. Find patched file
                addPatcherPatchLog("Locating patched image...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.8f, patchProgressLabel = "Locating patched image...") }
                val checkOut = connection.runShellCommand("ls $workspace/new-boot.img").trim()
                if (checkOut.contains("No such file") || checkOut.isEmpty()) {
                    throw Exception("Patched image not found! Script output was logged above.")
                }
                
                // 5. Pull patched image
                addPatcherPatchLog("Pulling patched image to host...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.85f, patchProgressLabel = "Pulling patched image...") }
                val remotePatchedPath = "$workspace/new-boot.img"
                
                val pulledBytes = connection.pullFile(remotePatchedPath) { received ->
                    _patcherState.update {
                        it.copy(
                            patchProgressLabel = "Pulling patched image (${String.format("%.1f MB", received.toFloat() / (1024 * 1024))})..."
                        )
                    }
                }
                
                val patchedFile = File(context.cacheDir, "patched_boot_${System.currentTimeMillis()}.img")
                try {
                    patchedFile.writeBytes(pulledBytes)
                    MagiskPatcherHelper.validateBootImageForPartition(
                        patchedFile,
                        _patcherState.value.deviceData?.targetPartition
                    )
                } catch (e: Exception) {
                    patchedFile.delete()
                    throw e
                }

                _patcherState.value.patchedFile?.let { oldPatched ->
                    if (oldPatched.absolutePath != patchedFile.absolutePath) {
                        oldPatched.delete()
                    }
                }
                
                // 6. Clean staging area
                addPatcherPatchLog("Cleaning staging area...", "INFO")
                _patcherState.update { it.copy(patchProgress = 0.95f, patchProgressLabel = "Cleaning up...") }
                connection.runShellCommand("rm -rf $workspace")
                
                addPatcherPatchLog("Successfully patched and retrieved!", "SUCCESS")
                _patcherState.update {
                    it.copy(
                        patchStatus = "SUCCESS",
                        patchProgress = 1f,
                        patchProgressLabel = "Patch complete!",
                        patchedFile = patchedFile
                    )
                }
            } catch (e: Exception) {
                addPatcherPatchLog("Error: ${e.message}", "ERROR")
                _patcherState.update {
                    it.copy(
                        patchStatus = "FAILED",
                        patchProgress = 0f,
                        patchProgressLabel = "Failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun rebootMagiskToBootloader() {
        val connection = _activeConnection.value as? OtgConnectionManager ?: return
        viewModelScope.launch(Dispatchers.IO) {
            connection.runShellCommand("reboot bootloader")
            _patcherState.update {
                it.copy(
                    fastbootStatus = "STATUS: REBOOT_SENT",
                    flashStep = 2
                )
            }
        }
    }

    fun flashMagiskPatchedImage() {
        val connection = _activeConnection.value as? OtgConnectionManager ?: return
        val state = _patcherState.value
        val patchedFile = state.patchedFile ?: return
        val deviceData = state.deviceData ?: return
        val targetPartition = deviceData.targetPartition
        
        _patcherState.update {
            it.copy(fastbootStatus = "STATUS: FLASHING_PARTITION")
        }
        addPatcherPatchLog("Writing patched image to $targetPartition...", "INFO")
        
        viewModelScope.launch(Dispatchers.IO) {
            var flashSuccess = false
            var lastErrorMsg = ""
            
            try {
                FileInputStream(patchedFile).use { fis ->
                    val primaryResult = connection.flashFastbootImage(targetPartition, fis, patchedFile.length())
                    if (primaryResult.contains("Successfully flashed")) {
                        flashSuccess = true
                    } else {
                        lastErrorMsg = primaryResult
                        addPatcherPatchLog("Flash to $targetPartition failed: $primaryResult", "WARNING")
                    }
                }

                if (!flashSuccess) {
                    val slot = deviceData.currentSlot
                    if (slot.isNotEmpty() && !targetPartition.endsWith(slot)) {
                        val slotted = "$targetPartition$slot"
                        addPatcherPatchLog("Retrying flash with slot suffix ($slotted)...", "INFO")
                        FileInputStream(patchedFile).use { fis ->
                            val slottedResult = connection.flashFastbootImage(slotted, fis, patchedFile.length())
                            if (slottedResult.contains("Successfully flashed")) {
                                flashSuccess = true
                            } else {
                                lastErrorMsg = slottedResult
                                addPatcherPatchLog("Slotted flash ($slotted) failed: $slottedResult", "WARNING")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lastErrorMsg = e.message ?: "Unknown flash error"
                addPatcherPatchLog("Flash error: ${e.message}", "WARNING")
            }
            
            if (flashSuccess) {
                addPatcherPatchLog("Flash complete!", "SUCCESS")
                _patcherState.update {
                    it.copy(
                        fastbootStatus = "STATUS: FLASH_SUCCESS",
                        flashComplete = true,
                        flashStep = 3
                    )
                }
            } else {
                addPatcherPatchLog("Flash fatal error: $lastErrorMsg", "ERROR")
                addPatcherPatchLog("Tip: Ensure device is in Bootloader/Fastboot mode and not userspace fastbootd.", "WARNING")
                _patcherState.update {
                    it.copy(fastbootStatus = "STATUS: FLASH_FAILED")
                }
            }
        }
    }

    fun installMagiskApkFromPatcher() {
        val connection = _activeConnection.value as? OtgConnectionManager ?: return
        val state = _patcherState.value
        val apkFile = state.magiskApkFile ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connection.pushFile(FileInputStream(apkFile), apkFile.length(), "/data/local/tmp/magisk.apk")
                val res = connection.runShellCommand("pm install -r /data/local/tmp/magisk.apk")
                if (res.contains("Success", ignoreCase = true)) {
                    _patcherState.update { it.copy(finalApkInstalled = true) }
                    addPatcherPatchLog("Magisk Manager APK successfully installed on device!", "SUCCESS")
                } else {
                    addPatcherPatchLog("Failed to install APK: $res", "ERROR")
                }
                connection.runShellCommand("rm -f /data/local/tmp/magisk.apk")
            } catch (e: Exception) {
                addPatcherPatchLog("Failed to install APK: ${e.message}", "ERROR")
            }
        }
    }

    private fun addPatcherExtractLog(msg: String) {
        android.util.Log.d("MainViewModel", "[EXTRACT] $msg")
        val entry = LogEntry("INFO", msg)
        _patcherExtractLogs.update { it + entry }
        _patcherState.update { it.copy(extractLogs = it.extractLogs + entry) }
    }

    private fun addPatcherPatchLog(msg: String, type: String = "INFO") {
        if (type == "ERROR") {
            android.util.Log.e("MainViewModel", "[PATCH] $msg")
        } else {
            android.util.Log.d("MainViewModel", "[PATCH] [$type] $msg")
        }
        val entry = LogEntry(type, msg)
        _patcherPatchLogs.update { it + entry }
        _patcherState.update { it.copy(patchLogs = it.patchLogs + entry) }
    }
}


