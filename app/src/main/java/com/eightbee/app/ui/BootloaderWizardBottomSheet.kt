package com.eightbee.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.BootloaderWizardState
import com.eightbee.app.MainViewModel
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.OtgConnectionManager

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

    // Automatically transition to CommandSelection once Fastboot is connected
    LaunchedEffect(connectionState, wizardState) {
        if (connectionState == ConnectionState.FastbootConnected && 
            wizardState is BootloaderWizardState.WaitingForFastboot) {
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
    val operationName = if (isUnlock) "Unlock Bootloader" else "Relock Bootloader"
    
    // Checkbox states
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
        contentDescription = "Warning Icon",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Critical: $operationName Warnings",
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
                text = "Please read and confirm the following warnings before proceeding. Locking or unlocking the bootloader modifies critical partitions of the device.",
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
        // Warning 1: Data Wipe (All Operations)
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
                text = "I understand this operation will trigger a factory reset and wipe all user data (photos, files, app data) on the target phone.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (isUnlock) {
            // Warning 2: Backup Confirm (Unlock)
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
                    text = "I have backed up all critical data from the target phone to a safe location.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            // Warning 2: Stock Firmware Confirm (Relock)
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
                    text = "I confirm the target device is running 100% stock, unmodified firmware. I acknowledge that relocking on custom ROMs, kernels, root, or custom recoveries WILL permanently brick the device.",
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
            Text("Cancel")
        }
        Button(
            onClick = onProceed,
            enabled = allChecked,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f)
        ) {
            Text("Proceed")
        }
    }
}

@Composable
fun RebootingView() {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Rebooting Target Phone...",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Sending the reboot-to-bootloader command. The target device will turn off and boot into bootloader/fastboot mode. Please keep the cable connected.",
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
        contentDescription = "USB",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Waiting for Fastboot Mode",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "The target device is rebooting. Once it shows the bootloader screen, this phone will automatically detect it.\n\n" +
                "If it stays on this screen for a long time, please try the following steps:\n",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Start,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("1. Unplug the OTG USB cable from THIS host phone, and then plug it back in.", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("2. If a system dialog pops up asking for USB permissions, tap 'Allow' or 'OK'.", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("3. Ensure the target phone is physically displaying the Bootloader/Fastboot screen.", style = MaterialTheme.typography.bodySmall)
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
    var selectedPresetIndex by remember { mutableStateOf(0) }
    var useCustomCommand by remember { mutableStateOf(false) }
    var customCommandText by remember { mutableStateOf("") }

    val operationName = if (isUnlock) "Unlock" else "Lock"
    
    Text(
        text = "Select Fastboot $operationName Command",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))

    // Preset Options List
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
                    Text(text = "fastboot $option", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (index == 0) "Standard for modern devices (Android 8.0+)" else "Fallback for legacy devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Custom Option
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
                Text(text = "Custom Fastboot Command", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "For specific brands (e.g. Motorola oem unlock [key])",
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
            label = { Text("Fastboot Command Arguments") },
            placeholder = { Text("e.g. oem unlock UNIQUE_KEY") },
            prefix = { Text("fastboot ") },
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
            Text("Cancel")
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
            Text("Next")
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
        contentDescription = "Report Warning",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Confirm Execution",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    
    Text(
        text = "You are about to execute the following command over OTG USB Fastboot protocol:",
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
            text = "fastboot $command",
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                    text = "CRITICAL WARNING: If the target device does not contain 100% stock unmodified partition images, relocking the bootloader will result in a hard-brick (the device will fail to boot and cannot be recovered easily). Make sure you have flashed the official factory image first.",
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
            text = "I confirm that I want to send this command and accept all risks of device modification.",
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
            Text("Back")
        }
        Button(
            onClick = onProceed,
            enabled = checkedUnderstand,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f)
        ) {
            Text("Execute")
        }
    }
}

@Composable
fun ExecutingCommandView(command: String) {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Executing Fastboot Command...",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Sending 'fastboot $command' to the target phone. Please do not unplug the cable or shut down the devices.",
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
    val actionText = if (isUnlock) "Unlock the bootloader" else "Lock the bootloader"
    
    Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = "User Action Required",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Action Required on Target Phone!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    
    Text(
        text = "The fastboot command was accepted. Look at the screen of the target phone right now. You will see a warning page requiring physical confirmation.",
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
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Step 1", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Press the VOLUME UP or VOLUME DOWN buttons on the target phone.",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "Use them to navigate and highlight '$actionText' (do NOT select the 'Do not' option).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 32.dp)
            )
            
            Divider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = "Step 2", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Press the POWER button on the target phone to select.",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "This confirms the lock/unlock. The phone will perform a factory reset.",
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
        Text("I Have Pressed the Power Button")
    }
}

@Composable
fun FinishedView(
    isUnlock: Boolean,
    success: Boolean,
    message: String,
    onClose: () -> Unit
) {
    val actionText = if (isUnlock) "Unlock" else "Lock"
    
    if (success) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Success",
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Command Sent & Confirmed!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "The bootloader $actionText command was successfully initiated.",
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
                    text = "Important Next Steps:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "1. The target device is performing a factory reset and will reboot back to its Fastboot/Bootloader screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "2. To boot into the Android system, ensure the START option is highlighted on the target phone (this is highlighted by default).",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "3. Press the POWER button on the target phone to select the START option and boot into the system.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "4. Once the phone starts booting, you can safely unplug the OTG USB cable.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Execution Failed",
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
        Text("Close Wizard")
    }
}
