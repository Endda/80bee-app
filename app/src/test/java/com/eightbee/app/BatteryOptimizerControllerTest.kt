package com.eightbee.app

import com.eightbee.app.features.BATCH_FAILURE_MARKER
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.CommandRunner
import com.eightbee.app.features.OptimizerTweak
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizerControllerTest {

    @Test
    fun batteryOptimizerController_preservesMinTimeToAlarmWhileOtherDozeToggleActive() = runBlocking {
        val fakeConn = FakeConnectionManager()
        val controller = BatteryOptimizerController(
            scope = this,
            connectionProvider = { fakeConn },
            runner = eagerRunner(this, fakeConn)
        )

        controller.setTweak(OptimizerTweak.MAX_HIBERNATION, true)
        controller.setTweak(OptimizerTweak.DOZE_ZERO, true)
        fakeConn.executedShellCommands.clear()

        // Restoring only maxHibernation must keep min_time_to_alarm because dozeZero is still active.
        controller.setTweak(OptimizerTweak.MAX_HIBERNATION, false)
        assertFalse(fakeConn.executedShellCommands.any { it.contains("min_time_to_alarm") })

        // Restoring the second toggle should now clean up min_time_to_alarm.
        controller.setTweak(OptimizerTweak.DOZE_ZERO, false)
        assertTrue(fakeConn.executedShellCommands.any { it.contains("device_config delete device_idle min_time_to_alarm") })
    }

    @Test
    fun batteryOptimizerController_doesNotCommitStateWhenBatchFails() = runBlocking {
        val fakeConn = FakeConnectionManager(shellResponder = { BATCH_FAILURE_MARKER })
        val runner = eagerRunner(this, fakeConn)
        val controller = BatteryOptimizerController(
            scope = this,
            connectionProvider = { fakeConn },
            runner = runner
        )

        controller.setTweak(OptimizerTweak.MAX_HIBERNATION, true)
        assertFalse(OptimizerTweak.MAX_HIBERNATION in controller.state.value)
        assertTrue(runner.output.value.startsWith("Error: "))
    }

    /** One battery optimizer switch: how the screen flips it, how it reads back, and the on/off transcripts. */
    private class OptimizerToggleCase(
        val name: String,
        val toggle: (BatteryOptimizerController, Boolean) -> Unit,
        val isOn: (Set<OptimizerTweak>) -> Boolean,
        val on: List<String>,
        val off: List<String>
    )

    private fun batch(vararg commands: String) = "(${commands.joinToString(" && ")}) || echo '$BATCH_FAILURE_MARKER'"

    private fun job(status: String, batch: String, message: String) = listOf("> $status", batch, "> $message")

    private val hibernationRestore = arrayOf(
        "device_config delete device_idle light_after_inactive_to",
        "device_config delete device_idle inactive_to",
        "device_config delete device_idle sensing_to",
        "device_config delete device_idle locating_to",
        "device_config delete device_idle motion_inactive_to"
    )
    private val dozeZeroRestore = arrayOf(
        "device_config delete device_idle light_idle_maintenance_min_budget",
        "device_config delete device_idle light_idle_maintenance_max_budget",
        "device_config delete device_idle min_deep_maintenance_time"
    )
    private val alarmRestore = "device_config delete device_idle min_time_to_alarm"
    private val superDimMatrix = "0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,1,0"

    private val optimizerCases = listOf(
        OptimizerToggleCase(
            "maxHibernation",
            { c, on -> c.setTweak(OptimizerTweak.MAX_HIBERNATION, on) },
            { OptimizerTweak.MAX_HIBERNATION in it },
            on = job(
                "Applying Maximum Hibernation Doze Mode...",
                batch(
                    "device_config put device_idle light_after_inactive_to 0",
                    "device_config put device_idle inactive_to 0",
                    "device_config put device_idle sensing_to 0",
                    "device_config put device_idle locating_to 0",
                    "device_config put device_idle motion_inactive_to 0",
                    "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
                ),
                "Max Hibernation applied."
            ),
            off = job("Restoring Hibernation defaults...", batch(*hibernationRestore, alarmRestore), "Hibernation defaults restored.")
        ),
        OptimizerToggleCase(
            "dozeZero",
            { c, on -> c.setTweak(OptimizerTweak.DOZE_ZERO, on) },
            { OptimizerTweak.DOZE_ZERO in it },
            on = job(
                "Applying Zero Maintenance Standby...",
                batch(
                    "device_config put device_idle light_idle_maintenance_min_budget 0",
                    "device_config put device_idle light_idle_maintenance_max_budget 0",
                    "device_config put device_idle min_deep_maintenance_time 0",
                    "device_config put device_idle min_time_to_alarm +365d0h0m0s0ms"
                ),
                "Zero Maintenance applied."
            ),
            off = job("Restoring Zero Maintenance defaults...", batch(*dozeZeroRestore, alarmRestore), "Zero Maintenance defaults restored.")
        ),
        OptimizerToggleCase(
            "backgroundDrainMonitor",
            { c, on -> c.setTweak(OptimizerTweak.BACKGROUND_DRAIN_MONITOR, on) },
            { OptimizerTweak.BACKGROUND_DRAIN_MONITOR in it },
            on = job(
                "Updating background drain monitor...",
                batch("device_config put background_install_control bg_current_drain_monitor_enable true"),
                "Background current drain monitor enabled."
            ),
            off = job(
                "Updating background drain monitor...",
                batch("device_config delete background_install_control bg_current_drain_monitor_enable"),
                "Background current drain monitor restored."
            )
        ),
        OptimizerToggleCase(
            "lockRefreshRate60",
            { c, on -> c.setTweak(OptimizerTweak.LOCK_REFRESH_RATE_60, on) },
            { OptimizerTweak.LOCK_REFRESH_RATE_60 in it },
            on = job(
                "Updating display refresh rate...",
                batch("settings put system peak_refresh_rate 60.0", "settings put system min_refresh_rate 60.0"),
                "Locked refresh rate at 60Hz."
            ),
            off = job(
                "Updating display refresh rate...",
                batch("settings delete system peak_refresh_rate", "settings delete system min_refresh_rate"),
                "Refresh rate settings restored."
            )
        ),
        OptimizerToggleCase(
            "disableWindowBlurs",
            { c, on -> c.setTweak(OptimizerTweak.DISABLE_WINDOW_BLURS, on) },
            { OptimizerTweak.DISABLE_WINDOW_BLURS in it },
            on = job("Updating window blurs...", batch("settings put global disable_window_blurs 1"), "Disabled window blurs."),
            off = job("Updating window blurs...", batch("settings put global disable_window_blurs 0"), "Enabled window blurs.")
        ),
        OptimizerToggleCase(
            "superDim",
            { c, on -> c.setTweak(OptimizerTweak.SUPER_DIM, on) },
            { OptimizerTweak.SUPER_DIM in it },
            on = job(
                "Applying Super Dim matrix...",
                batch("settings put secure accessibility_display_color_matrix $superDimMatrix"),
                "Super Dim color matrix applied."
            ),
            off = job(
                "Restoring display color matrix...",
                batch("settings delete secure accessibility_display_color_matrix"),
                "Super Dim color matrix restored."
            )
        ),
        OptimizerToggleCase(
            "samsungCpuBoosterOff",
            { c, on -> c.setTweak(OptimizerTweak.SAMSUNG_CPU_BOOSTER_OFF, on) },
            { OptimizerTweak.SAMSUNG_CPU_BOOSTER_OFF in it },
            on = job(
                "Updating Samsung CPU responsiveness...",
                batch("settings put global sem_enhanced_cpu_responsiveness 0"),
                "Disabled Samsung CPU responsiveness."
            ),
            off = job(
                "Updating Samsung CPU responsiveness...",
                batch("settings put global sem_enhanced_cpu_responsiveness 1"),
                "Enabled Samsung CPU responsiveness."
            )
        ),
        OptimizerToggleCase(
            "samsungRamPlusOff",
            { c, on -> c.setTweak(OptimizerTweak.SAMSUNG_RAM_PLUS_OFF, on) },
            { OptimizerTweak.SAMSUNG_RAM_PLUS_OFF in it },
            on = job("Updating Samsung RAM Plus...", batch("settings put global ram_expand_size_list 0"), "Disabled Samsung RAM Plus."),
            off = job(
                "Updating Samsung RAM Plus...",
                batch("settings delete global ram_expand_size_list"),
                "Restored Samsung RAM Plus defaults."
            )
        ),
        OptimizerToggleCase(
            "samsungMotionEngineOff",
            { c, on -> c.setTweak(OptimizerTweak.SAMSUNG_MOTION_ENGINE_OFF, on) },
            { OptimizerTweak.SAMSUNG_MOTION_ENGINE_OFF in it },
            on = job(
                "Updating Samsung Motion Engine...",
                batch("settings put system motion_engine 0"),
                "Disabled Samsung Motion Engine."
            ),
            off = job(
                "Updating Samsung Motion Engine...",
                batch("settings delete system motion_engine"),
                "Restored Samsung Motion Engine defaults."
            )
        )
    )

    /** Runs [actions] against one optimizer controller and returns the commands interleaved with "> console" lines. */
    private fun TestScope.optimizerTranscript(actions: (BatteryOptimizerController, MutableList<String>) -> Unit) {
        val transcript = mutableListOf<String>()
        val conn = FakeConnectionManager(shellResponder = { cmd -> transcript += cmd; "" })
        val runner = CommandRunner(this, connectionProvider = { conn }, dispatcher = StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.output.drop(1).collect { transcript += "> $it" }
        }
        actions(BatteryOptimizerController(scope = this, connectionProvider = { conn }, runner = runner), transcript)
    }

    @Test
    fun batteryOptimizer_eachToggleRunsItsExactBatchAndCommitsOnlyItsOwnState() = runTest {
        for (case in optimizerCases) {
            optimizerTranscript { controller, transcript ->
                case.toggle(controller, true)
                advanceUntilIdle()
                assertEquals("${case.name} on", case.on, transcript)
                assertEquals(listOf(case.name), optimizerCases.filter { it.isOn(controller.state.value) }.map { it.name })

                transcript.clear()
                case.toggle(controller, false)
                advanceUntilIdle()
                assertEquals("${case.name} off", case.off, transcript)
                assertEquals(emptyList<String>(), optimizerCases.filter { it.isOn(controller.state.value) }.map { it.name })
            }
        }
    }

    @Test
    fun batteryOptimizer_restoringOneDozeToggleKeepsSharedAlarmWindowForTheOther() = runTest {
        val (hibernation, dozeZero) = optimizerCases.take(2)
        for ((first, second) in listOf(hibernation to dozeZero, dozeZero to hibernation)) {
            optimizerTranscript { controller, transcript ->
                first.toggle(controller, true)
                second.toggle(controller, true)
                advanceUntilIdle()
                transcript.clear()

                first.toggle(controller, false)
                advanceUntilIdle()
                val firstRestore = if (first === hibernation) hibernationRestore else dozeZeroRestore
                assertEquals(
                    "${first.name} off while ${second.name} on",
                    listOf(first.off[0], batch(*firstRestore), first.off[2]),
                    transcript
                )

                transcript.clear()
                second.toggle(controller, false)
                advanceUntilIdle()
                assertEquals("${second.name} off last", second.off, transcript)
            }
        }
    }

    @Test
    fun batteryOptimizer_refreshQueriesEverySettingOnceAndParsesEachToggle() = runBlocking {
        val queries = listOf(
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
        val script = queries.joinToString("; ") { (key, cmd) -> "echo \"__K_${key}__=$($cmd 2>/dev/null)\"" }
        var values = emptyMap<String, String>()
        val fakeConn = FakeConnectionManager(shellResponder = { cmd ->
            assertEquals(script, cmd)
            values.entries.joinToString("\n") { (k, v) -> "__K_${k}__=$v" }
        })
        val controller = BatteryOptimizerController(
            scope = this,
            connectionProvider = { fakeConn },
            runner = eagerRunner(this, fakeConn)
        )
        suspend fun refreshTo(newValues: Map<String, String>, expectedOn: List<String>) {
            values = newValues
            controller.refreshState()
            withTimeout(5_000) {
                controller.state.first { state -> optimizerCases.filter { it.isOn(state) }.map { it.name } == expectedOn }
            }
        }

        refreshTo(
            mapOf(
                "light_after" to "0", "inactive" to "0", "light_maint_min" to "0", "deep_maint_time" to "0",
                "bg_drain" to "TRUE", "peak_refresh" to "60", "min_refresh" to "60.0", "window_blurs" to "1",
                "color_matrix" to superDimMatrix, "samsung_cpu" to "0", "samsung_ram_plus" to "0", "samsung_motion" to "0"
            ),
            optimizerCases.map { it.name }
        )
        refreshTo(
            mapOf(
                "light_after" to "0", "inactive" to "1", "light_maint_min" to "0", "deep_maint_time" to "0",
                "bg_drain" to "false", "peak_refresh" to "60.0", "window_blurs" to "0",
                "color_matrix" to "null", "samsung_cpu" to "1", "samsung_ram_plus" to "0", "samsung_motion" to ""
            ),
            listOf("dozeZero", "samsungRamPlusOff")
        )
        assertEquals(2, fakeConn.executedShellCommands.size)
    }
}
