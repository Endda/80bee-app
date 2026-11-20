package com.eightbee.app.ui.bootloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.FastbootCommandPolicy
import com.eightbee.app.features.FastbootValidationResult
import com.eightbee.app.ui.theme.SuccessGreen

private val WAITING_FASTBOOT_STEPS = listOf(
    R.string.wizard_waiting_step1,
    R.string.wizard_waiting_step2,
    R.string.wizard_waiting_step3
)

private val FINISHED_NEXT_STEPS = listOf(
    R.string.wizard_next_step_1 to FontWeight.Normal,
    R.string.wizard_next_step_2 to FontWeight.SemiBold,
    R.string.wizard_next_step_3 to FontWeight.SemiBold,
    R.string.wizard_next_step_4 to FontWeight.Normal
)

@Composable
internal fun ConfirmingWarningsView(
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

    val allChecked = checkedDataWipe && if (isUnlock) checkedBackupConfirm else checkedFirmwareConfirm

    WizardIconHeader(
        icon = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.wizard_cd_warning),
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.wizard_warnings_title_format, operationName)
    )
    Spacer(modifier = Modifier.height(16.dp))

    WizardErrorBanner(text = stringResource(R.string.wizard_warnings_banner))
    Spacer(modifier = Modifier.height(20.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WizardCheckboxRow(
            checked = checkedDataWipe,
            onCheckedChange = { checkedDataWipe = it },
            label = stringResource(R.string.wizard_warn_data_wipe)
        )
        if (isUnlock) {
            WizardCheckboxRow(
                checked = checkedBackupConfirm,
                onCheckedChange = { checkedBackupConfirm = it },
                label = stringResource(R.string.wizard_warn_backup)
            )
        } else {
            WizardCheckboxRow(
                checked = checkedFirmwareConfirm,
                onCheckedChange = { checkedFirmwareConfirm = it },
                label = stringResource(R.string.wizard_warn_stock_firmware),
                emphasized = true
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    WizardFooterButtons(
        secondaryLabel = stringResource(R.string.action_cancel),
        onSecondaryClick = onCancel,
        primaryLabel = stringResource(R.string.action_proceed),
        onPrimaryClick = onProceed,
        primaryEnabled = allChecked,
        isDestructive = true
    )
}

@Composable
internal fun WaitingForFastbootView() {
    Spacer(modifier = Modifier.height(16.dp))
    WizardIconHeader(
        icon = Icons.Filled.Refresh,
        contentDescription = stringResource(R.string.wizard_cd_usb),
        iconTint = MaterialTheme.colorScheme.primary,
        iconSize = 56.dp,
        iconBottomSpacing = 24.dp,
        title = stringResource(R.string.wizard_waiting_title),
        titleStyle = MaterialTheme.typography.titleLarge,
        titleAlign = TextAlign.Unspecified,
        subtitle = stringResource(R.string.wizard_waiting_desc),
        subtitleAlign = TextAlign.Start,
        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WAITING_FASTBOOT_STEPS.forEach { stepRes ->
                Text(stringResource(stepRes), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
internal fun CommandSelectionView(
    isUnlock: Boolean,
    presetOptions: List<String>,
    controllerError: String? = null,
    onProceed: (String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var useCustomCommand by remember { mutableStateOf(false) }
    var customCommandText by remember { mutableStateOf("") }
    // Live inline validation mirrors BootloaderWizardController.proceedFromCommandSelection via the shared policy.
    val customValidation = remember(customCommandText) {
        FastbootCommandPolicy.validateWizardCustomCommand(customCommandText)
    }
    val customErrorText = if (useCustomCommand) {
        (customValidation as? FastbootValidationResult.Blocked)
            ?.takeIf { customCommandText.isNotBlank() }
            ?.reason
            ?: controllerError
    } else {
        null
    }
    val selectedCommand = if (useCustomCommand) {
        (customValidation as? FastbootValidationResult.Allowed)?.normalizedCommand
    } else {
        presetOptions.getOrNull(selectedPresetIndex)
    }

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
            WizardRadioOptionRow(
                title = stringResource(R.string.wizard_command_preview_format, option),
                description = stringResource(
                    if (index == 0) R.string.wizard_preset_modern_desc
                    else R.string.wizard_preset_legacy_desc
                ),
                selected = !useCustomCommand && selectedPresetIndex == index,
                onClick = {
                    useCustomCommand = false
                    selectedPresetIndex = index
                }
            )
        }
        WizardRadioOptionRow(
            title = stringResource(R.string.wizard_custom_command_title),
            description = stringResource(R.string.wizard_custom_command_desc),
            selected = useCustomCommand,
            onClick = { useCustomCommand = true }
        )
    }

    if (useCustomCommand) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customCommandText,
            onValueChange = { customCommandText = it },
            label = { Text(stringResource(R.string.wizard_custom_command_label)) },
            placeholder = { Text(stringResource(R.string.wizard_custom_command_placeholder)) },
            prefix = { Text(stringResource(R.string.wizard_fastboot_prefix)) },
            isError = customErrorText != null,
            supportingText = customErrorText?.let { err -> { Text(err) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    WizardFooterButtons(
        secondaryLabel = stringResource(R.string.action_cancel),
        onSecondaryClick = onCancel,
        primaryLabel = stringResource(R.string.action_next),
        onPrimaryClick = { selectedCommand?.let(onProceed) },
        primaryEnabled = selectedCommand != null
    )
}

@Composable
internal fun ConfirmingExecutionView(
    isUnlock: Boolean,
    command: String,
    onProceed: () -> Unit,
    onBack: () -> Unit
) {
    var checkedUnderstand by remember { mutableStateOf(false) }

    WizardIconHeader(
        icon = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.wizard_cd_report_warning),
        iconTint = MaterialTheme.colorScheme.error,
        iconSize = 56.dp,
        title = stringResource(R.string.wizard_confirm_exec_title),
        titleStyle = MaterialTheme.typography.titleLarge,
        titleAlign = TextAlign.Unspecified,
        subtitle = stringResource(R.string.wizard_confirm_exec_desc)
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
        WizardErrorBanner(
            text = stringResource(R.string.wizard_relock_critical_warning),
            textStyle = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    WizardCheckboxRow(
        checked = checkedUnderstand,
        onCheckedChange = { checkedUnderstand = it },
        label = stringResource(R.string.wizard_confirm_exec_checkbox)
    )

    Spacer(modifier = Modifier.height(24.dp))
    WizardFooterButtons(
        secondaryLabel = stringResource(R.string.action_back),
        onSecondaryClick = onBack,
        primaryLabel = stringResource(R.string.action_execute),
        onPrimaryClick = onProceed,
        primaryEnabled = checkedUnderstand,
        isDestructive = true
    )
}

@Composable
internal fun PhysicalConfirmationPromptView(
    isUnlock: Boolean,
    onConfirmed: () -> Unit
) {
    val actionText = stringResource(
        if (isUnlock) R.string.wizard_action_unlock_bootloader else R.string.wizard_action_lock_bootloader
    )

    WizardIconHeader(
        icon = Icons.Filled.Info,
        contentDescription = stringResource(R.string.wizard_cd_user_action),
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.wizard_physical_title),
        titleBottomSpacing = 16.dp,
        subtitle = stringResource(R.string.wizard_physical_desc)
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
            PhysicalInstructionStep(
                icon = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.wizard_cd_step1),
                title = stringResource(R.string.wizard_physical_step1_title),
                description = stringResource(R.string.wizard_physical_step1_desc, actionText)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            PhysicalInstructionStep(
                icon = Icons.Filled.Build,
                contentDescription = stringResource(R.string.wizard_cd_step2),
                title = stringResource(R.string.wizard_physical_step2_title),
                description = stringResource(R.string.wizard_physical_step2_desc)
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
private fun PhysicalInstructionStep(
    icon: ImageVector,
    contentDescription: String,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 32.dp)
    )
}

@Composable
internal fun FinishedView(
    isUnlock: Boolean,
    success: Boolean,
    message: String,
    onClose: () -> Unit
) {
    val actionText = stringResource(
        if (isUnlock) R.string.wizard_op_unlock_short else R.string.wizard_op_lock_short
    )

    if (success) {
        WizardIconHeader(
            icon = Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.cd_success),
            iconTint = SuccessGreen,
            title = stringResource(R.string.wizard_finished_title),
            subtitle = stringResource(R.string.wizard_finished_desc, actionText)
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
                FINISHED_NEXT_STEPS.forEach { (stepRes, weight) ->
                    Text(
                        text = stringResource(stepRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = weight
                    )
                }
            }
        }
    } else {
        WizardIconHeader(
            icon = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.wizard_cd_failed),
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.wizard_failed_title),
            subtitle = message,
            subtitleColor = MaterialTheme.colorScheme.error
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
