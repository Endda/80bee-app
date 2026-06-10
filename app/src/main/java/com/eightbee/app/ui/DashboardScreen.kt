package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.DeviceSettings
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.launch
import java.io.InputStream

@Composable
fun DashboardScreen(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val outputText by viewModel.output.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { viewModel.refreshSettings() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh Status")
                }
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Output Console
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isExecuting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                Text(
                    text = outputText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        BootManagerCard(onExecute = { viewModel.executeCommand(it) })
        Spacer(modifier = Modifier.height(16.dp))
        
        SpeedTweakerCard(
            settings = settings,
            onExecute = { viewModel.executeCommand(it) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        DnsToggleCard(
            settings = settings,
            onExecute = { viewModel.executeCommand(it) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BatteryOptimizerCard(
            settings = settings,
            onExecute = { viewModel.executeCommand(it) }
        )
    }
}

@Composable
fun AdvancedAppManagerCard(
    onOpenDebloater: () -> Unit,
    onApkPicked: (Uri) -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onApkPicked(it) }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "App Management", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDebloater, modifier = Modifier.weight(1f)) { Text("Debloater") }
                FilledTonalButton(
                    onClick = { filePickerLauncher.launch("*/*") }, 
                    modifier = Modifier.weight(1f)
                ) { 
                    Text("Sideload") 
                }
            }
        }
    }
}

@Composable
fun BootManagerCard(onExecute: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Boot Manager", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onExecute("reboot") }, modifier = Modifier.weight(1f)) { Text("System") }
                FilledTonalButton(onClick = { onExecute("reboot recovery") }, modifier = Modifier.weight(1f)) { Text("Recovery") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onExecute("reboot bootloader") }, modifier = Modifier.weight(1f)) { Text("Bootloader") }
                FilledTonalButton(onClick = { onExecute("reboot fastboot") }, modifier = Modifier.weight(1f)) { Text("Fastbootd") }
            }
        }
    }
}

@Composable
fun SpeedTweakerCard(settings: DeviceSettings, onExecute: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Speed Tweaker", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                SuggestionChip(
                    onClick = {},
                    label = { Text("${settings.windowAnimationScale}x") }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Animation Scales", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { 
                        onExecute("settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5") 
                    }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.windowAnimationScale != "0.5"
                ) { Text("0.5x") }
                FilledTonalButton(
                    onClick = { 
                        onExecute("settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0") 
                    }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.windowAnimationScale != "1.0"
                ) { Text("1.0x") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Display Density (DPI)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onExecute("wm density reset") }, modifier = Modifier.weight(1f)) { Text("Reset") }
                FilledTonalButton(onClick = { onExecute("wm density 400") }, modifier = Modifier.weight(1f)) { Text("400") }
                FilledTonalButton(onClick = { onExecute("wm density 450") }, modifier = Modifier.weight(1f)) { Text("450") }
            }
        }
    }
}

@Composable
fun DnsToggleCard(settings: DeviceSettings, onExecute: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "DNS Toggle", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                val dnsLabel = when(settings.privateDnsMode) {
                    "off" -> "OFF"
                    "opportunistic" -> "AUTO"
                    "hostname" -> settings.privateDnsHostname.split(".").firstOrNull()?.uppercase() ?: "HOST"
                    else -> "???"
                }
                SuggestionChip(onClick = {}, label = { Text(dnsLabel) })
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onExecute("settings put global private_dns_mode off") }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "off"
                ) { Text("Off") }
                FilledTonalButton(
                    onClick = { onExecute("settings put global private_dns_mode opportunistic") }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "opportunistic"
                ) { Text("Auto") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { 
                        onExecute("settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.adguard.com") 
                    }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "dns.adguard.com"
                ) { Text("AdGuard") }
                FilledTonalButton(
                    onClick = { 
                        onExecute("settings put global private_dns_mode hostname; settings put global private_dns_specifier 1dot1dot1dot1.cloudflare-dns.com") 
                    }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "1dot1dot1dot1.cloudflare-dns.com"
                ) { Text("Cloudflare") }
            }
        }
    }
}

@Composable
fun BatteryOptimizerCard(settings: DeviceSettings, onExecute: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Battery Optimizer", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                SuggestionChip(
                    onClick = {}, 
                    label = { Text(if (settings.adaptiveBatteryEnabled) "Adaptive ON" else "Adaptive OFF") }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onExecute("dumpsys deviceidle force-idle") }, modifier = Modifier.weight(1f)) { Text("Doze On") }
                FilledTonalButton(onClick = { onExecute("dumpsys deviceidle unforce") }, modifier = Modifier.weight(1f)) { Text("Doze Off") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onExecute("settings put global adaptive_battery_management_enabled 1") }, 
                    modifier = Modifier.weight(1f),
                    enabled = !settings.adaptiveBatteryEnabled
                ) { Text("Adaptive On") }
                FilledTonalButton(
                    onClick = { onExecute("settings put global adaptive_battery_management_enabled 0") }, 
                    modifier = Modifier.weight(1f),
                    enabled = settings.adaptiveBatteryEnabled
                ) { Text("Adaptive Off") }
            }
        }
    }
}


