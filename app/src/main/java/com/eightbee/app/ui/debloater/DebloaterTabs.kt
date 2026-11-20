package com.eightbee.app.ui.debloater

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.features.SnapshotFeedback

@Composable
internal fun <T> PackageScanTab(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes scanButtonTextRes: Int,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onSelectOption: (T) -> Unit,
    onScan: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selectedOption == option,
                    onClick = { onSelectOption(option) },
                    label = { Text(optionLabel(option)) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.debloater_cd_scan))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(scanButtonTextRes))
        }
    }
}

@Composable
internal fun RestoreControlsTab(
    snapshotFeedback: SnapshotFeedback?,
    onExportSnapshot: () -> Unit,
    onImportSnapshot: () -> Unit,
    onScanDisabled: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.debloater_restore_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.debloater_restore_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        RestoreActionCard(
            titleRes = R.string.debloater_create_snapshot_title,
            descriptionRes = R.string.debloater_create_snapshot_desc,
            buttonTextRes = R.string.debloater_save_snapshot,
            buttonContentDescRes = R.string.debloater_cd_create_snapshot,
            buttonIcon = Icons.Filled.Add,
            onClick = onExportSnapshot
        )

        RestoreActionCard(
            titleRes = R.string.debloater_restore_snapshot_title,
            descriptionRes = R.string.debloater_restore_snapshot_desc,
            buttonTextRes = R.string.debloater_upload_backup,
            buttonContentDescRes = R.string.debloater_cd_upload_snapshot,
            buttonIcon = Icons.Filled.KeyboardArrowUp,
            onClick = onImportSnapshot,
            tonalButton = true
        ) {
            snapshotFeedback?.let { feedback ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = feedback.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedback.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        RestoreActionCard(
            titleRes = R.string.debloater_find_disabled_title,
            descriptionRes = R.string.debloater_find_disabled_desc,
            buttonTextRes = R.string.debloater_scan_disabled,
            buttonContentDescRes = R.string.debloater_cd_scan_disabled,
            buttonIcon = Icons.Filled.Search,
            onClick = onScanDisabled
        )
    }
}

@Composable
private fun RestoreActionCard(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes buttonTextRes: Int,
    @StringRes buttonContentDescRes: Int,
    buttonIcon: ImageVector,
    onClick: () -> Unit,
    tonalButton: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            val buttonContent: @Composable () -> Unit = {
                Icon(buttonIcon, contentDescription = stringResource(buttonContentDescRes))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(buttonTextRes))
            }
            if (tonalButton) {
                FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    buttonContent()
                }
            } else {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    buttonContent()
                }
            }
            footer?.invoke(this)
        }
    }
}
