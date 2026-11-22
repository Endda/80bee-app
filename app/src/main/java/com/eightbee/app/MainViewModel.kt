package com.eightbee.app

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager
import com.eightbee.app.features.BackgroundOperationCoordinator
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.DeviceTweaksController
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.features.SideloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

data class BatteryOptimizerState(
    val maxHibernation: Boolean = false,
    val dozeZero: Boolean = false,
    val backgroundDrainMonitor: Boolean = false,
    val lockRefreshRate60: Boolean = false,
    val disableWindowBlurs: Boolean = false,
    val superDim: Boolean = false,
    val samsungCpuBoosterOff: Boolean = false,
    val samsungRamPlusOff: Boolean = false,
    val samsungMotionEngineOff: Boolean = false
)

enum class BootMode(val shellCommand: String, val label: String) {
    SYSTEM("reboot", "Rebooting to system..."),
    RECOVERY("reboot recovery", "Rebooting to recovery..."),
    BOOTLOADER("reboot bootloader", "Rebooting to bootloader..."),
    FASTBOOTD("reboot fastboot", "Rebooting to fastbootd...")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val shizukuManager = ShizukuConnectionManager()
    val otgManager = OtgConnectionManager(
        application.applicationContext,
        application.getSystemService(Context.USB_SERVICE) as UsbManager
    )

    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val executionMutex = Mutex()

    // Navigation State
    private val _currentScreen = MutableStateFlow(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()
    private val navStack = mutableListOf(Screen.Dashboard)

    private val backgroundOperations: BackgroundOperationCoordinator =
        (application as EightBeeApplication).backgroundOperations

    val deviceTweaksController = DeviceTweaksController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        backgroundOperations = backgroundOperations,
        onOutput = { _output.value = it },
        emitSnackbar = { _snackbarEvents.tryEmit(it) },
        runExclusive = { status, notify, refresh, block ->
            runExclusive(status, notifySnackbar = notify, refreshDeviceSettings = refresh, block = block)
        }
    )

    val debloaterController = DebloaterController(
        application = application,
        runExclusive = { status, notify, block ->
            runExclusive(status, notifySnackbar = notify, block = block)
        }
    )

    val batteryOptimizerController = BatteryOptimizerController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        runExclusive = { status, notify, block ->
            runExclusive(status, notifySnackbar = notify, block = block)
        }
    )

    val bootloaderWizardController = BootloaderWizardController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        onOutput = { _output.value = it }
    )

    val sideloadController = SideloadController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value }
    )

    val magiskPatcherController = MagiskPatcherController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value }
    )

    init {
        shizukuManager.startListening()
        viewModelScope.launch {
            activeConnection.collectLatest { conn ->
                conn?.connectionState?.collect { state ->
                    magiskPatcherController.onConnectionStateChanged(state)
                }
            }
        }
    }

    override fun onCleared() {
        shizukuManager.close()
        otgManager.close()
        val activeCoordinatorConn = backgroundOperations.connectionManager
        if (activeCoordinatorConn === otgManager || activeCoordinatorConn === _activeConnection.value) {
            backgroundOperations.connectionManager = null
        }
    }

    fun navigateTo(screen: Screen) {
        if (navStack.lastOrNull() != screen) {
            navStack.add(screen)
            _currentScreen.value = screen
        }
    }

    fun navigateBack(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            _currentScreen.value = navStack.last()
            return true
        }
        return false
    }

    fun resetNavigation() {
        navStack.clear()
        navStack.add(Screen.Dashboard)
        _currentScreen.value = Screen.Dashboard
    }

    fun setActiveConnection(connection: ConnectionManager?) {
        _activeConnection.value = connection
        backgroundOperations.connectionManager = connection
        if (connection != null) {
            deviceTweaksController.refreshSettings()
        } else {
            resetNavigation()
        }
    }

    private fun runExclusive(
        initialStatus: String,
        notifySnackbar: Boolean = false,
        refreshDeviceSettings: Boolean = false,
        block: suspend (ConnectionManager) -> String
    ) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            executionMutex.withLock {
                _isExecuting.value = true
                _output.value = initialStatus
                try {
                    val result = block(connection).ifBlank { "Command completed." }
                    _output.value = result
                    if (notifySnackbar) {
                        _snackbarEvents.tryEmit(summarizeForSnackbar(result))
                    }
                    if (refreshDeviceSettings) {
                        deviceTweaksController.refreshSettingsInternal(connection)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val errorMsg = "Error: ${e.message ?: "Operation failed"}"
                    _output.value = errorMsg
                    if (notifySnackbar) {
                        _snackbarEvents.tryEmit(errorMsg)
                    }
                } finally {
                    _isExecuting.value = false
                }
            }
        }
    }

    private fun summarizeForSnackbar(result: String): String {
        val firstLine = result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length > 90) firstLine.take(87) + "..." else firstLine.ifEmpty { "Done." }
    }
}
