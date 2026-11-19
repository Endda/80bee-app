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
fun UiAdjustmentsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var customDpiText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_adj_title)) },
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
                        text = stringResource(R.string.ui_adj_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ui_adj_card_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                stringResource(R.string.ui_adj_anim_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_adj_anim_state_format, settings.windowAnimationScale.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.setAnimationScale(0.0f) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ui_adj_anim_off))
                }
                Button(
                    onClick = { viewModel.setAnimationScale(0.5f) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ui_adj_anim_fast))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.setAnimationScale(1.0f) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ui_adj_anim_stock))
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.ui_adj_dpi_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_adj_dpi_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val parsedDpi = customDpiText.trim().toIntOrNull()
            val isDpiValid = parsedDpi != null && parsedDpi in 120..800
            val showDpiError = customDpiText.isNotBlank() && !isDpiValid

            OutlinedTextField(
                value = customDpiText,
                onValueChange = { customDpiText = it },
                label = { Text(stringResource(R.string.ui_adj_dpi_label)) },
                isError = showDpiError,
                supportingText = if (showDpiError) {
                    { Text(stringResource(R.string.ui_adj_dpi_error)) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (parsedDpi != null && isDpiValid) {
                            viewModel.setDisplayDensity(parsedDpi)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isDpiValid
                ) {
                    Text(stringResource(R.string.ui_adj_dpi_apply))
                }
                FilledTonalButton(
                    onClick = {
                        viewModel.resetDisplayDensity()
                        customDpiText = ""
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ui_adj_dpi_reset))
                }
            }
        }
    }
}
