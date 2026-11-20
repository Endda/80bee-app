package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.features.DebloaterController

private data class PackageDisplayItem(
    val pkg: String,
    val label: String,
    val description: String?,
    val risk: String?
)

@Composable
fun DebloaterScreen(
    controller: DebloaterController,
    activeConnection: ConnectionManager?,
    onBack: () -> Unit
) {
    val resources = LocalResources.current
    val debloatList by controller.debloatList.collectAsState()
    val degoogleList by controller.degoogleList.collectAsState()
    val desamsungList by controller.desamsungList.collectAsState()
    val restoreList by controller.restoreList.collectAsState()
    val disabledPackages by controller.disabledPackages.collectAsState()
    val snapshotFeedback by controller.snapshotFeedback.collectAsState()
    val isLocalConnection = activeConnection?.isLocal == true

    var selectedTab by remember { mutableIntStateOf(0) } // 0: General, 1: De-Google, 2: De-Samsung, 3: Restore
    var safetyLevel by remember { mutableStateOf("recommended") }
    var degoogleTier by remember { mutableIntStateOf(1) }
    var desamsungTier by remember { mutableIntStateOf(1) }
    var hideDisabled by remember { mutableStateOf(false) }

    val checkedPackages = remember { mutableStateMapOf<String, Boolean>() }
    var pendingConfirmUninstall by remember { mutableStateOf<Boolean?>(null) }

    val exportSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { controller.exportSnapshotToUri(it) }
    }

    val importSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            checkedPackages.clear()
            controller.importSnapshotFromUri(it)
            selectedTab = 3
        }
    }

    val bloatItems = remember(debloatList, resources) {
        debloatList.map { item ->
            val riskText = if (item.safety.equals("recommended", ignoreCase = true) || item.safety.isBlank()) {
                null
            } else {
                resources.getString(R.string.debloater_safety_warning_format, item.safety)
            }
            PackageDisplayItem(
                pkg = item.packageName,
                label = item.label,
                description = item.description.takeIf { it.isNotBlank() },
                risk = riskText
            )
        }
    }

    val degoogleItems = remember(degoogleList) {
        degoogleList.map { preset ->
            PackageDisplayItem(
                pkg = preset.id,
                label = preset.name,
                description = preset.description.takeIf { it.isNotBlank() },
                risk = preset.riskFactor.takeIf { it.isNotBlank() }
            )
        }
    }

    val desamsungItems = remember(desamsungList) {
        desamsungList.map { preset ->
            PackageDisplayItem(
                pkg = preset.id,
                label = preset.name,
                description = preset.description.takeIf { it.isNotBlank() },
                risk = preset.riskFactor.takeIf { it.isNotBlank() }
            )
        }
    }

    val restoreItems = remember(restoreList) {
        restoreList.map { preset ->
            PackageDisplayItem(
                pkg = preset.id,
                label = preset.name,
                description = preset.description.takeIf { it.isNotBlank() },
                risk = null
            )
        }
    }

    val activeItems = when (selectedTab) {
        0 -> bloatItems
        1 -> degoogleItems
        2 -> desamsungItems
        else -> restoreItems
    }
    val activeByPkg = remember(activeItems) { activeItems.associateBy { it.pkg } }
    val isRestoreTab = selectedTab == 3
    val visibleItems = remember(activeItems, disabledPackages, hideDisabled, isRestoreTab) {
        if (!isRestoreTab && hideDisabled) {
            activeItems.filterNot { it.pkg in disabledPackages }
        } else {
            activeItems
        }
    }
    var allChecked by remember(selectedTab, visibleItems) { mutableStateOf(false) }

    ToolScreenScaffold(
        title = stringResource(R.string.debloater_title),
        onBack = onBack,
        scrollable = false
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header_card") {
                ToolIntroCard(
                    title = stringResource(R.string.debloater_card_title),
                    description = stringResource(R.string.debloater_card_desc)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "tab_row") {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.debloater_tab_bloat)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.debloater_tab_google)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.debloater_tab_samsung)) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text(stringResource(R.string.debloater_tab_restore)) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            item(key = "tab_controls_$selectedTab") {
                when (selectedTab) {
                    0 -> GeneralBloatScanTab(
                        safetyLevel = safetyLevel,
                        onSelectSafetyLevel = { safetyLevel = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanBloatware(safetyLevel)
                        }
                    )
                    1 -> TierScanTab(
                        title = stringResource(R.string.debloater_degoogle_title),
                        description = stringResource(R.string.debloater_degoogle_desc),
                        scanButtonText = stringResource(R.string.debloater_scan_google),
                        selectedTier = degoogleTier,
                        onSelectTier = { degoogleTier = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanDegoogle(degoogleTier)
                        }
                    )
                    2 -> TierScanTab(
                        title = stringResource(R.string.debloater_desamsung_title),
                        description = stringResource(R.string.debloater_desamsung_desc),
                        scanButtonText = stringResource(R.string.debloater_scan_samsung),
                        selectedTier = desamsungTier,
                        onSelectTier = { desamsungTier = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanDesamsung(desamsungTier)
                        }
                    )
                    else -> RestoreControlsTab(
                        snapshotFeedback = snapshotFeedback,
                        onExportSnapshot = {
                            exportSnapshotLauncher.launch("80bee_snapshot_${System.currentTimeMillis()}.json")
                        },
                        onImportSnapshot = {
                            importSnapshotLauncher.launch("application/json")
                        },
                        onScanDisabled = {
                            checkedPackages.clear()
                            controller.scanDisabledPackages()
                        }
                    )
                }
            }

            if (activeItems.isNotEmpty()) {
                item(key = "list_header") {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isRestoreTab) {
                        Text(
                            text = stringResource(R.string.debloater_apps_to_restore_format, activeItems.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                allChecked = !allChecked
                                visibleItems.forEach { checkedPackages[it.pkg] = allChecked }
                            },
                            enabled = visibleItems.isNotEmpty()
                        ) {
                            Text(
                                stringResource(
                                    if (allChecked) R.string.debloater_deselect_all
                                    else R.string.debloater_select_all
                                )
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isRestoreTab) {
                                FilterChip(
                                    selected = hideDisabled,
                                    onClick = { hideDisabled = !hideDisabled },
                                    label = { Text(stringResource(R.string.debloater_hide_disabled)) }
                                )
                            }
                            Text(
                                text = pluralStringResource(
                                    R.plurals.debloater_package_count,
                                    visibleItems.size,
                                    visibleItems.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (visibleItems.isEmpty() && !isRestoreTab && hideDisabled) {
                    item(key = "all_hidden") {
                        Text(
                            text = stringResource(R.string.debloater_all_hidden_by_filter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }

                items(
                    items = visibleItems,
                    key = { item -> "$selectedTab:${item.pkg}" }
                ) { item ->
                    PackageRowItem(
                        pkg = item.pkg,
                        label = item.label,
                        description = item.description,
                        risk = item.risk,
                        isDisabled = !isRestoreTab && item.pkg in disabledPackages,
                        checked = checkedPackages[item.pkg] ?: false,
                        onCheckedChange = { checkedPackages[item.pkg] = it }
                    )
                }
            }
        }

        if (visibleItems.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (isRestoreTab) {
                        Button(
                            onClick = {
                                val selected = visibleItems.map { it.pkg }.filter { checkedPackages[it] == true }
                                if (selected.isNotEmpty()) {
                                    controller.restorePackages(selected)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.debloater_cd_restore))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.debloater_restore_selected))
                        }
                    } else {
                        val selected = visibleItems.map { it.pkg }.filter { checkedPackages[it] == true }
                        val disabledSelected = selected.filter { it in disabledPackages }
                        val triggerAction = { isUninstall: Boolean ->
                            if (selected.isNotEmpty()) {
                                val hasRisky = selected.any { !activeByPkg[it]?.risk.isNullOrBlank() }
                                if (hasRisky || (isLocalConnection && isUninstall)) {
                                    pendingConfirmUninstall = isUninstall
                                } else {
                                    controller.disablePackages(selected, isUninstall)
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (disabledSelected.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { controller.restorePackages(disabledSelected) },
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
    }

    val confirmIsUninstall = pendingConfirmUninstall
    if (confirmIsUninstall != null) {
        val selected = visibleItems.map { it.pkg }.filter { checkedPackages[it] == true }
        val riskySelected = selected.mapNotNull { pkg ->
            activeByPkg[pkg]?.risk?.let { risk -> pkg to risk }
        }
        AlertDialog(
            onDismissRequest = { pendingConfirmUninstall = null },
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
                                selected.size,
                                selected.size,
                                riskySelected.size
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.debloater_selected_count,
                                selected.size,
                                selected.size
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
                    onClick = {
                        pendingConfirmUninstall = null
                        controller.disablePackages(selected, confirmIsUninstall)
                    },
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
                TextButton(onClick = { pendingConfirmUninstall = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun GeneralBloatScanTab(
    safetyLevel: String,
    onSelectSafetyLevel: (String) -> Unit,
    onScan: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.debloater_general_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.debloater_general_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("recommended", "advanced", "unsafe").forEach { level ->
                FilterChip(
                    selected = safetyLevel == level,
                    onClick = { onSelectSafetyLevel(level) },
                    label = { Text(level.uppercase()) }
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
            Text(stringResource(R.string.debloater_scan_bloat))
        }
    }
}

@Composable
private fun TierScanTab(
    title: String,
    description: String,
    scanButtonText: String,
    selectedTier: Int,
    onSelectTier: (Int) -> Unit,
    onScan: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(1, 2, 3).forEach { tier ->
                FilterChip(
                    selected = selectedTier == tier,
                    onClick = { onSelectTier(tier) },
                    label = { Text(stringResource(R.string.debloater_tier_format, tier)) }
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
            Text(scanButtonText)
        }
    }
}

@Composable
private fun RestoreControlsTab(
    snapshotFeedback: String?,
    onExportSnapshot: () -> Unit,
    onImportSnapshot: () -> Unit,
    onScanDisabled: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.debloater_restore_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.debloater_restore_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.debloater_create_snapshot_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.debloater_create_snapshot_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onExportSnapshot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.debloater_cd_create_snapshot))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.debloater_save_snapshot))
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.debloater_restore_snapshot_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.debloater_restore_snapshot_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onImportSnapshot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.debloater_cd_upload_snapshot))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.debloater_upload_backup))
                }
                snapshotFeedback?.let { feedback ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (feedback.startsWith("Failed") || feedback.contains("Ignored")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.debloater_find_disabled_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.debloater_find_disabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onScanDisabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.debloater_cd_scan_disabled))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.debloater_scan_disabled))
                }
            }
        }
    }
}

@Composable
private fun PackageRowItem(
    pkg: String,
    label: String,
    description: String?,
    risk: String?,
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
                    text = label,
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
            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (risk != null) {
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
                        text = risk,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
