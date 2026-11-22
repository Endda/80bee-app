package com.eightbee.app.features

import android.app.Application
import android.net.Uri
import android.util.Log
import com.eightbee.app.BootExtractionResult
import com.eightbee.app.FirmwarePhase
import com.eightbee.app.FlashPhase
import com.eightbee.app.LogEntry
import com.eightbee.app.LogLevel
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
import java.util.EnumMap
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

    private enum class JobSlot {
        RECON, BOOT_FILE, MAGISK_APK, PATCH, REBOOT, FLASH, INSTALL_APK
    }

    private val activeJobs = EnumMap<JobSlot, Job>(JobSlot::class.java)
    private var cachedComponents: Map<String, File> = emptyMap()

    private fun isJobActive(slot: JobSlot): Boolean = activeJobs[slot]?.isActive == true

    private fun cancelActiveJobs() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private fun launchJob(
        slot: JobSlot,
        replaceExisting: Boolean = false,
        block: suspend CoroutineScope.() -> Unit
    ) {
        if (replaceExisting) {
            activeJobs[slot]?.cancel()
        }
        activeJobs[slot] = scope.launch(ioDispatcher, block = block)
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
        clearMagiskComponentsCache()
        _state.value = PixelMagiskPatcherState(isActive = false)
    }

    fun goToStep(step: Int) {
        if (step in 1..6) {
            val currentState = _state.value
            _state.update { it.copy(currentStep = step) }
            if (step == 4 && currentState.patchedFile == null && currentState.patchPhase !is PatchPhase.Succeeded) {
                _state.update { it.copy(patchLogs = emptyList()) }
                addPatchLog("Awaiting execution trigger...")
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

        launchJob(JobSlot.RECON, replaceExisting = true) {
            try {
                if (!connection.isAvailable()) {
                    throw Exception("ADB Connection is still initializing or unauthorized. Please ensure target device is unlocked and authorize the USB debugging prompt on the target device screen.")
                }

                val props = connection.queryKeyValues(RECON_PROP_QUERIES)
                val deviceData = parseReconDeviceData(props)

                _state.update {
                    it.copy(
                        reconPhase = ReconPhase.Succeeded,
                        deviceData = deviceData,
                        showUnlockWarning = !deviceData.isUnlocked
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
                addPatchLog("Recon failed: ${e.message}", LogLevel.ERROR)
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

        launchJob(JobSlot.BOOT_FILE, replaceExisting = true) {
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
                    val tempImg = copyStreamToCacheFile(
                        prefix = "boot_",
                        suffix = ".img",
                        budget = MAX_BOOT_IMAGE_BYTES,
                        openErrorMessage = "Unable to open selected image file.",
                        overflowMessage = "Selected image exceeds the 512 MB boot image limit.",
                        openStream = openStream
                    )
                    try {
                        MagiskPatcherHelper.validateBootImageForPartition(tempImg, deviceData?.targetPartition)
                    } catch (e: Throwable) {
                        tempImg.delete()
                        throw e
                    }
                    BootExtractionResult(tempImg, buildWarning = null)
                }

                deleteIfReplaced(_state.value.bootFile, extractionResult.bootFile)

                _state.update {
                    it.copy(
                        bootFile = extractionResult.bootFile,
                        bootFileName = extractionResult.bootFile.name,
                        bootFileSize = formatMegabytes(extractionResult.bootFile.length()),
                        firmwarePhase = FirmwarePhase.Loaded,
                        imageWarning = extractionResult.buildWarning
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        bootFile = null,
                        bootFileName = "",
                        bootFileSize = "",
                        firmwarePhase = FirmwarePhase.Failed(e.message ?: "Failed to load firmware image."),
                        imageWarning = null
                    )
                }
                addPatchLog("Load boot file failed: ${e.message}", LogLevel.ERROR)
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

        launchJob(JobSlot.MAGISK_APK, replaceExisting = true) {
            var tempApkFile: File? = null
            try {
                tempApkFile = copyStreamToCacheFile(
                    prefix = "magisk_temp_",
                    suffix = ".apk",
                    budget = MAX_MAGISK_APK_BYTES,
                    openErrorMessage = "Unable to open selected Magisk APK.",
                    overflowMessage = "Selected Magisk APK exceeds the 256 MB limit.",
                    openStream = openStream
                )

                addExtractLog("Processing archive entries...")

                val components = extractMagiskComponentsFresh(tempApkFile)
                for (name in components.keys) {
                    addExtractLog("Decompressing $name...")
                }

                deleteIfReplaced(_state.value.magiskApkFile, tempApkFile)

                addExtractLog("Extraction complete. Environment ready.")

                _state.update {
                    it.copy(
                        magiskApkFile = tempApkFile,
                        magiskExtractPhase = MagiskExtractPhase.Ready
                    )
                }
            } catch (e: CancellationException) {
                tempApkFile?.delete()
                clearMagiskComponentsCache()
                throw e
            } catch (e: Exception) {
                tempApkFile?.delete()
                clearMagiskComponentsCache()
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
        if (isJobActive(JobSlot.PATCH)) return
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
        addPatchLog("Initializing scoped workspace...")

        launchJob(JobSlot.PATCH) {
            val workspace = "/data/local/tmp/80bee_patcher"
            try {
                connection.runShellCommand("rm -rf $workspace && mkdir -p $workspace")

                patchStep(0.1f, "Uploading boot image...", log = "Pushing boot image...")
                val remoteBootPath = "$workspace/boot.img"
                FileInputStream(bootFile).use { fis ->
                    connection.pushFile(fis, bootFile.length(), remoteBootPath) { sent, total ->
                        patchStep(0.1f + (sent.toFloat() / total) * 0.4f, "Uploading boot image (${formatMegabytes(sent)})...")
                    }
                }

                patchStep(0.5f, "Pushing Magisk binaries...", log = "Pushing Magisk components...")

                val extractedFiles = cachedComponents
                    .takeIf { it.isNotEmpty() && it.values.all(File::exists) }
                    ?: extractMagiskComponentsFresh(apkFile)

                val totalComp = extractedFiles.size
                var compIdx = 0
                for ((name, file) in extractedFiles) {
                    FileInputStream(file).use { fis ->
                        connection.pushFile(fis, file.length(), "$workspace/$name")
                    }
                    compIdx++
                    patchStep(0.5f + (compIdx.toFloat() / totalComp) * 0.15f, "Pushed $name")
                }

                patchStep(0.7f, "Setting permissions...", log = "Executing boot_patch.sh...")
                connection.runShellCommand("chmod +x $workspace/*")

                patchStep(0.75f, "Patching kernel image...")
                val patchCmd = "cd $workspace && export KEEPVERITY=true; export KEEPFORCEENCRYPT=true; export PATCHVBMETAFLAG=false; sh boot_patch.sh $remoteBootPath"

                connection.runShellCommandLive(patchCmd) { line ->
                    addPatchLog(line, LogLevel.PROMPT)
                }

                patchStep(0.8f, "Locating patched image...", log = "Locating patched image...")
                val checkOut = connection.runShellCommand("ls $workspace/new-boot.img").trim()
                if (checkOut.contains("No such file") || checkOut.isEmpty()) {
                    throw Exception("Patched image not found! Script output was logged above.")
                }

                patchStep(0.85f, "Pulling patched image...", log = "Pulling patched image to host...")
                val remotePatchedPath = "$workspace/new-boot.img"
                val patchedFile = File.createTempFile("patched_boot_", ".img", cacheDirProvider())
                try {
                    connection.pullFile(
                        remotePath = remotePatchedPath,
                        destination = patchedFile,
                        maxBytes = MAX_BOOT_IMAGE_BYTES
                    ) { received ->
                        patchStep(0.85f, "Pulling patched image (${formatMegabytes(received)})...")
                    }
                    MagiskPatcherHelper.validateBootImageForPartition(
                        patchedFile,
                        _state.value.deviceData?.targetPartition
                    )
                } catch (e: Exception) {
                    patchedFile.delete()
                    throw e
                }

                deleteIfReplaced(_state.value.patchedFile, patchedFile)

                patchStep(0.95f, "Cleaning up...", log = "Cleaning staging area...")
                connection.runShellCommand("rm -rf $workspace")

                addPatchLog("Successfully patched and retrieved!", LogLevel.SUCCESS)
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
                addPatchLog("Error: $msg", LogLevel.ERROR)
                _state.update {
                    it.copy(patchPhase = PatchPhase.Failed(msg))
                }
            }
        }
    }

    fun rebootToBootloader() {
        if (isJobActive(JobSlot.REBOOT)) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        launchJob(JobSlot.REBOOT) {
            try {
                connection.runShellCommand("reboot bootloader")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addPatchLog("Reboot command note: ${e.message}", LogLevel.WARNING)
            }
            _state.update { it.copy(flashPhase = FlashPhase.RebootSent) }
        }
    }

    fun flashPatchedImage() {
        if (isJobActive(JobSlot.FLASH)) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        val currentState = _state.value
        if (currentState.flashPhase == FlashPhase.Flashing) return
        val patchedFile = currentState.patchedFile ?: return
        val deviceData = currentState.deviceData ?: return
        val targetPartition = deviceData.targetPartition

        _state.update { it.copy(flashPhase = FlashPhase.Flashing) }
        addPatchLog("Writing patched image to $targetPartition...")

        val slot = deviceData.currentSlot
        val attempts = listOfNotNull(
            FlashAttempt(targetPartition, intro = null, failure = "Flash to $targetPartition failed", fallbackError = "Flash failed"),
            "$targetPartition$slot".takeIf { slot.isNotEmpty() && !targetPartition.endsWith(slot) }?.let { slotted ->
                FlashAttempt(
                    slotted,
                    intro = "Retrying flash with slot suffix ($slotted)...",
                    failure = "Slotted flash ($slotted) failed",
                    fallbackError = "Slotted flash failed"
                )
            }
        )

        launchJob(JobSlot.FLASH) {
            var lastErrorMsg = ""
            val flashSuccess = attempts.any { attempt ->
                attempt.intro?.let { addPatchLog(it) }
                try {
                    FileInputStream(patchedFile).use { fis ->
                        connection.flashFastbootImage(attempt.partition, fis, patchedFile.length())
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: attempt.fallbackError
                    addPatchLog("${attempt.failure}: $lastErrorMsg", LogLevel.WARNING)
                    false
                }
            }

            if (flashSuccess) {
                addPatchLog("Flash complete!", LogLevel.SUCCESS)
                _state.update { it.copy(flashPhase = FlashPhase.Succeeded) }
            } else {
                addPatchLog("Flash fatal error: $lastErrorMsg", LogLevel.ERROR)
                addPatchLog("Tip: Ensure device is in Bootloader/Fastboot mode and not userspace fastbootd.", LogLevel.WARNING)
                _state.update { it.copy(flashPhase = FlashPhase.Failed(lastErrorMsg)) }
            }
        }
    }

    fun installMagiskApk() {
        if (isJobActive(JobSlot.INSTALL_APK)) return
        val connection = connectionProvider()?.takeIf { it.supportsFastboot } ?: return
        val apkFile = _state.value.magiskApkFile ?: return

        launchJob(JobSlot.INSTALL_APK) {
            try {
                FileInputStream(apkFile).use { fis ->
                    connection.pushFile(fis, apkFile.length(), "/data/local/tmp/magisk.apk")
                }
                val res = connection.runShellCommand("pm install -r /data/local/tmp/magisk.apk")
                if (res.contains("Success", ignoreCase = true)) {
                    _state.update { it.copy(finalApkInstalled = true) }
                    addPatchLog("Magisk Manager APK successfully installed on device!", LogLevel.SUCCESS)
                } else {
                    addPatchLog("Failed to install APK: $res", LogLevel.ERROR)
                }
                connection.runShellCommand("rm -f /data/local/tmp/magisk.apk")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addPatchLog("Failed to install APK: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private fun parseReconDeviceData(props: Map<String, String>): MagiskDeviceData {
        val codename = props["codename"].orEmpty()
        val buildId = props["build_id"].orEmpty()
        val previewSdk = props["preview_sdk"]?.toIntOrNull() ?: 0
        val buildCodename = props["build_codename"].orEmpty()
        val abi = props["abi"].orEmpty()
        val flashLocked = props["flash_locked"].orEmpty()
        val vbState = props["vb_state"].orEmpty()

        val resolution = MagiskPatcherHelper.resolveReconTarget(
            codename = codename,
            abi = abi,
            firstApiLevelRaw = props["first_api"].orEmpty(),
            boardApiLevelRaw = props["board_api"].orEmpty(),
            vendorApiLevelRaw = props["vendor_api"].orEmpty()
        )

        return MagiskDeviceData(
            codename = codename,
            buildId = buildId,
            apiLevel = resolution.apiLevel,
            previewSdk = previewSdk,
            isBeta = previewSdk > 0 || buildCodename != "REL" ||
                buildId.contains("beta", ignoreCase = true) ||
                buildId.contains("preview", ignoreCase = true),
            abi = abi,
            oemAllowed = props["oem_allowed"] == "1",
            currentSlot = props["slot_suffix"].orEmpty(),
            isUnlocked = flashLocked == "0" || vbState == "orange",
            targetPartition = resolution.targetPartition
        )
    }

    private fun copyStreamToCacheFile(
        prefix: String,
        suffix: String,
        budget: Long,
        openErrorMessage: String,
        overflowMessage: String,
        openStream: () -> InputStream?
    ): File {
        val tempFile = File.createTempFile(prefix, suffix, cacheDirProvider())
        try {
            val inputStream = openStream() ?: throw IllegalStateException(openErrorMessage)
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    copyBounded(
                        input = input,
                        output = output,
                        budget = budget,
                        overflowMessage = overflowMessage
                    )
                }
            }
            return tempFile
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    private fun magiskComponentsDir(): File = File(cacheDirProvider(), "magisk_components")

    private fun extractMagiskComponentsFresh(apkFile: File): Map<String, File> {
        val componentsDir = magiskComponentsDir()
        componentsDir.deleteRecursively()
        componentsDir.mkdirs()
        return MagiskPatcherHelper.extractMagiskComponents(apkFile, componentsDir).also {
            cachedComponents = it
        }
    }

    private fun clearMagiskComponentsCache() {
        cachedComponents = emptyMap()
        magiskComponentsDir().deleteRecursively()
    }

    private fun deleteIfReplaced(previous: File?, current: File) {
        if (previous != null && previous.absolutePath != current.absolutePath) {
            previous.delete()
        }
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024 * 1024))

    private fun addExtractLog(msg: String) {
        logSink(false, TAG, "[EXTRACT] $msg")
        val entry = LogEntry(LogLevel.INFO, msg)
        _state.update { it.copy(extractLogs = it.extractLogs + entry) }
    }

    private fun addPatchLog(msg: String, level: LogLevel = LogLevel.INFO) {
        if (level == LogLevel.ERROR) {
            logSink(true, TAG, "[PATCH] $msg")
        } else {
            logSink(false, TAG, "[PATCH] [$level] $msg")
        }
        val entry = LogEntry(level, msg)
        _state.update { it.copy(patchLogs = it.patchLogs + entry) }
    }

    /** Logs [log] if given, then shows [label] at [progress] on the patch progress bar. */
    private fun patchStep(progress: Float, label: String, log: String? = null) {
        log?.let { addPatchLog(it) }
        _state.update { it.copy(patchPhase = PatchPhase.InProgress(progress, label)) }
    }

    /** One fastboot write target, with the log wording for trying it and for its failure. */
    private class FlashAttempt(val partition: String, val intro: String?, val failure: String, val fallbackError: String)

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
