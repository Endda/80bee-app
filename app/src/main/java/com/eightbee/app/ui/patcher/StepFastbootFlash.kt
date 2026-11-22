package com.eightbee.app.ui.patcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.FlashPhase
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.ui.AlertBanner
import com.eightbee.app.ui.AlertTone
import com.eightbee.app.ui.theme.ActionBlue
import com.eightbee.app.ui.theme.SuccessGreen

@Composable
internal fun StepFastbootFlash(controller: MagiskPatcherController, state: PixelMagiskPatcherState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.patcher_flashing_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.patcher_flashing_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (state.showUnlockWarning) {
            AlertBanner(
                title = stringResource(R.string.patcher_unlock_alert_title),
                message = stringResource(R.string.patcher_unlock_alert_desc),
                tone = AlertTone.Error
            )
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.patcher_flashing_workflow_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val flashPhase = state.flashPhase
                val step1Active = flashPhase == FlashPhase.AwaitingHandshake && !state.isFastbootConnected
                val step1Completed = flashPhase != FlashPhase.AwaitingHandshake || state.isFastbootConnected
                val step2Active = flashPhase == FlashPhase.RebootSent && !state.isFastbootConnected
                val step2Completed = state.isFastbootConnected || flashPhase == FlashPhase.Succeeded
                val step3Active = state.isFastbootConnected ||
                    flashPhase == FlashPhase.Flashing ||
                    flashPhase == FlashPhase.Succeeded ||
                    flashPhase is FlashPhase.Failed
                val step3Completed = flashPhase == FlashPhase.Succeeded

                WorkflowItem(
                    stepNumber = 1,
                    title = stringResource(R.string.patcher_wf_step1_title),
                    description = stringResource(R.string.patcher_wf_step1_desc),
                    active = step1Active,
                    completed = step1Completed
                ) {
                    Button(
                        onClick = { controller.rebootToBootloader() },
                        enabled = step1Active
                    ) {
                        Text(stringResource(R.string.patcher_wf_step1_button))
                    }
                }

                WorkflowItem(
                    stepNumber = 2,
                    title = stringResource(R.string.patcher_wf_step2_title),
                    description = stringResource(R.string.patcher_wf_step2_desc),
                    active = step2Active,
                    completed = step2Completed
                ) {
                    Text(
                        text = stringResource(
                            if (state.isFastbootConnected) R.string.patcher_wf_step2_connected
                            else R.string.patcher_wf_step2_waiting
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isFastbootConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                WorkflowItem(
                    stepNumber = 3,
                    title = stringResource(R.string.patcher_wf_step3_title),
                    description = stringResource(R.string.patcher_wf_step3_desc),
                    active = step3Active,
                    completed = step3Completed
                ) {
                    Button(
                        onClick = { controller.flashPatchedImage() },
                        enabled = state.isFastbootConnected &&
                            flashPhase != FlashPhase.Succeeded &&
                            flashPhase != FlashPhase.Flashing,
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.patcher_wf_step3_button))
                    }
                }
            }
        }

        val fastbootStatusLabel = when (val phase = state.flashPhase) {
            FlashPhase.AwaitingHandshake -> "Awaiting Fastboot Handshake"
            FlashPhase.RebootSent -> "STATUS: REBOOT_SENT"
            FlashPhase.FastbootReady -> "STATUS: FASTBOOT_CONNECTED"
            FlashPhase.Flashing -> "STATUS: FLASHING_PARTITION"
            FlashPhase.Succeeded -> "STATUS: FLASH_SUCCESS"
            is FlashPhase.Failed -> "STATUS: FLASH_FAILED (${phase.message})"
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patcher_fastboot_status_format, fastbootStatusLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (state.patchLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TerminalLogView(logs = state.patchLogs, modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}
