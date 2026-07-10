package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BackgroundOperationService
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDiagnosticBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isMonitoring by BackgroundOperationService.isMonitoringBattery.collectAsState()
    val telemetryText by BackgroundOperationService.batteryTelemetry.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Battery Diagnostics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Service status control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isMonitoring) "Monitoring Active" else "Monitoring Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = {
                        if (isMonitoring) {
                            viewModel.stopBatteryMonitoring(context)
                        } else {
                            viewModel.startBatteryMonitoring(context)
                        }
                    }
                ) {
                    Text(if (isMonitoring) "Stop Scan" else "Start Scan")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isMonitoring && telemetryText.isNotEmpty()) {
                val parsedData = remember(telemetryText) { parseBatteryDumpsys(telemetryText) }
                BatteryMetricsView(parsedData)

                Spacer(modifier = Modifier.height(20.dp))

                // Simulate Unplug controls
                if (parsedData.isCharging) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = "Charging Warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Device is Charging",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Simulate unplugging to capture diagnostic stats without charging contamination.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.executeCommand("dumpsys battery unplug") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Simulate Unplug")
                    }
                    Button(
                        onClick = { viewModel.executeCommand("dumpsys battery reset") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset Charging")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Raw Dumpsys view
                Text(
                    text = "Raw Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = telemetryText,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Text(
                    text = "Click 'Start Scan' to query battery parameters dynamically.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}

@Composable
fun BatteryMetricsView(data: BatteryMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Key Telemetry",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            MetricRow("Level", "${data.level}%")
            MetricRow("Health", data.healthString)
            MetricRow("Temperature", "${data.temp}°C")
            MetricRow("Voltage", "${data.voltage} V")
            MetricRow("Power Source", if (data.isCharging) "Charging (AC/USB)" else "Battery (Discharging)")
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

data class BatteryMetrics(
    val level: Int,
    val temp: Double,
    val voltage: Double,
    val isCharging: Boolean,
    val healthString: String
)

fun parseBatteryDumpsys(raw: String): BatteryMetrics {
    var level = 0
    var temp = 0.0
    var voltage = 0.0
    var isCharging = false
    var health = 1

    val lines = raw.split("\n")
    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("level:") -> {
                level = trimmed.substringAfter("level:").trim().toIntOrNull() ?: 0
            }
            trimmed.startsWith("temperature:") || trimmed.startsWith("temp:") -> {
                val tempRaw = trimmed.substringAfter("temp:").substringAfter("temperature:").trim().toDoubleOrNull() ?: 0.0
                temp = tempRaw / 10.0 // dumpsys temp is in tenths of a degree
            }
            trimmed.startsWith("voltage:") -> {
                val voltRaw = trimmed.substringAfter("voltage:").trim().toDoubleOrNull() ?: 0.0
                voltage = voltRaw / 1000.0 // dumpsys voltage is in millivolts
            }
            trimmed.startsWith("AC powered: true") || trimmed.startsWith("USB powered: true") || trimmed.startsWith("Wireless powered: true") -> {
                isCharging = true
            }
            trimmed.startsWith("health:") -> {
                health = trimmed.substringAfter("health:").trim().toIntOrNull() ?: 1
            }
        }
    }

    val healthStr = when (health) {
        2 -> "Good"
        3 -> "Overheat"
        4 -> "Dead"
        5 -> "Over Voltage"
        6 -> "Unspecified Failure"
        7 -> "Cold"
        else -> "Unknown"
    }

    return BatteryMetrics(level, temp, voltage, isCharging, healthStr)
}
