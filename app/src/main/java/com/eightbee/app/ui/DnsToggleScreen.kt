package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.DeviceTweaksController

@Composable
fun DnsToggleScreen(controller: DeviceTweaksController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    var customDnsHost by remember { mutableStateOf("") }

    ToolScreenScaffold(
        title = stringResource(R.string.dns_title),
        onBack = onBack
    ) {
        ToolIntroCard(
            title = stringResource(R.string.dns_card_title),
            description = stringResource(R.string.dns_card_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))

        val currentModeLabel = when (settings.privateDnsMode) {
            "off" -> stringResource(R.string.dns_mode_off)
            "opportunistic" -> stringResource(R.string.dns_mode_auto)
            "hostname" -> stringResource(R.string.dns_mode_hostname_format, settings.privateDnsHostname)
            else -> stringResource(R.string.dns_mode_unknown)
        }
        ToolSectionHeader(
            stringResource(R.string.dns_current_mode_header),
            currentModeLabel,
            subtitleColor = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        ToolSectionHeader(stringResource(R.string.dns_presets_header))
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { controller.setPrivateDnsOff() },
                modifier = Modifier.weight(1f),
                enabled = settings.privateDnsMode != "off"
            ) {
                Text(stringResource(R.string.dns_turn_off))
            }
            Button(
                onClick = { controller.setPrivateDnsAuto() },
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
                onClick = { controller.setPrivateDnsHostname("dns.adguard.com") },
                modifier = Modifier.weight(1f),
                enabled = settings.privateDnsHostname != "dns.adguard.com" || settings.privateDnsMode != "hostname"
            ) {
                Text(stringResource(R.string.dns_adguard))
            }
            FilledTonalButton(
                onClick = { controller.setPrivateDnsHostname("1dot1dot1dot1.cloudflare-dns.com") },
                modifier = Modifier.weight(1f),
                enabled = settings.privateDnsHostname != "1dot1dot1dot1.cloudflare-dns.com" || settings.privateDnsMode != "hostname"
            ) {
                Text(stringResource(R.string.dns_cloudflare))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        ToolSectionHeader(
            stringResource(R.string.dns_custom_header),
            stringResource(R.string.dns_custom_desc)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = customDnsHost,
            onValueChange = { customDnsHost = it },
            label = { Text(stringResource(R.string.dns_custom_label)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        val hostIsValid = DeviceTweaksController.isValidPrivateDnsHost(customDnsHost)
        Button(
            onClick = { controller.setPrivateDnsHostname(customDnsHost) },
            modifier = Modifier.fillMaxWidth(),
            enabled = hostIsValid
        ) {
            Text(stringResource(R.string.dns_apply_custom))
        }
    }
}
