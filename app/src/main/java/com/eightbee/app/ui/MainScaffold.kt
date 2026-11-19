package com.eightbee.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.R
import com.eightbee.app.Screen
import com.eightbee.app.connection.OtgConnectionManager

sealed class NavItem(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    object Home : NavItem(R.string.nav_home, Icons.Filled.Home)
    object Advanced : NavItem(R.string.nav_advanced, Icons.Filled.Build)
    object About : NavItem(R.string.nav_about, Icons.Filled.Settings)
}

@Composable
fun MainScaffold(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    var selectedItem by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(NavItem.Home, NavItem.Advanced, NavItem.About)
    val currentScreen by viewModel.currentScreen.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            if (viewModel.currentScreen.value != Screen.Dashboard) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Handle physical back button presses in SPA model
    BackHandler(enabled = currentScreen != Screen.Dashboard) {
        viewModel.navigateBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (currentScreen == Screen.Dashboard) {
                NavigationBar {
                    items.forEachIndexed { index, item ->
                        val label = stringResource(item.titleRes)
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = selectedItem == index,
                            onClick = { selectedItem = index }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
    var showSideloadSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(R.string.advanced_features_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        AdvancedAppLauncherCard(
            title = stringResource(R.string.card_app_management_title),
            description = stringResource(R.string.card_app_management_desc),
            onOpenDebloater = { viewModel.navigateTo(Screen.Debloater) },
            onApkPicked = { uri ->
                viewModel.startSideload(uri)
                showSideloadSheet = true
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        AdvancedLauncherCard(
            title = stringResource(R.string.card_battery_optimizer_title),
            description = stringResource(R.string.card_battery_optimizer_desc),
            onClick = { viewModel.navigateTo(Screen.BatteryOptimizer) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        AdvancedLauncherCard(
            title = stringResource(R.string.card_battery_diagnostics_title),
            description = stringResource(R.string.card_battery_diagnostics_desc),
            onClick = { viewModel.navigateTo(Screen.BatteryDiagnostic) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        AdvancedLauncherCard(
            title = stringResource(R.string.card_ha_installer_title),
            description = stringResource(R.string.card_ha_installer_desc),
            onClick = { viewModel.navigateTo(Screen.HaInstaller) }
        )

        if (connectionManager is OtgConnectionManager) {
            Spacer(modifier = Modifier.height(16.dp))
            AdvancedLauncherCard(
                title = stringResource(R.string.card_bootloader_utilities_title),
                description = stringResource(R.string.card_bootloader_utilities_desc),
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
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDebloater, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_debloater))
                }
                FilledTonalButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_sideload_apk))
                }
            }
        }
    }
}

@Composable
fun AboutTab() {
    val context = LocalContext.current
    val versionName = remember(context) {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.3"
        } catch (_: Exception) {
            "1.3"
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
        Text(stringResource(R.string.about_version_format, versionName), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodySmall)
    }
}
