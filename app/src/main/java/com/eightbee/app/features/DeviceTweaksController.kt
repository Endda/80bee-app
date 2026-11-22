package com.eightbee.app.features

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.os.Build
import com.eightbee.app.BackgroundOperationService
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

enum class BootMode(val shellCommand: String, val label: String) {
    SYSTEM("reboot", "Rebooting to system..."),
    RECOVERY("reboot recovery", "Rebooting to recovery..."),
    BOOTLOADER("reboot bootloader", "Rebooting to bootloader..."),
    FASTBOOTD("reboot fastboot", "Rebooting to fastbootd...")
}

/** Handles device tweaks, settings refresh, service triggers, and Termux HA deployment. */
class DeviceTweaksController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val runner: CommandRunner
) {
    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()
    private var refreshSettingsJob: Job? = null

    fun refreshSettings() {
        val connection = connectionProvider() ?: return
        if (!connection.isAvailable()) return
        if (connection.connectionState.value == ConnectionState.FastbootConnected) return

        refreshSettingsJob?.cancel()
        refreshSettingsJob = scope.launch(Dispatchers.IO) {
            try {
                refreshSettingsInternal(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun refreshSettingsInternal(connection: ConnectionManager) {
        if (!connection.isAvailable()) return
        if (connection.connectionState.value == ConnectionState.FastbootConnected) return

        val values = connection.queryKeyValues(SETTINGS_QUERIES)

        _settings.value = DeviceSettings(
            privateDnsMode = values["dns_mode"].orEmpty(),
            privateDnsHostname = values["dns_host"].orEmpty(),
            windowAnimationScale = values["win_scale"].orEmpty(),
            transitionAnimationScale = values["trans_scale"].orEmpty(),
            animatorDurationScale = values["anim_scale"].orEmpty(),
            adaptiveBatteryEnabled = values["adaptive_battery"] == "1"
        )
    }

    // --- Private DNS ---
    fun setPrivateDnsOff() {
        runner.run("Disabling Private DNS...", afterSuccess = ::refreshSettingsInternal) { conn ->
            conn.runShellCommand("settings put global private_dns_mode off")
            "Private DNS turned off."
        }
    }

    fun setPrivateDnsAuto() {
        runner.run("Setting Private DNS to automatic...", afterSuccess = ::refreshSettingsInternal) { conn ->
            conn.runShellCommand("settings put global private_dns_mode opportunistic")
            "Private DNS set to Automatic."
        }
    }

    fun setPrivateDnsHostname(rawHost: String) {
        val host = rawHost.trim()
        if (!isValidPrivateDnsHost(host)) {
            runner.report("Invalid Private DNS hostname.")
            return
        }
        runner.run("Applying Private DNS ($host)...", afterSuccess = ::refreshSettingsInternal) { conn ->
            conn.runShellCommand("settings put global private_dns_mode hostname; settings put global private_dns_specifier '$host'")
            "Private DNS set to $host."
        }
    }

    // --- UI Adjustments ---
    fun setAnimationScale(scale: Float) {
        if (scale !in listOf(0.0f, 0.5f, 1.0f)) return
        val label = "${scale}x"
        runner.run("Setting animation scales to $label...", afterSuccess = ::refreshSettingsInternal) { conn ->
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
            runner.report("DPI must be between 120 and 800.")
            return
        }
        runner.run("Applying display density $dpi...") { conn ->
            conn.runShellCommand("wm density $dpi")
            "Display density set to $dpi."
        }
    }

    fun resetDisplayDensity() {
        runner.run("Resetting display density...") { conn ->
            conn.runShellCommand("wm density reset")
            "Display density reset to stock."
        }
    }

    // --- Boot Manager ---
    fun rebootDevice(mode: BootMode) {
        runner.run(mode.label) { conn ->
            val out = conn.runShellCommand(mode.shellCommand)
            out.ifBlank { "${mode.label} command sent." }
        }
    }

    // --- Battery Diagnostics & Doze ---
    fun setForceDoze(enabled: Boolean) {
        val status = if (enabled) "Forcing Doze standby..." else "Unforcing Doze standby..."
        val cmd = if (enabled) "dumpsys deviceidle force-idle" else "dumpsys deviceidle unforce"
        runner.run(status) { conn ->
            conn.runShellCommand(cmd).ifBlank {
                if (enabled) "Forced Doze standby." else "Disabled forced Doze."
            }
        }
    }

    fun setAdaptiveBattery(enabled: Boolean) {
        val status = if (enabled) "Enabling Adaptive Battery..." else "Disabling Adaptive Battery..."
        val flag = if (enabled) "1" else "0"
        runner.run(status, afterSuccess = ::refreshSettingsInternal) { conn ->
            conn.runShellCommand("settings put global adaptive_battery_management_enabled $flag")
            if (enabled) "Adaptive Battery enabled." else "Adaptive Battery disabled."
        }
    }

    fun simulateBatteryUnplug() {
        runner.run("Simulating battery unplug...") { conn ->
            conn.runShellCommand("dumpsys battery unplug").ifBlank { "Battery unplug simulation active." }
        }
    }

    fun resetBatterySimulation() {
        runner.run("Resetting battery state...") { conn ->
            conn.runShellCommand("dumpsys battery reset").ifBlank { "Battery state reset to hardware reporting." }
        }
    }

    // --- Bluetooth Repair ---
    fun executeBluetoothRepair(oem: BluetoothOem, isAdvanced: Boolean) {
        runner.run("Starting Bluetooth Repair...") { conn ->
            runner.show("Disabling Bluetooth...")
            conn.runShellCommand("cmd bluetooth_manager disable || svc bluetooth disable")
            delay(2000)

            val packages = STOCK_BLUETOOTH_PACKAGES +
                (if (isAdvanced) listOf("com.android.networkstack") else emptyList()) +
                oem.repairPackages

            for (pkg in packages) {
                if (isAdvanced) {
                    runner.show("Clearing data for $pkg...")
                    conn.runShellCommand("pm clear $pkg")
                } else {
                    runner.show("Restarting $pkg...")
                    conn.runShellCommand("am force-stop $pkg")
                }
            }

            if (isAdvanced) {
                val sdkInt = conn.sdkInt()
                if (sdkInt >= Build.VERSION_CODES.R) {
                    runner.show("Cycling radios via Airplane Mode...")
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
                    runner.show("Skipping Airplane Mode radio cycle (cmd connectivity requires Android 11+; target SDK is $sdkInt).")
                }
            }

            runner.show("Enabling Bluetooth...")
            conn.runShellCommand("cmd bluetooth_manager enable || svc bluetooth enable")
            delay(3000)

            runner.show("Checking Bluetooth state...")
            val dump = conn.runShellCommand("dumpsys bluetooth_manager")
            "Repair complete!\n\n$dump"
        }
    }

    fun applyBluetoothQuirk(oem: BluetoothOem) {
        val quirk = oem.quirk ?: return
        runner.run("Applying ${oem.name} Bluetooth Doze & background exemptions...") { conn ->
            val exempted = mutableListOf<String>()
            for (pkg in quirk.packages) {
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
                "No matching Bluetooth packages found for ${oem.name}."
            }
        }
    }

    // --- Audio Mods ---
    fun executeAudioBypass() {
        runner.run("Applying Audio Safety Bypass...") { conn ->
            if (conn.sdkInt() >= 34) {
                conn.runShellCommand("settings put global audio_safe_csd_next_warning 999999.0")
            }
            conn.runShellCommand("settings put global safe_media_volume_enabled 0")
            conn.runShellCommand("settings put global audio_safe_volume_state 3")
            "Bypass applied successfully! Rebooting the device is highly recommended."
        }
    }

    fun restoreAudioDefaults() {
        runner.run("Restoring Audio Safety Defaults...") { conn ->
            conn.runShellCommand("settings delete global audio_safe_csd_next_warning")
            conn.runShellCommand("settings put global safe_media_volume_enabled 1")
            conn.runShellCommand("settings put global audio_safe_volume_state 1")
            "Defaults restored! Reboot recommended."
        }
    }

    // --- Foreground Service Controls ---
    fun startArtCompilation() = sendServiceAction(BackgroundOperationService.ACTION_START_COMPILE, foreground = true)
    fun stopArtCompilation() = sendServiceAction(BackgroundOperationService.ACTION_STOP_COMPILE, foreground = false)
    fun startBatteryMonitoring() = sendServiceAction(BackgroundOperationService.ACTION_START_BATTERY_MONITOR, foreground = true)
    fun stopBatteryMonitoring() = sendServiceAction(BackgroundOperationService.ACTION_STOP_BATTERY_MONITOR, foreground = false)

    private fun sendServiceAction(action: String, foreground: Boolean) {
        val intent = Intent(application, BackgroundOperationService::class.java).apply {
            this.action = action
        }
        if (foreground) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }

    // --- Home Assistant Installer ---
    fun prepareHaSystem() {
        runner.run("Preparing system for Home Assistant...") { conn ->
            conn.runShellBatch(
                listOf(
                    "device_config set_sync_disabled_for_tests persistent",
                    "device_config put activity_manager max_phantom_processes 2147483647",
                    "settings put global settings_enable_monitor_phantom_procs false",
                    "appops set com.termux SYSTEM_ALERT_WINDOW allow",
                    "dumpsys deviceidle whitelist +com.termux"
                )
            )
            "System preparation complete. Phantom Process Killer disabled and Termux whitelisted."
        }
    }

    // /sdcard/Download/setup_ha.sh is pushed onto the remote ADB target, not read on the local host.
    @SuppressLint("SdCardPath")
    fun deployHomeAssistant() {
        runner.run("Checking Termux installation...") { conn ->
            val termuxPath = conn.runShellCommand("pm path com.termux").trim()
            if (!termuxPath.startsWith("package:")) {
                return@run "Error: Termux (com.termux) is not installed on the target device."
            }

            val isShizuku = conn.isLocal
            runner.show("Generating installation script...")
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

            val tempFile = File(application.cacheDir, "setup_ha.sh")
            try {
                tempFile.writeText(setupScript)
                tempFile.inputStream().use { inputStream ->
                    conn.pushFile(inputStream, tempFile.length(), "/sdcard/Download/setup_ha.sh")
                }
            } finally {
                tempFile.delete()
            }

            val verifyOut = conn.runShellCommand("test -s /sdcard/Download/setup_ha.sh && echo OK").trim()
            if (verifyOut != "OK") {
                return@run "Error: Failed to stage /sdcard/Download/setup_ha.sh on target device."
            }

            runner.show("Launching Termux and running installer...")
            conn.runShellCommand("am start -n com.termux/com.termux.app.TermuxActivity")
            delay(2000)

            if (isShizuku) {
                runner.show("[Shizuku Guard] Keystroke injection may fail depending on OEM security settings. If it fails, open Termux and run: bash /sdcard/Download/setup_ha.sh")
            }
            conn.runShellCommand("input text bash")
            conn.runShellCommand("input keyevent 62")
            conn.runShellCommand("input text /sdcard/Download/setup_ha.sh")
            conn.runShellCommand("input keyevent 66")

            "Deployment commands sent! Check Termux on your device."
        }
    }

    companion object {
        private val STOCK_BLUETOOTH_PACKAGES = listOf(
            "com.android.bluetooth",
            "com.android.bluetoothmidiservice",
            "com.google.android.bluetooth"
        )

        private val SETTINGS_QUERIES = linkedMapOf(
            "dns_mode" to "settings get global private_dns_mode",
            "dns_host" to "settings get global private_dns_specifier",
            "win_scale" to "settings get global window_animation_scale",
            "trans_scale" to "settings get global transition_animation_scale",
            "anim_scale" to "settings get global animator_duration_scale",
            "adaptive_battery" to "settings get global adaptive_battery_management_enabled"
        )

        // Dot-separated labels of letters, digits and hyphens, e.g. dns.quad9.net.
        private val PRIVATE_DNS_HOST_REGEX =
            Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

        fun isValidPrivateDnsHost(host: String): Boolean = PRIVATE_DNS_HOST_REGEX.matches(host.trim())
    }
}
