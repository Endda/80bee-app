package com.eightbee.app.features

/** One direction of an [OptimizerTweak]: the console status while it runs, its shell batch, and its result. */
class TweakChange(val status: String, val commands: List<String>, val message: String)

private const val SUPER_DIM_MATRIX = "0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0"
private const val RESTORE_ALARM_WINDOW = "device_config delete device_idle min_time_to_alarm"

/**
 * A battery optimizer switch. On refresh its [queries] are read and [isActive] decides whether it is on.
 * Turning it [off] also runs its [sharedRestore] commands, unless another active tweak shares them.
 */
enum class OptimizerTweak(
    val on: TweakChange,
    val off: TweakChange,
    val queries: Map<String, String>,
    val isActive: (Map<String, String>) -> Boolean,
    val sharedRestore: List<String> = emptyList()
) {
    MAX_HIBERNATION(
        on = TweakChange(
            "Applying Maximum Hibernation Doze Mode...",
            listOf(
                "device_config put device_idle light_after_inactive_to 0",
                "device_config put device_idle inactive_to 0",
                "device_config put device_idle sensing_to 0",
                "device_config put device_idle locating_to 0",
                "device_config put device_idle motion_inactive_to 0",
                "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
            ),
            "Max Hibernation applied."
        ),
        off = TweakChange(
            "Restoring Hibernation defaults...",
            listOf(
                "device_config delete device_idle light_after_inactive_to",
                "device_config delete device_idle inactive_to",
                "device_config delete device_idle sensing_to",
                "device_config delete device_idle locating_to",
                "device_config delete device_idle motion_inactive_to"
            ),
            "Hibernation defaults restored."
        ),
        queries = mapOf(
            "light_after" to "device_config get device_idle light_after_inactive_to",
            "inactive" to "device_config get device_idle inactive_to"
        ),
        isActive = { it["light_after"] == "0" && it["inactive"] == "0" },
        sharedRestore = listOf(RESTORE_ALARM_WINDOW)
    ),
    DOZE_ZERO(
        on = TweakChange(
            "Applying Zero Maintenance Standby...",
            listOf(
                "device_config put device_idle light_idle_maintenance_min_budget 0",
                "device_config put device_idle light_idle_maintenance_max_budget 0",
                "device_config put device_idle min_deep_maintenance_time 0",
                "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
            ),
            "Zero Maintenance applied."
        ),
        off = TweakChange(
            "Restoring Zero Maintenance defaults...",
            listOf(
                "device_config delete device_idle light_idle_maintenance_min_budget",
                "device_config delete device_idle light_idle_maintenance_max_budget",
                "device_config delete device_idle min_deep_maintenance_time"
            ),
            "Zero Maintenance defaults restored."
        ),
        queries = mapOf(
            "light_maint_min" to "device_config get device_idle light_idle_maintenance_min_budget",
            "deep_maint_time" to "device_config get device_idle min_deep_maintenance_time"
        ),
        isActive = { it["light_maint_min"] == "0" && it["deep_maint_time"] == "0" },
        sharedRestore = listOf(RESTORE_ALARM_WINDOW)
    ),
    BACKGROUND_DRAIN_MONITOR(
        on = TweakChange(
            "Updating background drain monitor...",
            listOf("device_config put background_install_control bg_current_drain_monitor_enable true"),
            "Background current drain monitor enabled."
        ),
        off = TweakChange(
            "Updating background drain monitor...",
            listOf("device_config delete background_install_control bg_current_drain_monitor_enable"),
            "Background current drain monitor restored."
        ),
        queries = mapOf("bg_drain" to "device_config get background_install_control bg_current_drain_monitor_enable"),
        isActive = { it["bg_drain"].equals("true", ignoreCase = true) }
    ),
    LOCK_REFRESH_RATE_60(
        on = TweakChange(
            "Updating display refresh rate...",
            listOf("settings put system peak_refresh_rate 60.0", "settings put system min_refresh_rate 60.0"),
            "Locked refresh rate at 60Hz."
        ),
        off = TweakChange(
            "Updating display refresh rate...",
            listOf("settings delete system peak_refresh_rate", "settings delete system min_refresh_rate"),
            "Refresh rate settings restored."
        ),
        queries = mapOf(
            "peak_refresh" to "settings get system peak_refresh_rate",
            "min_refresh" to "settings get system min_refresh_rate"
        ),
        isActive = { it["peak_refresh"]?.toFloatOrNull() == 60.0f && it["min_refresh"]?.toFloatOrNull() == 60.0f }
    ),
    DISABLE_WINDOW_BLURS(
        on = TweakChange(
            "Updating window blurs...",
            listOf("settings put global disable_window_blurs 1"),
            "Disabled window blurs."
        ),
        off = TweakChange(
            "Updating window blurs...",
            listOf("settings put global disable_window_blurs 0"),
            "Enabled window blurs."
        ),
        queries = mapOf("window_blurs" to "settings get global disable_window_blurs"),
        isActive = { it["window_blurs"] == "1" }
    ),
    SUPER_DIM(
        on = TweakChange(
            "Applying Super Dim matrix...",
            listOf("settings put secure accessibility_display_color_matrix $SUPER_DIM_MATRIX"),
            "Super Dim color matrix applied."
        ),
        off = TweakChange(
            "Restoring display color matrix...",
            listOf("settings delete secure accessibility_display_color_matrix"),
            "Super Dim color matrix restored."
        ),
        queries = mapOf("color_matrix" to "settings get secure accessibility_display_color_matrix"),
        isActive = { it["color_matrix"] == SUPER_DIM_MATRIX }
    ),

    /** On means Samsung's enhanced CPU responsiveness is turned off. */
    SAMSUNG_CPU_BOOSTER_OFF(
        on = TweakChange(
            "Updating Samsung CPU responsiveness...",
            listOf("settings put global sem_enhanced_cpu_responsiveness 0"),
            "Disabled Samsung CPU responsiveness."
        ),
        off = TweakChange(
            "Updating Samsung CPU responsiveness...",
            listOf("settings put global sem_enhanced_cpu_responsiveness 1"),
            "Enabled Samsung CPU responsiveness."
        ),
        queries = mapOf("samsung_cpu" to "settings get global sem_enhanced_cpu_responsiveness"),
        isActive = { it["samsung_cpu"] == "0" }
    ),
    SAMSUNG_RAM_PLUS_OFF(
        on = TweakChange(
            "Updating Samsung RAM Plus...",
            listOf("settings put global ram_expand_size_list 0"),
            "Disabled Samsung RAM Plus."
        ),
        off = TweakChange(
            "Updating Samsung RAM Plus...",
            listOf("settings delete global ram_expand_size_list"),
            "Restored Samsung RAM Plus defaults."
        ),
        queries = mapOf("samsung_ram_plus" to "settings get global ram_expand_size_list"),
        isActive = { it["samsung_ram_plus"] == "0" }
    ),
    SAMSUNG_MOTION_ENGINE_OFF(
        on = TweakChange(
            "Updating Samsung Motion Engine...",
            listOf("settings put system motion_engine 0"),
            "Disabled Samsung Motion Engine."
        ),
        off = TweakChange(
            "Updating Samsung Motion Engine...",
            listOf("settings delete system motion_engine"),
            "Restored Samsung Motion Engine defaults."
        ),
        queries = mapOf("samsung_motion" to "settings get system motion_engine"),
        isActive = { it["samsung_motion"] == "0" }
    )
}
