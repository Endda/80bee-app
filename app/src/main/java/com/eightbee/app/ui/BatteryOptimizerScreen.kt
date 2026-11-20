package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BackgroundOperationService
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun BatteryOptimizerScreen(
    tweaksController: DeviceTweaksController,
    optimizerController: BatteryOptimizerController,
    activeConnection: ConnectionManager?,
    onBack: () -> Unit
) {
    val settings by tweaksController.settings.collectAsState()
    val optimizerState by optimizerController.state.collectAsState()

    LaunchedEffect(activeConnection) {
        optimizerController.refreshState()
    }

    val compileProgress by BackgroundOperationService.compileProgress.collectAsState()
    val isCompiling by BackgroundOperationService.isCompiling.collectAsState()
    val startCompileWithPermission = rememberNotificationPermissionLauncher {
        tweaksController.startArtCompilation()
    }

    ToolScreenScaffold(
        title = stringResource(R.string.battery_opt_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.battery_opt_card_title),
            description = stringResource(R.string.battery_opt_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.battery_opt_standby_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.battery_opt_standby_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { tweaksController.setForceDoze(true) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.battery_opt_force_doze))
            }
            FilledTonalButton(
                onClick = { tweaksController.setForceDoze(false) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.battery_opt_disable_doze))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { tweaksController.setAdaptiveBattery(true) },
                modifier = Modifier.weight(1f),
                enabled = !settings.adaptiveBatteryEnabled
            ) {
                Text(stringResource(R.string.battery_opt_enable_adaptive))
            }
            FilledTonalButton(
                onClick = { tweaksController.setAdaptiveBattery(false) },
                modifier = Modifier.weight(1f),
                enabled = settings.adaptiveBatteryEnabled
            ) {
                Text(stringResource(R.string.battery_opt_disable_adaptive))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.battery_opt_deep_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_max_hibernation_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_max_hibernation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.maxHibernation,
                onCheckedChange = {
                    if (it) optimizerController.applyMaxHibernation() else optimizerController.restoreMaxHibernation()
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_zero_maint_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_zero_maint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.dozeZero,
                onCheckedChange = {
                    if (it) optimizerController.applyDozeZero() else optimizerController.restoreDozeZero()
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_drain_monitor_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_drain_monitor_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.backgroundDrainMonitor,
                onCheckedChange = {
                    optimizerController.setBackgroundDrainMonitor(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_lock_60hz_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_lock_60hz_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.lockRefreshRate60,
                onCheckedChange = {
                    optimizerController.setLockRefreshRate60(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_disable_blurs_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_disable_blurs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.disableWindowBlurs,
                onCheckedChange = {
                    optimizerController.setDisableWindowBlurs(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_super_dim_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_super_dim_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.superDim,
                onCheckedChange = {
                    if (it) optimizerController.applySuperDim() else optimizerController.restoreSuperDim()
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.battery_opt_samsung_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.battery_opt_samsung_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_cpu_booster_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_cpu_booster_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.samsungCpuBoosterOff,
                onCheckedChange = {
                    optimizerController.setSamsungCpuResponsiveness(!it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_ram_plus_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_ram_plus_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.samsungRamPlusOff,
                onCheckedChange = {
                    optimizerController.setSamsungRamPlusOff(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.battery_opt_motion_engine_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.battery_opt_motion_engine_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = optimizerState.samsungMotionEngineOff,
                onCheckedChange = {
                    optimizerController.setSamsungMotionEngineOff(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.battery_opt_art_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.battery_opt_art_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isCompiling) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(compileProgress, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { startCompileWithPermission() },
                enabled = !isCompiling,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.battery_opt_start_compile))
            }
            FilledTonalButton(
                onClick = { tweaksController.stopArtCompilation() },
                enabled = isCompiling,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.battery_opt_cancel_compile))
            }
        }
    }
}
