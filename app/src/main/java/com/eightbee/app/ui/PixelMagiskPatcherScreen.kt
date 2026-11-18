package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eightbee.app.LogEntry
import com.eightbee.app.MainViewModel
import com.eightbee.app.PixelMagiskPatcherState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelMagiskPatcherScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.patcherState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pixel Magisk Patcher", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Step progress indicator at the top
            StepProgressBar(currentStep = state.currentStep, totalSteps = 6)
            Spacer(modifier = Modifier.height(16.dp))

            // Main step content area (scrollable if needed)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (state.currentStep) {
                    1 -> StepConnectionRecon(viewModel, state)
                    2 -> StepFirmwareHandoff(viewModel, state)
                    3 -> StepMagiskPrep(viewModel, state)
                    4 -> StepPatching(viewModel, state)
                    5 -> StepFastbootFlash(viewModel, state)
                    6 -> StepFinalSetup(viewModel, state, onDismiss)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation footer (hidden on Step 6 because it has its own finish action)
            if (state.currentStep < 6) {
                StepFooter(
                    currentStep = state.currentStep,
                    state = state,
                    onPrev = { viewModel.goToPatcherStep(state.currentStep - 1) },
                    onNext = { viewModel.goToPatcherStep(state.currentStep + 1) }
                )
            }
        }
    }
}

