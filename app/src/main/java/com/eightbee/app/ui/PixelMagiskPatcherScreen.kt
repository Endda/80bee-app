package com.eightbee.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.FlashPhase
import com.eightbee.app.MagiskExtractPhase
import com.eightbee.app.PatchPhase
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.ui.patcher.StepConnectionRecon
import com.eightbee.app.ui.patcher.StepFastbootFlash
import com.eightbee.app.ui.patcher.StepFinalSetup
import com.eightbee.app.ui.patcher.StepFirmwareHandoff
import com.eightbee.app.ui.patcher.StepMagiskPrep
import com.eightbee.app.ui.patcher.StepPatching

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelMagiskPatcherScreen(
    controller: MagiskPatcherController,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsState()
    val isBusyWithCriticalStep = state.patchPhase is PatchPhase.InProgress ||
        state.flashPhase == FlashPhase.Flashing

    BackHandler(enabled = true) {
        if (isBusyWithCriticalStep) return@BackHandler
        if (state.currentStep in 2..5) {
            controller.goToStep(state.currentStep - 1)
        } else {
            onDismiss()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patcher_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isBusyWithCriticalStep
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            StepProgressBar(currentStep = state.currentStep, totalSteps = 6)
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (state.currentStep) {
                    1 -> StepConnectionRecon(controller, state)
                    2 -> StepFirmwareHandoff(controller, state)
                    3 -> StepMagiskPrep(controller, state)
                    4 -> StepPatching(controller, state)
                    5 -> StepFastbootFlash(controller, state)
                    6 -> StepFinalSetup(controller, state, onDismiss)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.currentStep < 6) {
                StepFooter(
                    currentStep = state.currentStep,
                    state = state,
                    onPrev = { controller.goToStep(state.currentStep - 1) },
                    onNext = { controller.goToStep(state.currentStep + 1) }
                )
            }
        }
    }
}

@Composable
private fun StepProgressBar(currentStep: Int, totalSteps: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    when (currentStep) {
                        1 -> R.string.patcher_step1_title
                        2 -> R.string.patcher_step2_title
                        3 -> R.string.patcher_step3_title
                        4 -> R.string.patcher_step4_title
                        5 -> R.string.patcher_step5_title
                        else -> R.string.patcher_step6_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.patcher_step_counter_format, currentStep, totalSteps),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / totalSteps },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun StepFooter(
    currentStep: Int,
    state: PixelMagiskPatcherState,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val isNextEnabled = when (currentStep) {
        1 -> state.deviceData != null
        2 -> state.bootFile != null
        3 -> state.magiskExtractPhase == MagiskExtractPhase.Ready
        4 -> state.patchedFile != null
        5 -> state.flashPhase == FlashPhase.Succeeded
        else -> true
    }

    val nextLabel = stringResource(
        when (currentStep) {
            1 -> R.string.patcher_next_to_firmware
            2 -> R.string.patcher_next_to_magisk
            3 -> R.string.patcher_next_to_patching
            4 -> R.string.patcher_next_to_flashing
            5 -> R.string.patcher_next_to_final
            else -> R.string.patcher_finish
        }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentStep > 1) {
            OutlinedButton(
                onClick = onPrev,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_back))
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Button(
            onClick = onNext,
            enabled = isNextEnabled,
            modifier = Modifier
                .weight(1.5f)
                .height(48.dp)
        ) {
            Text(nextLabel)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
