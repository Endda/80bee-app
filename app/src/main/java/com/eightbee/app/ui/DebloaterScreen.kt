package com.eightbee.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloaterScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var outputText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Fetch all packages to allow true debloating, not just 3rd party
        val result = connectionManager.runShellCommand("pm list packages")
        packages = result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .sorted()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debloater Manager") },
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
        ) {
            if (outputText.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = outputText,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(packages) { pkg ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = pkg, modifier = Modifier.weight(1f))
                                Row {
                                    Button(
                                        onClick = {
                                            outputText = "Disabling $pkg..."
                                            coroutineScope.launch {
                                                val res = connectionManager.runShellCommand("pm disable-user --user 0 $pkg")
                                                outputText = res.ifBlank { "Disabled $pkg" }
                                            }
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) { Text("Disable") }
                                    Button(
                                        onClick = {
                                            outputText = "Uninstalling $pkg..."
                                            coroutineScope.launch {
                                                val res = connectionManager.runShellCommand("pm uninstall --user 0 $pkg")
                                                outputText = res.ifBlank { "Uninstalled $pkg" }
                                                packages = packages.filter { it != pkg }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Uninstall") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
