package com.eightbee.app.connection

import android.content.Context
import android.hardware.usb.UsbDevice
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

class OtgConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager
) : ConnectionManager {

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
    @Volatile private var adbConnection: AdbConnection? = null
    @Volatile private var fastbootClient: FastbootUsbClient? = null
    @Volatile private var connectionJob: Job? = null
    @Volatile private var pendingDisconnectJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Load/generate the 2048-bit RSA ADB keypair off the main thread; connectDevice() awaits this.
    private val cryptoDeferred: Deferred<AdbCrypto?> = ioScope.async { loadOrGenerateCrypto() }

    private fun loadOrGenerateCrypto(): AdbCrypto? {
        val base64 = AdbBase64Impl()
        val privateKey = File(context.filesDir, "private_key")
        val publicKey = File(context.filesDir, "public_key")

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
                Log.e(TAG, "Failed to setup crypto", e)
            }
        }
        return crypto
    }

    fun setDevice(device: UsbDevice?): Boolean {
        Log.d(TAG, "setDevice: $device")
        if (device == null) {
            disconnect()
            return false
        }

        val current = connectedDevice
        if (current != null &&
            (current.deviceId == device.deviceId || current.deviceName == device.deviceName) &&
            _connectionState.value != ConnectionState.Disconnected
        ) {
            Log.d(TAG, "Device ${device.deviceName} is already active/connecting; skipping reconnect")
            return false
        }

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Permission already granted for ${device.deviceName}")
            if (current != null || adbConnection != null || fastbootClient != null) {
                pendingDisconnectJob = disconnect()
            }
            connectedDevice = device
            connectDevice()
            return true
        } else {
            Log.d(TAG, "Waiting for permission for ${device.deviceName}")
            return false
        }
    }

    fun onDeviceDetached(device: UsbDevice): Boolean {
        val current = connectedDevice ?: return false
        if (current.deviceId != device.deviceId && current.deviceName != device.deviceName) {
            Log.d(TAG, "Ignoring detach for unrelated USB device ${device.deviceName}")
            return false
        }
        disconnect()
        return true
    }

    private fun connectDevice() {
        val device = connectedDevice ?: return
        Log.d(TAG, "connectDevice: Starting connection process for ${device.deviceName}")
        Log.d(TAG, "Device details: Vendor=${device.vendorId}, Product=${device.productId}, Class=${device.deviceClass}")
        Log.d(TAG, "Configuration count: ${device.configurationCount}")

        val (adbInterface, fastbootInterface) = findInterfaces(device)

        if (adbInterface == null && fastbootInterface == null) {
            Log.e(TAG, "Aborting: No ADB or Fastboot interface found")
            return
        }

        connectionJob?.cancel()
        _connectionState.value = ConnectionState.Connecting

        connectionJob = ioScope.launch {
            pendingDisconnectJob?.join()
            var openedConnection: android.hardware.usb.UsbDeviceConnection? = null
            var pendingAdb: AdbConnection? = null
            var pendingFastboot: FastbootUsbClient? = null
            var committed = false
            try {
                val connection = usbManager.openDevice(device) ?: run {
                    Log.e(TAG, "Failed to open USB device connection")
                    synchronized(stateLock) {
                        if (connectedDevice == device) {
                            connectedDevice = null
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                    return@launch
                }
                openedConnection = connection

                if (device.configurationCount > 1) {
                    connection.setConfiguration(device.getConfiguration(0))
                }

                if (adbInterface != null) {
                    val crypto = cryptoDeferred.await() ?: run {
                        Log.e(TAG, "AdbCrypto not initialized")
                        synchronized(stateLock) {
                            if (connectedDevice == device) {
                                connectedDevice = null
                                _connectionState.value = ConnectionState.Disconnected
                            }
                        }
                        return@launch
                    }

                    val channel = UsbChannel(connection, adbInterface)
                    val newAdbConnection = AdbConnection.create(channel, crypto)
                    pendingAdb = newAdbConnection
                    newAdbConnection.connect()

                    synchronized(stateLock) {
                        if (connectedDevice == device && isActive) {
                            adbConnection = newAdbConnection
                            committed = true
                            _isAvailableFlow.value = true
                            _connectionState.value = ConnectionState.AdbConnected
                        }
                    }
                    if (committed) {
                        Log.d(TAG, "ADB Connection successful for ${device.deviceName}")
                    }
                } else if (fastbootInterface != null) {
                    val client = FastbootUsbClient(fastbootInterface, connection)
                    pendingFastboot = client
                    synchronized(stateLock) {
                        if (connectedDevice == device && isActive) {
                            fastbootClient = client
                            committed = true
                            _isAvailableFlow.value = true
                            _connectionState.value = ConnectionState.FastbootConnected
                        }
                    }
                    if (committed) {
                        Log.d(TAG, "Fastboot Connection successful for ${device.deviceName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed for ${device.deviceName}", e)
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

    override suspend fun runShellCommand(command: String): String = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: throw IOException("ADB not connected.")
        var stream: AdbStream? = null
        try {
            stream = connection.open("shell:$command")
            val output = StringBuilder()
            readStreamFully(stream) { bytes ->
                output.append(String(bytes, StandardCharsets.UTF_8))
            }
            output.toString().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error executing via OTG: ${e.message}", e)
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {}
        }
    }

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: throw IOException("ADB not connected.")
        var stream: AdbStream? = null
        var writerJob: Job? = null
        try {
            stream = connection.open("shell:$command")
            val currentStream = stream
            writerJob = launch {
                try {
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (currentStream.isClosed) break
                        val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
                        currentStream.write(chunk)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing stream input", e)
                }
            }

            val output = StringBuilder()
            readStreamFully(stream) { bytes ->
                output.append(String(bytes, StandardCharsets.UTF_8))
            }
            writerJob.join()
            output.toString().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error executing via OTG: ${e.message}", e)
        } finally {
            writerJob?.cancel()
            try {
                stream?.close()
            } catch (_: Exception) {}
        }
    }

    override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: throw IOException("ADB not connected.")
        var stream: AdbStream? = null
        try {
            stream = connection.open("shell:$command")
            val buffer = StringBuilder()
            readStreamFully(stream) { bytes ->
                val chunk = String(bytes, StandardCharsets.UTF_8)
                buffer.append(chunk)

                var newlineIndex = buffer.indexOf("\n")
                while (newlineIndex != -1) {
                    val line = buffer.substring(0, newlineIndex)
                    onLineRead(line)
                    buffer.delete(0, newlineIndex + 1)
                    newlineIndex = buffer.indexOf("\n")
                }
            }
            if (buffer.isNotEmpty()) {
                onLineRead(buffer.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error executing via OTG: ${e.message}", e)
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {}
        }
    }

    override suspend fun sendFastbootCommand(command: String): FastbootReply = withContext(Dispatchers.IO) {
        val client = fastbootClient ?: throw IOException("No Fastboot OTG device connected or authorized.")

        try {
            client.sendCommand(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error executing Fastboot via OTG: ${e.message}", e)
        }
    }

    override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = withContext(Dispatchers.IO) {
        val client = fastbootClient ?: throw IOException("No Fastboot OTG device connected.")
        try {
            val result = client.flashImage(partition, stream, size)
            if (!result.startsWith("Successfully flashed")) {
                throw IOException(result)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error flashing via OTG: ${e.message}", e)
        }
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
    ): Unit = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: throw IOException("ADB not connected.")
        val quotedPath = quoteRemoteShellPath(remotePath)
        var stream: AdbStream? = null
        try {
            Log.d(TAG, "pushFile: Opening stream to dd of=$quotedPath status=none...")
            stream = connection.open("exec:dd of=$quotedPath status=none")
            val currentStream = stream
            var sent = 0L
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (currentStream.isClosed) {
                    throw IOException("Remote closed stream prematurely after $sent / $size bytes")
                }
                val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
                currentStream.write(chunk)
                sent += bytesRead
                onProgress?.invoke(sent, size)
            }

            if (sent != size) {
                throw IOException("Push incomplete for $remotePath: sent $sent of $size bytes")
            }

            // Closing the stream delivers EOF (CLSE) to dd's stdin so it exits cleanly.
            currentStream.close()
            stream = null

            var remoteSize: Long? = null
            for (attempt in 0 until 15) {
                remoteSize = statRemoteSize(remotePath)
                if (remoteSize == size) break
                delay(100)
            }
            if (remoteSize != size) {
                throw IOException("Push size verification failed for $remotePath: expected $size bytes, got ${remoteSize ?: "unknown"}")
            }
            Log.d(TAG, "pushFile: Verified $size bytes written to $remotePath")
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {}
        }
        Unit
    }

    override suspend fun pullFile(
        remotePath: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)?
    ): Unit = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: throw IOException("ADB not connected.")
        val quotedPath = quoteRemoteShellPath(remotePath)
        val expectedSize = statRemoteSize(remotePath)
            ?: throw IOException("Unable to stat remote file before pull: $remotePath")
        if (expectedSize <= 0L || expectedSize > maxBytes) {
            throw IOException("Unexpected remote file size for $remotePath: $expectedSize bytes")
        }

        var stream: AdbStream? = null
        try {
            stream = connection.open("exec:cat $quotedPath")
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
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {}
        }
    }
}
