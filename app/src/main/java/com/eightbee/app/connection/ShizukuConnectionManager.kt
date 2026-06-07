package com.eightbee.app.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class ShizukuConnectionManager : ConnectionManager {

    private val _isAvailableFlow = MutableStateFlow(isAvailable())
    override val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    override suspend fun runShellCommand(command: String): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "Error: Shizuku is not running or permission not granted."
        }

        try {
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Error executing via Shizuku: ${e.message}"
        }
    }

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "Error: Shizuku is not running or permission not granted."
        }

        try {
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val outputStream = process.outputStream
            
            val job = launch {
                try {
                    input.copyTo(outputStream)
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            job.join()
            output.toString().trim()
        } catch (e: Exception) {
            "Error executing via Shizuku: ${e.message}"
        }
    }

    override suspend fun sendFastbootCommand(command: String): String {
        return "Error: Fastboot commands cannot be executed locally via Shizuku."
    }

    override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String {
        return "Error: Fastboot commands cannot be executed locally via Shizuku."
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    fun requestPermission(requestCode: Int) {
        try {
            if (!Shizuku.pingBinder()) {
                // If binder is not available, we can't request permission
                return
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun refresh() {
        _isAvailableFlow.value = isAvailable()
    }
}