@Composable
fun StepProgressBar(currentStep: Int, totalSteps: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (currentStep) {
                    1 -> "Connection & Recon"
                    2 -> "Firmware Handoff"
                    3 -> "Magisk Preparation"
                    4 -> "On-Device Patching"
                    5 -> "Fastboot & Flashing"
                    else -> "Final Verification"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "0$currentStep / 0$totalSteps",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / totalSteps },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
fun StepFooter(
    currentStep: Int,
    state: PixelMagiskPatcherState,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val isNextEnabled = when (currentStep) {
        1 -> state.deviceData != null
        2 -> state.bootFile != null
        3 -> state.magiskComponentsReady
        4 -> state.patchedFile != null
        5 -> state.flashComplete
        else -> true
    }

    val nextLabel = when (currentStep) {
        1 -> "Continue to Firmware"
        2 -> "Continue to Magisk"
        3 -> "Continue to Patching"
        4 -> "Continue to Flashing"
        5 -> "View Final Instructions"
        else -> "Finish"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentStep > 1) {
            OutlinedButton(
                onClick = onPrev,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Button(
            onClick = onNext,
            enabled = isNextEnabled,
            modifier = Modifier
                .weight(1.5f)
                .height(48.dp)
        ) {
            Text(nextLabel)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
fun StepConnectionRecon(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Establish ADB Connection",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Text(
            text = "Please make sure USB debugging is enabled on your target Pixel device, then tap the connection trigger to read hardware parameters.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Pulsing dot badge status
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (state.deviceData != null) Color(0xFF4CAF50) else Color(0xFFF44336),
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (state.deviceData != null) "Device Synced" else "Waiting for Device...",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (state.integrityStatus.startsWith("RECON_FAILED")) {
            val errorMsg = state.integrityStatus.substringAfter("RECON_FAILED:").trim()
            val displayMsg = if (errorMsg.isNotEmpty()) errorMsg else "Recon failed. Ensure target device is connected, unlocked, and USB debugging authorization has been granted."
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = displayMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )

        Button(
            onClick = { viewModel.startMagiskRecon() },
            enabled = !state.isReconning,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (state.isReconning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Recon & Query Hardware")
            }
        }

        state.deviceData?.let { device ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Technical Dossier",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DossierRow(label = "Codename", value = device.codename)
                    DossierRow(label = "Build ID", value = device.buildId)
                    DossierRow(label = "Channel", value = if (device.isBeta) "Beta / Preview" else "Stable")
                    DossierRow(label = "API Level", value = device.apiLevel.toString())
                    DossierRow(label = "Target Partition", value = "${device.targetPartition}.img")
                    DossierRow(label = "Current Slot", value = device.currentSlot)
                    DossierRow(label = "Bootloader", value = if (device.isUnlocked) "Unlocked" else "Locked")
                    if (!device.isUnlocked) {
                        DossierRow(
                            label = "OEM Unlocking",
                            value = if (device.oemAllowed) "Allowed" else "Disabled (Developer Options)"
                        )
                    }
                }
            }

            if (device.isBeta) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Beta Warning",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Beta / Preview build detected. Make sure you download the exact Beta factory image matching this build ID rather than a stable release.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (!device.isUnlocked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        val unlockText = if (!device.oemAllowed) {
                            "Warning: Bootloader is locked and 'OEM unlocking' is disabled in Developer Options. Enable OEM unlocking on the target device before flashing; unlocking will factory-reset your device."
                        } else {
                            "Warning: Bootloader is locked. Unlocking is required before flashing. Proceeding will factory-reset your device."
                        }
                        Text(
                            text = unlockText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepFirmwareHandoff(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val bootFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.handleMagiskBootFileSelected(context, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Firmware Handoff",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "To proceed, download the Google Factory Image corresponding to your exact build ID, then extract and upload either the outer factory ZIP or the inner kernel image file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Guide Card Step 1
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 1: Download Google Factory Image",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Build ID: ${state.deviceData?.buildId ?: "Unknown"}. (Pressing download automatically copies your Build ID to search.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val buildId = state.deviceData?.buildId ?: ""
                        if (buildId.isNotEmpty()) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Build ID", buildId)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Copied Build ID: $buildId", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        val codename = state.deviceData?.codename ?: ""
                        uriHandler.openUri("https://developers.google.com/android/images#$codename")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Get Factory Image")
                }
            }
        }

        // Guide Card Step 2
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 2: Upload firmware image",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select the downloaded factory zip (e.g. raven-*.zip) or raw image file (boot.img / init_boot.img).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { bootFilePicker.launch("*/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AccountBox, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Browse Local Files")
                }
            }
        }

        // Integrity details
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System Integrity Status",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.integrityStatus,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (state.integrityProgress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.integrityProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (state.bootFile != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    DossierRow(label = "Loaded File", value = state.bootFileName)
                    DossierRow(label = "File Size", value = state.bootFileSize)
                }
            }
        }

        state.imageError?.let { errorMsg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        state.imageWarning?.let { warningMsg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = warningMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun StepMagiskPrep(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val apkFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.handleMagiskApkSelected(context, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Magisk Preparation",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Provide the official Magisk APK. The patcher will extract necessary components (scripts, binaries) to prepare the patching toolchain.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Step 1
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 1: Download Magisk Manager APK",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        uriHandler.openUri("https://github.com/topjohnwu/Magisk/releases/latest")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Get Latest Magisk Release")
                }
            }
        }

        // Step 2
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 2: Upload APK to 80bee",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { apkFilePicker.launch("application/vnd.android.package-archive") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AccountBox, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Browse Magisk APK")
                }
            }
        }

        // Decompress Status Log Card
        if (state.appStatus != "STATUS: IDLE") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Decompress log",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(state.extractProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.extractProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = state.appStatus,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TerminalLogView(logs = state.extractLogs, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun StepPatching(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "On-Device Patching",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = "The app is pushing binaries to /data/local/tmp/80bee_patcher and executing the patching script inside your target device's shell.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Operation Patch-Core Console
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Operation Patch-Core",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = state.patchStatus,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (state.patchProgress > 0f) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.patchProgressLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(state.patchProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.patchProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                TerminalLogView(logs = state.patchLogs, modifier = Modifier.weight(1f))
            }
        }

        Button(
            onClick = { viewModel.startMagiskPatching(context) },
            enabled = state.patchStatus != "EXECUTING_PATCH" && state.bootFile != null && state.magiskApkFile != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Patching Environment")
        }
    }
}

