package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.BATCH_FAILURE_MARKER
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.DeviceTweaksController
import com.eightbee.app.features.FastbootCommandPolicy
import com.eightbee.app.features.FastbootValidationResult
import com.eightbee.app.features.parseKeyValueOutput
import com.eightbee.app.features.parsePmListPackages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FeatureControllersTest {

    private class FakeConnectionManager(
        var fastbootReply: FastbootReply = FastbootReply(status = "OKAY", message = "done"),
        var shellResponder: (String) -> String = { "" },
        initialConnectionState: ConnectionState = ConnectionState.AdbConnected
    ) : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(initialConnectionState)
        override val isLocal: Boolean = false
        override val supportsFastboot: Boolean = true
        val executedShellCommands = mutableListOf<String>()
        val executedFastbootCommands = mutableListOf<String>()

        override suspend fun runShellCommand(command: String): String {
            executedShellCommands.add(command)
            return shellResponder(command)
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""

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

        override fun isAvailable(): Boolean = true
    }

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
            runExclusive = { _, _, block ->
                runBlocking { block(fakeConn) }
            }
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
        val capturedErrors = mutableListOf<String>()
        val controller = BatteryOptimizerController(
            scope = this,
            connectionProvider = { fakeConn },
            runExclusive = { _, _, block ->
                runBlocking {
                    try {
                        block(fakeConn)
                    } catch (e: Exception) {
                        capturedErrors.add(e.message.orEmpty())
                    }
                }
            }
        )

        controller.applyMaxHibernation()
        assertFalse(controller.state.value.maxHibernation)
        assertEquals(1, capturedErrors.size)
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
            runExclusive = { _, _, block -> runBlocking { block(fakeConn) } }
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
            runExclusive = { _, _, block -> runBlocking { block(fakeConn) } }
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
            runExclusive = { _, _, block -> runBlocking { block(fakeConn) } },
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
        assertEquals("Exported snapshot with 1 package(s).", controller.snapshotFeedback.value)
        assertEquals("com.example.installed", exported.toString(Charsets.UTF_8.name()))

        // Export failure
        shouldFailExport = true
        controller.exportSnapshot { null }
        assertEquals("Failed to export snapshot: Unable to open destination file.", controller.snapshotFeedback.value)

        // Import success without rejected entries
        snapshotInput = "clean"
        controller.importSnapshot { snapshotInput }
        assertEquals(listOf("com.example.missing"), controller.restoreList.value.map { it.id })
        assertEquals("Loaded 1 missing package(s) from snapshot.", controller.snapshotFeedback.value)

        // Import with rejected entries triggers Ignored warning
        snapshotInput = "with-invalid"
        controller.importSnapshot { snapshotInput }
        assertEquals(
            "Loaded 1 missing package(s) from snapshot. Ignored 1 invalid package entry/entries.",
            controller.snapshotFeedback.value
        )

        // Import failure clears restoreList and reports error
        snapshotInput = "bad"
        controller.importSnapshot { snapshotInput }
        assertTrue(controller.restoreList.value.isEmpty())
        assertEquals("Failed to load snapshot: Malformed snapshot", controller.snapshotFeedback.value)

        // Disabled scan clears snapshotFeedback
        controller.scanDisabledPackages()
        assertEquals(null, controller.snapshotFeedback.value)
        assertEquals(listOf("com.example.disabled"), controller.restoreList.value.map { it.id })
    }
}
