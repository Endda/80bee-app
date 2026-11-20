package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun AudioModsScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    ToolScreenScaffold(
        title = stringResource(R.string.audio_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.audio_csd_title),
            description = stringResource(R.string.audio_csd_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.audio_actions_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.audio_bypass_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.audio_bypass_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { controller.executeAudioBypass() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.audio_bypass_button))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.audio_restore_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.audio_restore_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { controller.restoreAudioDefaults() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.audio_restore_button))
            }
        }
    }
}