@Composable
fun StepFastbootFlash(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Fastboot & Flashing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Reboot your target device to bootloader mode, connect over USB Fastboot, and write the patched kernel to the target partition.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Show unlock warning if unlock is false or requested
        if (state.showUnlockWarning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Unlock Alert",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your device bootloader is locked. Unlocking is required before flashing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Sequential Flashing Card
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Flashing Workflow",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Step 1: Reboot
                WorkflowItem(
                    stepNumber = 1,
                    title = "Reboot to Bootloader",
                    description = "ADB restart target phone into Bootloader mode.",
                    active = state.flashStep == 1,
                    completed = state.flashStep > 1
                ) {
                    Button(
                        onClick = { viewModel.rebootMagiskToBootloader() },
                        enabled = state.flashStep == 1
                    ) {
                        Text("Reboot Device")
                    }
                }

                // Step 2: Connection
                WorkflowItem(
                    stepNumber = 2,
                    title = "Connect Fastboot",
                    description = "Handshake with target bootloader parameters.",
                    active = state.flashStep == 2,
                    completed = state.flashStep > 2 || state.isFastbootConnected
                ) {
                    Text(
                        text = if (state.isFastbootConnected) "Connected in Fastboot!" else "Waiting for Fastboot device...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isFastbootConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Step 3: Flash
                WorkflowItem(
                    stepNumber = 3,
                    title = "Flash Patched Image",
                    description = "Flashing patched kernel to target slot partition.",
                    active = state.flashStep == 3 || state.isFastbootConnected,
                    completed = state.flashComplete
                ) {
                    Button(
                        onClick = { viewModel.flashMagiskPatchedImage() },
                        enabled = state.isFastbootConnected && !state.flashComplete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("FLASH NOW")
                    }
                }
            }
        }

        // Mini status logger
        if (state.fastbootStatus.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Status: ${state.fastbootStatus}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (state.patchLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TerminalLogView(logs = state.patchLogs, modifier = Modifier.height(100.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StepFinalSetup(viewModel: MainViewModel, state: PixelMagiskPatcherState, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Success Hero Icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFFE8F5E9), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(40.dp)
            )
        }

        Text(
            text = "Final Setup Instructions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Your patched kernel partition is flashed! Follow these final verification steps to complete root authorization on the device:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepInstructionRow(number = 1, text = "Press Power button on target phone to select START in Fastboot to reboot it.")
                StepInstructionRow(number = 2, text = "Once target phone boots, unlock it, and re-connect USB debugging cable.")
                StepInstructionRow(number = 3, text = "Tap the button below to install the official Magisk Manager application on the device:")

                Button(
                    onClick = { viewModel.installMagiskApkFromPatcher() },
                    enabled = !state.finalApkInstalled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.finalApkInstalled) "APK Installed!" else "Install Magisk APK")
                }

                StepInstructionRow(number = 4, text = "Launch Magisk app on target device. Complete any 'Additional Setup' prompts (prompts a final reboot). Done!")
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Return to Tools")
        }
    }
}

@Composable
fun StepInstructionRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun WorkflowItem(
    stepNumber: Int,
    title: String,
    description: String,
    active: Boolean,
    completed: Boolean,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = when {
                completed -> Color(0xFF4CAF50)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (completed) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (active && !completed) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
fun DossierRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun TerminalLogView(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            state.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF333333), shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { log ->
                val color = when (log.type) {
                    "ERROR" -> Color(0xFFF44336)
                    "SUCCESS" -> Color(0xFF4CAF50)
                    "WARNING" -> Color(0xFFFFC107)
                    "PROMPT" -> Color(0xFFE0E0E0)
                    "WAIT" -> Color(0xFF2196F3)
                    else -> Color(0xFF888888)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF00FF00),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "[${log.type}] ${log.message}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = color,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}
