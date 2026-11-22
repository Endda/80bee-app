package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.BluetoothOem
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun BluetoothRepairScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    var selectedOem by remember { mutableStateOf(BluetoothOem.AOSP) }
    var expanded by remember { mutableStateOf(false) }

    ToolScreenScaffold(
        title = stringResource(R.string.bt_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.bt_card_title),
            description = stringResource(R.string.bt_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        ToolSectionHeader(
            stringResource(R.string.bt_oem_header),
            stringResource(R.string.bt_oem_desc)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_oem_profile_format, selectedOem.name))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                BluetoothOem.entries.forEach { oem ->
                    DropdownMenuItem(
                        text = { Text(oem.name) },
                        onClick = {
                            selectedOem = oem
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        ToolSectionHeader(stringResource(R.string.bt_routines_header))
        Spacer(modifier = Modifier.height(12.dp))

        ToolActionList(
            listOf(
                ToolAction(
                    R.string.bt_standard_title,
                    R.string.bt_standard_desc,
                    R.string.bt_standard_button,
                    onClick = { controller.executeBluetoothRepair(selectedOem, false) }
                ),
                ToolAction(
                    R.string.bt_advanced_title,
                    R.string.bt_advanced_desc,
                    R.string.bt_advanced_button,
                    ActionEmphasis.Tonal,
                    onClick = { controller.executeBluetoothRepair(selectedOem, true) }
                )
            )
        )

        val quirk = selectedOem.quirk
        if (quirk != null) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            ToolSectionHeader(stringResource(R.string.bt_quirks_header))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(quirk.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { controller.applyBluetoothQuirk(selectedOem) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(quirk.buttonRes))
            }
        }
    }
}
