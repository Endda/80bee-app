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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiAdjustmentsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var customDpiText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UI Adjustments") },
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
                        text = "Optimize Animation & UI Scaling",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lowering window, transition, and animator durations cuts rendering delays, making your device feel instantly faster. Overriding display density adjusts the UI scaling (DPI) globally.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Animation Scales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Current state: Window Animation Scale is ${settings.windowAnimationScale}x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.executeCommand("settings put global window_animation_scale 0.0; settings put global transition_animation_scale 0.0; settings put global animator_duration_scale 0.0")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Turn Animations Off")
                }
                Button(
                    onClick = {
                        viewModel.executeCommand("settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Faster (0.5x)")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.executeCommand("settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore Stock Defaults (1.0x)")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("UI Density (DPI Override)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Change the physical viewport density. Input custom density, or reset to stock settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = customDpiText,
                onValueChange = { customDpiText = it },
                label = { Text("Custom Density (e.g. 400, 450)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val dpi = customDpiText.toIntOrNull()
                        if (dpi != null) {
                            viewModel.executeCommand("wm density $dpi")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = customDpiText.isNotEmpty()
                ) {
                    Text("Apply DPI")
                }
                FilledTonalButton(
                    onClick = {
                        viewModel.executeCommand("wm density reset")
                        customDpiText = ""
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Stock")
                }
            }
        }
    }
}
