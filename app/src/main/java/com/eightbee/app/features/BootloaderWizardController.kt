package com.eightbee.app.features

import com.eightbee.app.BootloaderWizardState
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coordinates the multi-step fastboot bootloader lock/unlock wizard and curated fastboot utilities. */
class BootloaderWizardController(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val onOutput: (String) -> Unit
) {
    private val _state = MutableStateFlow<BootloaderWizardState>(BootloaderWizardState.Idle)
    val state: StateFlow<BootloaderWizardState> = _state.asStateFlow()

    private val _utilityLogs = MutableStateFlow<List<String>>(emptyList())
    val utilityLogs: StateFlow<List<String>> = _utilityLogs.asStateFlow()

    private val _isRunningUtility = MutableStateFlow(false)
    val isRunningUtility: StateFlow<Boolean> = _isRunningUtility.asStateFlow()

    private var activeJob: Job? = null
    private var utilityJob: Job? = null

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
                try {
                    val result = connection.runShellCommand("reboot bootloader")
                    onOutput("Reboot command output: $result")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onOutput("Reboot command failed: ${e.message}")
                }
            }
            _state.value = BootloaderWizardState.WaitingForFastboot(isUnlock)
        }
    }

    fun transitionToCommandSelection() {
        val currentState = _state.value as? BootloaderWizardState.WaitingForFastboot ?: return
        _state.value = BootloaderWizardState.CommandSelection(
            isUnlock = currentState.isUnlock,
            commandOptions = commandOptionsFor(currentState.isUnlock)
        )
    }

    fun proceedFromCommandSelection(command: String) {
        val currentState = _state.value as? BootloaderWizardState.CommandSelection ?: return
        when (val validation = FastbootCommandPolicy.validateWizardCustomCommand(command)) {
            is FastbootValidationResult.Blocked -> {
                _state.value = currentState.copy(validationError = validation.reason)
                onOutput("Blocked fastboot command: ${validation.reason}")
            }
            is FastbootValidationResult.Allowed -> {
                _state.value = BootloaderWizardState.ConfirmingExecution(
                    isUnlock = currentState.isUnlock,
                    command = validation.normalizedCommand
                )
            }
        }
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
            commandOptions = commandOptionsFor(isUnlock)
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
            try {
                val reply = connection.sendFastbootCommand(command)
                val formatted = reply.formatDisplay()
                onOutput("Fastboot output: $formatted")

                if (reply.isOkay) {
                    _state.value = BootloaderWizardState.PhysicalConfirmationPrompt(
                        isUnlock = isUnlock,
                        command = command,
                        responseText = formatted
                    )
                } else {
                    _state.value = BootloaderWizardState.Finished(
                        isUnlock = isUnlock,
                        success = false,
                        message = "Fastboot execution failed: $formatted"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Fastboot command failed."
                onOutput("Fastboot output: $message")
                _state.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Fastboot execution failed: $message"
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

    fun clearUtilityLogs() {
        _utilityLogs.value = emptyList()
    }

    fun runCuratedFastbootCommand(command: String) {
        val trimmed = command.trim()
        if (!FastbootCommandPolicy.isAllowedUtilityCommand(trimmed)) {
            val msg = "Blocked non-allowlisted fastboot command: '$trimmed'"
            appendUtilityLog(msg)
            onOutput(msg)
            return
        }

        utilityJob?.cancel()
        utilityJob = scope.launch {
            _isRunningUtility.value = true
            try {
                val connection = connectionProvider()
                if (connection == null ||
                    !connection.supportsFastboot ||
                    connection.connectionState.value != ConnectionState.FastbootConnected
                ) {
                    val msg = "Error: Connect target device in USB OTG Fastboot mode before running '$trimmed'."
                    appendUtilityLog(msg)
                    onOutput(msg)
                    return@launch
                }

                appendUtilityLog("> fastboot $trimmed")
                onOutput("Running fastboot command: $trimmed")
                val reply = connection.sendFastbootCommand(trimmed)
                val formatted = reply.formatDisplay()
                appendUtilityLog(formatted)
                onOutput(formatted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "FAIL: ${e.message ?: "Fastboot command failed."}"
                appendUtilityLog(msg)
                onOutput(msg)
            } finally {
                _isRunningUtility.value = false
            }
        }
    }

    private fun appendUtilityLog(entry: String) {
        _utilityLogs.update { (it + entry).takeLast(120) }
    }

    private fun commandOptionsFor(isUnlock: Boolean): List<String> {
        return if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
    }
}
