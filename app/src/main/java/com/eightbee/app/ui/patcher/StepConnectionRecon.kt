package com.eightbee.app.ui.patcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.ReconPhase
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.ui.theme.StatusError
import com.eightbee.app.ui.theme.SuccessGreen

@Composable
internal fun StepConnectionRecon(controller: MagiskPatcherController, state: PixelMagiskPatcherState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.patcher_recon_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Text(
            text = stringResource(R.string.patcher_recon_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (state.deviceData != null) SuccessGreen else StatusError,
                            shape = CircleShape
                        )
                )
                Text(
                    text = stringResource(
                        if (state.deviceData != null) R.string.patcher_device_synced
                        else R.string.patcher_waiting_device
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        val reconPhase = state.reconPhase
        if (reconPhase is ReconPhase.Failed) {
            val displayMsg = reconPhase.message.ifBlank { stringResource(R.string.patcher_recon_failed_default) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.cd_error),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = displayMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )

        val isReconning = reconPhase is ReconPhase.InProgress
        Button(
            onClick = { controller.startRecon() },
            enabled = !isReconning,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (isReconning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.patcher_recon_button))
            }
        }

        state.deviceData?.let { device ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.patcher_dossier_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DossierRow(label = stringResource(R.string.patcher_dossier_codename), value = device.codename)
                    DossierRow(label = stringResource(R.string.patcher_dossier_build_id), value = device.buildId)
                    DossierRow(
                        label = stringResource(R.string.patcher_dossier_channel),
                        value = stringResource(
                            if (device.isBeta) R.string.patcher_channel_beta
                            else R.string.patcher_channel_stable
                        )
                    )
                    DossierRow(label = stringResource(R.string.patcher_dossier_api_level), value = device.apiLevel.toString())
                    DossierRow(
                        label = stringResource(R.string.patcher_dossier_target_partition),
                        value = stringResource(R.string.patcher_target_partition_format, device.targetPartition)
                    )
                    DossierRow(label = stringResource(R.string.patcher_dossier_current_slot), value = device.currentSlot)
                    DossierRow(
                        label = stringResource(R.string.patcher_dossier_bootloader),
                        value = stringResource(
                            if (device.isUnlocked) R.string.patcher_bootloader_unlocked
                            else R.string.patcher_bootloader_locked
                        )
                    )
                    if (!device.isUnlocked) {
                        DossierRow(
                            label = stringResource(R.string.patcher_dossier_oem_unlocking),
                            value = stringResource(
                                if (device.oemAllowed) R.string.patcher_oem_allowed
                                else R.string.patcher_oem_disabled
                            )
                        )
                    }
                }
            }

            if (device.isBeta) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.patcher_cd_beta_warning),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.patcher_beta_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (!device.isUnlocked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.cd_warning),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(
                                if (!device.oemAllowed) R.string.patcher_unlock_warn_oem_disabled
                                else R.string.patcher_unlock_warn_oem_allowed
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}
