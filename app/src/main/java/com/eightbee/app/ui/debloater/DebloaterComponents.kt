package com.eightbee.app.ui.debloater

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BloatInfo
import com.eightbee.app.PresetPkg
import com.eightbee.app.R

internal data class PackageDisplayItem(
    val pkg: String,
    val label: String,
    val description: String?,
    val risk: String?
)

internal fun BloatInfo.toDisplayItem(resources: Resources): PackageDisplayItem {
    val riskText = if (safety.equals("recommended", ignoreCase = true) || safety.isBlank()) {
        null
    } else {
        resources.getString(R.string.debloater_safety_warning_format, safety)
    }
    return PackageDisplayItem(
        pkg = packageName,
        label = label,
        description = description.takeIf { it.isNotBlank() },
        risk = riskText
    )
}

internal fun PresetPkg.toDisplayItem(includeRisk: Boolean = true): PackageDisplayItem = PackageDisplayItem(
    pkg = id,
    label = name,
    description = description.takeIf { it.isNotBlank() },
    risk = if (includeRisk) riskFactor.takeIf { it.isNotBlank() } else null
)

@Composable
internal fun DebloaterActionBar(
    isRestoreTab: Boolean,
    selectedPackages: List<String>,
    disabledPackages: Set<String>,
    activeByPkg: Map<String, PackageDisplayItem>,
    isLocalConnection: Boolean,
    onRestorePackages: (List<String>) -> Unit,
    onDisableOrUninstall: (List<String>, Boolean) -> Unit,
    onRequestConfirm: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (isRestoreTab) {
                Button(
                    onClick = {
                        if (selectedPackages.isNotEmpty()) {
                            onRestorePackages(selectedPackages)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.debloater_cd_restore))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.debloater_restore_selected))
                }
            } else {
                val disabledSelected = selectedPackages.filter { it in disabledPackages }
                val triggerAction = { isUninstall: Boolean ->
                    if (selectedPackages.isNotEmpty()) {
                        val hasRisky = selectedPackages.any { !activeByPkg[it]?.risk.isNullOrBlank() }
                        if (hasRisky || (isLocalConnection && isUninstall)) {
                            onRequestConfirm(isUninstall)
                        } else {
                            onDisableOrUninstall(selectedPackages, isUninstall)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (disabledSelected.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { onRestorePackages(disabledSelected) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.debloater_cd_restore))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.debloater_restore_disabled_format, disabledSelected.size))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { triggerAction(false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.debloater_cd_disable))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.debloater_disable))
                        }
                        Button(
                            onClick = { triggerAction(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.debloater_cd_uninstall))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.debloater_uninstall))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DebloaterConfirmDialog(
    confirmIsUninstall: Boolean,
    selectedPackages: List<String>,
    activeByPkg: Map<String, PackageDisplayItem>,
    isLocalConnection: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val riskySelected = selectedPackages.mapNotNull { pkg ->
        activeByPkg[pkg]?.risk?.let { risk -> pkg to risk }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                stringResource(
                    if (confirmIsUninstall) R.string.debloater_confirm_uninstall_title
                    else R.string.debloater_confirm_disable_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLocalConnection) {
                    Text(
                        text = stringResource(R.string.debloater_shizuku_lockout_warning),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = if (riskySelected.isNotEmpty()) {
                        pluralStringResource(
                            R.plurals.debloater_selected_risky_count,
                            selectedPackages.size,
                            selectedPackages.size,
                            riskySelected.size
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.debloater_selected_count,
                            selectedPackages.size,
                            selectedPackages.size
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                riskySelected.forEach { (pkg, risk) ->
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = activeByPkg[pkg]?.label ?: pkg,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = risk,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(
                    stringResource(
                        if (confirmIsUninstall) R.string.debloater_uninstall_selected
                        else R.string.debloater_disable_selected
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun PackageRowItem(
    item: PackageDisplayItem,
    isDisabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.label,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isDisabled) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = stringResource(R.string.debloater_badge_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(item.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!item.description.isNullOrBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
            if (item.risk != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.debloater_cd_risk_warning),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.risk,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
