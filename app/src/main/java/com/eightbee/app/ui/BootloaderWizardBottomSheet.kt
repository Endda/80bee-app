package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.BootloaderWizardState
import com.eightbee.app.MainViewModel
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootloaderWizardBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val wizardState by viewModel.bootloaderWizardState.collectAsState()
    val activeConnection by viewModel.activeConnection.collectAsState()

    val connectionState = if (activeConnection is OtgConnectionManager) {
        (activeConnection as OtgConnectionManager).connectionState.collectAsState().value
    } else {
        ConnectionState.Disconnected
    }

    LaunchedEffect(connectionState, wizardState) {
        if (connectionState == ConnectionState.FastbootConnected &&
            wizardState is BootloaderWizardState.WaitingForFastboot
        ) {
            viewModel.transitionToCommandSelection()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (wizardState !is BootloaderWizardState.ExecutingCommand) {
                viewModel.clearBootloaderWizardState()
                onDismiss()
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
                        onProceed = { viewModel.proceedFromWarnings() },
                        onCancel = {
                            viewModel.clearBootloaderWizardState()
                            onDismiss()
                        }
                    )
                }
                is BootloaderWizardState.Rebooting -> {
                    RebootingView()
                }
                is BootloaderWizardState.WaitingForFastboot -> {
                    WaitingForFastbootView()
                }
                is BootloaderWizardState.CommandSelection -> {
                    CommandSelectionView(
                        isUnlock = state.isUnlock,
                        presetOptions = state.commandOptions,
                        onProceed = { command -> viewModel.proceedFromCommandSelection(command) },
                        onCancel = {
                            viewModel.clearBootloaderWizardState()
                            onDismiss()
                        }
                    )
                }
                is BootloaderWizardState.ConfirmingExecution -> {
                    ConfirmingExecutionView(
                        isUnlock = state.isUnlock,
                        command = state.command,
                        onProceed = { viewModel.confirmAndExecuteBootloaderCommand() },
                        onBack = { viewModel.goBackToCommandSelection() }
                    )
                }
                is BootloaderWizardState.ExecutingCommand -> {
                    ExecutingCommandView(command = state.command)
                }
                is BootloaderWizardState.PhysicalConfirmationPrompt -> {
                    PhysicalConfirmationPromptView(
                        isUnlock = state.isUnlock,
                        onConfirmed = { viewModel.completePhysicalConfirmation() }
                    )
                }
                is BootloaderWizardState.Finished -> {
                    FinishedView(
                        isUnlock = state.isUnlock,
                        success = state.success,
                        message = state.message,
                        onClose = {
                            viewModel.clearBootloaderWizardState()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmingWarningsView(
    isUnlock: Boolean,
    onProceed: () -> Unit,
    onCancel: () -> Unit
) {
    val operationName = stringResource(
        if (isUnlock) R.string.wizard_op_unlock_full else R.string.wizard_op_relock_full
    )

    var checkedDataWipe by remember { mutableStateOf(false) }
    var checkedBackupConfirm by remember { mutableStateOf(false) }
    var checkedFirmwareConfirm by remember { mutableStateOf(false) }

    val allChecked = if (isUnlock) {
        checkedDataWipe && checkedBackupConfirm
    } else {
        checkedDataWipe && checkedFirmwareConfirm
    }

    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.wizard_cd_warning),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.wizard_warnings_title_format, operationName),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.wizard_warnings_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = checkedDataWipe,
                onCheckedChange = { checkedDataWipe = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.wizard_warn_data_wipe),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (isUnlock) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = checkedBackupConfirm,
                    onCheckedChange = { checkedBackupConfirm = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wizard_warn_backup),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = checkedFirmwareConfirm,
                    onCheckedChange = { checkedFirmwareConfirm = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wizard_warn_stock_firmware),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = onProceed,
            enabled = allChecked,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_proceed))
        }
    }
}

@Composable
fun RebootingView() {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.wizard_rebooting_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.wizard_rebooting_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun WaitingForFastbootView() {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Filled.Refresh,
        contentDescription = stringResource(R.string.wizard_cd_usb),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.wizard_waiting_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.wizard_waiting_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Start,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.wizard_waiting_step1), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.wizard_waiting_step2), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.wizard_waiting_step3), style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
