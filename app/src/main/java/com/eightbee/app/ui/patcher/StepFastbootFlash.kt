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
import com.eightbee.app.MainViewModel
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.ui.theme.ActionBlue
import com.eightbee.app.ui.theme.SuccessGreen

@Composable
internal fun StepFastbootFlash(viewModel: MainViewModel, state: PixelMagiskPatcherState) {
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.patcher_unlock_alert_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.patcher_unlock_alert_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
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

                WorkflowItem(
                    stepNumber = 1,
                    title = stringResource(R.string.patcher_wf_step1_title),
                    description = stringResource(R.string.patcher_wf_step1_desc),
                    active = state.flashStep == 1,
                    completed = state.flashStep > 1
                ) {
                    Button(
                        onClick = { viewModel.rebootMagiskToBootloader() },
                        enabled = state.flashStep == 1
                    ) {
                        Text(stringResource(R.string.patcher_wf_step1_button))
                    }
                }

                WorkflowItem(
                    stepNumber = 2,
                    title = stringResource(R.string.patcher_wf_step2_title),
                    description = stringResource(R.string.patcher_wf_step2_desc),
                    active = state.flashStep == 2,
                    completed = state.flashStep > 2 || state.isFastbootConnected
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
                    active = state.flashStep == 3 || state.isFastbootConnected,
                    completed = state.flashComplete
                ) {
                    Button(
                        onClick = { viewModel.flashMagiskPatchedImage() },
                        enabled = state.isFastbootConnected &&
                            !state.flashComplete &&
                            state.fastbootStatus != "STATUS: FLASHING_PARTITION",
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.patcher_wf_step3_button))
                    }
                }
            }
        }

        if (state.fastbootStatus.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.patcher_fastboot_status_format, state.fastbootStatus),
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
}
