package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.eightbee.app.MainViewModel
import com.eightbee.app.R
import com.eightbee.app.connection.ShizukuConnectionManager

private data class PackageListUiData(
    val packages: List<String>,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    val riskFactors: Map<String, String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloaterScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val resources = LocalResources.current
    val debloatList by viewModel.debloatList.collectAsState()
    val degoogleList by viewModel.degoogleList.collectAsState()
    val desamsungList by viewModel.desamsungList.collectAsState()
    val restoreList by viewModel.restoreList.collectAsState()
    val snapshotFeedback by viewModel.snapshotFeedback.collectAsState()
    val activeConnection by viewModel.activeConnection.collectAsState()
    val isLocalConnection = activeConnection is ShizukuConnectionManager

    var selectedTab by remember { mutableIntStateOf(0) } // 0: General, 1: De-Google, 2: De-Samsung, 3: Restore
    var safetyLevel by remember { mutableStateOf("recommended") }
    var degoogleTier by remember { mutableIntStateOf(1) }
    var desamsungTier by remember { mutableIntStateOf(1) }

    val checkedPackages = remember { mutableStateMapOf<String, Boolean>() }
    var pendingConfirmUninstall by remember { mutableStateOf<Boolean?>(null) }

    val exportSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportPackageSnapshot(it) }
    }

    val importSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            checkedPackages.clear()
            viewModel.importPackageSnapshot(it)
            selectedTab = 3
        }
    }

    val bloatUiData = remember(debloatList, resources) {
        PackageListUiData(
            packages = debloatList.map { it.packageName },
            labels = debloatList.associate { it.packageName to it.label },
            descriptions = debloatList.associate { it.packageName to it.description },
            riskFactors = debloatList.associate {
                it.packageName to if (it.safety.equals("recommended", ignoreCase = true) || it.safety.isBlank()) {
                    ""
                } else {
                    resources.getString(R.string.debloater_safety_warning_format, it.safety)
                }
            }
        )
    }

    val degoogleUiData = remember(degoogleList) {
        PackageListUiData(
            packages = degoogleList.map { it.id },
            labels = degoogleList.associate { it.id to it.name },
            descriptions = degoogleList.associate { it.id to it.description },
            riskFactors = degoogleList.associate { it.id to it.riskFactor }
        )
    }

    val desamsungUiData = remember(desamsungList) {
        PackageListUiData(
            packages = desamsungList.map { it.id },
            labels = desamsungList.associate { it.id to it.name },
            descriptions = desamsungList.associate { it.id to it.description },
            riskFactors = desamsungList.associate { it.id to it.riskFactor }
        )
    }

    val restoreUiData = remember(restoreList) {
        PackageListUiData(
            packages = restoreList.map { it.id },
            labels = restoreList.associate { it.id to it.name },
            descriptions = restoreList.associate { it.id to it.description },
            riskFactors = emptyMap()
        )
    }

    val activeUiData = when (selectedTab) {
        0 -> bloatUiData
        1 -> degoogleUiData
        2 -> desamsungUiData
        else -> restoreUiData
    }
    val isRestoreTab = selectedTab == 3
    var allChecked by remember(selectedTab, activeUiData.packages) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debloater_title)) },
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
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "header_card") {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.debloater_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.debloater_card_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                        0 -> {
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
                                            onClick = { safetyLevel = level },
                                            label = { Text(level.uppercase()) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        checkedPackages.clear()
                                        viewModel.scanBloatware(safetyLevel)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.debloater_cd_scan))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.debloater_scan_bloat))
                                }
                            }
                        }
                        1 -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.debloater_degoogle_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.debloater_degoogle_desc),
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
                                            selected = degoogleTier == tier,
                                            onClick = { degoogleTier = tier },
                                            label = { Text(stringResource(R.string.debloater_tier_format, tier)) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        checkedPackages.clear()
                                        viewModel.scanDegoogle(degoogleTier)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.debloater_cd_scan))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.debloater_scan_google))
                                }
                            }
                        }
                        2 -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.debloater_desamsung_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.debloater_desamsung_desc),
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
                                            selected = desamsungTier == tier,
                                            onClick = { desamsungTier = tier },
                                            label = { Text(stringResource(R.string.debloater_tier_format, tier)) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        checkedPackages.clear()
                                        viewModel.scanDesamsung(desamsungTier)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.debloater_cd_scan))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.debloater_scan_samsung))
                                }
                            }
                        }
                        else -> {
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
                                            onClick = {
                                                exportSnapshotLauncher.launch("80bee_snapshot_${System.currentTimeMillis()}.json")
                                            },
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
                                            onClick = { importSnapshotLauncher.launch("application/json") },
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
                                            onClick = {
                                                checkedPackages.clear()
                                                viewModel.scanDisabledPackages()
                                            },
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
                    }
                }

                if (activeUiData.packages.isNotEmpty()) {
                    item(key = "list_header") {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (isRestoreTab) {
                            Text(
                                text = stringResource(R.string.debloater_apps_to_restore_format, activeUiData.packages.size),
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
                                    activeUiData.packages.forEach { checkedPackages[it] = allChecked }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (allChecked) R.string.debloater_deselect_all
                                        else R.string.debloater_select_all
                                    )
                                )
                            }
                            Text(
                                text = pluralStringResource(
                                    R.plurals.debloater_package_count,
                                    activeUiData.packages.size,
                                    activeUiData.packages.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(
                        items = activeUiData.packages,
                        key = { pkg -> "$selectedTab:$pkg" }
                    ) { pkg ->
                        PackageRowItem(
                            pkg = pkg,
                            label = activeUiData.labels[pkg] ?: pkg,
                            description = activeUiData.descriptions[pkg],
                            risk = activeUiData.riskFactors[pkg]?.takeIf { it.isNotBlank() },
                            checked = checkedPackages[pkg] ?: false,
                            onCheckedChange = { checkedPackages[pkg] = it }
                        )
                    }
                }
            }

            if (activeUiData.packages.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (isRestoreTab) {
                            Button(
                                onClick = {
                                    val selected = activeUiData.packages.filter { checkedPackages[it] == true }
                                    if (selected.isNotEmpty()) {
                                        viewModel.restorePackages(selected)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.debloater_cd_restore))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.debloater_restore_selected))
                            }
                        } else {
                            val triggerAction = { isUninstall: Boolean ->
                                val selected = activeUiData.packages.filter { checkedPackages[it] == true }
                                if (selected.isNotEmpty()) {
                                    val hasRisky = selected.any { !activeUiData.riskFactors[it].isNullOrBlank() }
                                    if (hasRisky || (isLocalConnection && isUninstall)) {
                                        pendingConfirmUninstall = isUninstall
                                    } else {
                                        viewModel.disablePackages(selected, isUninstall)
                                    }
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
        val selected = activeUiData.packages.filter { checkedPackages[it] == true }
        val riskySelected = selected.mapNotNull { pkg ->
            activeUiData.riskFactors[pkg]?.takeIf { it.isNotBlank() }?.let { pkg to it }
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
                                text = activeUiData.labels[pkg] ?: pkg,
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
                        viewModel.disablePackages(selected, confirmIsUninstall)
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
private fun PackageRowItem(
    pkg: String,
    label: String,
    description: String?,
    risk: String?,
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
        Column {
            Text(label, fontWeight = FontWeight.Bold)
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
