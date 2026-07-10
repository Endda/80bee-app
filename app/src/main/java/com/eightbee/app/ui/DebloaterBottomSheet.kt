package com.eightbee.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun DebloaterBottomSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val debloatList by viewModel.debloatList.collectAsState()
    val degoogleList by viewModel.degoogleList.collectAsState()
    val desamsungList by viewModel.desamsungList.collectAsState()
    val outputText by viewModel.output.collectAsState()

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Debloater & App Manager",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

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
                    Text("Select safety level for scanning:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Text("Select Google Tier:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Text("Select Samsung Tier:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Text("Snapshot Tools:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { createSnapshot(context, viewModel, coroutineScope) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Backup")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Create")
                        }
                        FilledTonalButton(
                            onClick = { filePicker.launch("application/json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Upload")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Upload")
                        }
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
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Find Disabled")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Disabled")
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

@Composable
fun PackageList(
    packages: List<String>,
    labels: Map<String, String>,
    descriptions: Map<String, String>,
    checkedPackages: MutableMap<String, Boolean>,
    isRestore: Boolean = false,
    onExecute: (isUninstall: Boolean) -> Unit
) {
    var allChecked by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = {
                allChecked = !allChecked
                packages.forEach { checkedPackages[it] = allChecked }
            }
        ) {
            Text(if (allChecked) "Deselect All" else "Select All")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // List of packages
    packages.forEach { pkg ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = checkedPackages[pkg] ?: false,
                onCheckedChange = { checkedPackages[pkg] = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(labels[pkg] ?: pkg, fontWeight = FontWeight.Bold)
                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                descriptions[pkg]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (isRestore) {
        Button(
            onClick = { onExecute(false) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restore")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Restore Selected")
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onExecute(false) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Disable")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Disable")
            }
            Button(
                onClick = { onExecute(true) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Uninstall")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Uninstall")
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
