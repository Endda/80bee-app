package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.features.BootloaderWizardController
import com.eightbee.app.features.CuratedFastbootAction
import com.eightbee.app.features.FastbootCommandPolicy
import com.eightbee.app.features.FastbootToolCategory

@Composable
fun BootloaderUtilitiesScreen(
    wizardController: BootloaderWizardController,
    activeConnection: ConnectionManager?,
    onBack: () -> Unit
) {
    val connectionState = activeConnection?.connectionState?.collectAsState()?.value
        ?: ConnectionState.Disconnected
    val isFastbootConnected = activeConnection?.supportsFastboot == true &&
        connectionState == ConnectionState.FastbootConnected
    val utilityLogs by wizardController.utilityLogs.collectAsState()
    val isRunningUtility by wizardController.isRunningUtility.collectAsState()
    var pendingConfirmAction by remember { mutableStateOf<CuratedFastbootAction?>(null) }

    val groupedActions = remember {
        FastbootCommandPolicy.CURATED_UTILITY_ACTIONS.groupBy { it.category }
    }

    ToolScreenScaffold(
        title = stringResource(R.string.bootloader_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.bootloader_card_title),
            description = stringResource(R.string.bootloader_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.bootloader_controls_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bootloader_unlock_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.bootloader_unlock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { wizardController.start(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.bootloader_unlock_button))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bootloader_relock_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.bootloader_relock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { wizardController.start(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.bootloader_lock_button))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.bootloader_tools_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = if (isFastbootConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                text = stringResource(
                    if (isFastbootConnected) R.string.bootloader_tools_fastboot_ready
                    else R.string.bootloader_tools_fastboot_waiting
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (isFastbootConnected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        listOf(
            FastbootToolCategory.DIAGNOSTIC to R.string.bootloader_tools_cat_diagnostic,
            FastbootToolCategory.REBOOT to R.string.bootloader_tools_cat_reboot,
            FastbootToolCategory.SLOT_AND_OEM to R.string.bootloader_tools_cat_slot_oem
        ).forEach { (category, headerRes) ->
            val actions = groupedActions[category].orEmpty()
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(headerRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                actions.forEach { action ->
                    CuratedFastbootActionRow(
                        action = action,
                        enabled = isFastbootConnected && !isRunningUtility,
                        onInvoke = {
                            if (action.requiresConfirmation) {
                                pendingConfirmAction = action
                            } else {
                                wizardController.runCuratedFastbootCommand(action.command)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.bootloader_tools_log_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (utilityLogs.isNotEmpty()) {
                TextButton(onClick = { wizardController.clearUtilityLogs() }) {
                    Text(stringResource(R.string.bootloader_tools_clear_log))
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = if (utilityLogs.isEmpty()) {
                    stringResource(R.string.bootloader_tools_log_empty)
                } else {
                    utilityLogs.joinToString("\n")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    val confirmAction = pendingConfirmAction
    if (confirmAction != null) {
        AlertDialog(
            onDismissRequest = { pendingConfirmAction = null },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.bootloader_tools_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.bootloader_tools_confirm_body_format,
                        confirmAction.command,
                        stringResource(confirmAction.descriptionRes)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfirmAction = null
                        wizardController.runCuratedFastbootCommand(confirmAction.command)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.bootloader_tools_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmAction = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun CuratedFastbootActionRow(
    action: CuratedFastbootAction,
    enabled: Boolean,
    onInvoke: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(action.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(action.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (action.requiresConfirmation) {
                FilledTonalButton(onClick = onInvoke, enabled = enabled) {
                    Text(stringResource(R.string.bootloader_tools_run_button))
                }
            } else {
                Button(onClick = onInvoke, enabled = enabled) {
                    Text(stringResource(R.string.bootloader_tools_run_button))
                }
            }
        }
    }
}
