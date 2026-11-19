package com.eightbee.app.connection

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method

class ShizukuConnectionManager : ConnectionManager {

    private val _isAvailableFlow = MutableStateFlow(isAvailable())
    override val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    @Volatile
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refresh()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refresh()
    }

    private val newProcessMethod: Method by lazy {
        // Shizuku.newProcess is private in API v13; cache the reflective handle once.
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    override suspend fun runShellCommand(command: String): String =
        executeProcess(command, input = null)

    override suspend fun runShellCommandWithInput(command: String, input: InputStream): String =
        executeProcess(command, input = input)

    private suspend fun executeProcess(command: String, input: InputStream?): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "Error: Shizuku is not running or permission not granted."
        }

        try {
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            coroutineScope {
                val inputJob = input?.let { stream ->
                    launch {
                        try {
                            process.outputStream.use { out ->
                                stream.copyTo(out)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // Drain stdout and stderr concurrently so large stderr cannot deadlock the pipe buffer.
                val stdoutDeferred = async {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                }
                val stderrDeferred = async {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                }

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                inputJob?.join()
                process.waitFor()

                buildString {
                    if (stdout.isNotEmpty()) append(stdout)
                    if (stderr.isNotEmpty()) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(stderr)
                    }
                }.trim()
            }
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
            Log.e("ShizukuConnectionManager", "Failed to request Shizuku permission", e)
        }
    }

    fun startListening() {
        if (listenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
        } catch (e: Exception) {
            Log.w("ShizukuConnectionManager", "Failed to register Shizuku listeners", e)
        }
        refresh()
    }

    fun close() {
        if (!listenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.w("ShizukuConnectionManager", "Failed to unregister Shizuku listeners", e)
        } finally {
            listenersRegistered = false
        }
    }

    fun refresh() {
        _isAvailableFlow.value = isAvailable()
    }
}