package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.InputStream

class CommandRunnerTest {

    private object StubConnection : ConnectionManager {
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.AdbConnected)
        override val isLocal: Boolean = false
        override val supportsFastboot: Boolean = false
        override suspend fun runShellCommand(command: String): String = ""
        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""
        override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {}
        override suspend fun pushFile(input: InputStream, size: Long, remotePath: String, onProgress: ((Long, Long) -> Unit)?) {}
        override suspend fun pullFile(remotePath: String, destination: File, maxBytes: Long, onProgress: ((Long) -> Unit)?) {}
        override suspend fun sendFastbootCommand(command: String): FastbootReply = FastbootReply(status = "OKAY", message = "")
        override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = ""
        override fun isAvailable(): Boolean = true
    }

    /** Runs [body] with a runner whose jobs execute eagerly, returning the snackbar events it emitted. */
    private fun withRunner(
        connection: ConnectionManager? = StubConnection,
        body: suspend CoroutineScope.(CommandRunner) -> Unit
    ): List<String> = runBlocking {
        val runner = CommandRunner(this, connectionProvider = { connection }, dispatcher = Dispatchers.Unconfined)
        val events = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { runner.snackbarEvents.toList(events) }
        body(runner)
        collector.cancel()
        events
    }

    @Test
    fun run_showsResultAndSummarizesFirstNonBlankLineInSnackbar() {
        lateinit var runner: CommandRunner
        val events = withRunner {
            runner = it
            it.run("Working...", notifySnackbar = true) { "\n  First line  \nsecond" }
        }
        assertEquals("\n  First line  \nsecond", runner.output.value)
        assertEquals(listOf("First line"), events)
        assertFalse(runner.isExecuting.value)
    }

    @Test
    fun run_replacesBlankResultWithCommandCompleted() {
        lateinit var runner: CommandRunner
        val events = withRunner {
            runner = it
            it.run("Working...", notifySnackbar = true) { "  " }
        }
        assertEquals("Command completed.", runner.output.value)
        assertEquals(listOf("Command completed."), events)
    }

    @Test
    fun run_truncatesLongSnackbarLinesTo90Chars() {
        val longLine = "x".repeat(120)
        val events = withRunner { it.run("Working...", notifySnackbar = true) { longLine } }
        assertEquals(listOf("x".repeat(87) + "..."), events)
    }

    @Test
    fun run_reportsExceptionMessageOrFallback() {
        lateinit var runner: CommandRunner
        val events = withRunner {
            runner = it
            it.run("Working...", notifySnackbar = true) { throw IllegalStateException("boom") }
            it.run("Working...", notifySnackbar = true) { throw IllegalStateException() }
        }
        assertEquals(listOf("Error: boom", "Error: Operation failed"), events)
        assertEquals("Error: Operation failed", runner.output.value)
        assertFalse(runner.isExecuting.value)
    }

    @Test
    fun run_withoutSnackbarOnlyUpdatesOutput() {
        lateinit var runner: CommandRunner
        val events = withRunner {
            runner = it
            it.run("Working...") { "Done here" }
        }
        assertEquals("Done here", runner.output.value)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun run_invokesAfterSuccessAfterResultAndReportsItsFailureAsError() {
        lateinit var runner: CommandRunner
        val seenOutputs = mutableListOf<String>()
        val events = withRunner {
            runner = it
            it.run("Working...", notifySnackbar = true, afterSuccess = { seenOutputs.add(runner.output.value) }) { "Applied." }
            it.run("Working...", notifySnackbar = true, afterSuccess = { throw IllegalStateException("refresh failed") }) { "Applied again." }
        }
        assertEquals(listOf("Applied."), seenOutputs)
        assertEquals(listOf("Applied.", "Applied again.", "Error: refresh failed"), events)
        assertEquals("Error: refresh failed", runner.output.value)
    }

    @Test
    fun run_skipsAfterSuccessWhenBlockFails() {
        var refreshed = false
        withRunner {
            it.run("Working...", afterSuccess = { refreshed = true }) { throw IllegalStateException("boom") }
        }
        assertFalse(refreshed)
    }

    @Test
    fun run_isNoOpWithoutConnection() {
        lateinit var runner: CommandRunner
        var ran = false
        val events = withRunner(connection = null) {
            runner = it
            it.run("Working...", notifySnackbar = true) { ran = true; "x" }
        }
        assertFalse(ran)
        assertEquals("Ready.", runner.output.value)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun reportUpdatesOutputAndSnackbarWhileShowOnlyUpdatesOutput() {
        lateinit var runner: CommandRunner
        val events = withRunner {
            runner = it
            it.show("progress")
            it.report("Invalid input.")
        }
        assertEquals("Invalid input.", runner.output.value)
        assertEquals(listOf("Invalid input."), events)
    }
}
