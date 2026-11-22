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
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.BackgroundOperationCoordinator
import com.eightbee.app.features.BatteryOptimizerController
import com.eightbee.app.features.DeviceTweaksController
import com.eightbee.app.features.OptimizerTweak

private data class OptimizerToggleSpec(
    val tweak: OptimizerTweak,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int
)

private val DEEP_SYSTEM_TOGGLES = listOf(
    OptimizerToggleSpec(
        OptimizerTweak.MAX_HIBERNATION,
        R.string.battery_opt_max_hibernation_title,
        R.string.battery_opt_max_hibernation_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.DOZE_ZERO,
        R.string.battery_opt_zero_maint_title,
        R.string.battery_opt_zero_maint_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.BACKGROUND_DRAIN_MONITOR,
        R.string.battery_opt_drain_monitor_title,
        R.string.battery_opt_drain_monitor_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.LOCK_REFRESH_RATE_60,
        R.string.battery_opt_lock_60hz_title,
        R.string.battery_opt_lock_60hz_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.DISABLE_WINDOW_BLURS,
        R.string.battery_opt_disable_blurs_title,
        R.string.battery_opt_disable_blurs_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.SUPER_DIM,
        R.string.battery_opt_super_dim_title,
        R.string.battery_opt_super_dim_desc
    )
)

private val SAMSUNG_OEM_TOGGLES = listOf(
    OptimizerToggleSpec(
        OptimizerTweak.SAMSUNG_CPU_BOOSTER_OFF,
        R.string.battery_opt_cpu_booster_title,
        R.string.battery_opt_cpu_booster_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.SAMSUNG_RAM_PLUS_OFF,
        R.string.battery_opt_ram_plus_title,
        R.string.battery_opt_ram_plus_desc
    ),
    OptimizerToggleSpec(
        OptimizerTweak.SAMSUNG_MOTION_ENGINE_OFF,
        R.string.battery_opt_motion_engine_title,
        R.string.battery_opt_motion_engine_desc
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
    state: Set<OptimizerTweak>,
    controller: BatteryOptimizerController
) {
    toggles.forEachIndexed { index, spec ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        OptimizerToggleRow(
            titleRes = spec.titleRes,
            descriptionRes = spec.descriptionRes,
            checked = spec.tweak in state,
            onCheckedChange = { controller.setTweak(spec.tweak, it) }
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
