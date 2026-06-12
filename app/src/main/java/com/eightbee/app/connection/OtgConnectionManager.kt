package com.eightbee.app.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.UsbChannel
import com.rv882.fastbootjava.FastbootDeviceContext
import com.rv882.fastbootjava.transport.UsbTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object AdbConnected : ConnectionState()
    object FastbootConnected : ConnectionState()
}

class OtgConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager
) : ConnectionManager {

    companion object {
        private const val TAG = "OtgConnectionManager"
    }

    private val _isAvailableFlow = MutableStateFlow(false)
    override val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var connectedDevice: UsbDevice? = null
    private var adbConnection: AdbConnection? = null
    private var fastbootContext: FastbootDeviceContext? = null
    private var adbCrypto: AdbCrypto? = null
    private var connectionJob: Job? = null

    init {
        setupCrypto()
    }

    private fun setupCrypto() {
        val base64 = AdbBase64Impl()
        val privateKey = File(context.filesDir, "private_key")
        val publicKey = File(context.filesDir, "public_key")

        adbCrypto = if (privateKey.exists() && publicKey.exists()) {
            try {
                AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        if (adbCrypto == null) {
            try {
                adbCrypto = AdbCrypto.generateAdbKeyPair(base64)
                adbCrypto?.saveAdbKeyPair(privateKey, publicKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup crypto", e)
            }
        }
    }

    fun setDevice(device: UsbDevice?) {
        Log.d(TAG, "setDevice: $device")
        if (device == null) {
            disconnect()
            return
        }

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Permission already granted for ${device.deviceName}")
            connectedDevice = device
            connectDevice()
        } else {
            Log.d(TAG, "Waiting for permission for ${device.deviceName}")
        }
    }

    private fun connectDevice() {
        val device = connectedDevice ?: return
        Log.d(TAG, "connectDevice: Starting connection process for ${device.deviceName}")
        Log.d(TAG, "Device details: Vendor=${device.vendorId}, Product=${device.productId}, Class=${device.deviceClass}")
        Log.d(TAG, "Configuration count: ${device.configurationCount}")

        var adbInterface: UsbInterface? = null
        var fastbootInterface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.d(TAG, "Inspecting Interface $i: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}")
            if (intf.interfaceClass == 255) {
                if (intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                    Log.d(TAG, "Detected ADB interface at index $i")
                    adbInterface = intf
                } else if (intf.interfaceSubclass == 66 && intf.interfaceProtocol == 3) {
                    Log.d(TAG, "Detected alternative Fastboot interface at index $i")
                    fastbootInterface = intf
                } else if (intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x03) {
                    Log.d(TAG, "Detected standard Fastboot interface at index $i")
                    fastbootInterface = intf
                }
            }
        }

        if (adbInterface == null && fastbootInterface == null) {
            Log.e(TAG, "Aborting: No ADB or Fastboot interface found")
            return
        }

        connectionJob?.cancel()
        _connectionState.value = ConnectionState.Connecting
        
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = usbManager.openDevice(device) ?: run {
                    Log.e(TAG, "Failed to open USB device connection")
                    return@launch
                }

                // Samsung-specific: Try to set configuration if multiple exist
                if (device.configurationCount > 1) {
                    connection.setConfiguration(device.getConfiguration(0))
                }

                if (adbInterface != null) {
                    val crypto = adbCrypto ?: run {
                        Log.e(TAG, "AdbCrypto not initialized")
                        return@launch
                    }
                    
                    val channel = UsbChannel(connection, adbInterface)
                    val newAdbConnection = AdbConnection.create(channel, crypto)
                    newAdbConnection.connect()
                    
                    adbConnection = newAdbConnection
                    _isAvailableFlow.value = true
                    _connectionState.value = ConnectionState.AdbConnected
                    Log.d(TAG, "ADB Connection successful for ${device.deviceName}")
                } else if (fastbootInterface != null) {
                    val connection = usbManager.openDevice(device) ?: run {
                        Log.e(TAG, "Failed to open USB device")
                        return@launch
                    }
                    val transport = UsbTransport(fastbootInterface, connection)
                    fastbootContext = FastbootDeviceContext(transport)
                    _isAvailableFlow.value = true
                    _connectionState.value = ConnectionState.FastbootConnected
                    Log.d(TAG, "Fastboot Connection successful for ${device.deviceName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed for ${device.deviceName}", e)
                disconnect()
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        _isAvailableFlow.value = false
        _connectionState.value = ConnectionState.Disconnected
        
        val currentAdb = adbConnection
        val currentFb = fastbootContext
        
        adbConnection = null
        fastbootContext = null
        connectedDevice = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                currentAdb?.close()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                currentFb?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override suspend fun runShellCommand(command: String): String = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: return@withContext "Error: ADB not connected."
        
        try {
            val stream = connection.open("shell:$command")
            val output = StringBuilder()
            while (!stream.isClosed) {
                try {
                    val bytes = stream.read()
                    if (bytes != null) {
                        output.append(String(bytes, StandardCharsets.UTF_8))
                        stream.sendReady()
                    } else {
                        break
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
            output.toString().trim()
        } catch (e: Exception) {
            "Error executing via OTG: ${e.message}"
        }
    }

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = withContext(Dispatchers.IO) {
        val connection = adbConnection ?: return@withContext "Error: ADB not connected."
        
        try {
            val stream = connection.open("exec:$command")
            val job = launch {
                try {
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (stream.isClosed) break
                        val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
                        stream.write(chunk)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val output = StringBuilder()
            while (!stream.isClosed) {
                try {
                    val bytes = stream.read()
                    if (bytes != null) {
                        output.append(String(bytes, StandardCharsets.UTF_8))
                        stream.sendReady()
                    } else {
                        break
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
            job.join()
            output.toString().trim()
        } catch (e: Exception) {
            "Error executing via OTG: ${e.message}"
        }
    }

    override suspend fun sendFastbootCommand(command: String): String = withContext(Dispatchers.IO) {
        val fbContext = fastbootContext ?: return@withContext "Error: No Fastboot OTG device connected or authorized."

        try {
            fbContext.sendCommand(command.toByteArray(StandardCharsets.UTF_8))
            "${com.rv882.fastbootjava.FastbootResponse.getStatus().name}: ${com.rv882.fastbootjava.FastbootResponse.getData()}"
        } catch (e: Exception) {
            "Error Executing Fastboot via OTG: ${e.message}"
        }
    }

    override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String = withContext(Dispatchers.IO) {
        val fbContext = fastbootContext ?: return@withContext "Error: No Fastboot OTG device connected."
        try {
            // 1. Send download command
            val downloadCmd = String.format("download:%08x", size)
            fbContext.sendCommand(downloadCmd.toByteArray(StandardCharsets.UTF_8))
            if (com.rv882.fastbootjava.FastbootResponse.getStatus().name != "DATA") {
                return@withContext "Download failed: ${com.rv882.fastbootjava.FastbootResponse.getStatus().name} ${com.rv882.fastbootjava.FastbootResponse.getData()}"
            }
            
            // 2. Send payload bytes
            val bytes = stream.readBytes()
            fbContext.sendCommand(bytes)
            if (com.rv882.fastbootjava.FastbootResponse.getStatus().name != "OKAY") {
                return@withContext "Upload failed: ${com.rv882.fastbootjava.FastbootResponse.getStatus().name} ${com.rv882.fastbootjava.FastbootResponse.getData()}"
            }
            
            // 3. Flash command
            val flashCmd = "flash:$partition"
            fbContext.sendCommand(flashCmd.toByteArray(StandardCharsets.UTF_8))
            if (com.rv882.fastbootjava.FastbootResponse.getStatus().name != "OKAY") {
                return@withContext "Flash failed: ${com.rv882.fastbootjava.FastbootResponse.getStatus().name} ${com.rv882.fastbootjava.FastbootResponse.getData()}"
            }
            
            return@withContext "Successfully flashed $partition!"
        } catch (e: Exception) {
            return@withContext "Error flashing via OTG: ${e.message}"
        }
    }

    override fun isAvailable(): Boolean {
        return adbConnection != null || fastbootContext != null
    }
    
    fun isFastboot(): Boolean {
        return fastbootContext != null
    }
}
