package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.Screen

@Composable
fun DashboardScreen(viewModel: MainViewModel, onDisconnect: () -> Unit) {
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
                text = "Dashboard",
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

        // Home Tab Cards (Launchers)
        HomeLauncherCard(
            title = "Boot Manager",
            description = "Quick access reboot options including Recovery, Bootloader (Fastboot), and Fastbootd modes.",
            onClick = { viewModel.navigateTo(Screen.BootManager) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = "UI Adjustments",
            description = "Optimize window rendering latency, transitions duration scales, and global DPI densities.",
            onClick = { viewModel.navigateTo(Screen.UiAdjustments) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = "DNS Manager",
            description = "Enforce network-level tracker protection, cloudflare encryption, and ad-blocking preset resolvers.",
            onClick = { viewModel.navigateTo(Screen.DnsToggle) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = "Audio Modifications",
            description = "Override safe volume constraints and Calculated Sound Dose volume attenuation indices.",
            onClick = { viewModel.navigateTo(Screen.AudioMods) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = "Bluetooth Repair",
            description = "Reset pairing channels, clear cache corruption registers, and apply OEM fixes.",
            onClick = { viewModel.navigateTo(Screen.BluetoothRepair) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLauncherCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
