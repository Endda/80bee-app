package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.eightbee.app.ui.debloater.DebloaterActionBar
import com.eightbee.app.ui.debloater.DebloaterConfirmDialog
import com.eightbee.app.ui.debloater.PackageRowItem
import com.eightbee.app.ui.debloater.PackageScanTab
import com.eightbee.app.ui.debloater.RestoreControlsTab
import com.eightbee.app.ui.debloater.toDisplayItem

private enum class DebloaterTab(@param:StringRes val labelRes: Int) {
    General(R.string.debloater_tab_bloat),
    Google(R.string.debloater_tab_google),
    Samsung(R.string.debloater_tab_samsung),
    Restore(R.string.debloater_tab_restore)
}

private val SAFETY_LEVELS = listOf("recommended", "advanced", "unsafe")
private val PRESET_TIERS = listOf(1, 2, 3)

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

    var selectedTab by remember { mutableStateOf(DebloaterTab.General) }
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
            selectedTab = DebloaterTab.Restore
        }
    }

    val activeItems = remember(selectedTab, debloatList, degoogleList, desamsungList, restoreList, resources) {
        when (selectedTab) {
            DebloaterTab.General -> debloatList.map { it.toDisplayItem(resources) }
            DebloaterTab.Google -> degoogleList.map { it.toDisplayItem() }
            DebloaterTab.Samsung -> desamsungList.map { it.toDisplayItem() }
            DebloaterTab.Restore -> restoreList.map { it.toDisplayItem(includeRisk = false) }
        }
    }
    val activeByPkg = remember(activeItems) { activeItems.associateBy { it.pkg } }
    val isRestoreTab = selectedTab == DebloaterTab.Restore
    val visibleItems = remember(activeItems, disabledPackages, hideDisabled, isRestoreTab) {
        if (!isRestoreTab && hideDisabled) {
            activeItems.filterNot { it.pkg in disabledPackages }
        } else {
            activeItems
        }
    }
    val selectedPackages = visibleItems.mapNotNull { item ->
        item.pkg.takeIf { checkedPackages[item.pkg] == true }
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
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    DebloaterTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            item(key = "tab_controls_${selectedTab.ordinal}") {
                when (selectedTab) {
                    DebloaterTab.General -> PackageScanTab(
                        titleRes = R.string.debloater_general_title,
                        descriptionRes = R.string.debloater_general_desc,
                        scanButtonTextRes = R.string.debloater_scan_bloat,
                        options = SAFETY_LEVELS,
                        selectedOption = safetyLevel,
                        optionLabel = { it.uppercase() },
                        onSelectOption = { safetyLevel = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanBloatware(safetyLevel)
                        }
                    )
                    DebloaterTab.Google -> PackageScanTab(
                        titleRes = R.string.debloater_degoogle_title,
                        descriptionRes = R.string.debloater_degoogle_desc,
                        scanButtonTextRes = R.string.debloater_scan_google,
                        options = PRESET_TIERS,
                        selectedOption = degoogleTier,
                        optionLabel = { stringResource(R.string.debloater_tier_format, it) },
                        onSelectOption = { degoogleTier = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanDegoogle(degoogleTier)
                        }
                    )
                    DebloaterTab.Samsung -> PackageScanTab(
                        titleRes = R.string.debloater_desamsung_title,
                        descriptionRes = R.string.debloater_desamsung_desc,
                        scanButtonTextRes = R.string.debloater_scan_samsung,
                        options = PRESET_TIERS,
                        selectedOption = desamsungTier,
                        optionLabel = { stringResource(R.string.debloater_tier_format, it) },
                        onSelectOption = { desamsungTier = it },
                        onScan = {
                            checkedPackages.clear()
                            controller.scanDesamsung(desamsungTier)
                        }
                    )
                    DebloaterTab.Restore -> RestoreControlsTab(
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
                    key = { item -> "${selectedTab.ordinal}:${item.pkg}" }
                ) { item ->
                    PackageRowItem(
                        item = item,
                        isDisabled = !isRestoreTab && item.pkg in disabledPackages,
                        checked = checkedPackages[item.pkg] ?: false,
                        onCheckedChange = { checkedPackages[item.pkg] = it }
                    )
                }
            }
        }

        if (visibleItems.isNotEmpty()) {
            DebloaterActionBar(
                isRestoreTab = isRestoreTab,
                selectedPackages = selectedPackages,
                disabledPackages = disabledPackages,
                activeByPkg = activeByPkg,
                isLocalConnection = isLocalConnection,
                onRestorePackages = controller::restorePackages,
                onDisableOrUninstall = controller::disablePackages,
                onRequestConfirm = { pendingConfirmUninstall = it }
            )
        }
    }

    pendingConfirmUninstall?.let { confirmIsUninstall ->
        DebloaterConfirmDialog(
            confirmIsUninstall = confirmIsUninstall,
            selectedPackages = selectedPackages,
            activeByPkg = activeByPkg,
            isLocalConnection = isLocalConnection,
            onConfirm = {
                pendingConfirmUninstall = null
                controller.disablePackages(selectedPackages, confirmIsUninstall)
            },
            onDismiss = { pendingConfirmUninstall = null }
        )
    }
}
