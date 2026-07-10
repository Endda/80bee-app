package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.Screen
import com.eightbee.app.connection.OtgConnectionManager

sealed class NavItem(val title: String, val icon: @Composable () -> Unit) {
    object Home : NavItem("Home", { Icon(Icons.Filled.Home, contentDescription = "Home") })
    object Advanced : NavItem("Advanced", { Icon(Icons.Filled.Build, contentDescription = "Advanced") })
    object About : NavItem("About", { Icon(Icons.Filled.Settings, contentDescription = "About") })
}

@Composable
fun MainScaffold(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    var selectedItem by remember { mutableStateOf(0) }
    val items = listOf(NavItem.Home, NavItem.Advanced, NavItem.About)
    val currentScreen by viewModel.currentScreen.collectAsState()

    // Handle physical back button presses in SPA model
    androidx.activity.compose.BackHandler(enabled = currentScreen != Screen.Dashboard) {
        viewModel.navigateBack()
    }

    Scaffold(
        bottomBar = {
            if (currentScreen == Screen.Dashboard) {
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
        }
    ) { paddingValues ->
        Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(paddingValues)) {
            if (currentScreen == Screen.Dashboard) {
                when (selectedItem) {
                    0 -> HomeTab(viewModel, onDisconnect)
                    1 -> AdvancedTab(viewModel)
                    2 -> AboutTab()
                }
            } else {
                when (currentScreen) {
                    Screen.Debloater -> DebloaterScreen(viewModel) { viewModel.navigateBack() }
                    Screen.BatteryDiagnostic -> BatteryDiagnosticScreen(viewModel) { viewModel.navigateBack() }
                    Screen.BatteryOptimizer -> BatteryOptimizerScreen(viewModel) { viewModel.navigateBack() }
                    Screen.AudioMods -> AudioModsScreen(viewModel) { viewModel.navigateBack() }
                    Screen.BluetoothRepair -> BluetoothRepairScreen(viewModel) { viewModel.navigateBack() }
                    Screen.HaInstaller -> HaInstallerScreen(viewModel) { viewModel.navigateBack() }
                    Screen.BootManager -> BootManagerScreen(viewModel) { viewModel.navigateBack() }
                    Screen.UiAdjustments -> UiAdjustmentsScreen(viewModel) { viewModel.navigateBack() }
                    Screen.DnsToggle -> DnsToggleScreen(viewModel) { viewModel.navigateBack() }
                    Screen.BootloaderUtilities -> BootloaderUtilitiesScreen(viewModel) { viewModel.navigateBack() }
                    else -> {}
                }
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
    val connectionManager = viewModel.activeConnection.collectAsState().value ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSideloadSheet by remember { mutableStateOf(false) }

    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Advanced Features", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        
        AdvancedAppLauncherCard(
            title = "App Management",
            description = "Sideload APK installation files or use the advanced package debloater system.",
            onOpenDebloater = { viewModel.navigateTo(Screen.Debloater) },
            onApkPicked = { uri ->
                viewModel.startSideload(context, uri)
                showSideloadSheet = true
            }
        )
        
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        
        AdvancedLauncherCard(
            title = "Battery Optimizer",
            description = "Access Doze standby configurations, Samsung-specific hardware overrides, and the AOT app compiler.",
            onClick = { viewModel.navigateTo(Screen.BatteryOptimizer) }
        )
        
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        
        AdvancedLauncherCard(
            title = "Battery Diagnostics",
            description = "Track battery metrics dynamically, log dumpsys parameters, and simulate charging disconnects.",
            onClick = { viewModel.navigateTo(Screen.BatteryDiagnostic) }
        )
        
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        
        AdvancedLauncherCard(
            title = "Home Assistant Installer",
            description = "Setup a background Home Assistant server on Android. Runs Termux scripting environments.",
            onClick = { viewModel.navigateTo(Screen.HaInstaller) }
        )

        if (connectionManager is OtgConnectionManager) {
            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
            AdvancedLauncherCard(
                title = "Bootloader Utilities",
                description = "Unlock or lock the hardware bootloader parameters. Triggers hard data wipes.",
                onClick = { viewModel.navigateTo(Screen.BootloaderUtilities) }
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedLauncherCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedAppLauncherCard(
    title: String,
    description: String,
    onOpenDebloater: () -> Unit,
    onApkPicked: (Uri) -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onApkPicked(it) }
    }

    ElevatedCard(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
            Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDebloater, modifier = androidx.compose.ui.Modifier.weight(1f)) {
                    Text("Debloater")
                }
                FilledTonalButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = androidx.compose.ui.Modifier.weight(1f)
                ) {
                    Text("Sideload APK")
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
