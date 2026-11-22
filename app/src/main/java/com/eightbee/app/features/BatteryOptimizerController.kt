package com.eightbee.app.features

import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads and switches the [OptimizerTweak]s; [state] holds the ones currently on. */
class BatteryOptimizerController(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val runner: CommandRunner
) {
    private val _state = MutableStateFlow<Set<OptimizerTweak>>(emptySet())
    val state: StateFlow<Set<OptimizerTweak>> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refreshState() {
        val conn = connectionProvider() ?: return
        if (!conn.isAvailable()) return
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            try {
                val values = conn.queryKeyValues(REFRESH_QUERIES)
                _state.value = OptimizerTweak.entries.filter { it.isActive(values) }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Runs [tweak]'s on or off batch as one job and records the new state only if the whole batch succeeds. */
    fun setTweak(tweak: OptimizerTweak, active: Boolean) {
        val change = if (active) tweak.on else tweak.off
        val commands = if (active) {
            change.commands
        } else {
            val stillShared = (_state.value - tweak).flatMap { it.sharedRestore }
            change.commands + (tweak.sharedRestore - stillShared.toSet())
        }
        runner.run(change.status) { conn ->
            conn.runShellBatch(commands)
            _state.update { if (active) it + tweak else it - tweak }
            change.message
        }
    }

    private companion object {
        val REFRESH_QUERIES = buildMap { OptimizerTweak.entries.forEach { putAll(it.queries) } }
    }
}
