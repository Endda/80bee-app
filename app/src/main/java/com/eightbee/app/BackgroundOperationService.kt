package com.eightbee.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eightbee.app.features.BackgroundOperationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class BackgroundOperationService : Service() {

    companion object {
        const val ACTION_START_COMPILE = "com.eightbee.app.action.START_COMPILE"
        const val ACTION_STOP_COMPILE = "com.eightbee.app.action.STOP_COMPILE"
        const val ACTION_START_BATTERY_MONITOR = "com.eightbee.app.action.START_BATTERY_MONITOR"
        const val ACTION_STOP_BATTERY_MONITOR = "com.eightbee.app.action.STOP_BATTERY_MONITOR"

        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "background_ops_channel"
    }

    private data class NotificationUiState(
        val compiling: Boolean,
        val monitoring: Boolean,
        val text: String
    ) {
        val hasActiveOperations: Boolean
            get() = compiling || monitoring
    }

    private val coordinator: BackgroundOperationCoordinator by lazy {
        (application as EightBeeApplication).backgroundOperations
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var isForegroundStarted = false
    private var lastNotificationState: NotificationUiState? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            combine(
                coordinator.isCompiling,
                coordinator.compileProgress,
                coordinator.isMonitoringBattery
            ) { compiling, progress, monitoring ->
                buildNotificationState(compiling, progress, monitoring)
            }
                .distinctUntilChanged()
                .collect { state ->
                    reconcileForeground(state)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COMPILE -> coordinator.startCompile(serviceScope)
            ACTION_STOP_COMPILE -> coordinator.stopCompile()
            ACTION_START_BATTERY_MONITOR -> coordinator.startBatteryMonitor(serviceScope)
            ACTION_STOP_BATTERY_MONITOR -> coordinator.stopBatteryMonitor()
        }
        reconcileForeground(currentNotificationState(), ensureStartBeforeStop = true)
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
        lastNotificationState = null
        stopSelf(startId)
    }

    private fun currentNotificationState(): NotificationUiState = buildNotificationState(
        compiling = coordinator.isCompiling.value,
        progress = coordinator.compileProgress.value,
        monitoring = coordinator.isMonitoringBattery.value
    )

    private fun buildNotificationState(
        compiling: Boolean,
        progress: String,
        monitoring: Boolean
    ): NotificationUiState {
        val text = when {
            compiling && monitoring -> getString(R.string.bg_service_status_both)
            compiling -> progress
            monitoring -> getString(R.string.bg_service_status_battery)
            else -> getString(R.string.bg_service_status_active)
        }
        return NotificationUiState(
            compiling = compiling,
            monitoring = monitoring,
            text = text
        )
    }

    private fun reconcileForeground(
        state: NotificationUiState,
        ensureStartBeforeStop: Boolean = false
    ) {
        if (state.hasActiveOperations) {
            if (!isForegroundStarted) {
                startAsForeground(buildNotification(state))
                isForegroundStarted = true
                lastNotificationState = state
            } else if (state != lastNotificationState) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
                lastNotificationState = state
            }
            return
        }

        if (!isForegroundStarted && ensureStartBeforeStop) {
            // Ensure startForeground() is called before stopSelf() if launched via startForegroundService().
            startAsForeground(buildNotification(state))
            isForegroundStarted = true
        }
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
            lastNotificationState = null
            stopSelf()
        }
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: NotificationUiState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bg_service_notification_title))
            .setContentText(state.text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (state.compiling) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_action_stop_compile),
                createStopPendingIntent(ACTION_STOP_COMPILE, 1)
            )
        }
        if (state.monitoring) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_action_stop_monitor),
                createStopPendingIntent(ACTION_STOP_BATTERY_MONITOR, 2)
            )
        }

        return builder.build()
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
        lastNotificationState = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
