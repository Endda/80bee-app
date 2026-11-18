package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("UI Density (DPI Override)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Change the physical viewport density. Input custom density, or reset to stock settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            val parsedDpi = customDpiText.trim().toIntOrNull()
            val isDpiValid = parsedDpi != null && parsedDpi in 120..800
            val showDpiError = customDpiText.isNotBlank() && !isDpiValid

            OutlinedTextField(
                value = customDpiText,
                onValueChange = { customDpiText = it },
                label = { Text("Custom Density (120–800, e.g. 400)") },
                isError = showDpiError,
                supportingText = if (showDpiError) {
                    { Text("Enter a safe DPI value between 120 and 800.") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (parsedDpi != null && isDpiValid) {
                            viewModel.executeCommand("wm density $parsedDpi")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isDpiValid
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
