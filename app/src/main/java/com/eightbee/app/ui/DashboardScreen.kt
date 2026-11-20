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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.R
import com.eightbee.app.Screen

@Composable
fun DashboardScreen(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    val outputText by viewModel.output.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val activeConnection by viewModel.activeConnection.collectAsState()

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
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { viewModel.deviceTweaksController.refreshSettings() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_status)
                    )
                }
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.action_disconnect))
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
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (activeConnection?.supportsFastboot == true) {
            HomeLauncherCard(
                title = stringResource(R.string.card_magisk_patcher_title),
                description = stringResource(R.string.card_magisk_patcher_desc),
                onClick = { viewModel.magiskPatcherController.startPatcher() }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Home Tab Cards (Launchers)
        HomeLauncherCard(
            title = stringResource(R.string.card_boot_manager_title),
            description = stringResource(R.string.card_boot_manager_desc),
            onClick = { viewModel.navigateTo(Screen.BootManager) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = stringResource(R.string.card_ui_adjustments_title),
            description = stringResource(R.string.card_ui_adjustments_desc),
            onClick = { viewModel.navigateTo(Screen.UiAdjustments) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = stringResource(R.string.card_dns_manager_title),
            description = stringResource(R.string.card_dns_manager_desc),
            onClick = { viewModel.navigateTo(Screen.DnsToggle) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = stringResource(R.string.card_audio_mods_title),
            description = stringResource(R.string.card_audio_mods_desc),
            onClick = { viewModel.navigateTo(Screen.AudioMods) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        HomeLauncherCard(
            title = stringResource(R.string.card_bluetooth_repair_title),
            description = stringResource(R.string.card_bluetooth_repair_desc),
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
