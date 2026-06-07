package com.eightbee.app.connection

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

interface ConnectionManager {
    /**
     * A flow that emits the current availability and authorization status of this connection.
     */
    val isAvailableFlow: StateFlow<Boolean>

    /**
     * Executes a shell command on the device.
     */
    suspend fun runShellCommand(command: String): String

    /**
     * Executes a shell command and streams the provided InputStream to its standard input.
     */
    suspend fun runShellCommandWithInput(command: String, input: InputStream): String

    /**
     * Executes a fastboot command (OTG only).
     */
    suspend fun sendFastbootCommand(command: String): String

    /**
     * Flashes an image via Fastboot (OTG only).
     */
    suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String

    /**
     * Checks if this connection method is currently available and authorized.
     */
    fun isAvailable(): Boolean

    /**
     * Executes a command to query the status of a specific setting.
     * Returns the raw output of the query.
     */
    suspend fun queryStatus(command: String): String = runShellCommand(command)
}