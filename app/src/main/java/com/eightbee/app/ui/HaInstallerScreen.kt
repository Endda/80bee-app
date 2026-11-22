package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun HaInstallerScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    ToolScreenScaffold(
        title = stringResource(R.string.ha_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.ha_card_title),
            description = stringResource(R.string.ha_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        ToolSectionHeader(stringResource(R.string.ha_wizard_header))
        Spacer(modifier = Modifier.height(12.dp))

        ToolActionList(
            listOf(
                ToolAction(
                    R.string.ha_step1_title,
                    R.string.ha_step1_desc,
                    R.string.ha_step1_button,
                    onClick = { controller.prepareHaSystem() }
                ),
                ToolAction(
                    R.string.ha_step2_title,
                    R.string.ha_step2_desc,
                    R.string.ha_step2_button,
                    ActionEmphasis.Tonal,
                    onClick = { controller.deployHomeAssistant() }
                )
            )
        )
    }
}
