package com.eightbee.app.ui

import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Device Connections",
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
                Text(text = "Local (Shizuku)", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = if (isShizukuAvailable) "Connected & Authorized" else "Not Authorized or Not Running")
                Spacer(modifier = Modifier.height(16.dp))
                if (!isShizukuAvailable) {
                    Button(onClick = {
                        onRequestShizukuPermission()
                    }) {
                        Text("Request Permission")
                    }
                } else {
                    Button(onClick = { onConnect(shizukuManager) }) {
                        Text("Open Dashboard")
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
                Text(text = "OTG (USB Host)", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = if (isOtgAvailable) "Device Connected" else "Waiting for USB Device...")
                if (isOtgAvailable) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { onConnect(otgManager) }) {
                        Text("Open Dashboard")
                    }
                }
            }
        }
    }
}