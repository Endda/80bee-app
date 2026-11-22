package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

internal class FakeConnectionManager(
    var fastbootReply: FastbootReply = FastbootReply(status = "OKAY", message = "done"),
    var shellResponder: (String) -> String = { "" },
    var shellWithInputResponder: (String, ByteArray) -> String = { _, _ -> "" },
    var available: Boolean = true,
    initialConnectionState: ConnectionState = ConnectionState.AdbConnected
) : ConnectionManager {
    override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(initialConnectionState)
    override val isLocal: Boolean = false
    override val supportsFastboot: Boolean = true
    val executedShellCommands = mutableListOf<String>()
    val executedShellInputCommands = mutableListOf<Pair<String, ByteArray>>()
    val executedFastbootCommands = mutableListOf<String>()

    override suspend fun runShellCommand(command: String): String {
        executedShellCommands.add(command)
        return shellResponder(command)
    }

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String {
        val bytes = input.readBytes()
        executedShellInputCommands.add(command to bytes)
        return shellWithInputResponder(command, bytes)
    }

    override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {}

    override suspend fun pushFile(
        input: InputStream,
        size: Long,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?
    ) {}

    override suspend fun pullFile(
        remotePath: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)?
    ) {}

    override suspend fun sendFastbootCommand(command: String): FastbootReply {
        executedFastbootCommands.add(command)
        return fastbootReply
    }

    override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = "Successfully flashed $partition!"

    override fun isAvailable(): Boolean = available
}

/** A runner whose jobs execute eagerly on the caller's thread, so tests can assert right after a call. */
internal fun eagerRunner(scope: CoroutineScope, conn: ConnectionManager) =
    CommandRunner(scope, connectionProvider = { conn }, dispatcher = Dispatchers.Unconfined)
