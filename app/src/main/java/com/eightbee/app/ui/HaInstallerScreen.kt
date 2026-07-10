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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaInstallerScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Assistant Setup") },
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
                        text = "Home Assistant Core on Android",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Turn your phone into a dedicated smart home server. This wizard prepares Android systems, disables battery hibernation policies, sets permissions for Termux, and injects python setup commands directly into Termux terminals.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Installation Wizard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 1
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Step 1: System Preparation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Disables android battery standby optimizations, allows terminal executions, and enables wake locks for background processes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.prepareHaSystem() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Prepare Android System")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            // Step 2
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Step 2: Start Deploy in Termux", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Launches Termux and automatically injects python setup scripts. Note: Requires Termux installed on the device. On local connections, ensure USB Debugging (Security Settings) is enabled if text input injection fails.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { viewModel.deployHomeAssistant(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Deploy Home Assistant")
                }
            }
        }
    }
}
