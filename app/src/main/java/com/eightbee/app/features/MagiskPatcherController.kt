package com.eightbee.app.features

import android.app.Application
import android.net.Uri
import android.util.Log
import com.eightbee.app.BootExtractionResult
import com.eightbee.app.LogEntry
import com.eightbee.app.MagiskDeviceData
import com.eightbee.app.MagiskPatcherHelper
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.OtgConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** Coordinates the 6-step Pixel Magisk boot/init_boot patching and fastboot flashing workflow. */
class MagiskPatcherController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?
) {
    private val _state = MutableStateFlow(PixelMagiskPatcherState())
    val state: StateFlow<PixelMagiskPatcherState> = _state.asStateFlow()

    private var reconJob: Job? = null
    private var bootFileJob: Job? = null
    private var magiskApkJob: Job? = null
    private var patchJob: Job? = null
    private var rebootJob: Job? = null
    private var flashJob: Job? = null
    private var installApkJob: Job? = null

    private fun cancelActiveJobs() {
        reconJob?.cancel()
        bootFileJob?.cancel()
        magiskApkJob?.cancel()
        patchJob?.cancel()
        rebootJob?.cancel()
        flashJob?.cancel()
        installApkJob?.cancel()
        reconJob = null
        bootFileJob = null
        magiskApkJob = null
        patchJob = null
        rebootJob = null
        flashJob = null
        installApkJob = null
    }

    fun onConnectionStateChanged(connectionState: ConnectionState) {
        var shouldStartRecon = false
        _state.update { current ->
            if (!current.isActive) {
                current
            } else {
                when (connectionState) {
                    ConnectionState.FastbootConnected -> current.copy(
                        isFastbootConnected = true,
                        fastbootStatus = "STATUS: FASTBOOT_CONNECTED",
                        flashStep = 3
                    )
                    ConnectionState.AdbConnected -> {
                        if (current.currentStep == 1) {
                            shouldStartRecon = true
                        }
                        current.copy(isFastbootConnected = false)
                    }
                    else -> current.copy(isFastbootConnected = false)
                }
            }
        }
        if (shouldStartRecon) {
            startRecon()
        }
    }

    fun startPatcher() {
        cancelActiveJobs()
        _state.value = PixelMagiskPatcherState(isActive = true, currentStep = 1)
        val connection = connectionProvider()
        if (connection is OtgConnectionManager && connection.isAvailable() && !connection.isFastboot()) {
            startRecon()
        }
    }

    fun clearPatcherState() {
        cancelActiveJobs()
        val currentState = _state.value
        currentState.bootFile?.delete()
        currentState.magiskApkFile?.delete()
        currentState.patchedFile?.delete()
        File(application.cacheDir, "magisk_components").deleteRecursively()
        _state.value = PixelMagiskPatcherState(isActive = false)
    }

    fun goToStep(step: Int) {
        if (step in 1..6) {
            val currentState = _state.value
            _state.update { it.copy(currentStep = step) }
            if (step == 4 && currentState.patchedFile == null && currentState.patchStatus != "SUCCESS") {
                _state.update { it.copy(patchLogs = emptyList()) }
                addPatchLog("Awaiting execution trigger...", "INFO")
            }
        }
    }

    fun startRecon() {
        val connection = connectionProvider() as? OtgConnectionManager ?: return
        var started = false
        _state.update { current ->
            if (current.isReconning) {
                current
            } else {
                started = true
                current.copy(isReconning = true, integrityStatus = "GATHERING_DATA")
            }
        }
        if (!started) return

        reconJob = scope.launch(Dispatchers.IO) {
            try {
                if (!connection.isAvailable()) {
                    throw Exception("ADB Connection is still initializing or unauthorized. Please ensure target device is unlocked and authorize the USB debugging prompt on the target device screen.")
                }

                suspend fun readProp(prop: String): String {
                    val value = connection.runShellCommand("getprop $prop").trim()
                    if (value.startsWith("Error", ignoreCase = true)) {
                        throw Exception(value)
                    }
                    return value
                }

                val codename = readProp("ro.product.device")
                val buildId = readProp("ro.build.display.id")
                val apiLevelStr = readProp("ro.product.first_api_level")
                val boardApiLevelStr = if (apiLevelStr.isEmpty()) readProp("ro.board.first_api_level") else ""
                val vendorApiLevelStr = if (apiLevelStr.isEmpty() && boardApiLevelStr.isEmpty()) readProp("ro.vendor.api_level") else ""
                val previewSdk = readProp("ro.build.version.preview_sdk").toIntOrNull() ?: 0
                val buildCodename = readProp("ro.build.version.codename")
                val abi = readProp("ro.product.cpu.abi")
                val oemAllowed = readProp("sys.oem_unlock_allowed") == "1"
                val currentSlot = readProp("ro.boot.slot_suffix")

                val flashLocked = readProp("ro.boot.flash.locked")
                val vbState = readProp("ro.boot.verifiedbootstate")
                val isUnlocked = flashLocked == "0" || vbState == "orange"

                val isBeta = previewSdk > 0 || buildCodename != "REL" ||
                    buildId.contains("beta", ignoreCase = true) ||
                    buildId.contains("preview", ignoreCase = true)

                val resolution = MagiskPatcherHelper.resolveReconTarget(
                    codename = codename,
                    abi = abi,
                    firstApiLevelRaw = apiLevelStr,
                    boardApiLevelRaw = boardApiLevelStr,
                    vendorApiLevelRaw = vendorApiLevelStr
                )

                val deviceData = MagiskDeviceData(
                    codename = codename,
                    buildId = buildId,
                    apiLevel = resolution.apiLevel,
                    previewSdk = previewSdk,
                    isBeta = isBeta,
                    abi = abi,
                    oemAllowed = oemAllowed,
                    currentSlot = currentSlot,
                    isUnlocked = isUnlocked,
                    targetPartition = resolution.targetPartition
                )

                _state.update {
                    it.copy(
                        isReconning = false,
                        deviceData = deviceData,
                        showUnlockWarning = !isUnlocked,
                        integrityStatus = "READY_FOR_IMAGE"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isReconning = false,
                        integrityStatus = "RECON_FAILED: ${e.message}"
                    )
                }
                addPatchLog("Recon failed: ${e.message}", "ERROR")
            }
        }
    }

    fun handleBootFileSelected(uri: Uri) {
        _state.update {
            it.copy(
                integrityStatus = "PROCESSING",
                integrityProgress = 0.05f,
                imageWarning = null,
                imageError = null
            )
        }

        bootFileJob?.cancel()
        bootFileJob = scope.launch(Dispatchers.IO) {
            var copiedRawFile: File? = null
            try {
                val fileName = getUriFileName(application, uri)
                val deviceData = _state.value.deviceData
                val totalBytes = getUriSize(application, uri)

                val isZip = fileName.endsWith(".zip", ignoreCase = true) || run {
                    val probe = ByteArray(4)
                    val read = application.contentResolver.openInputStream(uri)?.use { it.read(probe) } ?: 0
                    MagiskPatcherHelper.isZipMagic(probe, read)
                }

                val extractionResult: BootExtractionResult = if (isZip) {
                    _state.update {
                        it.copy(
                            integrityStatus = "SCANNING_ARCHIVE",
                            integrityProgress = 0.1f
                        )
                    }
                    val inputStream = application.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected archive.")
                    inputStream.use { stream ->
                        MagiskPatcherHelper.extractBootImageFromStream(
                            inputStream = stream,
                            cacheDir = application.cacheDir,
                            targetPartition = deviceData?.targetPartition,
                            expectedCodename = deviceData?.codename,
                            expectedBuildId = deviceData?.buildId,
                            totalInputBytes = totalBytes,
                            sourceFileName = fileName
                        ) { status, progress ->
                            _state.update {
                                it.copy(
                                    integrityStatus = status,
                                    integrityProgress = progress
                                )
                            }
                        }
                    }
                } else {
                    val tempImg = File(application.cacheDir, "boot_${System.currentTimeMillis()}.img")
                    copiedRawFile = tempImg
                    val inputStream = application.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected image file.")
                    inputStream.use { input ->
                        tempImg.outputStream().use { output ->
                            copyBounded(
                                input = input,
                                output = output,
                                budget = MAX_BOOT_IMAGE_BYTES,
                                overflowMessage = "Selected image exceeds the 512 MB boot image limit."
                            )
                        }
                    }
                    MagiskPatcherHelper.validateBootImageForPartition(tempImg, deviceData?.targetPartition)
                    BootExtractionResult(tempImg, buildWarning = null)
                }

                _state.value.bootFile?.let { oldFile ->
                    if (oldFile.absolutePath != extractionResult.bootFile.absolutePath) {
                        oldFile.delete()
                    }
                }

                _state.update {
                    it.copy(
                        bootFile = extractionResult.bootFile,
                        bootFileName = extractionResult.bootFile.name,
                        bootFileSize = String.format(
                            Locale.US,
                            "%.1f MB",
                            extractionResult.bootFile.length().toFloat() / (1024 * 1024)
                        ),
                        integrityStatus = "IMAGE_LOADED",
                        integrityProgress = 1f,
                        imageWarning = extractionResult.buildWarning,
                        imageError = null
                    )
                }
            } catch (e: CancellationException) {
                copiedRawFile?.delete()
                throw e
            } catch (e: Exception) {
                copiedRawFile?.delete()
                _state.update {
                    it.copy(
                        bootFile = null,
                        bootFileName = "",
                        bootFileSize = "",
                        integrityStatus = "LOAD_FAILED",
                        integrityProgress = 0f,
                        imageWarning = null,
                        imageError = e.message ?: "Failed to load firmware image."
                    )
                }
                addPatchLog("Load boot file failed: ${e.message}", "ERROR")
            }
        }
    }

    fun handleMagiskApkSelected(uri: Uri) {
        _state.update {
            it.copy(
                appStatus = "STATUS: DECOMPRESSING",
                extractProgress = 0.1f,
                extractLogs = emptyList()
            )
        }
        addExtractLog("Scanning application: " + getUriFileName(application, uri))

        magiskApkJob?.cancel()
        magiskApkJob = scope.launch(Dispatchers.IO) {
            val tempApkFile = File(application.cacheDir, "magisk_temp_${System.currentTimeMillis()}.apk")
            val componentsDir = File(application.cacheDir, "magisk_components")
            try {
                val apkStream = application.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open selected Magisk APK.")
                apkStream.use { input ->
                    tempApkFile.outputStream().use { output ->
                        copyBounded(
                            input = input,
                            output = output,
                            budget = MAX_MAGISK_APK_BYTES,
                            overflowMessage = "Selected Magisk APK exceeds the 256 MB limit."
                        )
                    }
                }

                addExtractLog("Processing archive entries...")

                componentsDir.deleteRecursively()
                componentsDir.mkdirs()
                val components = MagiskPatcherHelper.extractMagiskComponents(tempApkFile, componentsDir)

                for (name in components.keys) {
                    addExtractLog("Decompressing $name...")
                }

                _state.value.magiskApkFile?.let { oldApk ->
                    if (oldApk.absolutePath != tempApkFile.absolutePath) {
                        oldApk.delete()
                    }
                }

                addExtractLog("Extraction complete. Environment ready.")

                _state.update {
                    it.copy(
                        magiskApkFile = tempApkFile,
                        appStatus = "STATUS: DECOMPRESS_SUCCESS",
                        extractProgress = 1f,
                        magiskComponentsReady = true
                    )
                }
            } catch (e: CancellationException) {
                tempApkFile.delete()
                componentsDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                tempApkFile.delete()
                componentsDir.deleteRecursively()
                _state.update {
                    it.copy(
                        appStatus = "STATUS: EXTRACTION_FAILED",
                        extractProgress = 0f,
                        magiskComponentsReady = false
                    )
                }
                addExtractLog("Fatal error: ${e.message}")
            }
        }
    }

    fun startPatching() {
        if (patchJob?.isActive == true) return
        val connection = connectionProvider() as? OtgConnectionManager ?: return
        val currentState = _state.value
        val bootFile = currentState.bootFile ?: return
        val apkFile = currentState.magiskApkFile ?: return

        _state.update {
            it.copy(
                patchStatus = "EXECUTING_PATCH",
                patchProgress = 0.05f,
                patchProgressLabel = "Initializing scoped workspace...",
                patchLogs = emptyList()
            )
        }
        addPatchLog("Initializing scoped workspace...", "INFO")

        patchJob = scope.launch(Dispatchers.IO) {
            val workspace = "/data/local/tmp/80bee_patcher"
            try {
                connection.runShellCommand("rm -rf $workspace && mkdir -p $workspace")

                addPatchLog("Pushing boot image...", "INFO")
                _state.update { it.copy(patchProgress = 0.1f, patchProgressLabel = "Uploading boot image...") }
                val remoteBootPath = "$workspace/boot.img"
                FileInputStream(bootFile).use { fis ->
                    connection.pushFile(fis, bootFile.length(), remoteBootPath) { sent, total ->
                        val progress = 0.1f + (sent.toFloat() / total) * 0.4f
                        _state.update {
                            it.copy(
                                patchProgress = progress,
                                patchProgressLabel = "Uploading boot image (${String.format(Locale.US, "%.1f MB", sent.toFloat() / (1024 * 1024))})..."
                            )
                        }
                    }
                }

                addPatchLog("Pushing Magisk components...", "INFO")
                _state.update { it.copy(patchProgress = 0.5f, patchProgressLabel = "Pushing Magisk binaries...") }

                val componentsDir = File(application.cacheDir, "magisk_components")
                componentsDir.deleteRecursively()
                componentsDir.mkdirs()
                try {
                    val extractedFiles = MagiskPatcherHelper.extractMagiskComponents(apkFile, componentsDir)
                    val totalComp = extractedFiles.size
                    var compIdx = 0
                    for ((name, file) in extractedFiles) {
                        FileInputStream(file).use { fis ->
                            connection.pushFile(fis, file.length(), "$workspace/$name")
                        }
                        compIdx++
                        val progress = 0.5f + (compIdx.toFloat() / totalComp) * 0.15f
                        _state.update {
                            it.copy(
                                patchProgress = progress,
                                patchProgressLabel = "Pushed $name"
                            )
                        }
                    }
                } finally {
                    componentsDir.deleteRecursively()
                }

                addPatchLog("Executing boot_patch.sh...", "INFO")
                _state.update { it.copy(patchProgress = 0.7f, patchProgressLabel = "Setting permissions...") }
                connection.runShellCommand("chmod +x $workspace/*")

                _state.update { it.copy(patchProgress = 0.75f, patchProgressLabel = "Patching kernel image...") }
                val patchCmd = "cd $workspace && export KEEPVERITY=true; export KEEPFORCEENCRYPT=true; export PATCHVBMETAFLAG=false; sh boot_patch.sh $remoteBootPath"

                connection.runShellCommandLive(patchCmd) { line ->
                    addPatchLog(line, "PROMPT")
                }

                addPatchLog("Locating patched image...", "INFO")
                _state.update { it.copy(patchProgress = 0.8f, patchProgressLabel = "Locating patched image...") }
                val checkOut = connection.runShellCommand("ls $workspace/new-boot.img").trim()
                if (checkOut.contains("No such file") || checkOut.isEmpty()) {
                    throw Exception("Patched image not found! Script output was logged above.")
                }

                addPatchLog("Pulling patched image to host...", "INFO")
                _state.update { it.copy(patchProgress = 0.85f, patchProgressLabel = "Pulling patched image...") }
                val remotePatchedPath = "$workspace/new-boot.img"
                val patchedFile = File(application.cacheDir, "patched_boot_${System.currentTimeMillis()}.img")
                try {
                    connection.pullFile(
                        remotePath = remotePatchedPath,
                        destination = patchedFile,
                        maxBytes = MAX_BOOT_IMAGE_BYTES
                    ) { received ->
                        _state.update {
                            it.copy(
                                patchProgressLabel = "Pulling patched image (${String.format(Locale.US, "%.1f MB", received.toFloat() / (1024 * 1024))})..."
                            )
                        }
                    }
                    MagiskPatcherHelper.validateBootImageForPartition(
                        patchedFile,
                        _state.value.deviceData?.targetPartition
                    )
                } catch (e: Exception) {
                    patchedFile.delete()
                    throw e
                }

                _state.value.patchedFile?.let { oldPatched ->
                    if (oldPatched.absolutePath != patchedFile.absolutePath) {
                        oldPatched.delete()
                    }
                }

                addPatchLog("Cleaning staging area...", "INFO")
                _state.update { it.copy(patchProgress = 0.95f, patchProgressLabel = "Cleaning up...") }
                connection.runShellCommand("rm -rf $workspace")

                addPatchLog("Successfully patched and retrieved!", "SUCCESS")
                _state.update {
                    it.copy(
                        patchStatus = "SUCCESS",
                        patchProgress = 1f,
                        patchProgressLabel = "Patch complete!",
                        patchedFile = patchedFile
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addPatchLog("Error: ${e.message}", "ERROR")
                _state.update {
                    it.copy(
                        patchStatus = "FAILED",
                        patchProgress = 0f,
                        patchProgressLabel = "Failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun rebootToBootloader() {
        if (rebootJob?.isActive == true) return
        val connection = connectionProvider() as? OtgConnectionManager ?: return
        rebootJob = scope.launch(Dispatchers.IO) {
            connection.runShellCommand("reboot bootloader")
            _state.update {
                it.copy(
                    fastbootStatus = "STATUS: REBOOT_SENT",
                    flashStep = 2
                )
            }
        }
    }

    fun flashPatchedImage() {
        if (flashJob?.isActive == true) return
        val connection = connectionProvider() as? OtgConnectionManager ?: return
        val currentState = _state.value
        if (currentState.fastbootStatus == "STATUS: FLASHING_PARTITION") return
        val patchedFile = currentState.patchedFile ?: return
        val deviceData = currentState.deviceData ?: return
        val targetPartition = deviceData.targetPartition

        _state.update {
            it.copy(fastbootStatus = "STATUS: FLASHING_PARTITION")
        }
        addPatchLog("Writing patched image to $targetPartition...", "INFO")

        flashJob = scope.launch(Dispatchers.IO) {
            var flashSuccess = false
            var lastErrorMsg = ""

            try {
                FileInputStream(patchedFile).use { fis ->
                    val primaryResult = connection.flashFastbootImage(targetPartition, fis, patchedFile.length())
                    if (primaryResult.contains("Successfully flashed")) {
                        flashSuccess = true
                    } else {
                        lastErrorMsg = primaryResult
                        addPatchLog("Flash to $targetPartition failed: $primaryResult", "WARNING")
                    }
                }

                if (!flashSuccess) {
                    val slot = deviceData.currentSlot
                    if (slot.isNotEmpty() && !targetPartition.endsWith(slot)) {
                        val slotted = "$targetPartition$slot"
                        addPatchLog("Retrying flash with slot suffix ($slotted)...", "INFO")
                        FileInputStream(patchedFile).use { fis ->
                            val slottedResult = connection.flashFastbootImage(slotted, fis, patchedFile.length())
                            if (slottedResult.contains("Successfully flashed")) {
                                flashSuccess = true
                            } else {
                                lastErrorMsg = slottedResult
                                addPatchLog("Slotted flash ($slotted) failed: $slottedResult", "WARNING")
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErrorMsg = e.message ?: "Unknown flash error"
                addPatchLog("Flash error: ${e.message}", "WARNING")
            }

            if (flashSuccess) {
                addPatchLog("Flash complete!", "SUCCESS")
                _state.update {
                    it.copy(
                        fastbootStatus = "STATUS: FLASH_SUCCESS",
                        flashComplete = true,
                        flashStep = 3
                    )
                }
            } else {
                addPatchLog("Flash fatal error: $lastErrorMsg", "ERROR")
                addPatchLog("Tip: Ensure device is in Bootloader/Fastboot mode and not userspace fastbootd.", "WARNING")
                _state.update {
                    it.copy(fastbootStatus = "STATUS: FLASH_FAILED")
                }
            }
        }
    }

    fun installMagiskApk() {
        if (installApkJob?.isActive == true) return
        val connection = connectionProvider() as? OtgConnectionManager ?: return
        val apkFile = _state.value.magiskApkFile ?: return

        installApkJob = scope.launch(Dispatchers.IO) {
            try {
                FileInputStream(apkFile).use { fis ->
                    connection.pushFile(fis, apkFile.length(), "/data/local/tmp/magisk.apk")
                }
                val res = connection.runShellCommand("pm install -r /data/local/tmp/magisk.apk")
                if (res.contains("Success", ignoreCase = true)) {
                    _state.update { it.copy(finalApkInstalled = true) }
                    addPatchLog("Magisk Manager APK successfully installed on device!", "SUCCESS")
                } else {
                    addPatchLog("Failed to install APK: $res", "ERROR")
                }
                connection.runShellCommand("rm -f /data/local/tmp/magisk.apk")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addPatchLog("Failed to install APK: ${e.message}", "ERROR")
            }
        }
    }

    private fun addExtractLog(msg: String) {
        Log.d(TAG, "[EXTRACT] $msg")
        val entry = LogEntry("INFO", msg)
        _state.update { it.copy(extractLogs = it.extractLogs + entry) }
    }

    private fun addPatchLog(msg: String, type: String = "INFO") {
        if (type == "ERROR") {
            Log.e(TAG, "[PATCH] $msg")
        } else {
            Log.d(TAG, "[PATCH] [$type] $msg")
        }
        val entry = LogEntry(type, msg)
        _state.update { it.copy(patchLogs = it.patchLogs + entry) }
    }

    private companion object {
        const val TAG = "MagiskPatcherController"
    }
}
