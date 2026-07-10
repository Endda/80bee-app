package com.eightbee.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.core.graphics.drawable.toBitmap

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

class MainViewModel : ViewModel() {


    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

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
                val rawManufacturer = conn.runShellCommand("getprop ro.product.manufacturer")
                val manufacturer = rawManufacturer.trim().lowercase()

                val listOutput = conn.runShellCommand("pm list packages")
                val installed = listOutput.split("\n")
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:") }
                    .filter { it.isNotEmpty() }
                    .toSet()

                val db = PackageDatabase.loadUltimateDb(context)

                val allowedLevels = mutableSetOf("recommended")
                if (safetyLevel == "advanced" || safetyLevel == "unsafe") {
                    allowedLevels.add("advanced")
                }
                if (safetyLevel == "unsafe") {
                    allowedLevels.add("unsafe")
                }

                val filtered = db.values.filter { bloat ->
                    installed.contains(bloat.packageName) &&
                    allowedLevels.contains(bloat.safety.lowercase()) &&
                    (bloat.oems.isEmpty() || bloat.oems.any { it.lowercase() == manufacturer })
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
                val cmd = if (isUninstall) {
                    "pm uninstall -k --user 0 $pkg"
                } else {
                    "pm disable-user --user 0 $pkg"
                }
                val out = conn.runShellCommand(cmd)
                if (out.toLowerCase().contains("success") || out.toLowerCase().contains("new state: disabled-user")) {
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
                var out = conn.runShellCommand("pm enable $pkg")
                if (out.toLowerCase().contains("new state: enabled") || out.toLowerCase().contains("success")) {
                    success++
                } else {
                    out = conn.runShellCommand("cmd package install-existing --user 0 $pkg")
                    if (out.toLowerCase().contains("installed") || out.toLowerCase().contains("success")) {
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

            val isShizuku = conn is ShizukuConnectionManager
            for (pkg in list) {
                if (isShizuku) {
                    _output.value = "[Shizuku Guard] Skipping clear for $pkg (pm clear requires ADB/OTG)"
                } else {
                    _output.value = "Clearing data for $pkg..."
                    conn.runShellCommand("pm clear $pkg")
                }
            }

            if (isAdvanced) {
                _output.value = "Cycling radios via Airplane Mode..."
                val apStateStr = conn.runShellCommand("settings get global airplane_mode_on").trim()
                val originalApState = apStateStr.toIntOrNull() ?: 0

                conn.runShellCommand("settings put global airplane_mode_on 1")
                conn.runShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
                delay(4000)

                conn.runShellCommand("settings put global airplane_mode_on $originalApState")
                conn.runShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${originalApState == 1}")
                delay(4000)
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
                if (conn is ShizukuConnectionManager) {
                    _output.value = "[Shizuku Guard] Clearing battery management apps requires ADB/OTG."
                } else {
                    _output.value = "Clearing battery app data..."
                    conn.runShellCommand("pm clear com.oplus.battery")
                    conn.runShellCommand("pm clear com.coloros.battery")
                    _output.value = "OnePlus Quirk Applied: Battery app cleared."
                }
            }
            _isExecuting.value = false
        }
    }

    // Audio Mods
    fun executeAudioBypass(apiLevel: Int) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isExecuting.value = true
            _output.value = "Applying Audio Safety Bypass..."
            if (apiLevel >= 34) {
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
    fun applyMaxHibernation() {
        val conn = _activeConnection.value ?: return
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
        viewModelScope.launch(Dispatchers.IO) {
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global disable_window_blurs $valStr")
            _output.value = if (enabled) "Disabled window blurs." else "Enabled window blurs."
        }
    }

    fun setSamsungCpuResponsiveness(enabled: Boolean) {
        val conn = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global sem_enhanced_cpu_responsiveness $valStr")
            _output.value = if (enabled) "Enabled Samsung CPU responsiveness." else "Disabled Samsung CPU responsiveness."
        }
    }

    fun setSamsungRamPlusOff(disabled: Boolean) {
        val conn = _activeConnection.value ?: return
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
        viewModelScope.launch(Dispatchers.IO) {
            conn.runShellCommand("settings put secure accessibility_display_color_matrix 0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0")
            _output.value = "Super Dim color matrix applied."
        }
    }

    fun restoreSuperDim() {
        val conn = _activeConnection.value ?: return
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
            _output.value = "Deploying Home Assistant..."

            val isShizuku = conn is ShizukuConnectionManager
            if (isShizuku) {
                _output.value = "[Shizuku Guard] Scoped storage may restrict writing directly to Termux files. Proceeding..."
            }
            conn.runShellCommand("mkdir -p /data/data/com.termux/files/home/.termux")
            conn.runShellCommand("chmod 700 /data/data/com.termux/files/home/.termux")
            val propertiesFile = "/data/data/com.termux/files/home/.termux/termux.properties"
            conn.runShellCommand("grep -q \"allow-external-apps\" $propertiesFile 2>/dev/null && sed -i 's/^#* *allow-external-apps *=.*/allow-external-apps=true/' $propertiesFile || echo \"allow-external-apps=true\" >> $propertiesFile")

            _output.value = "Generating installation script..."
            val setupScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                echo "=== Starting Home Assistant Core installation ==="
                pkg update -y && pkg upgrade -y
                pkg install python rust libjpeg-turbo libffi binutils openssl -y
                pip install --upgrade pip
                pip install homeassistant
                echo "=== Home Assistant Core installation finished ==="
                echo "Run 'hass' command to start server."
            """.trimIndent()

            val tempFile = File(context.cacheDir, "setup_ha.sh")
            tempFile.writeText(setupScript)
            val inputStream = tempFile.inputStream()
            conn.runShellCommandWithInput("cat > /sdcard/Download/setup_ha.sh", inputStream)
            tempFile.delete()

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
            _isExecuting.value = false
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

    fun startSideload(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _sideloadState.value = SideloadState.CopyingAndParsing
            try {
                val contentResolver = context.contentResolver
                val fileName = getFileName(context, uri)
                val isArchive = fileName.endsWith(".zip", true) || 
                                fileName.endsWith(".apks", true) || 
                                fileName.endsWith(".xapk", true)
                
                val tempFiles = mutableListOf<File>()
                if (isArchive) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val zipInputStream = ZipInputStream(inputStream)
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                val baseName = File(entry.name).name
                                val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_$baseName")
                                tempFile.outputStream().use { outputStream ->
                                    zipInputStream.copyTo(outputStream)
                                }
                                tempFiles.add(tempFile)
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                        zipInputStream.close()
                    }
                } else {
                    val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_$fileName")
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    tempFiles.add(tempFile)
                }

                if (tempFiles.isEmpty()) {
                    _sideloadState.value = SideloadState.Error("No APK files found in the selected file.", InstallStep.CHECK_CONNECTION)
                    return@launch
                }

                val baseApk = tempFiles.find { it.name.equals("base.apk", ignoreCase = true) } 
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
                        val writeCommand = "cmd package install-write -S ${file.length()} $sessionId ${file.name} -"
                        val writeResult = connection.runShellCommandWithInput(writeCommand, fis)
                        
                        if (writeResult.contains("Failure", ignoreCase = true)) {
                            connection.runShellCommand("cmd package install-abandon $sessionId")
                            throw Exception("Failed to write ${file.name}: $writeResult")
                        }
                    }
                }
                completedSteps.add(InstallStep.STREAM_APKS)
            } catch (e: Exception) {
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
}

