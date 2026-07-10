package com.eightbee.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackgroundOperationService : Service() {

    companion object {
        const val ACTION_START_COMPILE = "com.eightbee.app.action.START_COMPILE"
        const val ACTION_STOP_COMPILE = "com.eightbee.app.action.STOP_COMPILE"
        const val ACTION_START_BATTERY_MONITOR = "com.eightbee.app.action.START_BATTERY_MONITOR"
        const val ACTION_STOP_BATTERY_MONITOR = "com.eightbee.app.action.STOP_BATTERY_MONITOR"

        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "background_ops_channel"

        var connectionManager: ConnectionManager? = null

        private val _compileProgress = MutableStateFlow("")
        val compileProgress = _compileProgress.asStateFlow()

        private val _isCompiling = MutableStateFlow(false)
        val isCompiling = _isCompiling.asStateFlow()

        private val _batteryTelemetry = MutableStateFlow("")
        val batteryTelemetry = _batteryTelemetry.asStateFlow()

        private val _isMonitoringBattery = MutableStateFlow(false)
        val isMonitoringBattery = _isMonitoringBattery.asStateFlow()
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var compileJob: Job? = null
    private var batteryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_COMPILE -> startCompile()
            ACTION_STOP_COMPILE -> stopCompile()
            ACTION_START_BATTERY_MONITOR -> startBatteryMonitor()
            ACTION_STOP_BATTERY_MONITOR -> stopBatteryMonitor()
        }

        updateNotification()
        return START_NOT_STICKY
    }

    private fun startCompile() {
        if (_isCompiling.value) return
        _isCompiling.value = true
        _compileProgress.value = "Starting compilation..."

        compileJob = serviceScope.launch {
            val conn = connectionManager
            if (conn == null || !conn.isAvailable()) {
                _compileProgress.value = "Error: Connection not active."
                _isCompiling.value = false
                updateNotification()
                return@launch
            }

            try {
                _compileProgress.value = "Fetching installed packages..."
                updateNotification()
                val listOutput = conn.runShellCommand("pm list packages")
                val packages = listOutput
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:") }
                    .filter { it.isNotEmpty() }

                val total = packages.size
                if (total == 0) {
                    _compileProgress.value = "No packages found."
                    _isCompiling.value = false
                    updateNotification()
                    return@launch
                }

                for (i in 0 until total) {
                    if (!isActive) break
                    val pkg = packages[i]
                    _compileProgress.value = "Compiling [${i + 1}/$total]: $pkg"
                    updateNotification()
                    conn.runShellCommand("pm compile -f -m speed $pkg")
                }
                _compileProgress.value = "Compilation complete! Compiled $total apps."
            } catch (e: Exception) {
                _compileProgress.value = "Compilation error: ${e.message}"
            } finally {
                _isCompiling.value = false
                updateNotification()
                checkStopSelf()
            }
        }
    }

    private fun stopCompile() {
        compileJob?.cancel()
        _isCompiling.value = false
        _compileProgress.value = "Compilation cancelled."
        updateNotification()
        checkStopSelf()
    }

    private fun startBatteryMonitor() {
        if (_isMonitoringBattery.value) return
        _isMonitoringBattery.value = true
        _batteryTelemetry.value = "Initializing battery monitor..."

        batteryJob = serviceScope.launch {
            val conn = connectionManager
            if (conn == null || !conn.isAvailable()) {
                _batteryTelemetry.value = "Error: Connection not active."
                _isMonitoringBattery.value = false
                updateNotification()
                return@launch
            }

            try {
                while (isActive) {
                    val rawOutput = conn.runShellCommand("dumpsys battery")
                    _batteryTelemetry.value = rawOutput
                    updateNotification()
                    delay(5000)
                }
            } catch (e: Exception) {
                _batteryTelemetry.value = "Error: ${e.message}"
            } finally {
                _isMonitoringBattery.value = false
                updateNotification()
                checkStopSelf()
            }
        }
    }

    private fun stopBatteryMonitor() {
        batteryJob?.cancel()
        _isMonitoringBattery.value = false
        _batteryTelemetry.value = "Battery monitor stopped."
        updateNotification()
        checkStopSelf()
    }

    private fun checkStopSelf() {
        if (!_isCompiling.value && !_isMonitoringBattery.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val title = "80bee Background Service"
        var text = "Service is active"

        if (_isCompiling.value && _isMonitoringBattery.value) {
            text = "Compiling apps & monitoring battery..."
        } else if (_isCompiling.value) {
            text = _compileProgress.value
        } else if (_isMonitoringBattery.value) {
            text = "Monitoring battery status..."
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Operations Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
