package com.eightbee.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.connection.OtgConnectionManager
import android.net.Uri

sealed class NavItem(val title: String, val icon: @Composable () -> Unit) {

    object Home : NavItem("Home", { Icon(Icons.Filled.Home, contentDescription = "Home") })
    object Advanced : NavItem("Advanced", { Icon(Icons.Filled.Build, contentDescription = "Advanced") })
    object About : NavItem("About", { Icon(Icons.Filled.Settings, contentDescription = "About") })
}

@Composable
fun MainScaffold(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    var selectedItem by remember { mutableStateOf(0) }
    val items = listOf(NavItem.Home, NavItem.Advanced, NavItem.About)

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = item.icon,
                        label = { Text(item.title) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(paddingValues)) {
            when (selectedItem) {
                0 -> HomeTab(viewModel, onDisconnect)
                1 -> AdvancedTab(viewModel)
                2 -> AboutTab()
            }
        }
    }
}

@Composable
fun HomeTab(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    DashboardScreen(viewModel, onDisconnect)
}

@Composable
fun AdvancedTab(viewModel: MainViewModel) {
    var showDebloater by remember { mutableStateOf(false) }
    var showSideloadSheet by remember { mutableStateOf(false) }
    val connectionManager = viewModel.activeConnection.collectAsState().value ?: return
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showDebloater) {
        DebloaterScreen(connectionManager, onBack = { showDebloater = false })
    } else {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text("Advanced Features", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
            AdvancedAppManagerCard(
                onOpenDebloater = { showDebloater = true },
                onApkPicked = { uri ->
                    viewModel.startSideload(context, uri)
                    showSideloadSheet = true
                }
            )
            if (connectionManager is OtgConnectionManager) {
                Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                BootloaderUtilitiesCard(
                    onUnlockClicked = { viewModel.startBootloaderWizard(isUnlock = true) },
                    onLockClicked = { viewModel.startBootloaderWizard(isUnlock = false) }
                )
            }
        }
    }

    if (showSideloadSheet) {
        SideloadBottomSheet(
            viewModel = viewModel,
            connectionManager = connectionManager,
            onDismiss = { showSideloadSheet = false }
        )
    }
}

@Composable
fun BootloaderUtilitiesCard(
    onUnlockClicked: () -> Unit,
    onLockClicked: () -> Unit
) {
    ElevatedCard(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text(text = "Bootloader Utilities", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            Text(
                text = "Unlock or relock the bootloader of the connected phone via USB OTG. Requires device reboot and physical confirmation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onUnlockClicked,
                    modifier = androidx.compose.ui.Modifier.weight(1f)
                ) {
                    Text("Unlock Bootloader")
                }
                FilledTonalButton(
                    onClick = onLockClicked,
                    modifier = androidx.compose.ui.Modifier.weight(1f)
                ) {
                    Text("Lock Bootloader")
                }
            }
        }
    }
}

@Composable
fun AboutTab() {
    Column(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("80bee", style = MaterialTheme.typography.displayMedium)
        Text("Version 1.0.0", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = androidx.compose.ui.Modifier.height(32.dp))
        Text("Built with Material Design 3", style = MaterialTheme.typography.bodySmall)
    }
}
