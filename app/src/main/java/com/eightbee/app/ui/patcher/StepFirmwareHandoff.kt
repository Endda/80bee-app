package com.eightbee.app.ui.patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.FirmwarePhase
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.features.MagiskPatcherController
import com.eightbee.app.ui.AlertBanner
import com.eightbee.app.ui.AlertTone

@Composable
internal fun StepFirmwareHandoff(controller: MagiskPatcherController, state: PixelMagiskPatcherState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current

    val bootFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { controller.handleBootFileSelected(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.patcher_firmware_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.patcher_firmware_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patcher_firmware_step1_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                val buildIdDisplay = state.deviceData?.buildId ?: stringResource(R.string.patcher_build_id_unknown)
                Text(
                    text = stringResource(R.string.patcher_firmware_step1_desc_format, buildIdDisplay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val buildId = state.deviceData?.buildId ?: ""
                        if (buildId.isNotEmpty()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Build ID", buildId))
                            Toast.makeText(
                                context,
                                resources.getString(R.string.patcher_copied_build_id_format, buildId),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        val codename = state.deviceData?.codename ?: ""
                        uriHandler.openUri("https://developers.google.com/android/images#$codename")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.patcher_get_factory_image))
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patcher_firmware_step2_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.patcher_firmware_step2_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { bootFilePicker.launch("*/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AccountBox, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.patcher_browse_local_files))
                }
            }
        }

        val firmwarePhase = state.firmwarePhase
        val statusLabel = when (firmwarePhase) {
            FirmwarePhase.Idle -> "READY_FOR_IMAGE"
            is FirmwarePhase.InProgress -> firmwarePhase.label
            FirmwarePhase.Loaded -> "IMAGE_LOADED"
            is FirmwarePhase.Failed -> "LOAD_FAILED"
        }
        val firmwareProgress = when (firmwarePhase) {
            is FirmwarePhase.InProgress -> firmwarePhase.progress
            FirmwarePhase.Loaded -> 1f
            else -> 0f
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.patcher_integrity_status_header),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (firmwareProgress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { firmwareProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (state.bootFile != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    DossierRow(label = stringResource(R.string.patcher_loaded_file), value = state.bootFileName)
                    DossierRow(label = stringResource(R.string.patcher_file_size), value = state.bootFileSize)
                }
            }
        }

        if (firmwarePhase is FirmwarePhase.Failed) {
            AlertBanner(
                message = firmwarePhase.message,
                iconContentDescription = stringResource(R.string.cd_error),
                tone = AlertTone.Error
            )
        }

        state.imageWarning?.let { warningMsg ->
            AlertBanner(
                message = warningMsg,
                iconContentDescription = stringResource(R.string.cd_warning),
                tone = AlertTone.Info
            )
        }
    }
}
