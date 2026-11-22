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
import com.eightbee.app.features.CommandRunner
import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.DeviceTweaksController
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.features.SideloadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val shizukuManager = ShizukuConnectionManager()
    val otgManager = OtgConnectionManager(
        application.applicationContext,
        application.getSystemService(Context.USB_SERVICE) as UsbManager
    )

    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val commandRunner = CommandRunner(viewModelScope, connectionProvider = { _activeConnection.value })
    val output: StateFlow<String> = commandRunner.output
    val isExecuting: StateFlow<Boolean> = commandRunner.isExecuting
    val snackbarEvents: SharedFlow<String> = commandRunner.snackbarEvents

    // Navigation State
    private val _currentScreen = MutableStateFlow(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()
    private val navStack = mutableListOf(Screen.Dashboard)

    val backgroundOperations: BackgroundOperationCoordinator =
        (application as EightBeeApplication).backgroundOperations

    val deviceTweaksController = DeviceTweaksController(
        application = application,
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        runner = commandRunner
    )

    val debloaterController = DebloaterController(
        application = application,
        runner = commandRunner
    )

    val batteryOptimizerController = BatteryOptimizerController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        runner = commandRunner
    )

    val bootloaderWizardController = BootloaderWizardController(
        scope = viewModelScope,
        connectionProvider = { _activeConnection.value },
        onOutput = commandRunner::show
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
}
