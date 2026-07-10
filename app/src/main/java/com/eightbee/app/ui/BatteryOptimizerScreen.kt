package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BackgroundOperationService
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizerScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    var maxHibernationState by remember { mutableStateOf(false) }
    var dozeZeroState by remember { mutableStateOf(false) }
    var bgDrainState by remember { mutableStateOf(false) }
    var refreshRateState by remember { mutableStateOf(false) }
    var windowBlursState by remember { mutableStateOf(false) }
    var samsungCpuState by remember { mutableStateOf(false) }
    var samsungRamPlusState by remember { mutableStateOf(false) }
    var samsungMotionState by remember { mutableStateOf(false) }
    var superDimState by remember { mutableStateOf(false) }

    val compileProgress by BackgroundOperationService.compileProgress.collectAsState()
    val isCompiling by BackgroundOperationService.isCompiling.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery Optimizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Description card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Advanced Battery Optimization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Configuring system Doze parameters, restricting background telemetry, and disabling high resource display parameters can double standby battery life.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Standard Standby Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Control device idle triggers (Doze Mode).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.executeCommand("dumpsys deviceidle force-idle") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Force Doze Standby")
                }
                FilledTonalButton(
                    onClick = { viewModel.executeCommand("dumpsys deviceidle unforce") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disable Forced Doze")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { viewModel.executeCommand("settings put global adaptive_battery_management_enabled 1") },
                    modifier = Modifier.weight(1f),
                    enabled = !settings.adaptiveBatteryEnabled
                ) {
                    Text("Enable Adaptive Battery")
                }
                FilledTonalButton(
                    onClick = { viewModel.executeCommand("settings put global adaptive_battery_management_enabled 0") },
                    modifier = Modifier.weight(1f),
                    enabled = settings.adaptiveBatteryEnabled
                ) {
                    Text("Disable Adaptive")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Deep Standby Modifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Maximum Hibernation Mode", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Restricts battery sync tasks, cuts idle wake lock intervals, and enforces background app deep sleep.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = maxHibernationState,
                    onCheckedChange = {
                        maxHibernationState = it
                        if (it) viewModel.applyMaxHibernation() else viewModel.restoreMaxHibernation()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Zero Maintenance Standby", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Stops battery status checks, telemetry synchronizations, and system logs while in standby.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = dozeZeroState,
                    onCheckedChange = {
                        dozeZeroState = it
                        if (it) viewModel.applyDozeZero() else viewModel.restoreDozeZero()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background Drain Monitor", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Traces periodic background task wakelocks and stops battery leaking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = bgDrainState,
                    onCheckedChange = {
                        bgDrainState = it
                        viewModel.setBackgroundDrainMonitor(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lock Refresh Rate at 60Hz", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Halves screen GPU refreshes to dramatically decrease active usage power consumption.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = refreshRateState,
                    onCheckedChange = {
                        refreshRateState = it
                        viewModel.setLockRefreshRate60(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable Window Blurs", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Bypasses GPU blur calculations for system notification shades and folders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = windowBlursState,
                    onCheckedChange = {
                        windowBlursState = it
                        viewModel.setDisableWindowBlurs(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Super Dim Matrix", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Applies a custom hardware color matrix to dim pixels below normal hardware bounds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = superDimState,
                    onCheckedChange = {
                        superDimState = it
                        if (it) viewModel.applySuperDim() else viewModel.restoreSuperDim()
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Samsung OEM Customizations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Special features specifically optimized for Samsung One UI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable CPU Responsiveness Booster", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Stops Samsung's booster thread which spikes CPU clock speeds during scroll actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = samsungCpuState,
                    onCheckedChange = {
                        samsungCpuState = it
                        viewModel.setSamsungCpuResponsiveness(!it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable RAM Plus virtual paging", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Disables secondary storage-based paging (zRAM swap) to save flash memory read/writes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = samsungRamPlusState,
                    onCheckedChange = {
                        samsungRamPlusState = it
                        viewModel.setSamsungRamPlusOff(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable Motion Engine", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Disables secondary OEM sensors and motion predictors that scan body orientations in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = samsungMotionState,
                    onCheckedChange = {
                        samsungMotionState = it
                        viewModel.setSamsungMotionEngineOff(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("ART Compilation (AOT Optimizer)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Forces Ahead-Of-Time (AOT) speed compilation. Compiling installed applications into speed mode translates Java/Kotlin bytecode directly to local assembly machine code. Saves active battery usage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            if (isCompiling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(compileProgress, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.startArtCompilation(context) },
                    enabled = !isCompiling,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Compilation")
                }
                FilledTonalButton(
                    onClick = { viewModel.stopArtCompilation(context) },
                    enabled = isCompiling,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel Optimizer")
                }
            }
        }
    }
}
