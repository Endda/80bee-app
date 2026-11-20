package com.eightbee.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.parsePmListPackages
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
        private const val BATTERY_MONITOR_MAX_DURATION_MS = 30 * 60 * 1000L

        @Volatile
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
    private var isForegroundStarted = false

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

        if (_isCompiling.value || _isMonitoringBattery.value) {
            updateNotification()
        } else {
            if (!isForegroundStarted) {
                // Ensure startForeground() is called before stopSelf() if launched via startForegroundService().
                updateNotification()
            }
            checkStopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleServiceTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleServiceTimeout(startId)
    }

    private fun handleServiceTimeout(startId: Int) {
        compileJob?.cancel()
        batteryJob?.cancel()
        if (_isCompiling.value) {
            _isCompiling.value = false
            _compileProgress.value = "Compilation stopped: foreground service time limit reached."
        }
        if (_isMonitoringBattery.value) {
            _isMonitoringBattery.value = false
            _batteryTelemetry.value = "Battery monitor stopped: foreground service time limit reached."
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        stopSelf(startId)
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
                refreshOrStopSelf()
                return@launch
            }

            try {
                _compileProgress.value = "Fetching installed packages..."
                updateNotification()
                val listOutput = conn.runShellCommand("pm list packages")
                val packages = parsePmListPackages(listOutput)

                val total = packages.size
                if (total == 0) {
                    _compileProgress.value = "No packages found."
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _compileProgress.value = "Compilation error: ${e.message}"
            } finally {
                _isCompiling.value = false
                refreshOrStopSelf()
            }
        }
    }

    private fun stopCompile() {
        compileJob?.cancel()
        _isCompiling.value = false
        _compileProgress.value = "Compilation cancelled."
        refreshOrStopSelf()
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
                refreshOrStopSelf()
                return@launch
            }

            val startElapsedMs = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    if (SystemClock.elapsedRealtime() - startElapsedMs >= BATTERY_MONITOR_MAX_DURATION_MS) {
                        _batteryTelemetry.value = "Battery monitor stopped after 30-minute session limit."
                        break
                    }
                    val rawOutput = conn.runShellCommand("dumpsys battery")
                    _batteryTelemetry.value = rawOutput
                    updateNotification()
                    delay(5000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _batteryTelemetry.value = "Error: ${e.message}"
            } finally {
                _isMonitoringBattery.value = false
                refreshOrStopSelf()
            }
        }
    }

    private fun stopBatteryMonitor() {
        batteryJob?.cancel()
        _isMonitoringBattery.value = false
        _batteryTelemetry.value = "Battery monitor stopped."
        refreshOrStopSelf()
    }

    private fun refreshOrStopSelf() {
        if (_isCompiling.value || _isMonitoringBattery.value) {
            updateNotification()
        } else {
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (!_isCompiling.value && !_isMonitoringBattery.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
            stopSelf()
        }
    }

    private fun updateNotification() {
        val title = getString(R.string.bg_service_notification_title)
        var text = getString(R.string.bg_service_status_active)

        if (_isCompiling.value && _isMonitoringBattery.value) {
            text = getString(R.string.bg_service_status_both)
        } else if (_isCompiling.value) {
            text = _compileProgress.value
        } else if (_isMonitoringBattery.value) {
            text = getString(R.string.bg_service_status_battery)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (_isCompiling.value) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_action_stop_compile),
                createStopPendingIntent(ACTION_STOP_COMPILE, 1)
            )
        }
        if (_isMonitoringBattery.value) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_action_stop_monitor),
                createStopPendingIntent(ACTION_STOP_BATTERY_MONITOR, 2)
            )
        }

        val notification = builder.build()

        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createStopPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundOperationService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bg_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        compileJob?.cancel()
        batteryJob?.cancel()
        serviceJob.cancel()
        _isCompiling.value = false
        _isMonitoringBattery.value = false
        isForegroundStarted = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
