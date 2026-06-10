package com.eightbee.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.InstallStep
import com.eightbee.app.MainViewModel
import com.eightbee.app.SideloadState
import com.eightbee.app.connection.ConnectionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideloadBottomSheet(
    viewModel: MainViewModel,
    connectionManager: ConnectionManager,
    onDismiss: () -> Unit
) {
    val sideloadState by viewModel.sideloadState.collectAsState()
    
    ModalBottomSheet(
        onDismissRequest = {
            if (sideloadState !is SideloadState.Installing) {
                viewModel.clearSideloadState()
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
            when (val state = sideloadState) {
                is SideloadState.Idle -> {
                    Text("Select a file to begin", style = MaterialTheme.typography.bodyLarge)
                }
                is SideloadState.CopyingAndParsing -> {
                    ParsingView()
                }
                is SideloadState.ConfigReady -> {
                    ConfigView(
                        config = state,
                        connectionManager = connectionManager,
                        onInstall = { bypassLowSdk, grantPerms, allowDowngrade, testOnly, sdCard ->
                            viewModel.executeSideloadInstall(
                                config = state,
                                bypassLowTargetSdk = bypassLowSdk,
                                grantAllPermissions = grantPerms,
                                allowDowngrade = allowDowngrade,
                                testOnly = testOnly,
                                installToSdCard = sdCard
                            )
                        },
                        onCancel = {
                            viewModel.clearSideloadState()
                            onDismiss()
                        }
                    )
                }
                is SideloadState.Installing -> {
                    InstallingView(state = state)
                }
                is SideloadState.Success -> {
                    SuccessView(
                        appLabel = state.appLabel,
                        onDismiss = {
                            viewModel.clearSideloadState()
                            onDismiss()
                        }
                    )
                }
                is SideloadState.Error -> {
                    ErrorView(
                        message = state.message,
                        step = state.step,
                        onDismiss = {
                            viewModel.clearSideloadState()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ParsingView() {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Preparing Installer...",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "Extracting and parsing package information",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
fun ConfigView(
    config: SideloadState.ConfigReady,
    connectionManager: ConnectionManager,
    onInstall: (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var targetSdk by remember { mutableStateOf(34) }
    LaunchedEffect(connectionManager) {
        try {
            val sdkStr = connectionManager.runShellCommand("getprop ro.build.version.sdk").trim()
            sdkStr.toIntOrNull()?.let { targetSdk = it }
        } catch (e: Exception) {}
    }

    val isTargetAndroid14OrHigher = targetSdk >= 34

    var bypassLowTargetSdk by remember { mutableStateOf(isTargetAndroid14OrHigher) }
    var grantAllPermissions by remember { mutableStateOf(false) }
    var allowDowngrade by remember { mutableStateOf(false) }
    var testOnly by remember { mutableStateOf(false) }
    var installToSdCard by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Header: App Info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (config.appIcon != null) {
            Image(
                bitmap = config.appIcon.asImageBitmap(),
                contentDescription = "App Icon",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Build, 
                        contentDescription = "App Icon Placeholder",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = config.appLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = config.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("v${config.versionName}") }
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text(formatSize(config.totalSize)) }
                )
            }
        }
    }

    Divider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    // Archive/splits indicator
    if (config.isArchive) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.List, 
                    contentDescription = "Archive Info",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Split APK Bundle (${config.apkFiles.size} splits detected)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // Core Options
    // Replace Existing (-r) - always active
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = true, onCheckedChange = null, enabled = false)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text("Replace existing application (-r)", style = MaterialTheme.typography.bodyLarge)
            Text("Updates the app while preserving user data (recommended)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Bypass Low SDK
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = bypassLowTargetSdk,
            onCheckedChange = { if (isTargetAndroid14OrHigher) bypassLowTargetSdk = it },
            enabled = isTargetAndroid14OrHigher
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text("Bypass Low Target SDK Block", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (isTargetAndroid14OrHigher) "Forces installation of old legacy apps" else "Requires Android 14+ on target device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Grant All Permissions
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = grantAllPermissions, onCheckedChange = { grantAllPermissions = it })
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text("Grant all permissions (-g)", style = MaterialTheme.typography.bodyLarge)
            Text("Automatically grant all runtime permissions declared in manifest", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Advanced Options Dropdown Toggle
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(
        onClick = { advancedExpanded = !advancedExpanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced Options")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Expand Advanced Options",
                modifier = Modifier.rotate(if (advancedExpanded) 180f else 0f)
            )
        }
    }

    AnimatedVisibility(visible = advancedExpanded) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
            // Allow Downgrade
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = allowDowngrade, onCheckedChange = { allowDowngrade = it })
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Allow downgrade (-d)", style = MaterialTheme.typography.bodyLarge)
                    Text("Allows installing an older version over a newer version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Test Only
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = testOnly, onCheckedChange = { testOnly = it })
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Allow test packages (-t)", style = MaterialTheme.typography.bodyLarge)
                    Text("Allows installing packages marked as testOnly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // SD Card
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = installToSdCard, onCheckedChange = { installToSdCard = it })
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Install to SD Card (-s)", style = MaterialTheme.typography.bodyLarge)
                    Text("Forces the package to install on external storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text("Cancel")
        }
        Button(
            onClick = {
                onInstall(bypassLowTargetSdk, grantAllPermissions, allowDowngrade, testOnly, installToSdCard)
            },
            modifier = Modifier.weight(1f)
        ) {
            Text("Install")
        }
    }
}

@Composable
fun InstallingView(state: SideloadState.Installing) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Installing App...",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start
    )
    Spacer(modifier = Modifier.height(16.dp))

    InstallStep.entries.forEach { step ->
        val isCompleted = state.completedSteps.contains(step)
        val isCurrent = state.currentStep == step
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Step Completed",
                        tint = Color(0xFF4CAF50)
                    )
                } else if (isCurrent) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Step Pending",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = step.getDisplayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else if (isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                if (isCurrent && step == InstallStep.STREAM_APKS && state.currentFileProgress.isNotEmpty()) {
                    Text(
                        text = state.currentFileProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
fun SuccessView(appLabel: String, onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = "Success",
        tint = Color(0xFF4CAF50),
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Installation Completed!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Successfully sideloaded $appLabel.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 12.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Done")
    }
}

@Composable
fun ErrorView(message: String, step: InstallStep, onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = "Error",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Installation Failed",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error
    )
    Text(
        text = "Error occurred during step: ${step.getDisplayName()}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Troubleshooting Tips:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        BulletItem("Verify the USB OTG connection if using OTG Host Mode.")
        BulletItem("Ensure Shizuku is running and authorized if using Local Mode.")
        BulletItem("Check if 'Allow version downgrade' is needed (if replacing with an older version).")
        BulletItem("If the target app requires Test Only mode, enable 'Allow test packages'.")
        BulletItem("Verify the APK file itself is not corrupted or incompatible with the target CPU architecture.")
    }

    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Close")
    }
}

@Composable
fun BulletItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.2f MB", mb)
    } else {
        String.format("%.2f KB", kb)
    }
}
