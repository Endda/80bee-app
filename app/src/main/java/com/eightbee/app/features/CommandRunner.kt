package com.eightbee.app.features

import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs one shell job at a time against the active connection and reports it to the console and snackbar. */
class CommandRunner(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val mutex = Mutex()

    /**
     * Runs [block] exclusively, showing [initialStatus] while it works and its result (or error) when done.
     * [afterSuccess] runs after the result is reported; if it throws, the error replaces the result.
     */
    fun run(
        initialStatus: String,
        afterSuccess: suspend (ConnectionManager) -> Unit = {},
        block: suspend (ConnectionManager) -> String
    ) {
        val connection = connectionProvider() ?: return
        scope.launch(dispatcher) {
            mutex.withLock {
                _isExecuting.value = true
                _output.value = initialStatus
                try {
                    val result = block(connection).ifBlank { "Command completed." }
                    _output.value = result
                    _snackbarEvents.tryEmit(summarizeForSnackbar(result))
                    afterSuccess(connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Error: ${e.message ?: "Operation failed"}")
                } finally {
                    _isExecuting.value = false
                }
            }
        }
    }

    /** Replaces the console text without notifying the snackbar (progress lines). */
    fun show(message: String) {
        _output.value = message
    }

    /** Shows [message] on the console and in the snackbar. */
    fun report(message: String) {
        _output.value = message
        _snackbarEvents.tryEmit(message)
    }

    private fun summarizeForSnackbar(result: String): String {
        val firstLine = result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length > 90) firstLine.take(87) + "..." else firstLine.ifEmpty { "Done." }
    }
}
