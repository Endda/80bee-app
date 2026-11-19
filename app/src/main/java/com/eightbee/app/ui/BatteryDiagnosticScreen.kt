package com.eightbee.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eightbee.app.BackgroundOperationService
import com.eightbee.app.MainViewModel
import com.eightbee.app.R

@Composable
internal fun rememberNotificationPermissionLauncher(onProceed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnProceed by rememberUpdatedState(onProceed)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        currentOnProceed()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            currentOnProceed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDiagnosticScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val isMonitoring by BackgroundOperationService.isMonitoringBattery.collectAsState()
    val telemetryText by BackgroundOperationService.batteryTelemetry.collectAsState()
    val startMonitoringWithPermission = rememberNotificationPermissionLauncher {
        viewModel.startBatteryMonitoring()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.battery_diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.battery_diag_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.battery_diag_card_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (isMonitoring) R.string.battery_diag_monitoring_active
                        else R.string.battery_diag_monitoring_stopped
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = {
                        if (isMonitoring) {
                            viewModel.stopBatteryMonitoring()
                        } else {
                            startMonitoringWithPermission()
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (isMonitoring) R.string.battery_diag_stop_scan
                            else R.string.battery_diag_start_scan
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isMonitoring && telemetryText.isNotEmpty()) {
                val parsedData = remember(telemetryText) { parseBatteryDumpsys(telemetryText) }
                BatteryMetricsView(parsedData)

                Spacer(modifier = Modifier.height(20.dp))

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
                                contentDescription = stringResource(R.string.battery_diag_cd_charging_warning),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.battery_diag_charging_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.battery_diag_charging_desc),
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
                        onClick = { viewModel.simulateBatteryUnplug() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.battery_diag_simulate_unplug))
                    }
                    Button(
                        onClick = { viewModel.resetBatterySimulation() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.battery_diag_reset_charging))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.battery_diag_raw_console),
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
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.battery_diag_empty_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
                text = stringResource(R.string.battery_diag_key_telemetry),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            MetricRow(
                stringResource(R.string.battery_diag_metric_level),
                stringResource(R.string.battery_diag_level_format, data.level)
            )
            MetricRow(stringResource(R.string.battery_diag_metric_health), data.healthString)
            MetricRow(
                stringResource(R.string.battery_diag_metric_temp),
                stringResource(R.string.battery_diag_temp_format, data.temp.toString())
            )
            MetricRow(
                stringResource(R.string.battery_diag_metric_voltage),
                stringResource(R.string.battery_diag_voltage_format, data.voltage.toString())
            )
            MetricRow(
                stringResource(R.string.battery_diag_metric_power),
                stringResource(
                    if (data.isCharging) R.string.battery_diag_power_charging
                    else R.string.battery_diag_power_battery
                )
            )
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
