package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BootMode
import com.eightbee.app.R
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

        Text(
            stringResource(R.string.boot_actions_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        BootActionRow(
            title = stringResource(R.string.boot_system_title),
            description = stringResource(R.string.boot_system_desc),
            buttonText = stringResource(R.string.boot_system_button),
            onClick = { controller.rebootDevice(BootMode.SYSTEM) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        BootActionRow(
            title = stringResource(R.string.boot_recovery_title),
            description = stringResource(R.string.boot_recovery_desc),
            buttonText = stringResource(R.string.boot_recovery_button),
            onClick = { controller.rebootDevice(BootMode.RECOVERY) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        BootActionRow(
            title = stringResource(R.string.boot_bootloader_title),
            description = stringResource(R.string.boot_bootloader_desc),
            buttonText = stringResource(R.string.boot_bootloader_button),
            onClick = { controller.rebootDevice(BootMode.BOOTLOADER) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        BootActionRow(
            title = stringResource(R.string.boot_fastbootd_title),
            description = stringResource(R.string.boot_fastbootd_desc),
            buttonText = stringResource(R.string.boot_fastbootd_button),
            onClick = { controller.rebootDevice(BootMode.FASTBOOTD) }
        )
    }
}

@Composable
fun BootActionRow(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonText)
        }
    }
}
