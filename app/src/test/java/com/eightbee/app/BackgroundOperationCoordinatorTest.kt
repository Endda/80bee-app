package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class BackgroundOperationCoordinatorTest {

    private class FakeConnectionManager(
        var available: Boolean = true,
        var shellResponder: suspend (String) -> String = { "" }
    ) : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(available)
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.AdbConnected)
        override val isLocal: Boolean = false
        override val supportsFastboot: Boolean = false
        val executedCommands = mutableListOf<String>()

        override suspend fun runShellCommand(command: String): String {
            executedCommands.add(command)
            return shellResponder(command)
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""
        override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {}
        override suspend fun pushFile(input: InputStream, size: Long, remotePath: String, onProgress: ((Long, Long) -> Unit)?) {}
        override suspend fun pullFile(remotePath: String, destination: File, maxBytes: Long, onProgress: ((Long) -> Unit)?) {}
        override suspend fun sendFastbootCommand(command: String): FastbootReply = FastbootReply("OKAY", "")
        override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = ""
        override fun isAvailable(): Boolean = available
    }

    private fun CoroutineScope.awaitIdle() {
        val job = coroutineContext[Job] ?: return
        while (job.children.any { it.isActive }) {
            Thread.sleep(5)
        }
    }

    @Test
    fun startCompile_reportsErrorWhenConnectionMissingOrUnavailable() = runBlocking {
        val coordinator = BackgroundOperationCoordinator()
        val stateChanges = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.startCompile(scope) { stateChanges.incrementAndGet() }
            scope.awaitIdle()
            assertFalse(coordinator.isCompiling.value)
            assertEquals("Error: Connection not active.", coordinator.compileProgress.value)
            assertEquals(1, stateChanges.get())

            coordinator.connectionManager = FakeConnectionManager(available = false)
            coordinator.startCompile(scope) { stateChanges.incrementAndGet() }
            scope.awaitIdle()
            assertFalse(coordinator.isCompiling.value)
            assertEquals("Error: Connection not active.", coordinator.compileProgress.value)
            assertEquals(2, stateChanges.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startCompile_compilesAllParsedPackagesAndHandlesEmptyAndFailureCases() = runBlocking {
        val coordinator = BackgroundOperationCoordinator()
        val conn = FakeConnectionManager { cmd ->
            if (cmd == "pm list packages") "package:com.alpha\npackage:com.beta\n" else ""
        }
        coordinator.connectionManager = conn
        val stateChanges = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.startCompile(scope) { stateChanges.incrementAndGet() }
            scope.awaitIdle()

            assertFalse(coordinator.isCompiling.value)
            assertEquals("Compilation complete! Compiled 2 apps.", coordinator.compileProgress.value)
            assertEquals(
                listOf(
                    "pm list packages",
                    "pm compile -f -m speed com.alpha",
                    "pm compile -f -m speed com.beta"
                ),
                conn.executedCommands
            )
            assertTrue(stateChanges.get() >= 3)

            conn.shellResponder = { "" }
            coordinator.startCompile(scope) { stateChanges.incrementAndGet() }
            scope.awaitIdle()
            assertEquals("No packages found.", coordinator.compileProgress.value)

            conn.shellResponder = { throw IOException("adb dropped") }
            coordinator.startCompile(scope) { stateChanges.incrementAndGet() }
            scope.awaitIdle()
            assertEquals("Compilation error: adb dropped", coordinator.compileProgress.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startBatteryMonitor_respectsMaxDurationAndStopUpdatesState() = runBlocking {
        var nowMs = 1000L
        val coordinator = BackgroundOperationCoordinator(
            elapsedRealtimeMs = { nowMs },
            batteryPollIntervalMs = 10L,
            batteryMaxDurationMs = 100L
        )
        var polls = 0
        coordinator.connectionManager = FakeConnectionManager {
            polls++
            nowMs += 60L
            "level: ${100 - polls}"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.startBatteryMonitor(scope) {}
            scope.awaitIdle()

            assertFalse(coordinator.isMonitoringBattery.value)
            assertEquals(2, polls)
            assertEquals("Battery monitor stopped after 30-minute session limit.", coordinator.batteryTelemetry.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopAndTimeout_cancelActiveOperationsAndSetExpectedStatuses() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val coordinator = BackgroundOperationCoordinator(batteryPollIntervalMs = 5_000L)
        coordinator.connectionManager = FakeConnectionManager { cmd ->
            if (cmd == "pm list packages") {
                gate.await()
                "package:com.alpha"
            } else {
                "level: 90"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.startCompile(scope) {}
            coordinator.startBatteryMonitor(scope) {}
            assertTrue(coordinator.hasActiveOperations())

            coordinator.stopCompile {}
            assertFalse(coordinator.isCompiling.value)
            assertEquals("Compilation cancelled.", coordinator.compileProgress.value)
            assertTrue(coordinator.isMonitoringBattery.value)

            coordinator.handleTimeout()
            assertFalse(coordinator.isMonitoringBattery.value)
            assertFalse(coordinator.hasActiveOperations())
            assertEquals("Compilation cancelled.", coordinator.compileProgress.value)
            assertEquals(
                "Battery monitor stopped: foreground service time limit reached.",
                coordinator.batteryTelemetry.value
            )
            scope.awaitIdle()
        } finally {
            gate.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cancelAll_resetsActiveFlagsWithoutOverwritingStatus() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val coordinator = BackgroundOperationCoordinator()
        coordinator.connectionManager = FakeConnectionManager {
            gate.await()
            "level: 50"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.startBatteryMonitor(scope) {}
            assertTrue(coordinator.isMonitoringBattery.value)

            coordinator.cancelAll()
            assertFalse(coordinator.isMonitoringBattery.value)
            assertEquals("Initializing battery monitor...", coordinator.batteryTelemetry.value)
            scope.awaitIdle()
        } finally {
            gate.complete(Unit)
            scope.cancel()
        }
    }
}
