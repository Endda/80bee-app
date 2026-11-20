package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun BluetoothRepairScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    var selectedOem by remember { mutableStateOf("aosp") }
    var expanded by remember { mutableStateOf(false) }
    val oemList = listOf("aosp", "samsung", "xiaomi", "oneplus", "motorola")

    ToolScreenScaffold(
        title = stringResource(R.string.bt_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.bt_card_title),
            description = stringResource(R.string.bt_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.bt_oem_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.bt_oem_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_oem_profile_format, selectedOem.uppercase()))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                oemList.forEach { oem ->
                    DropdownMenuItem(
                        text = { Text(oem.uppercase()) },
                        onClick = {
                            selectedOem = oem
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            stringResource(R.string.bt_routines_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bt_standard_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.bt_standard_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { controller.executeBluetoothRepair(selectedOem, false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.bt_standard_button))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bt_advanced_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.bt_advanced_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { controller.executeBluetoothRepair(selectedOem, true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.bt_advanced_button))
            }
        }

        if (selectedOem == "xiaomi" || selectedOem == "oneplus") {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.bt_quirks_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (selectedOem == "xiaomi") {
                Text(
                    text = stringResource(R.string.bt_xiaomi_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { controller.applyBluetoothQuirk("xiaomi") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.bt_xiaomi_button))
                }
            }

            if (selectedOem == "oneplus") {
                Text(
                    text = stringResource(R.string.bt_oneplus_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { controller.applyBluetoothQuirk("oneplus") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.bt_oneplus_button))
                }
            }
        }
    }
}
