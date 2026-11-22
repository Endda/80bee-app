package com.eightbee.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BatteryOptimizerState
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.BackgroundOperationCoordinator
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.DeviceTweaksController

private data class OptimizerToggleSpec(
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val isChecked: (BatteryOptimizerState) -> Boolean,
    val onToggle: (BatteryOptimizerController, Boolean) -> Unit
)

private val DEEP_SYSTEM_TOGGLES = listOf(
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_max_hibernation_title,
        descriptionRes = R.string.battery_opt_max_hibernation_desc,
        isChecked = { it.maxHibernation },
        onToggle = { controller, enabled ->
            if (enabled) controller.applyMaxHibernation() else controller.restoreMaxHibernation()
        }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_zero_maint_title,
        descriptionRes = R.string.battery_opt_zero_maint_desc,
        isChecked = { it.dozeZero },
        onToggle = { controller, enabled ->
            if (enabled) controller.applyDozeZero() else controller.restoreDozeZero()
        }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_drain_monitor_title,
        descriptionRes = R.string.battery_opt_drain_monitor_desc,
        isChecked = { it.backgroundDrainMonitor },
        onToggle = { controller, enabled -> controller.setBackgroundDrainMonitor(enabled) }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_lock_60hz_title,
        descriptionRes = R.string.battery_opt_lock_60hz_desc,
        isChecked = { it.lockRefreshRate60 },
        onToggle = { controller, enabled -> controller.setLockRefreshRate60(enabled) }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_disable_blurs_title,
        descriptionRes = R.string.battery_opt_disable_blurs_desc,
        isChecked = { it.disableWindowBlurs },
        onToggle = { controller, enabled -> controller.setDisableWindowBlurs(enabled) }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_super_dim_title,
        descriptionRes = R.string.battery_opt_super_dim_desc,
        isChecked = { it.superDim },
        onToggle = { controller, enabled ->
            if (enabled) controller.applySuperDim() else controller.restoreSuperDim()
        }
    )
)

private val SAMSUNG_OEM_TOGGLES = listOf(
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_cpu_booster_title,
        descriptionRes = R.string.battery_opt_cpu_booster_desc,
        isChecked = { it.samsungCpuBoosterOff },
        onToggle = { controller, disabled -> controller.setSamsungCpuResponsiveness(!disabled) }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_ram_plus_title,
        descriptionRes = R.string.battery_opt_ram_plus_desc,
        isChecked = { it.samsungRamPlusOff },
        onToggle = { controller, disabled -> controller.setSamsungRamPlusOff(disabled) }
    ),
    OptimizerToggleSpec(
        titleRes = R.string.battery_opt_motion_engine_title,
        descriptionRes = R.string.battery_opt_motion_engine_desc,
        isChecked = { it.samsungMotionEngineOff },
        onToggle = { controller, disabled -> controller.setSamsungMotionEngineOff(disabled) }
    )
)

@Composable
fun BatteryOptimizerScreen(
    tweaksController: DeviceTweaksController,
    optimizerController: BatteryOptimizerController,
    backgroundOperations: BackgroundOperationCoordinator,
    activeConnection: ConnectionManager?,
    onBack: () -> Unit
) {
    val settings by tweaksController.settings.collectAsState()
    val optimizerState by optimizerController.state.collectAsState()

    LaunchedEffect(activeConnection) {
        optimizerController.refreshState()
    }

    val compileProgress by backgroundOperations.compileProgress.collectAsState()
    val isCompiling by backgroundOperations.isCompiling.collectAsState()
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

        ToolSectionHeader(
            stringResource(R.string.battery_opt_standby_header),
            stringResource(R.string.battery_opt_standby_desc)
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

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        ToolSectionHeader(stringResource(R.string.battery_opt_deep_header))
        Spacer(modifier = Modifier.height(12.dp))
        OptimizerToggleGroup(
            toggles = DEEP_SYSTEM_TOGGLES,
            state = optimizerState,
            controller = optimizerController
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        ToolSectionHeader(
            stringResource(R.string.battery_opt_samsung_header),
            stringResource(R.string.battery_opt_samsung_desc),
            subtitleColor = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        OptimizerToggleGroup(
            toggles = SAMSUNG_OEM_TOGGLES,
            state = optimizerState,
            controller = optimizerController
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        ToolSectionHeader(
            stringResource(R.string.battery_opt_art_header),
            stringResource(R.string.battery_opt_art_desc)
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

@Composable
private fun OptimizerToggleGroup(
    toggles: List<OptimizerToggleSpec>,
    state: BatteryOptimizerState,
    controller: BatteryOptimizerController
) {
    toggles.forEachIndexed { index, spec ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        OptimizerToggleRow(
            titleRes = spec.titleRes,
            descriptionRes = spec.descriptionRes,
            checked = spec.isChecked(state),
            onCheckedChange = { spec.onToggle(controller, it) }
        )
    }
}

@Composable
private fun OptimizerToggleRow(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
