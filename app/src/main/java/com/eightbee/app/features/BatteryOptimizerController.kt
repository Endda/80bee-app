package com.eightbee.app.features

import com.eightbee.app.BatteryOptimizerState
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Manages Doze, display, and Samsung OEM battery optimization toggles. */
class BatteryOptimizerController(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val runner: CommandRunner
) {
    private val _state = MutableStateFlow(BatteryOptimizerState())
    val state: StateFlow<BatteryOptimizerState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refreshState() {
        val conn = connectionProvider() ?: return
        if (!conn.isAvailable()) return
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            try {
                val values = conn.queryKeyValues(REFRESH_QUERIES)
                val peakRefresh = values["peak_refresh"]?.toFloatOrNull()
                val minRefresh = values["min_refresh"]?.toFloatOrNull()

                _state.value = BatteryOptimizerState(
                    maxHibernation = values["light_after"] == "0" && values["inactive"] == "0",
                    dozeZero = values["light_maint_min"] == "0" && values["deep_maint_time"] == "0",
                    backgroundDrainMonitor = values["bg_drain"].equals("true", ignoreCase = true),
                    lockRefreshRate60 = peakRefresh == 60.0f && minRefresh == 60.0f,
                    disableWindowBlurs = values["window_blurs"] == "1",
                    superDim = values["color_matrix"] == SUPER_DIM_MATRIX,
                    samsungCpuBoosterOff = values["samsung_cpu"] == "0",
                    samsungRamPlusOff = values["samsung_ram_plus"] == "0",
                    samsungMotionEngineOff = values["samsung_motion"] == "0"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun runAtomicBatch(
        status: String,
        commands: List<String>,
        successMessage: String,
        commitState: (BatteryOptimizerState) -> BatteryOptimizerState
    ) {
        runner.run(status) { conn ->
            conn.runShellBatch(commands)
            _state.update(commitState)
            successMessage
        }
    }

    fun applyMaxHibernation() {
        runAtomicBatch(
            status = "Applying Maximum Hibernation Doze Mode...",
            commands = listOf(
                "device_config put device_idle light_after_inactive_to 0",
                "device_config put device_idle inactive_to 0",
                "device_config put device_idle sensing_to 0",
                "device_config put device_idle locating_to 0",
                "device_config put device_idle motion_inactive_to 0",
                "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
            ),
            successMessage = "Max Hibernation applied."
        ) { it.copy(maxHibernation = true) }
    }

    fun restoreMaxHibernation() {
        val commands = buildList {
            add("device_config delete device_idle light_after_inactive_to")
            add("device_config delete device_idle inactive_to")
            add("device_config delete device_idle sensing_to")
            add("device_config delete device_idle locating_to")
            add("device_config delete device_idle motion_inactive_to")
            if (!_state.value.dozeZero) {
                add("device_config delete device_idle min_time_to_alarm")
            }
        }
        runAtomicBatch(
            status = "Restoring Hibernation defaults...",
            commands = commands,
            successMessage = "Hibernation defaults restored."
        ) { it.copy(maxHibernation = false) }
    }

    fun applyDozeZero() {
        runAtomicBatch(
            status = "Applying Zero Maintenance Standby...",
            commands = listOf(
                "device_config put device_idle light_idle_maintenance_min_budget 0",
                "device_config put device_idle light_idle_maintenance_max_budget 0",
                "device_config put device_idle min_deep_maintenance_time 0",
                "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
            ),
            successMessage = "Zero Maintenance applied."
        ) { it.copy(dozeZero = true) }
    }

    fun restoreDozeZero() {
        val commands = buildList {
            add("device_config delete device_idle light_idle_maintenance_min_budget")
            add("device_config delete device_idle light_idle_maintenance_max_budget")
            add("device_config delete device_idle min_deep_maintenance_time")
            if (!_state.value.maxHibernation) {
                add("device_config delete device_idle min_time_to_alarm")
            }
        }
        runAtomicBatch(
            status = "Restoring Zero Maintenance defaults...",
            commands = commands,
            successMessage = "Zero Maintenance defaults restored."
        ) { it.copy(dozeZero = false) }
    }

    fun setBackgroundDrainMonitor(enabled: Boolean) {
        val cmd = if (enabled) {
            "device_config put background_install_control bg_current_drain_monitor_enable true"
        } else {
            "device_config delete background_install_control bg_current_drain_monitor_enable"
        }
        val msg = if (enabled) "Background current drain monitor enabled." else "Background current drain monitor restored."
        runAtomicBatch("Updating background drain monitor...", listOf(cmd), msg) {
            it.copy(backgroundDrainMonitor = enabled)
        }
    }

    fun setLockRefreshRate60(enabled: Boolean) {
        val commands = if (enabled) {
            listOf(
                "settings put system peak_refresh_rate 60.0",
                "settings put system min_refresh_rate 60.0"
            )
        } else {
            listOf(
                "settings delete system peak_refresh_rate",
                "settings delete system min_refresh_rate"
            )
        }
        val msg = if (enabled) "Locked refresh rate at 60Hz." else "Refresh rate settings restored."
        runAtomicBatch("Updating display refresh rate...", commands, msg) {
            it.copy(lockRefreshRate60 = enabled)
        }
    }

    fun setDisableWindowBlurs(enabled: Boolean) {
        val valStr = if (enabled) "1" else "0"
        val msg = if (enabled) "Disabled window blurs." else "Enabled window blurs."
        runAtomicBatch(
            status = "Updating window blurs...",
            commands = listOf("settings put global disable_window_blurs $valStr"),
            successMessage = msg
        ) { it.copy(disableWindowBlurs = enabled) }
    }

    fun setSamsungCpuResponsiveness(enabled: Boolean) {
        val valStr = if (enabled) "1" else "0"
        val msg = if (enabled) "Enabled Samsung CPU responsiveness." else "Disabled Samsung CPU responsiveness."
        runAtomicBatch(
            status = "Updating Samsung CPU responsiveness...",
            commands = listOf("settings put global sem_enhanced_cpu_responsiveness $valStr"),
            successMessage = msg
        ) { it.copy(samsungCpuBoosterOff = !enabled) }
    }

    fun setSamsungRamPlusOff(disabled: Boolean) {
        val cmd = if (disabled) {
            "settings put global ram_expand_size_list 0"
        } else {
            "settings delete global ram_expand_size_list"
        }
        val msg = if (disabled) "Disabled Samsung RAM Plus." else "Restored Samsung RAM Plus defaults."
        runAtomicBatch("Updating Samsung RAM Plus...", listOf(cmd), msg) {
            it.copy(samsungRamPlusOff = disabled)
        }
    }

    fun setSamsungMotionEngineOff(disabled: Boolean) {
        val cmd = if (disabled) {
            "settings put system motion_engine 0"
        } else {
            "settings delete system motion_engine"
        }
        val msg = if (disabled) "Disabled Samsung Motion Engine." else "Restored Samsung Motion Engine defaults."
        runAtomicBatch("Updating Samsung Motion Engine...", listOf(cmd), msg) {
            it.copy(samsungMotionEngineOff = disabled)
        }
    }

    fun applySuperDim() {
        runAtomicBatch(
            status = "Applying Super Dim matrix...",
            commands = listOf("settings put secure accessibility_display_color_matrix $SUPER_DIM_MATRIX"),
            successMessage = "Super Dim color matrix applied."
        ) { it.copy(superDim = true) }
    }

    fun restoreSuperDim() {
        runAtomicBatch(
            status = "Restoring display color matrix...",
            commands = listOf("settings delete secure accessibility_display_color_matrix"),
            successMessage = "Super Dim color matrix restored."
        ) { it.copy(superDim = false) }
    }

    private companion object {
        const val SUPER_DIM_MATRIX = "0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0"

        val REFRESH_QUERIES = linkedMapOf(
            "light_after" to "device_config get device_idle light_after_inactive_to",
            "inactive" to "device_config get device_idle inactive_to",
            "light_maint_min" to "device_config get device_idle light_idle_maintenance_min_budget",
            "deep_maint_time" to "device_config get device_idle min_deep_maintenance_time",
            "bg_drain" to "device_config get background_install_control bg_current_drain_monitor_enable",
            "peak_refresh" to "settings get system peak_refresh_rate",
            "min_refresh" to "settings get system min_refresh_rate",
            "window_blurs" to "settings get global disable_window_blurs",
            "color_matrix" to "settings get secure accessibility_display_color_matrix",
            "samsung_cpu" to "settings get global sem_enhanced_cpu_responsiveness",
            "samsung_ram_plus" to "settings get global ram_expand_size_list",
            "samsung_motion" to "settings get system motion_engine"
        )
    }
}
