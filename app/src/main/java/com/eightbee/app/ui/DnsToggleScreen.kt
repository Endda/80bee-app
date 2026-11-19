package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsToggleScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var customDnsHost by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_title)) },
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
                        text = stringResource(R.string.dns_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dns_card_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                stringResource(R.string.dns_current_mode_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val currentModeLabel = when (settings.privateDnsMode) {
                "off" -> stringResource(R.string.dns_mode_off)
                "opportunistic" -> stringResource(R.string.dns_mode_auto)
                "hostname" -> stringResource(R.string.dns_mode_hostname_format, settings.privateDnsHostname)
                else -> stringResource(R.string.dns_mode_unknown)
            }
            Text(currentModeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.dns_presets_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.setPrivateDnsOff() },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "off"
                ) {
                    Text(stringResource(R.string.dns_turn_off))
                }
                Button(
                    onClick = { viewModel.setPrivateDnsAuto() },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsMode != "opportunistic"
                ) {
                    Text(stringResource(R.string.dns_auto_mode))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.setPrivateDnsHostname("dns.adguard.com") },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "dns.adguard.com" || settings.privateDnsMode != "hostname"
                ) {
                    Text(stringResource(R.string.dns_adguard))
                }
                FilledTonalButton(
                    onClick = { viewModel.setPrivateDnsHostname("1dot1dot1dot1.cloudflare-dns.com") },
                    modifier = Modifier.weight(1f),
                    enabled = settings.privateDnsHostname != "1dot1dot1dot1.cloudflare-dns.com" || settings.privateDnsMode != "hostname"
                ) {
                    Text(stringResource(R.string.dns_cloudflare))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.dns_custom_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.dns_custom_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = customDnsHost,
                onValueChange = { customDnsHost = it },
                label = { Text(stringResource(R.string.dns_custom_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            val hostIsValid = MainViewModel.isValidPrivateDnsHost(customDnsHost)
            Button(
                onClick = { viewModel.setPrivateDnsHostname(customDnsHost) },
                modifier = Modifier.fillMaxWidth(),
                enabled = hostIsValid
            ) {
                Text(stringResource(R.string.dns_apply_custom))
            }
        }
    }
}
