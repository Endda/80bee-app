package com.eightbee.app.features

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.eightbee.app.InstallStep
import com.eightbee.app.SideloadState
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Manages APK / split-APK archive parsing and staged ADB session installation. */
class SideloadController internal constructor(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val application: Application? = null
) {
    constructor(
        application: Application,
        scope: CoroutineScope,
        connectionProvider: () -> ConnectionManager?
    ) : this(
        scope = scope,
        connectionProvider = connectionProvider,
        ioDispatcher = Dispatchers.IO,
        application = application
    )

    private val _state = MutableStateFlow<SideloadState>(SideloadState.Idle)
    val state: StateFlow<SideloadState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun startSideload(uri: Uri) {
        val app = checkNotNull(application) { "Application is required to parse sideload URIs." }
        activeJob?.cancel()
        val prevState = _state.value
        if (prevState is SideloadState.ConfigReady) {
            prevState.apkFiles.forEach { it.delete() }
        }
        activeJob = scope.launch(ioDispatcher) {
            _state.value = SideloadState.CopyingAndParsing
            val tempFiles = mutableListOf<File>()
            try {
                val contentResolver = app.contentResolver
                val fileName = getUriFileName(app, uri)
                val isArchive = fileName.endsWith(".zip", true) ||
                    fileName.endsWith(".apks", true) ||
                    fileName.endsWith(".apkm", true) ||
                    fileName.endsWith(".xapk", true)

                var explicitBaseApk: File? = null
                if (isArchive) {
                    val archiveStream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected archive.")
                    archiveStream.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInputStream ->
                            var entry = zipInputStream.nextEntry
                            var bytesLeft = MAX_SIDELOAD_BYTES
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                    if (tempFiles.size >= MAX_SIDELOAD_APK_ENTRIES) {
                                        throw IOException("Archive contains too many APK files.")
                                    }
                                    val baseName = File(entry.name).name
                                    val tempFile = File(app.cacheDir, "sideload_${System.currentTimeMillis()}_$baseName")
                                    tempFiles.add(tempFile)
                                    if (baseName.equals("base.apk", ignoreCase = true) && explicitBaseApk == null) {
                                        explicitBaseApk = tempFile
                                    }
                                    tempFile.outputStream().use { outputStream ->
                                        bytesLeft -= copyBounded(zipInputStream, outputStream, bytesLeft)
                                    }
                                }
                                zipInputStream.closeEntry()
                                entry = zipInputStream.nextEntry
                            }
                        }
                    }
                } else {
                    val tempFile = File(app.cacheDir, "sideload_${System.currentTimeMillis()}_${File(fileName).name}")
                    tempFiles.add(tempFile)
                    val apkStream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected APK file.")
                    apkStream.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            copyBounded(inputStream, outputStream, MAX_SIDELOAD_BYTES)
                        }
                    }
                }

                if (tempFiles.isEmpty()) {
                    _state.value = SideloadState.Error("No APK files found in the selected file.", InstallStep.CHECK_CONNECTION)
                    return@launch
                }

                val baseApk = explicitBaseApk
                    ?: tempFiles.maxByOrNull { it.length() }
                    ?: tempFiles.first()

                val packageManager = app.packageManager
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)
                }

                val appInfo = packageInfo?.applicationInfo
                val appLabel = if (appInfo != null) {
                    appInfo.sourceDir = baseApk.absolutePath
                    appInfo.publicSourceDir = baseApk.absolutePath
                    appInfo.loadLabel(packageManager).toString()
                } else {
                    fileName.substringBeforeLast(".")
                }

                val packageName = packageInfo?.packageName ?: "unknown"
                val versionName = packageInfo?.versionName ?: "1.0"

                val appIcon = if (appInfo != null) {
                    try {
                        appInfo.loadIcon(packageManager).toBitmap()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val targetDeviceSdk = runCatching {
                    connectionProvider()
                        ?.takeIf { it.isAvailable() }
                        ?.runShellCommand("getprop ro.build.version.sdk")
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                }.getOrDefault(0)

                _state.value = SideloadState.ConfigReady(
                    appLabel = appLabel,
                    packageName = packageName,
                    versionName = versionName,
                    appIcon = appIcon,
                    totalSize = tempFiles.sumOf { it.length() },
                    apkFiles = tempFiles,
                    isArchive = isArchive,
                    archiveName = fileName,
                    targetDeviceSdk = targetDeviceSdk
                )
            } catch (e: CancellationException) {
                tempFiles.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                tempFiles.forEach { it.delete() }
                _state.value = SideloadState.Error(
                    "Failed to parse APK: ${e.localizedMessage ?: e.message}",
                    InstallStep.CHECK_CONNECTION
                )
            }
        }
    }

    fun executeInstall(
        config: SideloadState.ConfigReady,
        bypassLowTargetSdk: Boolean,
        grantAllPermissions: Boolean,
        allowDowngrade: Boolean,
        testOnly: Boolean,
        installToSdCard: Boolean
    ) {
        if (activeJob?.isActive == true) return
        val connection = connectionProvider()
        if (connection == null || !connection.isAvailable()) {
            _state.value = SideloadState.Error(
                "Device connection not active or unauthorized.",
                InstallStep.CHECK_CONNECTION
            )
            config.apkFiles.forEach { it.delete() }
            return
        }

        activeJob = scope.launch(ioDispatcher) {
            val completedSteps = mutableSetOf<InstallStep>()
            _state.value = SideloadState.Installing(InstallStep.CHECK_CONNECTION, completedSteps.toSet())
            completedSteps.add(InstallStep.CHECK_CONNECTION)

            _state.value = SideloadState.Installing(InstallStep.CREATE_SESSION, completedSteps.toSet())
            val installCmd = buildString {
                append("cmd package install-create -r")
                if (bypassLowTargetSdk) append(" --bypass-low-target-sdk-block")
                if (grantAllPermissions) append(" -g")
                if (allowDowngrade) append(" -d")
                if (testOnly) append(" -t")
                if (installToSdCard) append(" -s")
            }

            val createResult = try {
                connection.runShellCommand(installCmd)
            } catch (e: CancellationException) {
                config.apkFiles.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                _state.value = SideloadState.Error(
                    "Failed to create install session: ${e.message ?: "Connection error"}",
                    InstallStep.CREATE_SESSION
                )
                config.apkFiles.forEach { it.delete() }
                return@launch
            }
            if (!createResult.contains("Success", ignoreCase = true)) {
                _state.value = SideloadState.Error("Failed to create install session: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }

            val sessionId = Regex("\\[(\\d+)\\]").find(createResult)?.groupValues?.get(1)
            if (sessionId == null) {
                _state.value = SideloadState.Error("Could not parse session ID from: $createResult", InstallStep.CREATE_SESSION)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }

            completedSteps.add(InstallStep.CREATE_SESSION)
            _state.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps.toSet())

            try {
                config.apkFiles.forEachIndexed { index, file ->
                    val progressText = "Streaming ${file.name} (${file.length() / 1024 / 1024} MB)... [${index + 1}/${config.apkFiles.size}]"
                    _state.value = SideloadState.Installing(InstallStep.STREAM_APKS, completedSteps.toSet(), progressText)

                    FileInputStream(file).use { fis ->
                        val writeCommand = "cmd package install-write -S ${file.length()} $sessionId split_$index.apk -"
                        val writeResult = connection.runShellCommandWithInput(writeCommand, fis)
                        if (!writeResult.contains("Success", ignoreCase = true) || writeResult.contains("Failure", ignoreCase = true)) {
                            connection.runShellCommand("cmd package install-abandon $sessionId")
                            throw Exception("Failed to write ${file.name}: $writeResult")
                        }
                    }
                }
                completedSteps.add(InstallStep.STREAM_APKS)
            } catch (e: CancellationException) {
                config.apkFiles.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { connection.runShellCommand("cmd package install-abandon $sessionId") }
                _state.value = SideloadState.Error(e.message ?: "Failed to stream APKs", InstallStep.STREAM_APKS)
                config.apkFiles.forEach { it.delete() }
                return@launch
            }

            _state.value = SideloadState.Installing(InstallStep.COMMIT_INSTALL, completedSteps.toSet())
            val commitResult = try {
                connection.runShellCommand("cmd package install-commit $sessionId")
            } catch (e: CancellationException) {
                config.apkFiles.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                config.apkFiles.forEach { it.delete() }
                _state.value = SideloadState.Error(
                    "Installation failed: ${e.message ?: "Connection error"}",
                    InstallStep.COMMIT_INSTALL
                )
                return@launch
            }
            config.apkFiles.forEach { it.delete() }

            if (commitResult.contains("Success", ignoreCase = true)) {
                completedSteps.add(InstallStep.COMMIT_INSTALL)
                _state.value = SideloadState.Success(config.appLabel)
            } else {
                val detail = commitResult.ifBlank { "No response received from package manager." }
                _state.value = SideloadState.Error("Installation failed: $detail", InstallStep.COMMIT_INSTALL)
            }
        }
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        val currentState = _state.value
        if (currentState is SideloadState.ConfigReady) {
            currentState.apkFiles.forEach { it.delete() }
        }
        _state.value = SideloadState.Idle
    }
}
