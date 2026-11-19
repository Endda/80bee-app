package com.eightbee.app.features

import com.eightbee.app.BatteryOptimizerState
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Manages Doze, display, and Samsung OEM battery optimization toggles. */
class BatteryOptimizerController(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val runExclusive: (initialStatus: String, notifySnackbar: Boolean, block: suspend (ConnectionManager) -> String) -> Unit
) {
    private val _state = MutableStateFlow(BatteryOptimizerState())
    val state: StateFlow<BatteryOptimizerState> = _state.asStateFlow()

    fun refreshState() {
        val conn = connectionProvider() ?: return
        if (!conn.isAvailable()) return
        scope.launch(Dispatchers.IO) {
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

            _state.value = BatteryOptimizerState(
                maxHibernation = lightAfterInactive == "0" && inactiveTo == "0",
                dozeZero = lightMaintMin == "0" && deepMaintTime == "0",
                backgroundDrainMonitor = bgDrain.equals("true", ignoreCase = true),
                lockRefreshRate60 = peakRefresh == 60.0f && minRefresh == 60.0f,
                disableWindowBlurs = windowBlurs == "1",
                superDim = colorMatrix == SUPER_DIM_MATRIX,
                samsungCpuBoosterOff = samsungCpu == "0",
                samsungRamPlusOff = samsungRamPlus == "0",
                samsungMotionEngineOff = samsungMotion == "0"
            )
        }
    }

    fun applyMaxHibernation() {
        runExclusive("Applying Maximum Hibernation Doze Mode...", true) { conn ->
            _state.update { it.copy(maxHibernation = true) }
            conn.runShellCommand("device_config put device_idle light_after_inactive_to 0")
            conn.runShellCommand("device_config put device_idle inactive_to 0")
            conn.runShellCommand("device_config put device_idle sensing_to 0")
            conn.runShellCommand("device_config put device_idle locating_to 0")
            conn.runShellCommand("device_config put device_idle motion_inactive_to 0")
            conn.runShellCommand("device_config put device_idle min_time_to_alarm +365d0h0m0s0ms")
            "Max Hibernation applied."
        }
    }

    fun restoreMaxHibernation() {
        runExclusive("Restoring Hibernation defaults...", true) { conn ->
            _state.update { it.copy(maxHibernation = false) }
            conn.runShellCommand("device_config delete device_idle light_after_inactive_to")
            conn.runShellCommand("device_config delete device_idle inactive_to")
            conn.runShellCommand("device_config delete device_idle sensing_to")
            conn.runShellCommand("device_config delete device_idle locating_to")
            conn.runShellCommand("device_config delete device_idle motion_inactive_to")
            if (!_state.value.dozeZero) {
                conn.runShellCommand("device_config delete device_idle min_time_to_alarm")
            }
            "Hibernation defaults restored."
        }
    }

    fun applyDozeZero() {
        runExclusive("Applying Zero Maintenance Standby...", true) { conn ->
            _state.update { it.copy(dozeZero = true) }
            conn.runShellCommand("device_config put device_idle light_idle_maintenance_min_budget 0")
            conn.runShellCommand("device_config put device_idle light_idle_maintenance_max_budget 0")
            conn.runShellCommand("device_config put device_idle min_deep_maintenance_time 0")
            conn.runShellCommand("device_config put device_idle min_time_to_alarm +365d0h0m0s0ms")
            "Zero Maintenance applied."
        }
    }

    fun restoreDozeZero() {
        runExclusive("Restoring Zero Maintenance defaults...", true) { conn ->
            _state.update { it.copy(dozeZero = false) }
            conn.runShellCommand("device_config delete device_idle light_idle_maintenance_min_budget")
            conn.runShellCommand("device_config delete device_idle light_idle_maintenance_max_budget")
            conn.runShellCommand("device_config delete device_idle min_deep_maintenance_time")
            if (!_state.value.maxHibernation) {
                conn.runShellCommand("device_config delete device_idle min_time_to_alarm")
            }
            "Zero Maintenance defaults restored."
        }
    }

    fun setBackgroundDrainMonitor(enabled: Boolean) {
        runExclusive("Updating background drain monitor...", true) { conn ->
            _state.update { it.copy(backgroundDrainMonitor = enabled) }
            if (enabled) {
                conn.runShellCommand("device_config put background_install_control bg_current_drain_monitor_enable true")
                "Background current drain monitor enabled."
            } else {
                conn.runShellCommand("device_config delete background_install_control bg_current_drain_monitor_enable")
                "Background current drain monitor restored."
            }
        }
    }

    fun setLockRefreshRate60(enabled: Boolean) {
        runExclusive("Updating display refresh rate...", true) { conn ->
            _state.update { it.copy(lockRefreshRate60 = enabled) }
            if (enabled) {
                conn.runShellCommand("settings put system peak_refresh_rate 60.0")
                conn.runShellCommand("settings put system min_refresh_rate 60.0")
                "Locked refresh rate at 60Hz."
            } else {
                conn.runShellCommand("settings delete system peak_refresh_rate")
                conn.runShellCommand("settings delete system min_refresh_rate")
                "Refresh rate settings restored."
            }
        }
    }

    fun setDisableWindowBlurs(enabled: Boolean) {
        runExclusive("Updating window blurs...", true) { conn ->
            _state.update { it.copy(disableWindowBlurs = enabled) }
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global disable_window_blurs $valStr")
            if (enabled) "Disabled window blurs." else "Enabled window blurs."
        }
    }

    fun setSamsungCpuResponsiveness(enabled: Boolean) {
        runExclusive("Updating Samsung CPU responsiveness...", true) { conn ->
            _state.update { it.copy(samsungCpuBoosterOff = !enabled) }
            val valStr = if (enabled) "1" else "0"
            conn.runShellCommand("settings put global sem_enhanced_cpu_responsiveness $valStr")
            if (enabled) "Enabled Samsung CPU responsiveness." else "Disabled Samsung CPU responsiveness."
        }
    }

    fun setSamsungRamPlusOff(disabled: Boolean) {
        runExclusive("Updating Samsung RAM Plus...", true) { conn ->
            _state.update { it.copy(samsungRamPlusOff = disabled) }
            if (disabled) {
                conn.runShellCommand("settings put global ram_expand_size_list 0")
                "Disabled Samsung RAM Plus."
            } else {
                conn.runShellCommand("settings delete global ram_expand_size_list")
                "Restored Samsung RAM Plus defaults."
            }
        }
    }

    fun setSamsungMotionEngineOff(disabled: Boolean) {
        runExclusive("Updating Samsung Motion Engine...", true) { conn ->
            _state.update { it.copy(samsungMotionEngineOff = disabled) }
            if (disabled) {
                conn.runShellCommand("settings put system motion_engine 0")
                "Disabled Samsung Motion Engine."
            } else {
                conn.runShellCommand("settings delete system motion_engine")
                "Restored Samsung Motion Engine defaults."
            }
        }
    }

    fun applySuperDim() {
        runExclusive("Applying Super Dim matrix...", true) { conn ->
            _state.update { it.copy(superDim = true) }
            conn.runShellCommand("settings put secure accessibility_display_color_matrix $SUPER_DIM_MATRIX")
            "Super Dim color matrix applied."
        }
    }

    fun restoreSuperDim() {
        runExclusive("Restoring display color matrix...", true) { conn ->
            _state.update { it.copy(superDim = false) }
            conn.runShellCommand("settings delete secure accessibility_display_color_matrix")
            "Super Dim color matrix restored."
        }
    }

    private companion object {
        const val SUPER_DIM_MATRIX = "0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0"
    }
}
