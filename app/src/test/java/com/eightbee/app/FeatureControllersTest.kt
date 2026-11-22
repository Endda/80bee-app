package com.eightbee.app

import android.app.Application
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.BATCH_FAILURE_MARKER
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.BluetoothOem
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.CommandRunner
import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.DeviceTweaksController
import com.eightbee.app.features.FastbootCommandPolicy
import com.eightbee.app.features.FastbootValidationResult
import com.eightbee.app.features.SideloadController
import com.eightbee.app.features.SnapshotFeedback
import com.eightbee.app.features.parseKeyValueOutput
import com.eightbee.app.features.parsePmListPackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class FeatureControllersTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeConnectionManager(
        var fastbootReply: FastbootReply = FastbootReply(status = "OKAY", message = "done"),
        var shellResponder: (String) -> String = { "" },
        var shellWithInputResponder: (String, ByteArray) -> String = { _, _ -> "" },
        var available: Boolean = true,
        initialConnectionState: ConnectionState = ConnectionState.AdbConnected
    ) : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(initialConnectionState)
        override val isLocal: Boolean = false
        override val supportsFastboot: Boolean = true
        val executedShellCommands = mutableListOf<String>()
        val executedShellInputCommands = mutableListOf<Pair<String, ByteArray>>()
        val executedFastbootCommands = mutableListOf<String>()

        override suspend fun runShellCommand(command: String): String {
            executedShellCommands.add(command)
            return shellResponder(command)
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String {
            val bytes = input.readBytes()
            executedShellInputCommands.add(command to bytes)
            return shellWithInputResponder(command, bytes)
        }

        override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {}

        override suspend fun pushFile(
            input: InputStream,
            size: Long,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?
        ) {}

        override suspend fun pullFile(
            remotePath: String,
            destination: File,
            maxBytes: Long,
            onProgress: ((Long) -> Unit)?
        ) {}

        override suspend fun sendFastbootCommand(command: String): FastbootReply {
            executedFastbootCommands.add(command)
            return fastbootReply
        }

        override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = "Successfully flashed $partition!"

        override fun isAvailable(): Boolean = available
    }

    /** A runner whose jobs execute eagerly on the caller's thread, so tests can assert right after a call. */
    private fun eagerRunner(scope: CoroutineScope, conn: ConnectionManager) =
        CommandRunner(scope, connectionProvider = { conn }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun isValidPrivateDnsHost_acceptsHostnamesAndRejectsShellInjection() {
        assertTrue(DeviceTweaksController.isValidPrivateDnsHost("dns.adguard.com"))
        assertTrue(DeviceTweaksController.isValidPrivateDnsHost(" 1dot1dot1dot1.cloudflare-dns.com "))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost(""))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("localhost"))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("dns.quad9.net; id"))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("dns.quad9.net' && whoami"))
    }

    @Test
    fun parsePmListPackages_parsesTrimmedPackageIds() {
        val raw = """
            package:com.example.one
              package:com.example.two
            package:
            unrelated line
        """.trimIndent()
        assertEquals(listOf("com.example.one", "com.example.two"), parsePmListPackages(raw))
    }

    @Test
    fun batteryOptimizerController_preservesMinTimeToAlarmWhileOtherDozeToggleActive() = runBlocking {
        val fakeConn = FakeConnectionManager()
        val controller = BatteryOptimizerController(
            scope = this,
            connectionProvider = { fakeConn },
            runner = eagerRunner(this, fakeConn)
        )

        controller.applyMaxHibernation()
        controller.applyDozeZero()
        fakeConn.executedShellCommands.clear()

        // Restoring only maxHibernation must keep min_time_to_alarm because dozeZero is still active.
        controller.restoreMaxHibernation()
        assertFalse(fakeConn.executedShellCommands.any { it.contains("min_time_to_alarm") })

        // Restoring the second toggle should now clean up min_time_to_alarm.
        controller.restoreDozeZero()
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

        controller.applyMaxHibernation()
        assertFalse(controller.state.value.maxHibernation)
        assertTrue(runner.output.value.startsWith("Error: "))
    }

    /** One battery optimizer switch: how the screen flips it, how it reads back, and the on/off transcripts. */
    private class OptimizerToggleCase(
        val name: String,
        val toggle: (BatteryOptimizerController, Boolean) -> Unit,
        val isOn: (BatteryOptimizerState) -> Boolean,
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
            { c, on -> if (on) c.applyMaxHibernation() else c.restoreMaxHibernation() },
            { it.maxHibernation },
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
            { c, on -> if (on) c.applyDozeZero() else c.restoreDozeZero() },
            { it.dozeZero },
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
            { c, on -> c.setBackgroundDrainMonitor(on) },
            { it.backgroundDrainMonitor },
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
            { c, on -> c.setLockRefreshRate60(on) },
            { it.lockRefreshRate60 },
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
            { c, on -> c.setDisableWindowBlurs(on) },
            { it.disableWindowBlurs },
            on = job("Updating window blurs...", batch("settings put global disable_window_blurs 1"), "Disabled window blurs."),
            off = job("Updating window blurs...", batch("settings put global disable_window_blurs 0"), "Enabled window blurs.")
        ),
        OptimizerToggleCase(
            "superDim",
            { c, on -> if (on) c.applySuperDim() else c.restoreSuperDim() },
            { it.superDim },
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
            { c, disabled -> c.setSamsungCpuResponsiveness(!disabled) },
            { it.samsungCpuBoosterOff },
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
            { c, disabled -> c.setSamsungRamPlusOff(disabled) },
            { it.samsungRamPlusOff },
            on = job("Updating Samsung RAM Plus...", batch("settings put global ram_expand_size_list 0"), "Disabled Samsung RAM Plus."),
            off = job(
                "Updating Samsung RAM Plus...",
                batch("settings delete global ram_expand_size_list"),
                "Restored Samsung RAM Plus defaults."
            )
        ),
        OptimizerToggleCase(
            "samsungMotionEngineOff",
            { c, disabled -> c.setSamsungMotionEngineOff(disabled) },
            { it.samsungMotionEngineOff },
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

    @Test
    fun parseKeyValueOutput_extractsMarkerPairsAndIgnoresNoise() {
        val raw = """
            WARNING: linker warning
            __K_dns_mode__=hostname
            __K_matrix__=0.5,0,0,0
            __K_empty__=
        """.trimIndent()

        val parsed = parseKeyValueOutput(raw)
        assertEquals("hostname", parsed["dns_mode"])
        assertEquals("0.5,0,0,0", parsed["matrix"])
        assertEquals("", parsed["empty"])
        assertEquals(3, parsed.size)
    }

    @Test
    fun bootloaderWizardController_transitionsUnlockFlowOnOkayFastbootReply() = runBlocking {
        val fakeConn = FakeConnectionManager(fastbootReply = FastbootReply(status = "OKAY", message = "unlock pending"))
        val outputs = mutableListOf<String>()
        val controller = BootloaderWizardController(
            scope = this,
            connectionProvider = { fakeConn },
            onOutput = { outputs.add(it) }
        )

        controller.start(isUnlock = true)
        assertTrue(controller.state.value is BootloaderWizardState.ConfirmingWarnings)

        controller.proceedFromWarnings()
        yield()
        assertTrue(controller.state.value is BootloaderWizardState.WaitingForFastboot)
        assertTrue(fakeConn.executedShellCommands.contains("reboot bootloader"))

        controller.transitionToCommandSelection()
        val selection = controller.state.value as BootloaderWizardState.CommandSelection
        assertEquals(listOf("flashing unlock", "oem unlock"), selection.commandOptions)

        controller.proceedFromCommandSelection("flashing unlock")
        assertTrue(controller.state.value is BootloaderWizardState.ConfirmingExecution)

        controller.confirmAndExecute()
        yield()
        assertTrue(controller.state.value is BootloaderWizardState.PhysicalConfirmationPrompt)
        assertEquals(listOf("flashing unlock"), fakeConn.executedFastbootCommands)

        controller.completePhysicalConfirmation()
        val finished = controller.state.value as BootloaderWizardState.Finished
        assertTrue(finished.success)
    }

    @Test
    fun debloaterController_scansTrackDisabledPackagesAcrossTabs() = runBlocking {
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when (cmd) {
                    "getprop ro.product.manufacturer" -> "Google"
                    "getprop ro.product.brand" -> "google"
                    "pm list packages" -> "package:com.example.bloat\npackage:com.example.gapp\npackage:com.samsung.app"
                    "pm list packages -d" -> "package:com.example.gapp"
                    else -> ""
                }
            }
        )
        val controller = DebloaterController(
            loadUltimateDb = {
                mapOf(
                    "com.example.bloat" to BloatInfo(
                        packageName = "com.example.bloat",
                        label = "Example Bloat",
                        description = "Test",
                        safety = "recommended",
                        oems = listOf("aosp")
                    )
                )
            },
            loadDegoogleDb = {
                listOf(PresetPkg("com.example.gapp", "GApp", 1, "Desc", ""))
            },
            loadSamsungDb = {
                listOf(PresetPkg("com.samsung.app", "SApp", 1, "Desc", ""))
            },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn)
        )

        controller.scanBloatware("recommended")
        assertEquals(listOf("com.example.bloat"), controller.debloatList.value.map { it.packageName })
        assertEquals(setOf("com.example.gapp"), controller.disabledPackages.value)

        controller.scanDegoogle(1)
        assertEquals(listOf("com.example.gapp"), controller.degoogleList.value.map { it.id })
        assertEquals(setOf("com.example.gapp"), controller.disabledPackages.value)

        controller.scanDesamsung(1)
        assertEquals(listOf("com.samsung.app"), controller.desamsungList.value.map { it.id })
        assertEquals(3, fakeConn.executedShellCommands.count { it == "pm list packages -d" })
    }

    @Test
    fun debloaterController_syncsDisabledAndRestoreStateAfterDisableAndRestore() = runBlocking {
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd == "pm list packages" -> "package:com.example.a\npackage:com.example.b"
                    cmd == "pm list packages -d" -> "package:com.example.a"
                    cmd.startsWith("pm disable-user --user 0 ") -> "Package com.example.b new state: disabled-user"
                    cmd.startsWith("pm uninstall -k --user 0 ") -> "Success"
                    cmd.startsWith("pm enable ") -> "Package com.example.a new state: enabled"
                    else -> ""
                }
            }
        )
        val controller = DebloaterController(
            loadUltimateDb = { emptyMap() },
            loadDegoogleDb = {
                listOf(
                    PresetPkg("com.example.a", "App A", 1, "Desc", ""),
                    PresetPkg("com.example.b", "App B", 1, "Desc", "")
                )
            },
            loadSamsungDb = { emptyList() },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn)
        )

        controller.scanDegoogle(1)
        assertEquals(setOf("com.example.a"), controller.disabledPackages.value)

        // Disabling updates disabledPackages immediately without rescanning
        controller.disablePackages(listOf("com.example.b"), isUninstall = false)
        assertEquals(setOf("com.example.a", "com.example.b"), controller.disabledPackages.value)

        // Restoring removes restored package from disabledPackages and restoreList
        controller.scanDisabledPackages()
        assertEquals(listOf("com.example.a"), controller.restoreList.value.map { it.id })
        controller.restorePackages(listOf("com.example.a"))
        assertFalse("com.example.a" in controller.disabledPackages.value)
        assertTrue(controller.restoreList.value.isEmpty())

        // Uninstalling removes uninstalled package from scan lists and disabledPackages
        controller.disablePackages(listOf("com.example.b"), isUninstall = true)
        assertEquals(listOf("com.example.a"), controller.degoogleList.value.map { it.id })
        assertFalse("com.example.b" in controller.disabledPackages.value)
    }

    @Test
    fun fastbootCommandPolicy_allowsCuratedAndBlocksDestructiveCommands() {
        assertTrue(FastbootCommandPolicy.isAllowedUtilityCommand("getvar all"))
        assertTrue(FastbootCommandPolicy.isAllowedUtilityCommand("getvar current-slot"))
        assertTrue(FastbootCommandPolicy.isAllowedUtilityCommand("reboot-fastboot"))
        assertTrue(FastbootCommandPolicy.isAllowedUtilityCommand("set_active a"))
        assertFalse(FastbootCommandPolicy.isAllowedUtilityCommand("erase userdata"))
        assertFalse(FastbootCommandPolicy.isAllowedUtilityCommand("flash boot boot.img"))
        assertFalse(FastbootCommandPolicy.isAllowedUtilityCommand("format userdata"))

        assertEquals(
            FastbootValidationResult.Allowed("flashing unlock"),
            FastbootCommandPolicy.validateWizardCustomCommand("  flashing   unlock ")
        )
        assertEquals(
            FastbootValidationResult.Allowed("oem unlock TOKEN_123="),
            FastbootCommandPolicy.validateWizardCustomCommand("oem unlock TOKEN_123=")
        )
        assertTrue(FastbootCommandPolicy.validateWizardCustomCommand("erase userdata") is FastbootValidationResult.Blocked)
        assertTrue(FastbootCommandPolicy.validateWizardCustomCommand("flash boot boot.img") is FastbootValidationResult.Blocked)
        assertTrue(FastbootCommandPolicy.validateWizardCustomCommand("format userdata") is FastbootValidationResult.Blocked)
        assertTrue(FastbootCommandPolicy.validateWizardCustomCommand("flashing unlock; reboot") is FastbootValidationResult.Blocked)
    }

    @Test
    fun bootloaderWizardController_blocksInvalidWizardCommandAndLogsCuratedFastbootReplies() = runBlocking {
        val fakeConn = FakeConnectionManager(
            fastbootReply = FastbootReply(
                status = "OKAY",
                message = "finished",
                info = listOf("current-slot: a")
            ),
            initialConnectionState = ConnectionState.FastbootConnected
        )
        val controller = BootloaderWizardController(
            scope = this,
            connectionProvider = { fakeConn },
            onOutput = {}
        )

        controller.start(isUnlock = true)
        controller.proceedFromWarnings()
        yield()
        controller.transitionToCommandSelection()
        controller.proceedFromCommandSelection("erase userdata")
        val selection = controller.state.value as BootloaderWizardState.CommandSelection
        assertFalse(selection.validationError.isNullOrBlank())
        assertTrue(fakeConn.executedFastbootCommands.isEmpty())

        // Non-allowlisted utility command must be blocked
        controller.runCuratedFastbootCommand("flash boot_a bad.img")
        assertTrue(fakeConn.executedFastbootCommands.isEmpty())
        assertTrue(controller.utilityLogs.value.last().contains("Blocked"))

        // Curated diagnostic runs and formats INFO + OKAY into utilityLogs
        controller.runCuratedFastbootCommand("getvar current-slot")
        yield()
        assertEquals(listOf("getvar current-slot"), fakeConn.executedFastbootCommands)
        assertTrue(controller.utilityLogs.value.any { it.contains("INFO: current-slot: a") && it.contains("OKAY: finished") })
    }

    @Test
    fun debloaterController_snapshotExportAndImportReportFeedbackAndClearOnDisabledScan() = runBlocking {
        val exported = java.io.ByteArrayOutputStream()
        var snapshotInput = ""
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when (cmd) {
                    "pm list packages" -> "package:com.example.installed"
                    "pm list packages -d" -> "package:com.example.disabled"
                    else -> ""
                }
            }
        )
        var shouldFailExport = false
        val controller = DebloaterController(
            loadUltimateDb = { emptyMap() },
            loadDegoogleDb = { emptyList() },
            loadSamsungDb = { emptyList() },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn),
            encodeSnapshot = { packages -> packages.joinToString(",") },
            parseSnapshot = { raw ->
                when (raw) {
                    "clean" -> SnapshotParseResult(
                        validPackages = linkedSetOf("com.example.installed", "com.example.missing"),
                        rejectedCount = 0
                    )
                    "with-invalid" -> SnapshotParseResult(
                        validPackages = linkedSetOf("com.example.missing"),
                        rejectedCount = 1
                    )
                    else -> throw IllegalArgumentException("Malformed snapshot")
                }
            }
        )

        // Export success
        controller.exportSnapshot { if (shouldFailExport) null else exported }
        assertEquals(
            SnapshotFeedback("Exported snapshot with 1 package(s).", isError = false),
            controller.snapshotFeedback.value
        )
        assertEquals("com.example.installed", exported.toString(Charsets.UTF_8.name()))

        // Export failure
        shouldFailExport = true
        controller.exportSnapshot { null }
        assertEquals(
            SnapshotFeedback("Failed to export snapshot: Unable to open destination file.", isError = true),
            controller.snapshotFeedback.value
        )

        // Import success without rejected entries
        snapshotInput = "clean"
        controller.importSnapshot { snapshotInput }
        assertEquals(listOf("com.example.missing"), controller.restoreList.value.map { it.id })
        assertEquals(
            SnapshotFeedback("Loaded 1 missing package(s) from snapshot.", isError = false),
            controller.snapshotFeedback.value
        )

        // Import with rejected entries triggers error/warning flag
        snapshotInput = "with-invalid"
        controller.importSnapshot { snapshotInput }
        assertEquals(
            SnapshotFeedback(
                "Loaded 1 missing package(s) from snapshot. Ignored 1 invalid package entry/entries.",
                isError = true
            ),
            controller.snapshotFeedback.value
        )

        // Import failure clears restoreList and reports error
        snapshotInput = "bad"
        controller.importSnapshot { snapshotInput }
        assertTrue(controller.restoreList.value.isEmpty())
        assertEquals(
            SnapshotFeedback("Failed to load snapshot: Malformed snapshot", isError = true),
            controller.snapshotFeedback.value
        )

        // Disabled scan clears snapshotFeedback
        controller.scanDisabledPackages()
        assertEquals(null, controller.snapshotFeedback.value)
        assertEquals(listOf("com.example.disabled"), controller.restoreList.value.map { it.id })
    }

    private fun newSideloadConfig(vararg contents: Pair<String, ByteArray>): SideloadState.ConfigReady {
        val dir = tempFolder.newFolder()
        val files = contents.map { (name, bytes) ->
            File(dir, name).apply { writeBytes(bytes) }
        }
        return SideloadState.ConfigReady(
            appLabel = "Test App",
            packageName = "com.example.test",
            versionName = "1.0",
            appIcon = null,
            totalSize = files.sumOf { it.length() },
            apkFiles = files,
            isArchive = files.size > 1,
            archiveName = "test.apks",
            targetDeviceSdk = 34
        )
    }

    @Test
    fun sideloadController_executeInstallAppliesAllFlagsStreamsSplitsAndCleansUpOnSuccess() = runBlocking {
        val config = newSideloadConfig(
            "base.apk" to byteArrayOf(1, 2, 3),
            "split_en.apk" to byteArrayOf(4, 5)
        )
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("cmd package install-create") -> "Success: created install session [42]"
                    cmd == "cmd package install-commit 42" -> "Success"
                    else -> ""
                }
            },
            shellWithInputResponder = { _, _ -> "Success: streamed" }
        )
        val controller = SideloadController(
            scope = this,
            connectionProvider = { fakeConn },
            ioDispatcher = Dispatchers.Unconfined
        )

        controller.executeInstall(
            config = config,
            options = SideloadInstallOptions(
                bypassLowTargetSdk = true,
                grantAllPermissions = true,
                allowDowngrade = true,
                testOnly = true,
                installToSdCard = true
            )
        )

        assertEquals(SideloadState.Success("Test App"), controller.state.value)
        assertEquals(
            listOf(
                "cmd package install-create -r --bypass-low-target-sdk-block -g -d -t -s",
                "cmd package install-commit 42"
            ),
            fakeConn.executedShellCommands
        )
        assertEquals(2, fakeConn.executedShellInputCommands.size)
        assertEquals("cmd package install-write -S 3 42 split_0.apk -", fakeConn.executedShellInputCommands[0].first)
        assertTrue(fakeConn.executedShellInputCommands[0].second.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("cmd package install-write -S 2 42 split_1.apk -", fakeConn.executedShellInputCommands[1].first)
        assertTrue(fakeConn.executedShellInputCommands[1].second.contentEquals(byteArrayOf(4, 5)))
        assertTrue(config.apkFiles.none { it.exists() })
    }

    @Test
    fun sideloadController_executeInstallCleansUpOnUnavailableConnectionAndSessionCreateErrors() = runBlocking {
        val unavailableConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val unavailableConn = FakeConnectionManager(available = false)
        val unavailableController = SideloadController(
            scope = this,
            connectionProvider = { unavailableConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        unavailableController.executeInstall(unavailableConfig)
        assertEquals(
            SideloadState.Error("Device connection not active or unauthorized.", InstallStep.CHECK_CONNECTION),
            unavailableController.state.value
        )
        assertTrue(unavailableConfig.apkFiles.none { it.exists() })

        val badSessionConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val badSessionConn = FakeConnectionManager(shellResponder = { "Success: no id here" })
        val badSessionController = SideloadController(
            scope = this,
            connectionProvider = { badSessionConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        badSessionController.executeInstall(badSessionConfig)
        assertEquals(
            SideloadState.Error("Could not parse session ID from: Success: no id here", InstallStep.CREATE_SESSION),
            badSessionController.state.value
        )
        assertTrue(badSessionConfig.apkFiles.none { it.exists() })

        val throwingCreateConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val throwingCreateConn = FakeConnectionManager(shellResponder = { throw IOException("link down") })
        val throwingCreateController = SideloadController(
            scope = this,
            connectionProvider = { throwingCreateConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        throwingCreateController.executeInstall(throwingCreateConfig)
        assertEquals(
            SideloadState.Error("Failed to create install session: link down", InstallStep.CREATE_SESSION),
            throwingCreateController.state.value
        )
        assertTrue(throwingCreateConfig.apkFiles.none { it.exists() })
    }

    @Test
    fun sideloadController_executeInstallAbandonsSessionAndCleansUpOnStreamAndCommitFailures() = runBlocking {
        val streamFailConfig = newSideloadConfig("base.apk" to byteArrayOf(9, 9))
        val streamFailConn = FakeConnectionManager(
            shellResponder = { cmd ->
                if (cmd.startsWith("cmd package install-create")) "Success: [77]" else ""
            },
            shellWithInputResponder = { _, _ -> "Failure [INSTALL_FAILED_INVALID_APK]" }
        )
        val streamFailController = SideloadController(
            scope = this,
            connectionProvider = { streamFailConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        streamFailController.executeInstall(streamFailConfig)
        assertEquals(
            SideloadState.Error(
                "Failed to write base.apk: Failure [INSTALL_FAILED_INVALID_APK]",
                InstallStep.STREAM_APKS
            ),
            streamFailController.state.value
        )
        assertTrue(streamFailConn.executedShellCommands.contains("cmd package install-abandon 77"))
        assertTrue(streamFailConfig.apkFiles.none { it.exists() })

        val commitBlankConfig = newSideloadConfig("base.apk" to byteArrayOf(7))
        val commitBlankConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("cmd package install-create") -> "Success: [88]"
                    cmd == "cmd package install-commit 88" -> "   "
                    else -> ""
                }
            },
            shellWithInputResponder = { _, _ -> "Success" }
        )
        val commitBlankController = SideloadController(
            scope = this,
            connectionProvider = { commitBlankConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        commitBlankController.executeInstall(commitBlankConfig)
        assertEquals(
            SideloadState.Error(
                "Installation failed: No response received from package manager.",
                InstallStep.COMMIT_INSTALL
            ),
            commitBlankController.state.value
        )
        assertTrue(commitBlankConfig.apkFiles.none { it.exists() })
    }

    /** What a [DeviceTweaksController] job did: commands interleaved with "> console" lines, final output, virtual ms. */
    private data class TweakRun(val transcript: List<String>, val output: String, val elapsedMs: Long)

    private fun TestScope.runTweak(
        sdk: Int = 33,
        airplaneMode: String = "0",
        installed: Set<String> = emptySet(),
        action: DeviceTweaksController.() -> Unit
    ): TweakRun {
        val transcript = mutableListOf<String>()
        val conn = FakeConnectionManager(shellResponder = { cmd ->
            transcript += cmd
            when {
                cmd == "getprop ro.build.version.sdk" -> "$sdk\n"
                cmd == "settings get global airplane_mode_on" -> "$airplaneMode\n"
                cmd == "dumpsys bluetooth_manager" -> "BT_DUMP"
                cmd.startsWith("pm path ") ->
                    if (cmd.removePrefix("pm path ") in installed) "package:/data/app/base.apk" else ""
                else -> ""
            }
        })
        val runner = CommandRunner(this, connectionProvider = { conn }, dispatcher = StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.output.drop(1).collect { transcript += "> $it" }
        }
        val controller = DeviceTweaksController(
            application = Application(),
            scope = this,
            connectionProvider = { conn },
            runner = runner
        )
        val start = testScheduler.currentTime
        controller.action()
        advanceUntilIdle()
        return TweakRun(transcript, runner.output.value, testScheduler.currentTime - start)
    }

    @Test
    fun deviceTweaks_standardBluetoothRepairRestartsBaseAndOemPackages() = runTest {
        val base = listOf("com.android.bluetooth", "com.android.bluetoothmidiservice", "com.google.android.bluetooth")
        val oemExtras = mapOf(
            BluetoothOem.AOSP to emptyList(),
            BluetoothOem.SAMSUNG to listOf("com.samsung.android.bluetooth"),
            BluetoothOem.XIAOMI to listOf("com.miui.bluetooth", "com.xiaomi.bluetooth", "com.xiaomi.bluetooth.overlay"),
            BluetoothOem.ONEPLUS to listOf("com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"),
            BluetoothOem.MOTOROLA to listOf("com.motorola.bluetooth", "com.motorola.bt")
        )
        for ((oem, extras) in oemExtras) {
            val run = runTweak { executeBluetoothRepair(oem, isAdvanced = false) }
            val restarts = (base + extras).flatMap { listOf("> Restarting $it...", "am force-stop $it") }
            assertEquals(
                oem.name,
                listOf(
                    "> Starting Bluetooth Repair...",
                    "> Disabling Bluetooth...", "cmd bluetooth_manager disable || svc bluetooth disable"
                ) +
                    restarts +
                    listOf(
                        "> Enabling Bluetooth...", "cmd bluetooth_manager enable || svc bluetooth enable",
                        "> Checking Bluetooth state...", "dumpsys bluetooth_manager",
                        "> Repair complete!\n\nBT_DUMP"
                    ),
                run.transcript
            )
            assertEquals("Repair complete!\n\nBT_DUMP", run.output)
            assertEquals(5000L, run.elapsedMs)
        }
    }

    @Test
    fun deviceTweaks_advancedBluetoothRepairClearsDataAndCyclesAirplaneModeOnAndroid11Plus() = runTest {
        val clears = listOf(
            "com.android.bluetooth", "com.android.bluetoothmidiservice", "com.google.android.bluetooth",
            "com.android.networkstack", "com.samsung.android.bluetooth"
        ).flatMap { listOf("> Clearing data for $it...", "pm clear $it") }
        val prefix = listOf(
            "> Starting Bluetooth Repair...",
            "> Disabling Bluetooth...", "cmd bluetooth_manager disable || svc bluetooth disable"
        ) +
            clears + "getprop ro.build.version.sdk"
        val suffix = listOf(
            "> Enabling Bluetooth...", "cmd bluetooth_manager enable || svc bluetooth enable",
            "> Checking Bluetooth state...", "dumpsys bluetooth_manager",
            "> Repair complete!\n\nBT_DUMP"
        )

        val airplaneOff = runTweak(sdk = 30, airplaneMode = "0") { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Cycling radios via Airplane Mode...", "settings get global airplane_mode_on",
                "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable"
            ) + suffix,
            airplaneOff.transcript
        )
        assertEquals(13000L, airplaneOff.elapsedMs)

        val airplaneOn = runTweak(sdk = 34, airplaneMode = "1") { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Cycling radios via Airplane Mode...", "settings get global airplane_mode_on",
                "cmd connectivity airplane-mode disable", "cmd connectivity airplane-mode enable"
            ) + suffix,
            airplaneOn.transcript
        )

        val oldSdk = runTweak(sdk = 29) { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Skipping Airplane Mode radio cycle (cmd connectivity requires Android 11+; target SDK is 29)."
            ) + suffix,
            oldSdk.transcript
        )
        assertEquals(5000L, oldSdk.elapsedMs)
        assertEquals("Repair complete!\n\nBT_DUMP", oldSdk.output)
    }

    @Test
    fun deviceTweaks_bluetoothQuirkExemptsInstalledPackagesOnlyForXiaomiAndOnePlus() = runTest {
        val xiaomi = runTweak(installed = setOf("com.android.bluetooth", "com.miui.bluetooth")) {
            applyBluetoothQuirk(BluetoothOem.XIAOMI)
        }
        assertEquals(
            listOf(
                "> Applying XIAOMI Bluetooth Doze & background exemptions...",
                "pm path com.android.bluetooth",
                "dumpsys deviceidle whitelist +com.android.bluetooth",
                "cmd appops set com.android.bluetooth RUN_ANY_IN_BACKGROUND allow",
                "pm path com.xiaomi.bluetooth",
                "pm path com.miui.bluetooth",
                "dumpsys deviceidle whitelist +com.miui.bluetooth",
                "cmd appops set com.miui.bluetooth RUN_ANY_IN_BACKGROUND allow",
                "> Exempted 2 Bluetooth package(s):\ncom.android.bluetooth\ncom.miui.bluetooth"
            ),
            xiaomi.transcript
        )
        assertEquals("Exempted 2 Bluetooth package(s):\ncom.android.bluetooth\ncom.miui.bluetooth", xiaomi.output)

        val oneplus = runTweak { applyBluetoothQuirk(BluetoothOem.ONEPLUS) }
        assertEquals(
            listOf(
                "> Applying ONEPLUS Bluetooth Doze & background exemptions...",
                "pm path com.android.bluetooth",
                "pm path com.oplus.bluetooth",
                "pm path com.coloros.bluetooth",
                "pm path com.nearme.bluetooth",
                "> No matching Bluetooth packages found for ONEPLUS."
            ),
            oneplus.transcript
        )
        assertEquals("No matching Bluetooth packages found for ONEPLUS.", oneplus.output)

        for (oem in listOf(BluetoothOem.AOSP, BluetoothOem.SAMSUNG, BluetoothOem.MOTOROLA)) {
            val none = runTweak { applyBluetoothQuirk(oem) }
            assertEquals(oem.name, emptyList<String>(), none.transcript)
            assertEquals(oem.name, "Ready.", none.output)
        }
    }

    @Test
    fun deviceTweaks_audioBypassSetsCsdWarningOnlyOnAndroid14Plus() = runTest {
        val common = listOf(
            "settings put global safe_media_volume_enabled 0",
            "settings put global audio_safe_volume_state 3"
        )
        val sdk34 = runTweak(sdk = 34) { executeAudioBypass() }
        assertEquals(
            listOf(
                "> Applying Audio Safety Bypass...",
                "getprop ro.build.version.sdk",
                "settings put global audio_safe_csd_next_warning 999999.0"
            ) + common + "> Bypass applied successfully! Rebooting the device is highly recommended.",
            sdk34.transcript
        )
        assertEquals("Bypass applied successfully! Rebooting the device is highly recommended.", sdk34.output)

        val sdk33 = runTweak(sdk = 33) { executeAudioBypass() }
        assertEquals(
            listOf("> Applying Audio Safety Bypass...", "getprop ro.build.version.sdk") + common +
                "> Bypass applied successfully! Rebooting the device is highly recommended.",
            sdk33.transcript
        )
    }
}
