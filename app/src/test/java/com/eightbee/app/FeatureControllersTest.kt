package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.parsePmListPackages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class FeatureControllersTest {

    private class FakeConnectionManager(
        var fastbootReply: String = "OKAY: done"
    ) : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
        val executedShellCommands = mutableListOf<String>()
        val executedFastbootCommands = mutableListOf<String>()

        override suspend fun runShellCommand(command: String): String {
            executedShellCommands.add(command)
            return ""
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""

        override suspend fun sendFastbootCommand(command: String): String {
            executedFastbootCommands.add(command)
            return fastbootReply
        }

        override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = "OKAY"

        override fun isAvailable(): Boolean = true
    }

    @Test
    fun isValidPrivateDnsHost_acceptsHostnamesAndRejectsShellInjection() {
        assertTrue(MainViewModel.isValidPrivateDnsHost("dns.adguard.com"))
        assertTrue(MainViewModel.isValidPrivateDnsHost(" 1dot1dot1dot1.cloudflare-dns.com "))
        assertFalse(MainViewModel.isValidPrivateDnsHost(""))
        assertFalse(MainViewModel.isValidPrivateDnsHost("localhost"))
        assertFalse(MainViewModel.isValidPrivateDnsHost("dns.quad9.net; id"))
        assertFalse(MainViewModel.isValidPrivateDnsHost("dns.quad9.net' && whoami"))
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
        assertTrue(fakeConn.executedShellCommands.contains("device_config delete device_idle min_time_to_alarm"))
    }

    @Test
    fun bootloaderWizardController_transitionsUnlockFlowOnOkayFastbootReply() = runBlocking {
        val fakeConn = FakeConnectionManager(fastbootReply = "OKAY: unlock pending")
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
}
