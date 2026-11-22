package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

        ToolSectionHeader(stringResource(R.string.audio_actions_header))
        Spacer(modifier = Modifier.height(12.dp))

        ToolActionList(
            actions = listOf(
                ToolAction(
                    R.string.audio_bypass_title,
                    R.string.audio_bypass_desc,
                    R.string.audio_bypass_button,
                    onClick = { controller.executeAudioBypass() }
                ),
                ToolAction(
                    R.string.audio_restore_title,
                    R.string.audio_restore_desc,
                    R.string.audio_restore_button,
                    ActionEmphasis.Tonal,
                    onClick = { controller.restoreAudioDefaults() }
                )
            ),
            buttonSpacing = 12.dp
        )
    }
}