fun CommandSelectionView(
    isUnlock: Boolean,
    presetOptions: List<String>,
    onProceed: (String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var useCustomCommand by remember { mutableStateOf(false) }
    var customCommandText by remember { mutableStateOf("") }

    val operationName = stringResource(
        if (isUnlock) R.string.wizard_op_unlock_short else R.string.wizard_op_lock_short
    )

    Text(
        text = stringResource(R.string.wizard_select_command_title, operationName),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))

    Column(modifier = Modifier.fillMaxWidth()) {
        presetOptions.forEachIndexed { index, option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (!useCustomCommand && selectedPresetIndex == index),
                        onClick = {
                            useCustomCommand = false
                            selectedPresetIndex = index
                        }
                    )
                    .padding(vertical = 12.dp)
            ) {
                RadioButton(
                    selected = (!useCustomCommand && selectedPresetIndex == index),
                    onClick = {
                        useCustomCommand = false
                        selectedPresetIndex = index
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.wizard_command_preview_format, option),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            if (index == 0) R.string.wizard_preset_modern_desc
                            else R.string.wizard_preset_legacy_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = useCustomCommand,
                    onClick = { useCustomCommand = true }
                )
                .padding(vertical = 12.dp)
        ) {
            RadioButton(
                selected = useCustomCommand,
                onClick = { useCustomCommand = true }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = stringResource(R.string.wizard_custom_command_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.wizard_custom_command_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (useCustomCommand) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customCommandText,
            onValueChange = { customCommandText = it },
            label = { Text(stringResource(R.string.wizard_custom_command_label)) },
            placeholder = { Text(stringResource(R.string.wizard_custom_command_placeholder)) },
            prefix = { Text(stringResource(R.string.wizard_fastboot_prefix)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = {
                val commandToSend = if (useCustomCommand) {
                    customCommandText.trim()
                } else {
                    presetOptions[selectedPresetIndex]
                }
                if (commandToSend.isNotEmpty()) {
                    onProceed(commandToSend)
                }
            },
            enabled = !useCustomCommand || customCommandText.trim().isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_next))
        }
    }
}

@Composable
fun ConfirmingExecutionView(
    isUnlock: Boolean,
    command: String,
    onProceed: () -> Unit,
    onBack: () -> Unit
) {
    var checkedUnderstand by remember { mutableStateOf(false) }

    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.wizard_cd_report_warning),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.wizard_confirm_exec_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.wizard_confirm_exec_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(12.dp))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.wizard_command_preview_format, command),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (!isUnlock) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.wizard_relock_critical_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checkedUnderstand,
            onCheckedChange = { checkedUnderstand = it }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.wizard_confirm_exec_checkbox),
            style = MaterialTheme.typography.bodyMedium
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_back))
        }
        Button(
            onClick = onProceed,
            enabled = checkedUnderstand,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_execute))
        }
    }
}

@Composable
fun ExecutingCommandView(command: String) {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.wizard_executing_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.wizard_executing_desc, command),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun PhysicalConfirmationPromptView(
    isUnlock: Boolean,
    onConfirmed: () -> Unit
) {
    val actionText = stringResource(
        if (isUnlock) R.string.wizard_action_unlock_bootloader else R.string.wizard_action_lock_bootloader
    )

    Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = stringResource(R.string.wizard_cd_user_action),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.wizard_physical_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.wizard_physical_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(20.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.wizard_cd_step1),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wizard_physical_step1_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.wizard_physical_step1_desc, actionText),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 32.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = stringResource(R.string.wizard_cd_step2),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wizard_physical_step2_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.wizard_physical_step2_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 32.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onConfirmed,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.wizard_physical_confirmed_button))
    }
}

@Composable
fun FinishedView(
    isUnlock: Boolean,
    success: Boolean,
    message: String,
    onClose: () -> Unit
) {
    val actionText = stringResource(
        if (isUnlock) R.string.wizard_op_unlock_short else R.string.wizard_op_lock_short
    )

    if (success) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.cd_success),
            tint = SuccessGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.wizard_finished_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.wizard_finished_desc, actionText),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.wizard_next_steps_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = stringResource(R.string.wizard_next_step_1),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = stringResource(R.string.wizard_next_step_2),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.wizard_next_step_3),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.wizard_next_step_4),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.wizard_cd_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.wizard_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.wizard_close_button))
    }
}
