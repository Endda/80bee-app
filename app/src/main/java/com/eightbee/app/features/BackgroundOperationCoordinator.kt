package com.eightbee.app.features

import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.EnumMap

internal enum class BackgroundOpKind(
    val initialStatus: String,
    val stoppedStatus: String,
    val timeoutStatus: String,
    val errorPrefix: String
) {
    COMPILE(
        initialStatus = "Starting compilation...",
        stoppedStatus = "Compilation cancelled.",
        timeoutStatus = "Compilation stopped: foreground service time limit reached.",
        errorPrefix = "Compilation error: "
    ),
    BATTERY_MONITOR(
        initialStatus = "Initializing battery monitor...",
        stoppedStatus = "Battery monitor stopped.",
        timeoutStatus = "Battery monitor stopped: foreground service time limit reached.",
        errorPrefix = "Error: "
    )
}

class BackgroundOperationCoordinator internal constructor(
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val batteryPollIntervalMs: Long = 5000L,
    private val batteryMaxDurationMs: Long = 30 * 60 * 1000L
) {
    constructor() : this(elapsedRealtimeMs = { android.os.SystemClock.elapsedRealtime() })

    private class OpSlot {
        val isActive = MutableStateFlow(false)
        val status = MutableStateFlow("")
        var job: Job? = null
    }

    private val slots = EnumMap<BackgroundOpKind, OpSlot>(BackgroundOpKind::class.java).apply {
        BackgroundOpKind.entries.forEach { put(it, OpSlot()) }
    }

    @Volatile
    var connectionManager: ConnectionManager? = null

    val compileProgress: StateFlow<String> = slots.getValue(BackgroundOpKind.COMPILE).status.asStateFlow()
    val isCompiling: StateFlow<Boolean> = slots.getValue(BackgroundOpKind.COMPILE).isActive.asStateFlow()
    val batteryTelemetry: StateFlow<String> = slots.getValue(BackgroundOpKind.BATTERY_MONITOR).status.asStateFlow()
    val isMonitoringBattery: StateFlow<Boolean> = slots.getValue(BackgroundOpKind.BATTERY_MONITOR).isActive.asStateFlow()

    internal fun hasActiveOperations(): Boolean = slots.values.any { slot ->
        synchronized(slot) { slot.isActive.value }
    }

    fun startCompile(scope: CoroutineScope) {
        launchOperation(scope, BackgroundOpKind.COMPILE) { conn, updateStatus ->
            updateStatus("Fetching installed packages...")
            val packages = parsePmListPackages(conn.runShellCommand("pm list packages"))
            val total = packages.size
            if (total == 0) {
                updateStatus("No packages found.")
                return@launchOperation
            }

            for (i in 0 until total) {
                ensureActive()
                val pkg = packages[i]
                updateStatus("Compiling [${i + 1}/$total]: $pkg")
                conn.runShellCommand("pm compile -f -m speed $pkg")
            }
            ensureActive()
            updateStatus("Compilation complete! Compiled $total apps.")
        }
    }

    fun stopCompile() {
        cancelSlot(slots.getValue(BackgroundOpKind.COMPILE), status = BackgroundOpKind.COMPILE.stoppedStatus)
    }

    fun startBatteryMonitor(scope: CoroutineScope) {
        launchOperation(scope, BackgroundOpKind.BATTERY_MONITOR) { conn, updateStatus ->
            val startElapsedMs = elapsedRealtimeMs()
            while (true) {
                ensureActive()
                if (elapsedRealtimeMs() - startElapsedMs >= batteryMaxDurationMs) {
                    updateStatus("Battery monitor stopped after 30-minute session limit.")
                    break
                }
                updateStatus(conn.runShellCommand("dumpsys battery"))
                delay(batteryPollIntervalMs)
            }
        }
    }

    fun stopBatteryMonitor() {
        cancelSlot(
            slots.getValue(BackgroundOpKind.BATTERY_MONITOR),
            status = BackgroundOpKind.BATTERY_MONITOR.stoppedStatus
        )
    }

    fun handleTimeout() {
        slots.forEach { (kind, slot) ->
            cancelSlot(slot, status = kind.timeoutStatus, onlyIfActive = true)
        }
    }

    fun cancelAll() {
        slots.values.forEach { slot ->
            cancelSlot(slot)
        }
    }

    private fun cancelSlot(slot: OpSlot, status: String? = null, onlyIfActive: Boolean = false) {
        val jobToCancel: Job?
        synchronized(slot) {
            if (onlyIfActive && !slot.isActive.value) return
            jobToCancel = slot.job
            slot.job = null
            slot.isActive.value = false
            if (status != null) {
                slot.status.value = status
            }
        }
        jobToCancel?.cancel()
    }

    private fun launchOperation(
        scope: CoroutineScope,
        kind: BackgroundOpKind,
        block: suspend CoroutineScope.(
            conn: ConnectionManager,
            updateStatus: (String) -> Unit
        ) -> Unit
    ) {
        val slot = slots.getValue(kind)
        lateinit var launchedJob: Job
        synchronized(slot) {
            if (slot.isActive.value) return
            slot.isActive.value = true
            slot.status.value = kind.initialStatus
            launchedJob = scope.launch(start = CoroutineStart.LAZY) {
                val updateIfCurrent: (String) -> Unit = { message ->
                    synchronized(slot) {
                        if (slot.job === launchedJob) {
                            slot.status.value = message
                        }
                    }
                }

                try {
                    val conn = connectionManager
                    if (conn == null || !conn.isAvailable()) {
                        updateIfCurrent("Error: Connection not active.")
                        return@launch
                    }
                    block(conn, updateIfCurrent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateIfCurrent("${kind.errorPrefix}${e.message ?: "Operation failed"}")
                } finally {
                    releaseSlotIfCurrent(slot, launchedJob)
                }
            }
            slot.job = launchedJob
        }
        // Backstop for a job that never runs its body (e.g. launched into an already-cancelled
        // scope), so the slot can't stay active forever in this Application-lifetime coordinator.
        launchedJob.invokeOnCompletion { releaseSlotIfCurrent(slot, launchedJob) }
        launchedJob.start()
    }

    private fun releaseSlotIfCurrent(slot: OpSlot, job: Job) {
        synchronized(slot) {
            if (slot.job === job) {
                slot.job = null
                slot.isActive.value = false
            }
        }
    }
}
