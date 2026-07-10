package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothRepairScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var selectedOem by remember { mutableStateOf("aosp") }
    var expanded by remember { mutableStateOf(false) }
    val oemList = listOf("aosp", "samsung", "xiaomi", "oneplus", "motorola")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Repair") },
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
                        text = "Bluetooth Stack Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Corrupted pairing caches, background service timeouts, or OEM-specific optimization policies frequently cause adapter lockups, pairing failures, and audio stutters. This utility resets Bluetooth cache parameters without deleting your paired items.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Select Device OEM Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Adjusts diagnostic logic to suit OEM bluetooth implementations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Dropdown selector
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("OEM Profile: ${selectedOem.uppercase()}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    oemList.forEach { oem ->
                        DropdownMenuItem(
                            text = { Text(oem.uppercase()) },
                            onClick = {
                                selectedOem = oem
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Repair Routines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // Standard Fix
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Standard Repair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Cleans the internal state machine, restarts helper threads, toggles adapter state, and reapplies profiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.executeBluetoothRepair(selectedOem, false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Execute Standard Repair")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            // Advanced Fix
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Advanced Repair (Clear Storage)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Warning: On Shizuku connections, directory clearance is restricted. On USB OTG, it resets cache fully. Can clear system Bluetooth preferences.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { viewModel.executeBluetoothRepair(selectedOem, true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Execute Advanced Repair")
                }
            }

            if (selectedOem == "xiaomi" || selectedOem == "oneplus") {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("OEM-Specific Quirks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                if (selectedOem == "xiaomi") {
                    Button(
                        onClick = { viewModel.applyBluetoothQuirk("xiaomi") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Xiaomi Bluetooth Service Profiles")
                    }
                }

                if (selectedOem == "oneplus") {
                    Button(
                        onClick = { viewModel.applyBluetoothQuirk("oneplus") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore OnePlus Bluetooth Optimizer Whitelist")
                    }
                }
            }
        }
    }
}
