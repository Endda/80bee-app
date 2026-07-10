package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsToggleScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var customDnsHost by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Private DNS Manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            // Description card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Secure DNS (Private DNS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Encrypted DNS requests prevent your ISP and third parties from tracking what websites you visit. Toggling secure resolvers block ads, phishing trackers, and improve networking speeds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Current DNS Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            val currentModeLabel = when(settings.privateDnsMode) {
                "off" -> "Disabled (Using network defaults)"
                "opportunistic" -> "Automatic (Opportunistic TLS encryption)"
                "hostname" -> "Custom Hostname (${settings.privateDnsHostname})"
                else -> "Unknown State"
            }
            Text(currentModeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            Spacer(modifier = Modifier.height(16.dp))

            // Presets
            Text("Presets Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.executeCommand("settings put global private_dns_mode off") },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "off"
                ) {
                    Text("Turn Off")
                }
                Button(
                    onClick = { viewModel.executeCommand("settings put global private_dns_mode opportunistic") },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "opportunistic"
                ) {
                    Text("Auto Mode")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        viewModel.executeCommand("settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.adguard.com")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "dns.adguard.com" || settings.privateDnsMode != "hostname"
                ) {
                    Text("AdGuard (Blocks Ads)")
                }
                FilledTonalButton(
                    onClick = {
                        viewModel.executeCommand("settings put global private_dns_mode hostname; settings put global private_dns_specifier 1dot1dot1dot1.cloudflare-dns.com")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "1dot1dot1dot1.cloudflare-dns.com" || settings.privateDnsMode != "hostname"
                ) {
                    Text("Cloudflare DNS")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Custom Private DNS Host", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Supply any TLS-capable DNS provider URL endpoint (DoT hostname).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = customDnsHost,
                onValueChange = { customDnsHost = it },
                label = { Text("Private DNS Hostname (e.g. dns.quad9.net)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (customDnsHost.isNotEmpty()) {
                        viewModel.executeCommand("settings put global private_dns_mode hostname; settings put global private_dns_specifier $customDnsHost")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = customDnsHost.isNotEmpty()
            ) {
                Text("Apply Custom Hostname")
            }
        }
    }
}
