package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootManagerScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boot Manager") },
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
                        text = "About Boot Modes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android devices feature distinct boot partitions for recovery, flashing, and system operation. Changing boot states requires ADB shell reboot privileges.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Power Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // Options
            BootActionRow(
                title = "Standard Reboot",
                description = "Warm reboot the device back into the standard Android system.",
                buttonText = "Reboot System",
                onClick = { viewModel.executeCommand("reboot") }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = "Recovery Mode",
                description = "Boot into the recovery partition. Useful for clearing system cache, sideloading official OTA zips, or initiating factory resets.",
                buttonText = "Reboot Recovery",
                onClick = { viewModel.executeCommand("reboot recovery") }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = "Bootloader (Fastboot)",
                description = "Boot into low-level bootloader/boot ROM mode. Required to execute fastboot commands (e.g. flashing partitions, unlocking bootloader).",
                buttonText = "Reboot Bootloader",
                onClick = { viewModel.executeCommand("reboot bootloader") }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = "Fastbootd Mode",
                description = "Boot into user-space Fastboot (available on Android 10+). Needed for flashing dynamic / logical partitions (system, product, vendor).",
                buttonText = "Reboot Fastbootd",
                onClick = { viewModel.executeCommand("reboot fastboot") }
            )
        }
    }
}

@Composable
fun BootActionRow(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonText)
        }
    }
}
