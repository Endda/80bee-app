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
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.R
import com.eightbee.app.Screen

enum class NavItem(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Home(R.string.nav_home, Icons.Filled.Home),
    Advanced(R.string.nav_advanced, Icons.Filled.Build),
    About(R.string.nav_about, Icons.Filled.Settings)
}

@Composable
fun MainScaffold(viewModel: MainViewModel, onDisconnect: () -> Unit) {
    var selectedItem by rememberSaveable { mutableStateOf(NavItem.Home) }
    val currentScreen by viewModel.currentScreen.collectAsState()
    val activeConnection by viewModel.activeConnection.collectAsState()
    val onBack: () -> Unit = { viewModel.navigateBack() }
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
    BackHandler(enabled = currentScreen != Screen.Dashboard, onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (currentScreen == Screen.Dashboard) {
                NavigationBar {
                    NavItem.entries.forEach { item ->
                        val label = stringResource(item.titleRes)
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = selectedItem == item,
                            onClick = { selectedItem = item }
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
                    NavItem.Home -> DashboardScreen(viewModel, onDisconnect)
                    NavItem.Advanced -> AdvancedTab(viewModel)
                    NavItem.About -> AboutTab()
                }
            } else {
                when (currentScreen) {
                    Screen.Debloater -> DebloaterScreen(
                        controller = viewModel.debloaterController,
                        activeConnection = activeConnection,
                        onBack = onBack
                    )
                    Screen.BatteryDiagnostic -> BatteryDiagnosticScreen(
                        controller = viewModel.deviceTweaksController,
                        backgroundOperations = viewModel.backgroundOperations,
                        onBack = onBack
                    )
                    Screen.BatteryOptimizer -> BatteryOptimizerScreen(
                        tweaksController = viewModel.deviceTweaksController,
                        optimizerController = viewModel.batteryOptimizerController,
                        backgroundOperations = viewModel.backgroundOperations,
                        activeConnection = activeConnection,
                        onBack = onBack
                    )
                    Screen.AudioMods -> AudioModsScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.BluetoothRepair -> BluetoothRepairScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.HaInstaller -> HaInstallerScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.BootManager -> BootManagerScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.UiAdjustments -> UiAdjustmentsScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.DnsToggle -> DnsToggleScreen(
                        controller = viewModel.deviceTweaksController,
                        onBack = onBack
                    )
                    Screen.BootloaderUtilities -> BootloaderUtilitiesScreen(
                        wizardController = viewModel.bootloaderWizardController,
                        activeConnection = activeConnection,
                        onBack = onBack
                    )
                    else -> {}
                }
            }
        }
    }
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

        AppLauncherCard(
            onOpenDebloater = { viewModel.navigateTo(Screen.Debloater) },
            onApkPicked = { uri ->
                viewModel.sideloadController.startSideload(uri)
                showSideloadSheet = true
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
        LauncherList(advancedLaunchers, connectionManager.supportsFastboot, viewModel)
    }

    if (showSideloadSheet) {
        SideloadBottomSheet(
            controller = viewModel.sideloadController,
            onDismiss = { showSideloadSheet = false }
        )
    }
}

private val advancedLaunchers = listOf(
    ToolLauncher(R.string.card_battery_optimizer_title, R.string.card_battery_optimizer_desc) { navigateTo(Screen.BatteryOptimizer) },
    ToolLauncher(R.string.card_battery_diagnostics_title, R.string.card_battery_diagnostics_desc) { navigateTo(Screen.BatteryDiagnostic) },
    ToolLauncher(R.string.card_ha_installer_title, R.string.card_ha_installer_desc) { navigateTo(Screen.HaInstaller) },
    ToolLauncher(R.string.card_bootloader_utilities_title, R.string.card_bootloader_utilities_desc, requiresFastboot = true) {
        navigateTo(Screen.BootloaderUtilities)
    }
)

@Composable
private fun AppLauncherCard(onOpenDebloater: () -> Unit, onApkPicked: (Uri) -> Unit) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onApkPicked(it) }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        LauncherCardContent(
            title = stringResource(R.string.card_app_management_title),
            description = stringResource(R.string.card_app_management_desc)
        ) {
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
