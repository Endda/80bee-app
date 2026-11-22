package com.eightbee.app

import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.FastbootCommandPolicy
import com.eightbee.app.features.FastbootValidationResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootloaderWizardControllerTest {

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
}
