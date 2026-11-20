package com.eightbee.app.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import com.cgutman.adblib.UsbChannel
import com.eightbee.app.features.MAX_BOOT_IMAGE_BYTES
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class OtgConnectionManager internal constructor(
    private val filesDirProvider: () -> File,
    private val hasUsbPermission: (UsbDevice) -> Boolean = { false },
    private val openUsbDevice: (UsbDevice) -> UsbDeviceConnection? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cryptoProvider: (() -> AdbCrypto?)? = null,
    private val logSink: (isError: Boolean, tag: String, message: String) -> Unit = { isError, tag, message ->
        if (isError) Log.e(tag, message) else Log.d(tag, message)
    },
    initialAdbConnection: AdbConnection? = null,
    initialFastbootClient: FastbootUsbClient? = null
) : ConnectionManager {

    constructor(
        context: Context,
        usbManager: UsbManager
    ) : this(
        filesDirProvider = { context.filesDir },
        hasUsbPermission = { usbManager.hasPermission(it) },
        openUsbDevice = { usbManager.openDevice(it) }
    )

    companion object {
        private const val TAG = "OtgConnectionManager"

        fun findInterfaces(device: UsbDevice): Pair<UsbInterface?, UsbInterface?> {
            var adbInterface: UsbInterface? = null
            var fastbootInterface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 255) {
                    if (intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                        adbInterface = intf
                    } else if (intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x03) {
                        fastbootInterface = intf
                    }
                }
            }
            return adbInterface to fastbootInterface
        }

        fun isAdbOrFastbootDevice(device: UsbDevice): Boolean {
            val (adb, fastboot) = findInterfaces(device)
            return adb != null || fastboot != null
        }
    }

    private val _isAvailableFlow = MutableStateFlow(false)
    override val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val isLocal: Boolean = false
    override val supportsFastboot: Boolean = true

    private val stateLock = Any()

    @Volatile private var connectedDevice: UsbDevice? = null
    @Volatile private var adbConnection: AdbConnection? = initialAdbConnection
    @Volatile private var fastbootClient: FastbootUsbClient? = initialFastbootClient
    @Volatile private var connectionJob: Job? = null
    @Volatile private var pendingDisconnectJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    // Load/generate the 2048-bit RSA ADB keypair off the main thread; connectDevice() awaits this.
    private val cryptoDeferred: Deferred<AdbCrypto?> = ioScope.async {
        cryptoProvider?.invoke() ?: loadOrGenerateCrypto()
    }

    private fun loadOrGenerateCrypto(): AdbCrypto? {
        val base64 = AdbBase64Impl()
        val filesDir = filesDirProvider()
        val privateKey = File(filesDir, "private_key")
        val publicKey = File(filesDir, "public_key")

        var crypto: AdbCrypto? = if (privateKey.exists() && publicKey.exists()) {
            try {
                AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        if (crypto == null) {
            try {
                crypto = AdbCrypto.generateAdbKeyPair(base64)
                crypto?.saveAdbKeyPair(privateKey, publicKey)
            } catch (e: Exception) {
                logSink(true, TAG, "Failed to setup crypto: ${e.message}")
            }
        }
        return crypto
    }

    fun setDevice(device: UsbDevice?): Boolean {
        logSink(false, TAG, "setDevice: $device")
        if (device == null) {
            disconnect()
            return false
        }

        val current = connectedDevice
        if (current != null &&
            isSameDevice(current, device) &&
            _connectionState.value != ConnectionState.Disconnected
        ) {
            logSink(false, TAG, "Device ${device.deviceName} is already active/connecting; skipping reconnect")
            return false
        }

        if (hasUsbPermission(device)) {
            logSink(false, TAG, "Permission already granted for ${device.deviceName}")
            if (current != null || adbConnection != null || fastbootClient != null) {
                pendingDisconnectJob = disconnect()
            }
            connectedDevice = device
            connectDevice()
            return true
        } else {
            logSink(false, TAG, "Waiting for permission for ${device.deviceName}")
            return false
        }
    }

    fun onDeviceDetached(device: UsbDevice): Boolean {
        val current = connectedDevice ?: return false
        if (!isSameDevice(current, device)) {
            logSink(false, TAG, "Ignoring detach for unrelated USB device ${device.deviceName}")
            return false
        }
        disconnect()
        return true
    }

    private fun isSameDevice(first: UsbDevice, second: UsbDevice): Boolean =
        first.deviceId == second.deviceId || first.deviceName == second.deviceName

    private fun clearConnectingDeviceIfCurrent(device: UsbDevice) {
        synchronized(stateLock) {
            if (connectedDevice == device) {
                connectedDevice = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private inline fun commitConnectionIfCurrent(
        device: UsbDevice,
        active: Boolean,
        targetState: ConnectionState,
        assign: () -> Unit
    ): Boolean {
        synchronized(stateLock) {
            if (connectedDevice == device && active) {
                assign()
                _isAvailableFlow.value = true
                _connectionState.value = targetState
                return true
            }
        }
        return false
    }

    private fun connectDevice() {
        val device = connectedDevice ?: return
        logSink(false, TAG, "connectDevice: Starting connection process for ${device.deviceName}")
        logSink(false, TAG, "Device details: Vendor=${device.vendorId}, Product=${device.productId}, Class=${device.deviceClass}")
        logSink(false, TAG, "Configuration count: ${device.configurationCount}")

        val (adbInterface, fastbootInterface) = findInterfaces(device)

        if (adbInterface == null && fastbootInterface == null) {
            logSink(true, TAG, "Aborting: No ADB or Fastboot interface found")
            return
        }

        connectionJob?.cancel()
        _connectionState.value = ConnectionState.Connecting

        connectionJob = ioScope.launch {
            pendingDisconnectJob?.join()
            var openedConnection: UsbDeviceConnection? = null
            var pendingAdb: AdbConnection? = null
            var pendingFastboot: FastbootUsbClient? = null
            var committed = false
            try {
                val connection = openUsbDevice(device) ?: run {
                    logSink(true, TAG, "Failed to open USB device connection")
                    clearConnectingDeviceIfCurrent(device)
                    return@launch
                }
                openedConnection = connection

                if (device.configurationCount > 1) {
                    connection.setConfiguration(device.getConfiguration(0))
                }

                if (adbInterface != null) {
                    val crypto = cryptoDeferred.await() ?: run {
                        logSink(true, TAG, "AdbCrypto not initialized")
                        clearConnectingDeviceIfCurrent(device)
                        return@launch
                    }

                    val channel = UsbChannel(connection, adbInterface)
                    val newAdbConnection = AdbConnection.create(channel, crypto)
                    pendingAdb = newAdbConnection
                    newAdbConnection.connect()

                    committed = commitConnectionIfCurrent(device, isActive, ConnectionState.AdbConnected) {
                        adbConnection = newAdbConnection
                    }
                    if (committed) {
                        logSink(false, TAG, "ADB Connection successful for ${device.deviceName}")
                    }
                } else if (fastbootInterface != null) {
                    val client = FastbootUsbClient(fastbootInterface, connection)
                    pendingFastboot = client
                    committed = commitConnectionIfCurrent(device, isActive, ConnectionState.FastbootConnected) {
                        fastbootClient = client
                    }
                    if (committed) {
                        logSink(false, TAG, "Fastboot Connection successful for ${device.deviceName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logSink(true, TAG, "Connection failed for ${device.deviceName}: ${e.message}")
                disconnect()
            } finally {
                if (!committed) {
                    when {
                        pendingAdb != null -> runCatching { pendingAdb.close() }
                        pendingFastboot != null -> runCatching { pendingFastboot.close() }
                        else -> runCatching { openedConnection?.close() }
                    }
                }
            }
        }
    }

    fun disconnect(): Job {
        val currentAdb: AdbConnection?
        val currentFb: FastbootUsbClient?
        synchronized(stateLock) {
            connectionJob?.cancel()
            connectionJob = null
            _isAvailableFlow.value = false
            _connectionState.value = ConnectionState.Disconnected

            currentAdb = adbConnection
            currentFb = fastbootClient

            adbConnection = null
            fastbootClient = null
            connectedDevice = null
        }

        return ioScope.launch(NonCancellable) {
            runCatching { currentAdb?.close() }
            runCatching { currentFb?.close() }
        }
    }

    fun close() {
        val cleanupJob = disconnect()
        ioScope.launch(NonCancellable) {
            cleanupJob.join()
            ioScope.cancel()
        }
    }

    private fun requireAdbConnection(): AdbConnection =
        adbConnection ?: throw IOException("ADB not connected.")

    private fun requireFastbootClient(disconnectedMessage: String): FastbootUsbClient =
        fastbootClient ?: throw IOException(disconnectedMessage)

    private suspend inline fun <T> withWrappedIoError(
        errorPrefix: String,
        crossinline block: suspend CoroutineScope.() -> T
    ): T = withContext(ioDispatcher) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("$errorPrefix: ${e.message}", e)
        }
    }

    private inline fun <T> withAdbStream(
        destination: String,
        block: (AdbStream) -> T
    ): T {
        val connection = requireAdbConnection()
        var stream: AdbStream? = null
        return try {
            stream = connection.open(destination)
            block(stream)
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun readStreamText(stream: AdbStream): String {
        val output = StringBuilder()
        readStreamFully(stream) { bytes ->
            output.append(String(bytes, StandardCharsets.UTF_8))
        }
        return output.toString().trim()
    }

    private inline fun copyStreamToAdb(
        input: InputStream,
        stream: AdbStream,
        onClosed: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {}
    ): Long {
        var sent = 0L
        val buffer = ByteArray(4096)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (stream.isClosed) {
                onClosed(sent)
                break
            }
            val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
            stream.write(chunk)
            sent += bytesRead
            onProgress(sent)
        }
        return sent
    }

    private fun readStreamFully(stream: AdbStream, onChunk: (ByteArray) -> Unit) {
        while (true) {
            try {
                val bytes = stream.read() ?: break
                onChunk(bytes)
            } catch (_: InterruptedException) {
                break
            } catch (e: IOException) {
                if (e.message == "Stream closed") {
                    break
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun runShellCommand(command: String): String =
        withWrappedIoError("Error executing via OTG") {
            withAdbStream("shell:$command", ::readStreamText)
        }

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String =
        withWrappedIoError("Error executing via OTG") {
            withAdbStream("shell:$command") { stream ->
                val writerJob = launch {
                    try {
                        copyStreamToAdb(input, stream)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logSink(true, TAG, "Error writing stream input: ${e.message}")
                    }
                }
                try {
                    val output = readStreamText(stream)
                    writerJob.join()
                    output
                } finally {
                    writerJob.cancel()
                }
            }
        }

    override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit): Unit =
        withWrappedIoError("Error executing via OTG") {
            withAdbStream("shell:$command") { stream ->
                val buffer = StringBuilder()
                readStreamFully(stream) { bytes ->
                    buffer.append(String(bytes, StandardCharsets.UTF_8))

                    var newlineIndex = buffer.indexOf("\n")
                    while (newlineIndex != -1) {
                        onLineRead(buffer.substring(0, newlineIndex))
                        buffer.delete(0, newlineIndex + 1)
                        newlineIndex = buffer.indexOf("\n")
                    }
                }
                if (buffer.isNotEmpty()) {
                    onLineRead(buffer.toString())
                }
            }
        }

    override suspend fun sendFastbootCommand(command: String): FastbootReply =
        withWrappedIoError("Error executing Fastboot via OTG") {
            requireFastbootClient("No Fastboot OTG device connected or authorized.").sendCommand(command)
        }

    override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String =
        withWrappedIoError("Error flashing via OTG") {
            val client = requireFastbootClient("No Fastboot OTG device connected.")
            val result = client.flashImage(partition, stream, size)
            if (!result.startsWith("Successfully flashed")) {
                throw IOException(result)
            }
            result
        }

    override fun isAvailable(): Boolean {
        return adbConnection != null || fastbootClient != null
    }

    private suspend fun statRemoteSize(remotePath: String): Long? {
        val quoted = quoteRemoteShellPath(remotePath)
        val out = runShellCommand("stat -c %s $quoted").trim()
        return out.toLongOrNull()
    }

    override suspend fun pushFile(
        input: InputStream,
        size: Long,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?
    ): Unit = withContext(ioDispatcher) {
        requireAdbConnection()
        val quotedPath = quoteRemoteShellPath(remotePath)
        logSink(false, TAG, "pushFile: Opening stream to dd of=$quotedPath status=none...")
        // Closing the stream inside withAdbStream delivers EOF (CLSE) to dd's stdin so it exits cleanly.
        withAdbStream("exec:dd of=$quotedPath status=none") { stream ->
            val sent = copyStreamToAdb(
                input = input,
                stream = stream,
                onClosed = { sentSoFar ->
                    throw IOException("Remote closed stream prematurely after $sentSoFar / $size bytes")
                },
                onProgress = { sentSoFar -> onProgress?.invoke(sentSoFar, size) }
            )
            if (sent != size) {
                throw IOException("Push incomplete for $remotePath: sent $sent of $size bytes")
            }
        }

        var remoteSize: Long? = null
        for (attempt in 0 until 15) {
            remoteSize = statRemoteSize(remotePath)
            if (remoteSize == size) break
            delay(100)
        }
        if (remoteSize != size) {
            throw IOException("Push size verification failed for $remotePath: expected $size bytes, got ${remoteSize ?: "unknown"}")
        }
        logSink(false, TAG, "pushFile: Verified $size bytes written to $remotePath")
    }

    override suspend fun pullFile(
        remotePath: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)?
    ): Unit = withContext(ioDispatcher) {
        requireAdbConnection()
        val quotedPath = quoteRemoteShellPath(remotePath)
        val expectedSize = statRemoteSize(remotePath)
            ?: throw IOException("Unable to stat remote file before pull: $remotePath")
        if (expectedSize <= 0L || expectedSize > maxBytes) {
            throw IOException("Unexpected remote file size for $remotePath: $expectedSize bytes")
        }

        try {
            withAdbStream("exec:cat $quotedPath") { stream ->
                var received = 0L
                destination.outputStream().buffered().use { out ->
                    readStreamFully(stream) { bytes ->
                        received += bytes.size
                        if (received > maxBytes) {
                            throw IOException("Pulled file exceeded maximum allowed size ($maxBytes bytes)")
                        }
                        out.write(bytes)
                        onProgress?.invoke(received)
                    }
                }
                if (received != expectedSize) {
                    throw IOException("Pull size verification failed for $remotePath: expected $expectedSize bytes, got $received")
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
