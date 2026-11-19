package com.eightbee.app.features

import com.eightbee.app.BootloaderWizardState
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates the multi-step fastboot bootloader lock/unlock wizard. */
class BootloaderWizardController(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val onOutput: (String) -> Unit
) {
    private val _state = MutableStateFlow<BootloaderWizardState>(BootloaderWizardState.Idle)
    val state: StateFlow<BootloaderWizardState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun start(isUnlock: Boolean) {
        activeJob?.cancel()
        activeJob = null
        _state.value = BootloaderWizardState.ConfirmingWarnings(isUnlock)
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        _state.value = BootloaderWizardState.Idle
    }

    fun proceedFromWarnings() {
        val currentState = _state.value as? BootloaderWizardState.ConfirmingWarnings ?: return
        val isUnlock = currentState.isUnlock
        _state.value = BootloaderWizardState.Rebooting(isUnlock)

        activeJob?.cancel()
        activeJob = scope.launch {
            onOutput("Sending reboot to bootloader command...")
            val connection = connectionProvider()
            if (connection != null && connection.isAvailable()) {
                val result = connection.runShellCommand("reboot bootloader")
                onOutput("Reboot command output: $result")
            }
            _state.value = BootloaderWizardState.WaitingForFastboot(isUnlock)
        }
    }

    fun transitionToCommandSelection() {
        val currentState = _state.value as? BootloaderWizardState.WaitingForFastboot ?: return
        _state.value = BootloaderWizardState.CommandSelection(
            isUnlock = currentState.isUnlock,
            commandOptions = commandOptionsFor(currentState.isUnlock),
            customCommand = ""
        )
    }

    fun proceedFromCommandSelection(command: String) {
        val currentState = _state.value as? BootloaderWizardState.CommandSelection ?: return
        _state.value = BootloaderWizardState.ConfirmingExecution(
            isUnlock = currentState.isUnlock,
            command = command
        )
    }

    fun goBackToCommandSelection() {
        val currentState = _state.value
        val isUnlock = when (currentState) {
            is BootloaderWizardState.ConfirmingExecution -> currentState.isUnlock
            is BootloaderWizardState.ExecutingCommand -> currentState.isUnlock
            else -> return
        }
        _state.value = BootloaderWizardState.CommandSelection(
            isUnlock = isUnlock,
            commandOptions = commandOptionsFor(isUnlock),
            customCommand = ""
        )
    }

    fun confirmAndExecute() {
        val currentState = _state.value as? BootloaderWizardState.ConfirmingExecution ?: return
        val isUnlock = currentState.isUnlock
        val command = currentState.command
        _state.value = BootloaderWizardState.ExecutingCommand(isUnlock, command)

        activeJob?.cancel()
        activeJob = scope.launch {
            val connection = connectionProvider()
            if (connection == null) {
                _state.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Error: Connection lost. Ensure device is plugged in."
                )
                return@launch
            }

            onOutput("Executing fastboot command: $command...")
            val result = connection.sendFastbootCommand(command)
            onOutput("Fastboot output: $result")

            if (result.contains("OKAY", ignoreCase = true)) {
                _state.value = BootloaderWizardState.PhysicalConfirmationPrompt(
                    isUnlock = isUnlock,
                    command = command,
                    responseText = result
                )
            } else {
                _state.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Fastboot execution failed: $result"
                )
            }
        }
    }

    fun completePhysicalConfirmation() {
        val currentState = _state.value as? BootloaderWizardState.PhysicalConfirmationPrompt ?: return
        _state.value = BootloaderWizardState.Finished(
            isUnlock = currentState.isUnlock,
            success = true,
            message = "Command confirmed successfully."
        )
    }

    private fun commandOptionsFor(isUnlock: Boolean): List<String> {
        return if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
    }
}
