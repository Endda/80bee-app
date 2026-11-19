package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager

@Composable
fun DeviceDetectionScreen(
    shizukuManager: ShizukuConnectionManager,
    otgManager: OtgConnectionManager,
    onRequestShizukuPermission: () -> Unit,
    onConnect: (ConnectionManager) -> Unit
) {
    val isShizukuAvailable by shizukuManager.isAvailableFlow.collectAsState()
    val isOtgAvailable by otgManager.isAvailableFlow.collectAsState()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.device_connections_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isShizukuAvailable) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.connection_shizuku_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (isShizukuAvailable) R.string.connection_shizuku_ready
                            else R.string.connection_shizuku_not_ready
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (!isShizukuAvailable) {
                        Button(onClick = onRequestShizukuPermission) {
                            Text(stringResource(R.string.action_request_permission))
                        }
                    } else {
                        Button(onClick = { onConnect(shizukuManager) }) {
                            Text(stringResource(R.string.action_open_dashboard))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOtgAvailable) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.connection_otg_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (isOtgAvailable) R.string.connection_otg_ready
                            else R.string.connection_otg_waiting
                        )
                    )
                    if (isOtgAvailable) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { onConnect(otgManager) }) {
                            Text(stringResource(R.string.action_open_dashboard))
                        }
                    }
                }
            }
        }
    }
}
