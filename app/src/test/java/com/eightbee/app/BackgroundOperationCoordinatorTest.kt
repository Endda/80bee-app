package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.BackgroundOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections

class BackgroundOperationCoordinatorTest {

    private class FakeConnectionManager(
        var available: Boolean = true,
        var shellResponder: suspend (String) -> String = { "" }
    ) : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(available)
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.AdbConnected)
        override val isLocal: Boolean = false
        override val supportsFastboot: Boolean = false
        val executedCommands: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

    private fun createCoordinator(
        elapsedRealtimeMs: () -> Long = { 0L },
        batteryPollIntervalMs: Long = 5_000L,
        batteryMaxDurationMs: Long = 30 * 60 * 1000L
    ) = BackgroundOperationCoordinator(
        elapsedRealtimeMs = elapsedRealtimeMs,
        batteryPollIntervalMs = batteryPollIntervalMs,
        batteryMaxDurationMs = batteryMaxDurationMs
    )

    private suspend fun CoroutineScope.awaitIdle() {
        coroutineContext[Job]?.children?.toList()?.joinAll()
    }

    @Test
    fun startCompile_reportsErrorWhenConnectionMissingOrUnavailable() = runBlocking {
        withTimeout(5_000L) {
            val coordinator = createCoordinator()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                coordinator.startCompile(scope)
                scope.awaitIdle()
                assertFalse(coordinator.isCompiling.value)
                assertEquals("Error: Connection not active.", coordinator.compileProgress.value)

                coordinator.connectionManager = FakeConnectionManager(available = false)
                coordinator.startCompile(scope)
                scope.awaitIdle()
                assertFalse(coordinator.isCompiling.value)
                assertEquals("Error: Connection not active.", coordinator.compileProgress.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun startCompile_compilesAllParsedPackagesAndHandlesEmptyAndFailureCases() = runBlocking {
        withTimeout(5_000L) {
            val coordinator = createCoordinator()
            val conn = FakeConnectionManager { cmd ->
                if (cmd == "pm list packages") "package:com.alpha\npackage:com.beta\n" else ""
            }
            coordinator.connectionManager = conn
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                coordinator.startCompile(scope)
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

                conn.shellResponder = { "" }
                coordinator.startCompile(scope)
                scope.awaitIdle()
                assertEquals("No packages found.", coordinator.compileProgress.value)

                conn.shellResponder = { throw IOException("adb dropped") }
                coordinator.startCompile(scope)
                scope.awaitIdle()
                assertEquals("Compilation error: adb dropped", coordinator.compileProgress.value)

                conn.shellResponder = { throw IOException() }
                coordinator.startCompile(scope)
                scope.awaitIdle()
                assertEquals("Compilation error: Operation failed", coordinator.compileProgress.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun startBatteryMonitor_respectsMaxDurationAndUpdatesTelemetry() = runBlocking {
        withTimeout(5_000L) {
            var nowMs = 1000L
            val coordinator = createCoordinator(
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
                coordinator.startBatteryMonitor(scope)
                scope.awaitIdle()

                assertFalse(coordinator.isMonitoringBattery.value)
                assertEquals(2, polls)
                assertEquals("Battery monitor stopped after 30-minute session limit.", coordinator.batteryTelemetry.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun stopAndTimeout_cancelActiveOperationsAndSetExpectedStatuses() = runBlocking {
        withTimeout(5_000L) {
            val compileEntered = CompletableDeferred<Unit>()
            val batteryEntered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coordinator = createCoordinator(batteryPollIntervalMs = 5_000L)
            coordinator.connectionManager = FakeConnectionManager { cmd ->
                if (cmd == "pm list packages") {
                    compileEntered.complete(Unit)
                    gate.await()
                    "package:com.alpha"
                } else {
                    batteryEntered.complete(Unit)
                    gate.await()
                    "level: 90"
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                coordinator.startCompile(scope)
                coordinator.startBatteryMonitor(scope)
                compileEntered.await()
                batteryEntered.await()
                assertTrue(coordinator.hasActiveOperations())

                coordinator.stopCompile()
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
    }

    @Test
    fun stopCompile_preventsStaleJobErrorAndMidLoopCompletionOverwrite() = runBlocking {
        withTimeout(5_000L) {
            val inShellCommand = CompletableDeferred<Unit>()
            val releaseShellCommand = CompletableDeferred<Unit>()
            val coordinator = createCoordinator()
            coordinator.connectionManager = FakeConnectionManager { cmd ->
                if (cmd == "pm list packages") {
                    "package:com.alpha"
                } else {
                    inShellCommand.complete(Unit)
                    try {
                        releaseShellCommand.await()
                    } catch (_: CancellationException) {
                        throw IOException("connection closed during cancel")
                    }
                    ""
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                coordinator.startCompile(scope)
                inShellCommand.await()

                coordinator.stopCompile()
                releaseShellCommand.complete(Unit)
                scope.awaitIdle()

                assertFalse(coordinator.isCompiling.value)
                assertEquals("Compilation cancelled.", coordinator.compileProgress.value)
            } finally {
                releaseShellCommand.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test
    fun cancelAll_resetsActiveFlagsWithoutOverwritingStatus() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coordinator = createCoordinator()
            coordinator.connectionManager = FakeConnectionManager {
                entered.complete(Unit)
                gate.await()
                "level: 50"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                coordinator.startBatteryMonitor(scope)
                entered.await()
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
}
