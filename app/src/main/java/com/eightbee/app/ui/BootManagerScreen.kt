package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BootMode
import com.eightbee.app.MainViewModel
import com.eightbee.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootManagerScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boot_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.boot_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.boot_card_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                onClick = { viewModel.rebootDevice(BootMode.SYSTEM) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = stringResource(R.string.boot_recovery_title),
                description = stringResource(R.string.boot_recovery_desc),
                buttonText = stringResource(R.string.boot_recovery_button),
                onClick = { viewModel.rebootDevice(BootMode.RECOVERY) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = stringResource(R.string.boot_bootloader_title),
                description = stringResource(R.string.boot_bootloader_desc),
                buttonText = stringResource(R.string.boot_bootloader_button),
                onClick = { viewModel.rebootDevice(BootMode.BOOTLOADER) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            BootActionRow(
                title = stringResource(R.string.boot_fastbootd_title),
                description = stringResource(R.string.boot_fastbootd_desc),
                buttonText = stringResource(R.string.boot_fastbootd_button),
                onClick = { viewModel.rebootDevice(BootMode.FASTBOOTD) }
            )
        }
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
