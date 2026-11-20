package com.eightbee.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BackgroundOperationService : Service() {

    companion object {
        const val ACTION_START_COMPILE = "com.eightbee.app.action.START_COMPILE"
        const val ACTION_STOP_COMPILE = "com.eightbee.app.action.STOP_COMPILE"
        const val ACTION_START_BATTERY_MONITOR = "com.eightbee.app.action.START_BATTERY_MONITOR"
        const val ACTION_STOP_BATTERY_MONITOR = "com.eightbee.app.action.STOP_BATTERY_MONITOR"

        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "background_ops_channel"

        private val coordinator = BackgroundOperationCoordinator()

        var connectionManager: ConnectionManager?
            get() = coordinator.connectionManager
            set(value) {
                coordinator.connectionManager = value
            }

        val compileProgress = coordinator.compileProgress
        val isCompiling = coordinator.isCompiling
        val batteryTelemetry = coordinator.batteryTelemetry
        val isMonitoringBattery = coordinator.isMonitoringBattery
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COMPILE -> coordinator.startCompile(serviceScope, ::refreshOrStopSelf)
            ACTION_STOP_COMPILE -> coordinator.stopCompile(::refreshOrStopSelf)
            ACTION_START_BATTERY_MONITOR -> coordinator.startBatteryMonitor(serviceScope, ::refreshOrStopSelf)
            ACTION_STOP_BATTERY_MONITOR -> coordinator.stopBatteryMonitor(::refreshOrStopSelf)
        }

        if (coordinator.hasActiveOperations()) {
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
        coordinator.handleTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        stopSelf(startId)
    }

    private fun refreshOrStopSelf() {
        if (coordinator.hasActiveOperations()) {
            updateNotification()
        } else {
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (!coordinator.hasActiveOperations()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
            stopSelf()
        }
    }

    private fun updateNotification() {
        val compiling = isCompiling.value
        val monitoring = isMonitoringBattery.value
        val text = when {
            compiling && monitoring -> getString(R.string.bg_service_status_both)
            compiling -> compileProgress.value
            monitoring -> getString(R.string.bg_service_status_battery)
            else -> getString(R.string.bg_service_status_active)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bg_service_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (compiling) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_action_stop_compile),
                createStopPendingIntent(ACTION_STOP_COMPILE, 1)
            )
        }
        if (monitoring) {
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.cancelAll()
        serviceJob.cancel()
        isForegroundStarted = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
