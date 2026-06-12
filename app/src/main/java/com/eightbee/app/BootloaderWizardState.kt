package com.eightbee.app

sealed class BootloaderWizardState {
    object Idle : BootloaderWizardState()
    
    data class ConfirmingWarnings(
        val isUnlock: Boolean
    ) : BootloaderWizardState()
    
    data class Rebooting(
        val isUnlock: Boolean
    ) : BootloaderWizardState()
    
    data class WaitingForFastboot(
        val isUnlock: Boolean
    ) : BootloaderWizardState()
    
    data class CommandSelection(
        val isUnlock: Boolean,
        val commandOptions: List<String>,
        val customCommand: String = ""
    ) : BootloaderWizardState()
    
    data class ConfirmingExecution(
        val isUnlock: Boolean,
        val command: String
    ) : BootloaderWizardState()
    
    data class ExecutingCommand(
        val isUnlock: Boolean,
        val command: String
    ) : BootloaderWizardState()
    
    data class PhysicalConfirmationPrompt(
        val isUnlock: Boolean,
        val command: String,
        val responseText: String
    ) : BootloaderWizardState()
    
    data class Finished(
        val isUnlock: Boolean,
        val success: Boolean,
        val message: String
    ) : BootloaderWizardState()
}
