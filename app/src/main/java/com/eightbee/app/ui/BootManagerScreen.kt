package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.BootMode
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun BootManagerScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    ToolScreenScaffold(
        title = stringResource(R.string.boot_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.boot_card_title),
            description = stringResource(R.string.boot_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        ToolSectionHeader(stringResource(R.string.boot_actions_header))
        Spacer(modifier = Modifier.height(12.dp))

        ToolActionList(
            actions = BootMode.entries.map { mode -> mode.toolAction { controller.rebootDevice(mode) } },
            dividerSpacing = 12.dp
        )
    }
}

private fun BootMode.toolAction(onClick: () -> Unit): ToolAction = when (this) {
    BootMode.SYSTEM -> ToolAction(
        R.string.boot_system_title, R.string.boot_system_desc, R.string.boot_system_button, onClick = onClick
    )
    BootMode.RECOVERY -> ToolAction(
        R.string.boot_recovery_title, R.string.boot_recovery_desc, R.string.boot_recovery_button, onClick = onClick
    )
    BootMode.BOOTLOADER -> ToolAction(
        R.string.boot_bootloader_title, R.string.boot_bootloader_desc, R.string.boot_bootloader_button, onClick = onClick
    )
    BootMode.FASTBOOTD -> ToolAction(
        R.string.boot_fastbootd_title, R.string.boot_fastbootd_desc, R.string.boot_fastbootd_button, onClick = onClick
    )
}
