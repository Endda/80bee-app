package com.eightbee.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.drawable.toBitmap

data class DeviceSettings(
    val privateDnsMode: String = "unknown",
    val privateDnsHostname: String = "",
    val windowAnimationScale: String = "1.0",
    val transitionAnimationScale: String = "1.0",
    val animatorDurationScale: String = "1.0",
    val adaptiveBatteryEnabled: Boolean = true
)

class MainViewModel : ViewModel() {


    private val _activeConnection = MutableStateFlow<ConnectionManager?>(null)
    val activeConnection: StateFlow<ConnectionManager?> = _activeConnection.asStateFlow()

    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    private val _output = MutableStateFlow("Ready.")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    fun setActiveConnection(connection: ConnectionManager?) {
        _activeConnection.value = connection
        if (connection != null) {
            refreshSettings()
        }
    }

    fun executeCommand(command: String) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing: $command..."
            val result = connection.runShellCommand(command)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            // Refresh settings after potentially changing them
            refreshSettings()
        }
    }

    fun executeCommandWithInput(command: String, input: InputStream) {
        val connection = _activeConnection.value ?: return
        viewModelScope.launch {
            _isExecuting.value = true
            _output.value = "Executing command with input..."
            val result = connection.runShellCommandWithInput(command, input)
            _output.value = result.ifBlank { "Command completed." }
            _isExecuting.value = false
            refreshSettings()
        }
    }

    fun refreshSettings() {
        val connection = _activeConnection.value ?: return
        if (!connection.isAvailable()) return

        viewModelScope.launch {
            val dnsMode = connection.queryStatus("settings get global private_dns_mode")
            val dnsHost = connection.queryStatus("settings get global private_dns_specifier")
            val winScale = connection.queryStatus("settings get global window_animation_scale")
            val transScale = connection.queryStatus("settings get global transition_animation_scale")
            val animScale = connection.queryStatus("settings get global animator_duration_scale")
            val adaptiveBattery = connection.queryStatus("settings get global adaptive_battery_management_enabled")

            _settings.value = DeviceSettings(
                privateDnsMode = dnsMode.trim(),
                privateDnsHostname = dnsHost.trim(),
                windowAnimationScale = winScale.trim(),
                transitionAnimationScale = transScale.trim(),
                animatorDurationScale = animScale.trim(),
                adaptiveBatteryEnabled = adaptiveBattery.trim() == "1"
            )
        }
    }

    private val _bootloaderWizardState = MutableStateFlow<BootloaderWizardState>(BootloaderWizardState.Idle)
    val bootloaderWizardState: StateFlow<BootloaderWizardState> = _bootloaderWizardState.asStateFlow()

    fun startBootloaderWizard(isUnlock: Boolean) {
        _bootloaderWizardState.value = BootloaderWizardState.ConfirmingWarnings(isUnlock)
    }

    fun clearBootloaderWizardState() {
        _bootloaderWizardState.value = BootloaderWizardState.Idle
    }

    fun proceedFromWarnings() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.ConfirmingWarnings ?: return
        val isUnlock = currentState.isUnlock
        _bootloaderWizardState.value = BootloaderWizardState.Rebooting(isUnlock)

        viewModelScope.launch {
            _output.value = "Sending reboot to bootloader command..."
            val connection = _activeConnection.value
            if (connection != null && connection.isAvailable()) {
                val result = connection.runShellCommand("reboot bootloader")
                _output.value = "Reboot command output: $result"
            }
            _bootloaderWizardState.value = BootloaderWizardState.WaitingForFastboot(isUnlock)
        }
    }

    fun transitionToCommandSelection() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.WaitingForFastboot ?: return
        val isUnlock = currentState.isUnlock
        val options = if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
        _bootloaderWizardState.value = BootloaderWizardState.CommandSelection(
            isUnlock = isUnlock,
            commandOptions = options,
            customCommand = ""
        )
    }

    fun proceedFromCommandSelection(command: String) {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.CommandSelection ?: return
        _bootloaderWizardState.value = BootloaderWizardState.ConfirmingExecution(
            isUnlock = currentState.isUnlock,
            command = command
        )
    }

    fun goBackToCommandSelection() {
        val currentState = _bootloaderWizardState.value
        val isUnlock = when (currentState) {
            is BootloaderWizardState.ConfirmingExecution -> currentState.isUnlock
            is BootloaderWizardState.ExecutingCommand -> currentState.isUnlock
            else -> return
        }
        val options = if (isUnlock) {
            listOf("flashing unlock", "oem unlock")
        } else {
            listOf("flashing lock", "oem lock")
        }
        _bootloaderWizardState.value = BootloaderWizardState.CommandSelection(
            isUnlock = isUnlock,
            commandOptions = options,
            customCommand = ""
        )
    }

    fun confirmAndExecuteBootloaderCommand() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.ConfirmingExecution ?: return
        val isUnlock = currentState.isUnlock
        val command = currentState.command
        _bootloaderWizardState.value = BootloaderWizardState.ExecutingCommand(isUnlock, command)

        viewModelScope.launch {
            val connection = _activeConnection.value
            if (connection == null) {
                _bootloaderWizardState.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Error: Connection lost. Ensure device is plugged in."
                )
                return@launch
            }

            _output.value = "Executing fastboot command: $command..."
            val result = connection.sendFastbootCommand(command)
            _output.value = "Fastboot output: $result"

            if (result.contains("OKAY", ignoreCase = true)) {
                _bootloaderWizardState.value = BootloaderWizardState.PhysicalConfirmationPrompt(
                    isUnlock = isUnlock,
                    command = command,
                    responseText = result
                )
            } else {
                _bootloaderWizardState.value = BootloaderWizardState.Finished(
                    isUnlock = isUnlock,
                    success = false,
                    message = "Fastboot execution failed: $result"
                )
            }
        }
    }

    fun completePhysicalConfirmation() {
        val currentState = _bootloaderWizardState.value as? BootloaderWizardState.PhysicalConfirmationPrompt ?: return
        _bootloaderWizardState.value = BootloaderWizardState.Finished(
            isUnlock = currentState.isUnlock,
            success = true,
            message = "Command confirmed successfully."
        )
    }

    private val _sideloadState = MutableStateFlow<SideloadState>(SideloadState.Idle)
    val sideloadState: StateFlow<SideloadState> = _sideloadState.asStateFlow()

    fun startSideload(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _sideloadState.value = SideloadState.CopyingAndParsing
            try {
                val contentResolver = context.contentResolver
                val fileName = getFileName(context, uri)
                val isArchive = fileName.endsWith(".zip", true) || 
                                fileName.endsWith(".apks", true) || 
                                fileName.endsWith(".xapk", true)
                
                val tempFiles = mutableListOf<File>()
                if (isArchive) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val zipInputStream = ZipInputStream(inputStream)
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                val baseName = File(entry.name).name
                                val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_$baseName")
                                tempFile.outputStream().use { outputStream ->
                                    zipInputStream.copyTo(outputStream)
                                }
                                tempFiles.add(tempFile)
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                        zipInputStream.close()
                    }
                } else {
                    val tempFile = File(context.cacheDir, "sideload_${System.currentTimeMillis()}_$fileName")
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    tempFiles.add(tempFile)
                }

                if (tempFiles.isEmpty()) {
                    _sideloadState.value = SideloadState.Error("No APK files found in the selected file.", InstallStep.CHECK_CONNECTION)
                    return@launch
                }

                val baseApk = tempFiles.find { it.name.equals("base.apk", ignoreCase = true) } 
                    ?: tempFiles.maxByOrNull { it.length() } 
                    ?: tempFiles.first()
                
                val packageManager = context.packageManager
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)
                }

                val appLabel = if (packageInfo != null && packageInfo.applicationInfo != null) {
                    val appInfo = packageInfo.applicationInfo!!
                    appInfo.sourceDir = baseApk.absolutePath
                    appInfo.publicSourceDir = baseApk.absolutePath
                    appInfo.loadLabel(packageManager).toString()
                } else {
                    fileName.substringBeforeLast(".")
                }

                val packageName = packageInfo?.packageName ?: "unknown"
                val versionName = packageInfo?.versionName ?: "1.0"
                
                val appIcon = if (packageInfo != null && packageInfo.applicationInfo != null) {
                    try {
                        val appInfo = packageInfo.applicationInfo!!
                        appInfo.sourceDir = baseApk.absolutePath
                        appInfo.publicSourceDir = baseApk.absolutePath
                        val drawable = appInfo.loadIcon(packageManager)
                        drawable.toBitmap()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                val totalSize = tempFiles.sumOf { it.length() }

                _sideloadState.value = SideloadState.ConfigReady(
                    appLabel = appLabel,
                    packageName = packageName,
                    versionName = versionName,
                    appIcon = appIcon,
                    totalSize = totalSize,
                    apkFiles = tempFiles,
                    isArchive = isArchive,
                    archiveName = fileName
                )
            } catch (e: Exception) {
                _sideloadState.value = SideloadState.Error("Failed to parse APK: ${e.localizedMessage ?: e.message}", InstallStep.CHECK_CONNECTION)
            }
        }
    }

    fun executeSideloadInstall(
        config: SideloadState.ConfigReady,
        bypassLowTargetSdk: Boolean,
        grantAllPermissions: Boolean,
        allowDowngrade: Boolean,
        testOnly: Boolean,
        installToSdCard: Boolean
    ) {
        val connection = _activeConnection.value
        if (connection == null || !connection.isAvailable()) {
            _sideloadState.value = SideloadState.Error(
                "Device connection not active or unauthorized.",
                InstallStep.CHECK_CONNECTION
            )
            config.apkFiles.forEach { it.delete() }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val completedSteps = mutableSetOf<InstallStep>()
            _sideloadState.value = SideloadState.Installing(InstallStep.CHECK_CONNECTION, completedSteps)
            
            completedSteps.add(InstallStep.CHECK_CONNECTION)
            
            _sideloadState.value = SideloadState.Installing(InstallStep.CREATE_SESSION, completedSteps)
            val commandBuilder = java.lang.StringBuilder("cmd package install-create")
            commandBuilder.append(" -r")
            if (bypassLowTargetSdk) {
                commandBuilder.append(" --bypass-low-target-sdk-block")
            }
            if (grantAllPermissions) {
                commandBuilder.append(" -g")
            }
            if (allowDowngrade) {
                commandBuilder.append(" -d")
            }
            if (testOnly) {
                commandBuilder.append(" -t")
            }
            if (installToSdCard) {
                commandBuilder.append(" -s")
            }
            
            val createResult = connection.runShellCommand(commandBuilder.toString())
            if (!createResult.contains("Success", ignoreCase = true)) {
                _sideloadState.value = SideloadState.Error("Failed to create install session: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            val match = Regex("\\[(\\d+)\\]").find(createResult)
            val sessionId = match?.groupValues?.get(1)
            if (sessionId == null) {
                _sideloadState.value = SideloadState.Error("Could not parse session ID from: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            completedSteps.add(InstallStep.CREATE_SESSION)
            
            _sideloadState.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps)
            
            try {
                config.apkFiles.forEachIndexed { index, file ->
                    val progressText = "Streaming ${file.name} (${file.length() / 1024 / 1024} MB)... [${index + 1}/${config.apkFiles.size}]"
                    _sideloadState.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps, progressText)
                    
                    FileInputStream(file).use { fis ->
                        val writeCommand = "cmd package install-write -S ${file.length()} $sessionId ${file.name} -"
                        val writeResult = connection.runShellCommandWithInput(writeCommand, fis)
                        
                        if (writeResult.contains("Failure", ignoreCase = true)) {
                            connection.runShellCommand("cmd package install-abandon $sessionId")
                            throw Exception("Failed to write ${file.name}: $writeResult")
                        }
                    }
                }
                completedSteps.add(InstallStep.STREAM_APKS)
            } catch (e: Exception) {
                _sideloadState.value = SideloadState.Error(e.message ?: "Failed to stream APKs", InstallStep.STREAM_APKS)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            
            _sideloadState.value = SideloadState.Installing(InstallStep.COMMIT_INSTALL, completedSteps)
            
            val commitResult = connection.runShellCommand("cmd package install-commit $sessionId")
            
            config.apkFiles.forEach { it.delete() }
            
            if (commitResult.contains("Success", ignoreCase = true) || commitResult.isBlank()) {
                completedSteps.add(InstallStep.COMMIT_INSTALL)
                _sideloadState.value = SideloadState.Success(config.appLabel)
            } else {
                _sideloadState.value = SideloadState.Error("Installation failed: $commitResult", InstallStep.COMMIT_INSTALL)
            }
        }
    }

    fun clearSideloadState() {
        val state = _sideloadState.value
        if (state is SideloadState.ConfigReady) {
            state.apkFiles.forEach { it.delete() }
        }
        _sideloadState.value = SideloadState.Idle
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            val path = uri.path
            if (path != null) {
                val cut = path.lastIndexOf('/')
                result = if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result ?: "unknown"
    }
}

