package com.eightbee.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eightbee.app.BootloaderWizardState
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.ui.bootloader.CommandSelectionView
import com.eightbee.app.ui.bootloader.ConfirmingExecutionView
import com.eightbee.app.ui.bootloader.ConfirmingWarningsView
import com.eightbee.app.ui.bootloader.FinishedView
import com.eightbee.app.ui.bootloader.PhysicalConfirmationPromptView
import com.eightbee.app.ui.bootloader.WaitingForFastbootView
import com.eightbee.app.ui.bootloader.WizardProgressStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootloaderWizardBottomSheet(
    controller: BootloaderWizardController,
    activeConnection: ConnectionManager?,
    onDismiss: () -> Unit
) {
    val wizardState by controller.state.collectAsState()
    val connectionState = activeConnection?.connectionState?.collectAsState()?.value
        ?: ConnectionState.Disconnected
    val dismissAndClear = {
        controller.clear()
        onDismiss()
    }

    LaunchedEffect(connectionState, wizardState) {
        if (connectionState == ConnectionState.FastbootConnected &&
            wizardState is BootloaderWizardState.WaitingForFastboot
        ) {
            controller.transitionToCommandSelection()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (wizardState !is BootloaderWizardState.ExecutingCommand) {
                dismissAndClear()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = wizardState) {
                is BootloaderWizardState.Idle -> {
                    // Do nothing
                }
                is BootloaderWizardState.ConfirmingWarnings -> {
                    ConfirmingWarningsView(
                        isUnlock = state.isUnlock,
                        onProceed = controller::proceedFromWarnings,
                        onCancel = dismissAndClear
                    )
                }
                is BootloaderWizardState.Rebooting -> {
                    WizardProgressStep(
                        title = stringResource(R.string.wizard_rebooting_title),
                        description = stringResource(R.string.wizard_rebooting_desc)
                    )
                }
                is BootloaderWizardState.WaitingForFastboot -> {
                    WaitingForFastbootView()
                }
                is BootloaderWizardState.CommandSelection -> {
                    CommandSelectionView(
                        isUnlock = state.isUnlock,
                        presetOptions = state.commandOptions,
                        controllerError = state.validationError,
                        onProceed = controller::proceedFromCommandSelection,
                        onCancel = dismissAndClear
                    )
                }
                is BootloaderWizardState.ConfirmingExecution -> {
                    ConfirmingExecutionView(
                        isUnlock = state.isUnlock,
                        command = state.command,
                        onProceed = controller::confirmAndExecute,
                        onBack = controller::goBackToCommandSelection
                    )
                }
                is BootloaderWizardState.ExecutingCommand -> {
                    WizardProgressStep(
                        title = stringResource(R.string.wizard_executing_title),
                        description = stringResource(R.string.wizard_executing_desc, state.command)
                    )
                }
                is BootloaderWizardState.PhysicalConfirmationPrompt -> {
                    PhysicalConfirmationPromptView(
                        isUnlock = state.isUnlock,
                        onConfirmed = controller::completePhysicalConfirmation
                    )
                }
                is BootloaderWizardState.Finished -> {
                    FinishedView(
                        isUnlock = state.isUnlock,
                        success = state.success,
                        message = state.message,
                        onClose = dismissAndClear
                    )
                }
            }
        }
    }
}
