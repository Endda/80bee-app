package com.eightbee.app.connection

import com.eightbee.app.features.MAX_BOOT_IMAGE_BYTES
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object LocalConnected : ConnectionState()
    object AdbConnected : ConnectionState()
    object FastbootConnected : ConnectionState()
}

interface ConnectionManager {
    /**
     * A flow that emits the current availability and authorization status of this connection.
     */
    val isAvailableFlow: StateFlow<Boolean>

    /**
     * A flow that emits the current typed transport state of this connection.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * True when operating on the local device (e.g. Shizuku) rather than a remote OTG target.
     */
    val isLocal: Boolean

    /**
     * True when this transport supports USB Fastboot commands and flashing.
     */
    val supportsFastboot: Boolean

    /**
     * Executes a shell command on the device. Throws IOException on transport/connection failure.
     */
    suspend fun runShellCommand(command: String): String

    /**
     * Executes a shell command and streams the provided InputStream to its standard input.
     * Caller owns closing [input]. Throws IOException on transport/connection failure.
     */
    suspend fun runShellCommandWithInput(command: String, input: InputStream): String

    /**
     * Executes a shell command and streams output lines to [onLineRead].
     * Throws IOException on transport/connection failure.
     */
    suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit)

    /**
     * Pushes [input] of [size] bytes to [remotePath]. Caller owns closing [input].
     */
    suspend fun pushFile(
        input: InputStream,
        size: Long,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null
    )

    /**
     * Pulls [remotePath] to local [destination].
     */
    suspend fun pullFile(
        remotePath: String,
        destination: File,
        maxBytes: Long = MAX_BOOT_IMAGE_BYTES,
        onProgress: ((Long) -> Unit)? = null
    )

    /**
     * Executes a fastboot command and returns the parsed [FastbootReply].
     * Throws IOException on transport failure or UnsupportedOperationException if unsupported.
     */
    suspend fun sendFastbootCommand(command: String): FastbootReply

    /**
     * Flashes an image via Fastboot. Caller owns closing [stream].
     * Throws IOException on transport or protocol failure.
     */
    suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String

    /**
     * Checks if this connection method is currently available and authorized.
     */
    fun isAvailable(): Boolean
}

internal fun quoteRemoteShellPath(remotePath: String): String {
    if (remotePath.contains('\'') || remotePath.contains('\u0000')) {
        throw IllegalArgumentException("Invalid remote path: $remotePath")
    }
    return "'$remotePath'"
}