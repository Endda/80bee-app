package com.eightbee.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.BloatInfo
import com.eightbee.app.MainViewModel
import com.eightbee.app.PackageDatabase
import com.eightbee.app.PresetPkg
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloaterScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val debloatList by viewModel.debloatList.collectAsState()
    val degoogleList by viewModel.degoogleList.collectAsState()
    val desamsungList by viewModel.desamsungList.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: General, 1: De-Google, 2: De-Samsung, 3: Snapshot/Restore
    var safetyLevel by remember { mutableStateOf("recommended") }
    var degoogleTier by remember { mutableIntStateOf(1) }
    var desamsungTier by remember { mutableIntStateOf(1) }

    val checkedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // Snapshot restore state
    var restoreList by remember { mutableStateOf<List<PresetPkg>>(emptyList()) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val content = readUriText(context, it)
            parseSnapshotAndCompare(context, viewModel, coroutineScope, content) { list ->
                restoreList = list
                selectedTab = 3
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debloater & App Manager") },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Description card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System Package Manager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Uninstall or disable carrier junk, OEM diagnostic overlays, and background tracking services to free up RAM and reduce background power draw. Ensure backup snapshots are created before uninstalling critical packages.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Bloat") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Google") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Samsung") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Restore") })
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    // General Debloat
                    Text("General Bloatware Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    Text("Disable generic carrier junk, marketing frameworks, and analytics agents.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
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
                            viewModel.scanBloatware(context, safetyLevel)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Scan")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Bloatware")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (debloatList.isNotEmpty()) {
                        PackageList(
                            packages = debloatList.map { it.packageName },
                            labels = debloatList.associate { it.packageName to it.label },
                            descriptions = debloatList.associate { it.packageName to it.description },
                            checkedPackages = checkedPackages,
                            onExecute = { isUninstall ->
                                val selected = checkedPackages.filter { it.value }.keys.toList()
                                viewModel.disablePackages(selected, isUninstall)
                            }
                        )
                    }
                }
                1 -> {
                    // De-Google
                    Text("De-Google Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    Text("Tiers specify exposure levels. Tier 1 disables basic telemetry, Tier 2 blocks non-essential apps, Tier 3 removes GMS components.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3).forEach { tier ->
                            FilterChip(
                                selected = degoogleTier == tier,
                                onClick = { degoogleTier = tier },
                                label = { Text("TIER $tier") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            checkedPackages.clear()
                            viewModel.scanDegoogle(context, degoogleTier)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Scan")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Google Packages")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (degoogleList.isNotEmpty()) {
                        PackageList(
                            packages = degoogleList.map { it.id },
                            labels = degoogleList.associate { it.id to it.name },
                            descriptions = degoogleList.associate { it.id to it.description },
                            checkedPackages = checkedPackages,
                            onExecute = { isUninstall ->
                                val selected = checkedPackages.filter { it.value }.keys.toList()
                                viewModel.disablePackages(selected, isUninstall)
                            }
                        )
                    }
                }
                2 -> {
                    // De-Samsung
                    Text("De-Samsung Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    Text("Tier 1 disables Bixby & AR capabilities, Tier 2 blocks Samsung Health/billing services, Tier 3 cleans One UI system tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3).forEach { tier ->
                            FilterChip(
                                selected = desamsungTier == tier,
                                onClick = { desamsungTier = tier },
                                label = { Text("TIER $tier") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            checkedPackages.clear()
                            viewModel.scanDesamsung(context, desamsungTier)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Scan")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Samsung Packages")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (desamsungList.isNotEmpty()) {
                        PackageList(
                            packages = desamsungList.map { it.id },
                            labels = desamsungList.associate { it.id to it.name },
                            descriptions = desamsungList.associate { it.id to it.description },
                            checkedPackages = checkedPackages,
                            onExecute = { isUninstall ->
                                val selected = checkedPackages.filter { it.value }.keys.toList()
                                viewModel.disablePackages(selected, isUninstall)
                            }
                        )
                    }
                }
                3 -> {
                    // Snapshot and Restore
                    Text("Backup & Restore Utilities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    Text("Export snapshots of your current package list, upload backups to compare state, or scan for currently disabled applications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Card 1: Create Snapshot
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Create Backup Snapshot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Export all currently installed package names to a local configuration file for easy restoration later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { createSnapshot(context, viewModel, coroutineScope) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Create Snapshot")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create Snapshot")
                                }
                            }
                        }

                        // Card 2: Restore from Backup
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Restore Backup Snapshot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Upload a previously saved JSON snapshot. Compares system packages and reinstalls any missing applications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(12.dp))
                                FilledTonalButton(
                                    onClick = { filePicker.launch("application/json") },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Upload Snapshot")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Upload Backup")
                                }
                            }
                        }

                        // Card 3: Find Disabled System Packages
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Find Disabled Packages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Queries the package manager for services or system apps that are currently disabled. Select and re-enable them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        checkedPackages.clear()
                                        val conn = viewModel.activeConnection.value
                                        if (conn != null) {
                                            coroutineScope.launch {
                                                viewModel.executeCommand("pm list packages -d")
                                                val disabledOut = conn.runShellCommand("pm list packages -d")
                                                val disabled = disabledOut.split("\n")
                                                    .map { it.trim() }
                                                    .filter { it.startsWith("package:") }
                                                    .map { it.substringAfter("package:") }
                                                    .filter { it.isNotEmpty() }
                                                    
                                                restoreList = disabled.map { PresetPkg(it, it, 1, "Currently disabled package.", "") }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = "Scan Disabled")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan Disabled Apps")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (restoreList.isNotEmpty()) {
                        Text(
                            text = "Apps to Restore (${restoreList.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PackageList(
                            packages = restoreList.map { it.id },
                            labels = restoreList.associate { it.id to it.name },
                            descriptions = restoreList.associate { it.id to it.description },
                            checkedPackages = checkedPackages,
                            isRestore = true,
                            onExecute = {
                                val selected = checkedPackages.filter { it.value }.keys.toList()
                                viewModel.restorePackages(selected)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun createSnapshot(context: Context, viewModel: MainViewModel, scope: CoroutineScope) {
    val conn = viewModel.activeConnection.value ?: return
    scope.launch {
        viewModel.executeCommand("pm list packages")
        val packagesOut = conn.runShellCommand("pm list packages")
        val packages = packagesOut.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.substringAfter("package:") }
            .filter { it.isNotEmpty() }

        val obj = JSONObject()
        obj.put("timestamp", System.currentTimeMillis())
        val arr = org.json.JSONArray()
        packages.forEach { arr.put(it) }
        obj.put("packages", arr)

        val jsonString = obj.toString(2)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, jsonString)
            type = "application/json"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Export 80bee Backup")
        context.startActivity(shareIntent)
    }
}

private fun parseSnapshotAndCompare(
    context: Context,
    viewModel: MainViewModel,
    scope: CoroutineScope,
    content: String,
    onCompareComplete: (List<PresetPkg>) -> Unit
) {
    val conn = viewModel.activeConnection.value ?: return
    scope.launch {
        try {
            val obj = JSONObject(content)
            val arr = obj.getJSONArray("packages")
            val backupSet = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                backupSet.add(arr.getString(i))
            }

            val listOutput = conn.runShellCommand("pm list packages")
            val installed = listOutput.split("\n")
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.substringAfter("package:") }
                .toSet()

            // Find missing apps that were in backup but not currently installed/enabled
            val missing = backupSet.filter { !installed.contains(it) }
            val mapped = missing.map {
                PresetPkg(it, it, 1, "Backup snapshot package (uninstalled/disabled).", "")
            }
            onCompareComplete(mapped)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private fun readUriText(context: Context, uri: Uri): String {
    val sb = java.lang.StringBuilder()
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream))
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return sb.toString()
}
