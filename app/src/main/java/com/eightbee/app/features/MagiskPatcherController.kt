package com.eightbee.app.features

import android.app.Application
import android.net.Uri
import android.util.Log
import com.eightbee.app.BootExtractionResult
import com.eightbee.app.FirmwarePhase
import com.eightbee.app.FlashPhase
import com.eightbee.app.LogEntry
import com.eightbee.app.MagiskDeviceData
import com.eightbee.app.MagiskExtractPhase
import com.eightbee.app.MagiskPatcherHelper
import com.eightbee.app.PatchPhase
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.ReconPhase
import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.InputStream
import java.util.Locale

/** Coordinates the 6-step Pixel Magisk boot/init_boot patching and fastboot flashing workflow. */
class MagiskPatcherController internal constructor(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> ConnectionManager?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheDirProvider: () -> File,
    private val openUriStream: (Uri) -> InputStream? = { null },
    private val uriFileNameProvider: (Uri) -> String = { "unknown" },
    private val uriSizeProvider: (Uri) -> Long = { -1L },
    private val logSink: (isError: Boolean, tag: String, message: String) -> Unit = { isError, tag, message ->
        if (isError) Log.e(tag, message) else Log.d(tag, message)
    }
) {
    constructor(
        application: Application,
        scope: CoroutineScope,
        connectionProvider: () -> ConnectionManager?
    ) : this(
        scope = scope,
        connectionProvider = connectionProvider,
        ioDispatcher = Dispatchers.IO,
        cacheDirProvider = { application.cacheDir },
        openUriStream = { uri -> application.contentResolver.openInputStream(uri) },
        uriFileNameProvider = { uri -> getUriFileName(application, uri) },
        uriSizeProvider = { uri -> getUriSize(application, uri) }
    )
    private val _state = MutableStateFlow(PixelMagiskPatcherState())
    val state: StateFlow<PixelMagiskPatcherState> = _state.asStateFlow()

    private var reconJob: Job? = null
    private var bootFileJob: Job? = null
    private var magiskApkJob: Job? = null
    private var patchJob: Job? = null
    private var rebootJob: Job? = null
    private var flashJob: Job? = null
    private var installApkJob: Job? = null

    private var cachedComponents: Map<String, File> = emptyMap()

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
                        flashPhase = if (current.flashPhase == FlashPhase.AwaitingHandshake || current.flashPhase == FlashPhase.RebootSent) {
                            FlashPhase.FastbootReady
                        } else {
                            current.flashPhase
                        }
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
        if (connection != null && connection.supportsFastboot && connection.connectionState.value == ConnectionState.AdbConnected) {
            startRecon()
        }
    }

    fun clearPatcherState() {
        cancelActiveJobs()
        val currentState = _state.value
        currentState.bootFile?.delete()
        currentState.magiskApkFile?.delete()
        currentState.patchedFile?.delete()
        cachedComponents = emptyMap()
        File(cacheDirProvider(), "magisk_components").deleteRecursively()
        _state.value = PixelMagiskPatcherState(isActive = false)
    }

    fun goToStep(step: Int) {
        if (step in 1..6) {
            val currentState = _state.value
            _state.update { it.copy(currentStep = step) }
            if (step == 4 && currentState.patchedFile == null && currentState.patchPhase !is PatchPhase.Succeeded) {
                _state.update { it.copy(patchLogs = emptyList()) }
                addPatchLog("Awaiting execution trigger...", "INFO")
            }
        }
    }

    fun startRecon() {
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        var started = false
        _state.update { current ->
            if (current.reconPhase is ReconPhase.InProgress) {
                current
            } else {
                started = true
                current.copy(reconPhase = ReconPhase.InProgress)
            }
        }
        if (!started) return

        reconJob = scope.launch(ioDispatcher) {
            try {
                if (!connection.isAvailable()) {
                    throw Exception("ADB Connection is still initializing or unauthorized. Please ensure target device is unlocked and authorize the USB debugging prompt on the target device screen.")
                }

                val props = connection.queryKeyValues(RECON_PROP_QUERIES)

                val codename = props["codename"].orEmpty()
                val buildId = props["build_id"].orEmpty()
                val apiLevelStr = props["first_api"].orEmpty()
                val boardApiLevelStr = props["board_api"].orEmpty()
                val vendorApiLevelStr = props["vendor_api"].orEmpty()
                val previewSdk = props["preview_sdk"]?.toIntOrNull() ?: 0
                val buildCodename = props["build_codename"].orEmpty()
                val abi = props["abi"].orEmpty()
                val oemAllowed = props["oem_allowed"] == "1"
                val currentSlot = props["slot_suffix"].orEmpty()

                val flashLocked = props["flash_locked"].orEmpty()
                val vbState = props["vb_state"].orEmpty()
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
                        reconPhase = ReconPhase.Succeeded,
                        deviceData = deviceData,
                        showUnlockWarning = !isUnlocked
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        reconPhase = ReconPhase.Failed(e.message ?: "Hardware recon failed.")
                    )
                }
                addPatchLog("Recon failed: ${e.message}", "ERROR")
            }
        }
    }

    fun handleBootFileSelected(uri: Uri) {
        loadBootFile(
            fileName = uriFileNameProvider(uri),
            totalBytes = uriSizeProvider(uri),
            openStream = { openUriStream(uri) }
        )
    }

    internal fun loadBootFile(
        fileName: String,
        totalBytes: Long,
        openStream: () -> InputStream?
    ) {
        _state.update {
            it.copy(
                firmwarePhase = FirmwarePhase.InProgress("PROCESSING", 0.05f),
                imageWarning = null
            )
        }

        bootFileJob?.cancel()
        bootFileJob = scope.launch(ioDispatcher) {
            var copiedRawFile: File? = null
            try {
                val cacheDir = cacheDirProvider()
                val deviceData = _state.value.deviceData

                val isZip = fileName.endsWith(".zip", ignoreCase = true) || run {
                    val probe = ByteArray(4)
                    val read = openStream()?.use { it.read(probe) } ?: 0
                    MagiskPatcherHelper.isZipMagic(probe, read)
                }

                val extractionResult: BootExtractionResult = if (isZip) {
                    _state.update {
                        it.copy(firmwarePhase = FirmwarePhase.InProgress("SCANNING_ARCHIVE", 0.1f))
                    }
                    val inputStream = openStream()
                        ?: throw IllegalStateException("Unable to open selected archive.")
                    inputStream.use { stream ->
                        MagiskPatcherHelper.extractBootImageFromStream(
                            inputStream = stream,
                            cacheDir = cacheDir,
                            targetPartition = deviceData?.targetPartition,
                            expectedCodename = deviceData?.codename,
                            expectedBuildId = deviceData?.buildId,
                            totalInputBytes = totalBytes,
                            sourceFileName = fileName
                        ) { status, progress ->
                            _state.update {
                                it.copy(firmwarePhase = FirmwarePhase.InProgress(status, progress))
                            }
                        }
                    }
                } else {
                    val tempImg = File(cacheDir, "boot_${System.currentTimeMillis()}.img")
                    copiedRawFile = tempImg
                    val inputStream = openStream()
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
                        firmwarePhase = FirmwarePhase.Loaded,
                        imageWarning = extractionResult.buildWarning
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
                        firmwarePhase = FirmwarePhase.Failed(e.message ?: "Failed to load firmware image."),
                        imageWarning = null
                    )
                }
                addPatchLog("Load boot file failed: ${e.message}", "ERROR")
            }
        }
    }

    fun handleMagiskApkSelected(uri: Uri) {
        loadMagiskApk(
            fileName = uriFileNameProvider(uri),
            openStream = { openUriStream(uri) }
        )
    }

    internal fun loadMagiskApk(
        fileName: String,
        openStream: () -> InputStream?
    ) {
        _state.update {
            it.copy(
                magiskExtractPhase = MagiskExtractPhase.InProgress(0.1f),
                extractLogs = emptyList()
            )
        }
        addExtractLog("Scanning application: $fileName")

        magiskApkJob?.cancel()
        magiskApkJob = scope.launch(ioDispatcher) {
            val cacheDir = cacheDirProvider()
            val tempApkFile = File(cacheDir, "magisk_temp_${System.currentTimeMillis()}.apk")
            val componentsDir = File(cacheDir, "magisk_components")
            try {
                val apkStream = openStream()
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
                cachedComponents = components

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
                        magiskExtractPhase = MagiskExtractPhase.Ready
                    )
                }
            } catch (e: CancellationException) {
                tempApkFile.delete()
                cachedComponents = emptyMap()
                componentsDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                tempApkFile.delete()
                cachedComponents = emptyMap()
                componentsDir.deleteRecursively()
                _state.update {
                    it.copy(
                        magiskExtractPhase = MagiskExtractPhase.Failed(e.message ?: "Failed to extract Magisk components.")
                    )
                }
                addExtractLog("Fatal error: ${e.message}")
            }
        }
    }

    fun startPatching() {
        if (patchJob?.isActive == true) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        val currentState = _state.value
        val bootFile = currentState.bootFile ?: return
        val apkFile = currentState.magiskApkFile ?: return

        _state.update {
            it.copy(
                patchPhase = PatchPhase.InProgress(0.05f, "Initializing scoped workspace..."),
                patchLogs = emptyList()
            )
        }
        addPatchLog("Initializing scoped workspace...", "INFO")

        patchJob = scope.launch(ioDispatcher) {
            val workspace = "/data/local/tmp/80bee_patcher"
            try {
                connection.runShellCommand("rm -rf $workspace && mkdir -p $workspace")

                addPatchLog("Pushing boot image...", "INFO")
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.1f, "Uploading boot image...")) }
                val remoteBootPath = "$workspace/boot.img"
                FileInputStream(bootFile).use { fis ->
                    connection.pushFile(fis, bootFile.length(), remoteBootPath) { sent, total ->
                        val progress = 0.1f + (sent.toFloat() / total) * 0.4f
                        val label = "Uploading boot image (${String.format(Locale.US, "%.1f MB", sent.toFloat() / (1024 * 1024))})..."
                        _state.update { it.copy(patchPhase = PatchPhase.InProgress(progress, label)) }
                    }
                }

                addPatchLog("Pushing Magisk components...", "INFO")
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.5f, "Pushing Magisk binaries...")) }

                val componentsDir = File(cacheDirProvider(), "magisk_components")
                val extractedFiles = cachedComponents
                    .takeIf { it.isNotEmpty() && it.values.all(File::exists) }
                    ?: run {
                        componentsDir.deleteRecursively()
                        componentsDir.mkdirs()
                        MagiskPatcherHelper.extractMagiskComponents(apkFile, componentsDir).also {
                            cachedComponents = it
                        }
                    }

                val totalComp = extractedFiles.size
                var compIdx = 0
                for ((name, file) in extractedFiles) {
                    FileInputStream(file).use { fis ->
                        connection.pushFile(fis, file.length(), "$workspace/$name")
                    }
                    compIdx++
                    val progress = 0.5f + (compIdx.toFloat() / totalComp) * 0.15f
                    _state.update { it.copy(patchPhase = PatchPhase.InProgress(progress, "Pushed $name")) }
                }

                addPatchLog("Executing boot_patch.sh...", "INFO")
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.7f, "Setting permissions...")) }
                connection.runShellCommand("chmod +x $workspace/*")

                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.75f, "Patching kernel image...")) }
                val patchCmd = "cd $workspace && export KEEPVERITY=true; export KEEPFORCEENCRYPT=true; export PATCHVBMETAFLAG=false; sh boot_patch.sh $remoteBootPath"

                connection.runShellCommandLive(patchCmd) { line ->
                    addPatchLog(line, "PROMPT")
                }

                addPatchLog("Locating patched image...", "INFO")
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.8f, "Locating patched image...")) }
                val checkOut = connection.runShellCommand("ls $workspace/new-boot.img").trim()
                if (checkOut.contains("No such file") || checkOut.isEmpty()) {
                    throw Exception("Patched image not found! Script output was logged above.")
                }

                addPatchLog("Pulling patched image to host...", "INFO")
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.85f, "Pulling patched image...")) }
                val remotePatchedPath = "$workspace/new-boot.img"
                val patchedFile = File(cacheDirProvider(), "patched_boot_${System.currentTimeMillis()}.img")
                try {
                    connection.pullFile(
                        remotePath = remotePatchedPath,
                        destination = patchedFile,
                        maxBytes = MAX_BOOT_IMAGE_BYTES
                    ) { received ->
                        val label = "Pulling patched image (${String.format(Locale.US, "%.1f MB", received.toFloat() / (1024 * 1024))})..."
                        _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.85f, label)) }
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
                _state.update { it.copy(patchPhase = PatchPhase.InProgress(0.95f, "Cleaning up...")) }
                connection.runShellCommand("rm -rf $workspace")

                addPatchLog("Successfully patched and retrieved!", "SUCCESS")
                _state.update {
                    it.copy(
                        patchPhase = PatchPhase.Succeeded,
                        patchedFile = patchedFile
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Patching failed"
                addPatchLog("Error: $msg", "ERROR")
                _state.update {
                    it.copy(patchPhase = PatchPhase.Failed(msg))
                }
            }
        }
    }

    fun rebootToBootloader() {
        if (rebootJob?.isActive == true) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        rebootJob = scope.launch(ioDispatcher) {
            try {
                connection.runShellCommand("reboot bootloader")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addPatchLog("Reboot command note: ${e.message}", "WARNING")
            }
            _state.update { it.copy(flashPhase = FlashPhase.RebootSent) }
        }
    }

    fun flashPatchedImage() {
        if (flashJob?.isActive == true) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        val currentState = _state.value
        if (currentState.flashPhase == FlashPhase.Flashing) return
        val patchedFile = currentState.patchedFile ?: return
        val deviceData = currentState.deviceData ?: return
        val targetPartition = deviceData.targetPartition

        _state.update { it.copy(flashPhase = FlashPhase.Flashing) }
        addPatchLog("Writing patched image to $targetPartition...", "INFO")

        flashJob = scope.launch(ioDispatcher) {
            var flashSuccess = false
            var lastErrorMsg = ""

            try {
                try {
                    FileInputStream(patchedFile).use { fis ->
                        connection.flashFastbootImage(targetPartition, fis, patchedFile.length())
                    }
                    flashSuccess = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: "Flash failed"
                    addPatchLog("Flash to $targetPartition failed: $lastErrorMsg", "WARNING")
                }

                if (!flashSuccess) {
                    val slot = deviceData.currentSlot
                    if (slot.isNotEmpty() && !targetPartition.endsWith(slot)) {
                        val slotted = "$targetPartition$slot"
                        addPatchLog("Retrying flash with slot suffix ($slotted)...", "INFO")
                        try {
                            FileInputStream(patchedFile).use { fis ->
                                connection.flashFastbootImage(slotted, fis, patchedFile.length())
                            }
                            flashSuccess = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastErrorMsg = e.message ?: "Slotted flash failed"
                            addPatchLog("Slotted flash ($slotted) failed: $lastErrorMsg", "WARNING")
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
                _state.update { it.copy(flashPhase = FlashPhase.Succeeded) }
            } else {
                addPatchLog("Flash fatal error: $lastErrorMsg", "ERROR")
                addPatchLog("Tip: Ensure device is in Bootloader/Fastboot mode and not userspace fastbootd.", "WARNING")
                _state.update { it.copy(flashPhase = FlashPhase.Failed(lastErrorMsg)) }
            }
        }
    }

    fun installMagiskApk() {
        if (installApkJob?.isActive == true) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        val apkFile = _state.value.magiskApkFile ?: return

        installApkJob = scope.launch(ioDispatcher) {
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
        logSink(false, TAG, "[EXTRACT] $msg")
        val entry = LogEntry("INFO", msg)
        _state.update { it.copy(extractLogs = it.extractLogs + entry) }
    }

    private fun addPatchLog(msg: String, type: String = "INFO") {
        if (type == "ERROR") {
            logSink(true, TAG, "[PATCH] $msg")
        } else {
            logSink(false, TAG, "[PATCH] [$type] $msg")
        }
        val entry = LogEntry(type, msg)
        _state.update { it.copy(patchLogs = it.patchLogs + entry) }
    }

    private companion object {
        const val TAG = "MagiskPatcherController"

        val RECON_PROP_QUERIES = linkedMapOf(
            "codename" to "getprop ro.product.device",
            "build_id" to "getprop ro.build.display.id",
            "first_api" to "getprop ro.product.first_api_level",
            "board_api" to "getprop ro.board.first_api_level",
            "vendor_api" to "getprop ro.vendor.api_level",
            "preview_sdk" to "getprop ro.build.version.preview_sdk",
            "build_codename" to "getprop ro.build.version.codename",
            "abi" to "getprop ro.product.cpu.abi",
            "oem_allowed" to "getprop sys.oem_unlock_allowed",
            "slot_suffix" to "getprop ro.boot.slot_suffix",
            "flash_locked" to "getprop ro.boot.flash.locked",
            "vb_state" to "getprop ro.boot.verifiedbootstate"
        )
    }
}
