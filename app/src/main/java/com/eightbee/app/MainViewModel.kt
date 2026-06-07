package com.eightbee.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

class MainViewModel : ViewModel() {

    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    fun setActiveConnection(connection: ConnectionManager?) {
        _activeConnection.value = connection
        if (connection != null) {
            refreshSettings()
        }
    }

    fun executeCommand(command: String) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing: $command..."
            val result = connection.runShellCommand(command)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            // Refresh settings after potentially changing them
            refreshSettings()
        }
    }

    fun executeCommandWithInput(command: String, input: InputStream) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing command with input..."
            val result = connection.runShellCommandWithInput(command, input)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            refreshSettings()
        }
    }

    fun refreshSettings() {
        val connection = _activeConnection.value ?: return
        if (!connection.isAvailable()) return

        viewModelScope.launch {
            val dnsMode = connection.queryStatus("settings get global private_dns_mode")
            val dnsHost = connection.queryStatus("settings get global private_dns_specifier")
            val winScale = connection.queryStatus("settings get global window_animation_scale")
            val transScale = connection.queryStatus("settings get global transition_animation_scale")
            val animScale = connection.queryStatus("settings get global animator_duration_scale")
            val adaptiveBattery = connection.queryStatus("settings get global adaptive_battery_management_enabled")

            _settings.value = DeviceSettings(
                privateDnsMode = dnsMode.trim(),
                privateDnsHostname = dnsHost.trim(),
                windowAnimationScale = winScale.trim(),
                transitionAnimationScale = transScale.trim(),
                animatorDurationScale = animScale.trim(),
                adaptiveBatteryEnabled = adaptiveBattery.trim() == "1"
            )
        }
    }
}
