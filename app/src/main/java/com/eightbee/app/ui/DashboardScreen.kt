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

        LauncherList(homeLaunchers, activeConnection?.supportsFastboot == true, viewModel)
    }
}

private val homeLaunchers = listOf(
    ToolLauncher(R.string.card_magisk_patcher_title, R.string.card_magisk_patcher_desc, requiresFastboot = true) {
        magiskPatcherController.startPatcher()
    },
    ToolLauncher(R.string.card_boot_manager_title, R.string.card_boot_manager_desc) { navigateTo(Screen.BootManager) },
    ToolLauncher(R.string.card_ui_adjustments_title, R.string.card_ui_adjustments_desc) { navigateTo(Screen.UiAdjustments) },
    ToolLauncher(R.string.card_dns_manager_title, R.string.card_dns_manager_desc) { navigateTo(Screen.DnsToggle) },
    ToolLauncher(R.string.card_audio_mods_title, R.string.card_audio_mods_desc) { navigateTo(Screen.AudioMods) },
    ToolLauncher(R.string.card_bluetooth_repair_title, R.string.card_bluetooth_repair_desc) { navigateTo(Screen.BluetoothRepair) }
)
