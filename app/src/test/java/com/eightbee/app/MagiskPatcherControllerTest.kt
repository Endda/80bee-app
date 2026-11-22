package com.eightbee.app

import com.eightbee.app.connection.ConnectionManager
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.features.MagiskPatcherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MagiskPatcherControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakePatcherConnection(
        initialState: ConnectionState = ConnectionState.AdbConnected,
        override val supportsFastboot: Boolean = true,
        var available: Boolean = true,
        var shellResponder: (String) -> String = { "" },
        var pullResponder: (String, File) -> Unit = { _, _ -> },
        var flashResponder: (String, ByteArray) -> String = { partition, _ -> "Successfully flashed $partition!" }
    ) : ConnectionManager {
        private val _connectionState = MutableStateFlow(initialState)
        override val isAvailableFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override val connectionState: StateFlow<ConnectionState> = _connectionState
        override val isLocal: Boolean = false

        val shellCommands = mutableListOf<String>()
        val pushedFiles = mutableListOf<Pair<String, ByteArray>>()
        val flashedPartitions = mutableListOf<Pair<String, ByteArray>>()
        var onCall: (String) -> Unit = {}

        fun emitState(state: ConnectionState) {
            _connectionState.value = state
        }

        override suspend fun runShellCommand(command: String): String {
            onCall("shell $command")
            shellCommands.add(command)
            return shellResponder(command)
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""

        override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {
            onCall("live $command")
            shellCommands.add(command)
            onLineRead("Patching boot image...")
        }

        override suspend fun pushFile(
            input: InputStream,
            size: Long,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?
        ) {
            onCall("push $remotePath")
            val bytes = input.readBytes()
            pushedFiles.add(remotePath to bytes)
            onProgress?.invoke(bytes.size.toLong(), size.coerceAtLeast(1L))
        }

        override suspend fun pullFile(
            remotePath: String,
            destination: File,
            maxBytes: Long,
            onProgress: ((Long) -> Unit)?
        ) {
            onCall("pull $remotePath")
            pullResponder(remotePath, destination)
            onProgress?.invoke(destination.length())
        }

        override suspend fun sendFastbootCommand(command: String): FastbootReply =
            FastbootReply(status = "OKAY", message = "done")

        override suspend fun flashFastbootImage(partition: String, stream: InputStream, size: Long): String {
            val bytes = stream.readBytes()
            flashedPartitions.add(partition to bytes)
            return flashResponder(partition, bytes)
        }

        override fun isAvailable(): Boolean = available
    }

    private fun buildBootHeader(kernelSize: Int, ramdiskSize: Int, totalSize: Int = 4096): ByteArray {
        val bytes = ByteArray(totalSize)
        val magic = "ANDROID!".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, bytes, 0, magic.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(8, kernelSize)
        buf.putInt(12, ramdiskSize)
        buf.putInt(40, 4)
        return bytes
    }

    private fun buildMagiskApkBytes(includeAll: Boolean = true): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entries = if (includeAll) {
                listOf(
                    "assets/boot_patch.sh" to "echo patch",
                    "assets/util_functions.sh" to "echo util",
                    "assets/stub.apk" to "stub",
                    "lib/arm64-v8a/libmagiskboot.so" to "bootbin",
                    "lib/arm64-v8a/libmagiskinit.so" to "initbin",
                    "lib/arm64-v8a/libmagisk64.so" to "magisk64bin"
                )
            } else {
                listOf("assets/boot_patch.sh" to "echo patch")
            }
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun reconShellOutput(
        codename: String = "shiba",
        buildId: String = "AP2A.240805.005",
        firstApi: String = "34",
        abi: String = "arm64-v8a",
        slotSuffix: String = "_b",
        flashLocked: String = "1",
        vbState: String = "green",
        buildCodename: String = "REL"
    ): String {
        return """
            __K_codename__=$codename
            __K_build_id__=$buildId
            __K_first_api__=$firstApi
            __K_board_api__=
            __K_vendor_api__=
            __K_preview_sdk__=0
            __K_build_codename__=$buildCodename
            __K_abi__=$abi
            __K_oem_allowed__=1
            __K_slot_suffix__=$slotSuffix
            __K_flash_locked__=$flashLocked
            __K_vb_state__=$vbState
        """.trimIndent()
    }

    private fun createController(
        scope: kotlinx.coroutines.CoroutineScope,
        cacheDir: File = tempFolder.newFolder("cache"),
        logSink: (Boolean, String, String) -> Unit = { _, _, _ -> },
        connectionProvider: () -> ConnectionManager?
    ): MagiskPatcherController {
        return MagiskPatcherController(
            scope = scope,
            connectionProvider = connectionProvider,
            ioDispatcher = Dispatchers.Unconfined,
            cacheDirProvider = { cacheDir },
            logSink = logSink
        )
    }

    @Test
    fun startPatcher_parsesReconPropsAndSetsUnlockWarning() = runBlocking {
        val conn = FakePatcherConnection(
            shellResponder = {
                reconShellOutput(
                    codename = "shiba",
                    buildId = "AP2A.240805.005-beta1",
                    firstApi = "34",
                    slotSuffix = "_a",
                    flashLocked = "1",
                    vbState = "green"
                )
            }
        )
        val controller = createController(this) { conn }

        controller.startPatcher()

        val state = controller.state.value
        assertTrue(state.isActive)
        assertEquals(1, state.currentStep)
        assertEquals(ReconPhase.Succeeded, state.reconPhase)
        assertTrue(state.showUnlockWarning)
        val data = state.deviceData
        assertNotNull(data)
        assertEquals("shiba", data!!.codename)
        assertEquals(34, data.apiLevel)
        assertTrue(data.isBeta)
        assertTrue(data.oemAllowed)
        assertEquals("_a", data.currentSlot)
        assertFalse(data.isUnlocked)
        assertEquals("init_boot", data.targetPartition)
    }

    @Test
    fun startRecon_recordsFailureWhenUnavailable() = runBlocking {
        val conn = FakePatcherConnection(available = false)
        val controller = createController(this) { conn }

        controller.startRecon()

        val recon = controller.state.value.reconPhase
        assertTrue(recon is ReconPhase.Failed)
        assertTrue((recon as ReconPhase.Failed).message.contains("unauthorized"))
        assertTrue(controller.state.value.patchLogs.any { it.level == LogLevel.ERROR && it.message.contains("Recon failed") })
    }

    @Test
    fun goToStep_enforcesBoundsAndInitializesStep4LogsOnlyBeforeSuccess() = runBlocking {
        val conn = FakePatcherConnection(shellResponder = { reconShellOutput() })
        val controller = createController(this) { conn }
        controller.startPatcher()

        controller.goToStep(0)
        assertEquals(1, controller.state.value.currentStep)
        controller.goToStep(7)
        assertEquals(1, controller.state.value.currentStep)

        controller.goToStep(4)
        assertEquals(4, controller.state.value.currentStep)
        assertEquals(listOf("Awaiting execution trigger..."), controller.state.value.patchLogs.map { it.message })
    }

    @Test
    fun onConnectionStateChanged_updatesFastbootReadyAndTriggersReconOnStep1() = runBlocking {
        val conn = FakePatcherConnection(
            initialState = ConnectionState.Disconnected,
            shellResponder = { reconShellOutput(flashLocked = "0", vbState = "orange") }
        )
        val controller = createController(this) { conn }

        // Inactive controller ignores state changes
        controller.onConnectionStateChanged(ConnectionState.AdbConnected)
        assertEquals(ReconPhase.Idle, controller.state.value.reconPhase)

        controller.startPatcher()
        assertEquals(ReconPhase.Idle, controller.state.value.reconPhase)

        controller.onConnectionStateChanged(ConnectionState.AdbConnected)
        assertEquals(ReconPhase.Succeeded, controller.state.value.reconPhase)
        assertFalse(controller.state.value.showUnlockWarning)

        controller.onConnectionStateChanged(ConnectionState.FastbootConnected)
        assertTrue(controller.state.value.isFastbootConnected)
        assertEquals(FlashPhase.FastbootReady, controller.state.value.flashPhase)
    }

    @Test
    fun loadBootFile_replacesPreviousFileAndCleansUpOnValidationFailure() = runBlocking {
        val cacheDir = tempFolder.newFolder("boot_cache")
        val conn = FakePatcherConnection(shellResponder = { reconShellOutput(firstApi = "34") })
        val controller = createController(this, cacheDir) { conn }
        controller.startPatcher()

        val initBootBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        controller.loadBootFile("init_boot.img", initBootBytes.size.toLong()) {
            ByteArrayInputStream(initBootBytes)
        }
        assertEquals(FirmwarePhase.Loaded, controller.state.value.firmwarePhase)
        val firstFile = controller.state.value.bootFile!!
        assertTrue(firstFile.exists())

        // Loading a second valid image replaces and deletes the previous cached file
        controller.loadBootFile("init_boot_2.img", initBootBytes.size.toLong()) {
            ByteArrayInputStream(initBootBytes)
        }
        val secondFile = controller.state.value.bootFile!!
        assertTrue(secondFile.exists())
        assertFalse(firstFile.exists())

        // Loading an invalid boot image deletes the temp file and resets state
        val badBytes = "BAD_IMAGE".repeat(16).toByteArray()
        controller.loadBootFile("bad.img", badBytes.size.toLong()) {
            ByteArrayInputStream(badBytes)
        }
        assertTrue(controller.state.value.firmwarePhase is FirmwarePhase.Failed)
        assertNull(controller.state.value.bootFile)
        assertEquals(listOf(secondFile.name), cacheDir.listFiles()?.map { it.name })
    }

    @Test
    fun loadBootFile_backToBackLoadsNeverClobberTheLoadedFile() = runBlocking {
        val cacheDir = tempFolder.newFolder("rapid_cache")
        val conn = FakePatcherConnection(shellResponder = { reconShellOutput(firstApi = "34") })
        val controller = createController(this, cacheDir) { conn }
        controller.startPatcher()

        val goodBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        val badBytes = "BAD_IMAGE".repeat(16).toByteArray()
        repeat(50) {
            controller.loadBootFile("init_boot.img", goodBytes.size.toLong()) { ByteArrayInputStream(goodBytes) }
            val loaded = controller.state.value.bootFile!!
            controller.loadBootFile("bad.img", badBytes.size.toLong()) { ByteArrayInputStream(badBytes) }
            assertTrue("loaded file deleted by a failed load in the same millisecond", loaded.exists())
        }
    }

    @Test
    fun loadMagiskApk_extractsComponentsReplacesPreviousAndCleansUpOnFailure() = runBlocking {
        val cacheDir = tempFolder.newFolder("apk_cache")
        val controller = createController(this, cacheDir) { null }

        val validApk = buildMagiskApkBytes(includeAll = true)
        controller.loadMagiskApk("magisk-v27.apk") { ByteArrayInputStream(validApk) }
        assertEquals(MagiskExtractPhase.Ready, controller.state.value.magiskExtractPhase)
        val firstApk = controller.state.value.magiskApkFile!!
        assertTrue(firstApk.exists())
        assertTrue(File(cacheDir, "magisk_components/boot_patch.sh").exists())

        // Replacing APK deletes old temp APK
        Thread.sleep(5)
        controller.loadMagiskApk("magisk-v28.apk") { ByteArrayInputStream(validApk) }
        val secondApk = controller.state.value.magiskApkFile!!
        assertTrue(secondApk.exists())
        assertFalse(firstApk.exists())

        // Incomplete APK cleans up temp APK and components directory
        val incompleteApk = buildMagiskApkBytes(includeAll = false)
        controller.loadMagiskApk("broken.apk") { ByteArrayInputStream(incompleteApk) }
        assertTrue(controller.state.value.magiskExtractPhase is MagiskExtractPhase.Failed)
        assertFalse(File(cacheDir, "magisk_components").exists())
        assertEquals(1, cacheDir.listFiles { _, name -> name.endsWith(".apk") }?.size)
    }

    @Test
    fun flashPatchedImage_handlesPrimarySuccessSlottedRetryAndTotalFailure() = runBlocking {
        val cacheDir = tempFolder.newFolder("flash_cache")
        val initBootBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        var attempt = 0
        val conn = FakePatcherConnection(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("echo \"__K_") -> reconShellOutput(firstApi = "34", slotSuffix = "_b")
                    cmd.startsWith("ls /data/local/tmp/80bee_patcher/new-boot.img") -> "/data/local/tmp/80bee_patcher/new-boot.img"
                    else -> ""
                }
            },
            pullResponder = { _, dest -> dest.writeBytes(initBootBytes) },
            flashResponder = { partition, _ ->
                attempt++
                when (attempt) {
                    1 -> "OKAY" // Primary succeeds
                    2 -> throw IOException("partition not found") // Primary fails
                    3 -> "OKAY" // Slotted retry succeeds
                    else -> throw IOException("slotted write failed") // Both fail
                }
            }
        )
        val controller = createController(this, cacheDir) { conn }
        controller.startPatcher()
        controller.loadBootFile("init_boot.img", initBootBytes.size.toLong()) { ByteArrayInputStream(initBootBytes) }
        val apkBytes = buildMagiskApkBytes(includeAll = true)
        controller.loadMagiskApk("magisk.apk") { ByteArrayInputStream(apkBytes) }
        controller.startPatching()
        assertEquals(PatchPhase.Succeeded, controller.state.value.patchPhase)

        // 1. Primary partition succeeds without retry
        controller.flashPatchedImage()
        assertEquals(FlashPhase.Succeeded, controller.state.value.flashPhase)
        assertEquals(listOf("init_boot"), conn.flashedPartitions.map { it.first })

        // 2. Primary fails -> retries with slot suffix _b and succeeds
        conn.flashedPartitions.clear()
        controller.onConnectionStateChanged(ConnectionState.FastbootConnected)
        controller.flashPatchedImage()
        assertEquals(FlashPhase.Succeeded, controller.state.value.flashPhase)
        assertEquals(listOf("init_boot", "init_boot_b"), conn.flashedPartitions.map { it.first })
        assertTrue(controller.state.value.patchLogs.any { it.message.contains("Retrying flash with slot suffix (init_boot_b)") })

        // 3. Both primary and slotted fail -> Failed with last error message
        conn.flashedPartitions.clear()
        controller.flashPatchedImage()
        assertEquals(FlashPhase.Failed("slotted write failed"), controller.state.value.flashPhase)
        assertEquals(listOf("init_boot", "init_boot_b"), conn.flashedPartitions.map { it.first })

        // 4. clearPatcherState deletes all cached files and components
        controller.clearPatcherState()
        assertFalse(controller.state.value.isActive)
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)
    }

    /** A controller with recon, boot image and Magisk APK loaded, recording every log line and connection call. */
    private class PatchHarness(val controller: MagiskPatcherController, val conn: FakePatcherConnection, val trace: MutableList<String>) {
        fun logs(): List<String> = controller.state.value.patchLogs.map { "${it.level} ${it.message}" }
    }

    private fun patchHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        cacheDir: File,
        lsOutput: String = "/data/local/tmp/80bee_patcher/new-boot.img",
        pullResponder: (String, File) -> Unit
    ): PatchHarness {
        val bootBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        val trace = mutableListOf<String>()
        val conn = FakePatcherConnection(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("echo \"__K_") -> reconShellOutput(firstApi = "34", slotSuffix = "_b")
                    cmd.startsWith("ls /data/local/tmp/80bee_patcher/new-boot.img") -> lsOutput
                    else -> ""
                }
            },
            pullResponder = pullResponder
        )
        lateinit var controller: MagiskPatcherController
        controller = createController(
            scope,
            cacheDir,
            logSink = { isError, tag, message ->
                trace += "sink error=$isError $tag $message @ ${controller.state.value.patchPhase}"
            }
        ) { conn }
        conn.onCall = { call -> trace += "call ${call.take(60)} @ ${controller.state.value.patchPhase}" }
        controller.startPatcher()
        controller.loadBootFile("init_boot.img", bootBytes.size.toLong()) { ByteArrayInputStream(bootBytes) }
        controller.loadMagiskApk("magisk.apk") { ByteArrayInputStream(buildMagiskApkBytes(includeAll = true)) }
        trace.clear()
        return PatchHarness(controller, conn, trace)
    }

    @Test
    fun startPatching_successTranscript() = runBlocking {
        val cacheDir = tempFolder.newFolder("patch_ok")
        val bootBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        val h = patchHarness(this, cacheDir) { _, dest -> dest.writeBytes(bootBytes) }

        h.controller.startPatching()

        assertEquals(PatchPhase.Succeeded, h.controller.state.value.patchPhase)
        assertTrue(h.controller.state.value.patchedFile!!.exists())
        assertEquals(
            listOf(
                "INFO Initializing scoped workspace...",
                "INFO Pushing boot image...",
                "INFO Pushing Magisk components...",
                "INFO Executing boot_patch.sh...",
                "PROMPT Patching boot image...",
                "INFO Locating patched image...",
                "INFO Pulling patched image to host...",
                "INFO Cleaning staging area...",
                "SUCCESS Successfully patched and retrieved!"
            ),
            h.logs()
        )
        assertEquals(EXPECTED_SUCCESS_TRACE, h.trace.joinToString("\n"))
    }

    @Test
    fun startPatching_missingPatchedImageFails() = runBlocking {
        val cacheDir = tempFolder.newFolder("patch_missing")
        val h = patchHarness(
            this,
            cacheDir,
            lsOutput = "ls: /data/local/tmp/80bee_patcher/new-boot.img: No such file or directory"
        ) { _, _ -> throw AssertionError("must not pull") }

        h.controller.startPatching()

        val message = "Patched image not found! Script output was logged above."
        assertEquals(PatchPhase.Failed(message), h.controller.state.value.patchPhase)
        assertNull(h.controller.state.value.patchedFile)
        assertEquals(
            listOf(
                "INFO Initializing scoped workspace...",
                "INFO Pushing boot image...",
                "INFO Pushing Magisk components...",
                "INFO Executing boot_patch.sh...",
                "PROMPT Patching boot image...",
                "INFO Locating patched image...",
                "ERROR Error: $message"
            ),
            h.logs()
        )
        assertEquals(
            "sink error=true MagiskPatcherController [PATCH] Error: $message @ InProgress(progress=0.8, label=Locating patched image...)",
            h.trace.last()
        )
    }

    @Test
    fun startPatching_pullFailureDeletesPartialFile() = runBlocking {
        val cacheDir = tempFolder.newFolder("patch_pull_fail")
        val h = patchHarness(this, cacheDir) { _, dest ->
            dest.writeBytes(byteArrayOf(1, 2, 3))
            throw IOException("pull broke")
        }

        h.controller.startPatching()

        assertEquals(PatchPhase.Failed("pull broke"), h.controller.state.value.patchPhase)
        assertNull(h.controller.state.value.patchedFile)
        assertEquals(0, cacheDir.listFiles { _, name -> name.startsWith("patched_boot_") }?.size)
        assertEquals("INFO Pulling patched image to host...", h.logs().dropLast(1).last())
        assertEquals("ERROR Error: pull broke", h.logs().last())
        assertFalse(h.conn.shellCommands.contains("rm -rf /data/local/tmp/80bee_patcher"))
    }

    @Test
    fun flashPatchedImage_logTranscripts() = runBlocking {
        val cacheDir = tempFolder.newFolder("flash_logs")
        val bootBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        val h = patchHarness(this, cacheDir) { _, dest -> dest.writeBytes(bootBytes) }
        h.controller.startPatching()
        val failures = ArrayDeque<String?>()
        h.conn.flashResponder = { _, _ -> failures.removeFirst()?.let { throw IOException(it) } ?: "OKAY" }

        fun flashLogs(vararg outcomes: String?): List<String> {
            failures.clear()
            failures.addAll(outcomes)
            val before = h.controller.state.value.patchLogs.size
            h.controller.onConnectionStateChanged(ConnectionState.FastbootConnected)
            h.controller.flashPatchedImage()
            return h.logs().drop(before)
        }

        assertEquals(
            listOf("INFO Writing patched image to init_boot...", "SUCCESS Flash complete!"),
            flashLogs(null)
        )
        assertEquals(
            listOf(
                "INFO Writing patched image to init_boot...",
                "WARNING Flash to init_boot failed: partition not found",
                "INFO Retrying flash with slot suffix (init_boot_b)...",
                "SUCCESS Flash complete!"
            ),
            flashLogs("partition not found", null)
        )
        assertEquals(
            listOf(
                "INFO Writing patched image to init_boot...",
                "WARNING Flash to init_boot failed: partition not found",
                "INFO Retrying flash with slot suffix (init_boot_b)...",
                "WARNING Slotted flash (init_boot_b) failed: slotted write failed",
                "ERROR Flash fatal error: slotted write failed",
                "WARNING Tip: Ensure device is in Bootloader/Fastboot mode and not userspace fastbootd."
            ),
            flashLogs("partition not found", "slotted write failed")
        )
        assertEquals(FlashPhase.Failed("slotted write failed"), h.controller.state.value.flashPhase)
    }

    private companion object {
        val EXPECTED_SUCCESS_TRACE = """
            sink error=false MagiskPatcherController [PATCH] [INFO] Initializing scoped workspace... @ InProgress(progress=0.05, label=Initializing scoped workspace...)
            call shell rm -rf /data/local/tmp/80bee_patcher && mkdir -p /data @ InProgress(progress=0.05, label=Initializing scoped workspace...)
            sink error=false MagiskPatcherController [PATCH] [INFO] Pushing boot image... @ InProgress(progress=0.05, label=Initializing scoped workspace...)
            call push /data/local/tmp/80bee_patcher/boot.img @ InProgress(progress=0.1, label=Uploading boot image...)
            sink error=false MagiskPatcherController [PATCH] [INFO] Pushing Magisk components... @ InProgress(progress=0.5, label=Uploading boot image (0.0 MB)...)
            call push /data/local/tmp/80bee_patcher/boot_patch.sh @ InProgress(progress=0.5, label=Pushing Magisk binaries...)
            call push /data/local/tmp/80bee_patcher/util_functions.sh @ InProgress(progress=0.525, label=Pushed boot_patch.sh)
            call push /data/local/tmp/80bee_patcher/stub.apk @ InProgress(progress=0.55, label=Pushed util_functions.sh)
            call push /data/local/tmp/80bee_patcher/magiskboot @ InProgress(progress=0.575, label=Pushed stub.apk)
            call push /data/local/tmp/80bee_patcher/magiskinit @ InProgress(progress=0.6, label=Pushed magiskboot)
            call push /data/local/tmp/80bee_patcher/magisk64 @ InProgress(progress=0.625, label=Pushed magiskinit)
            sink error=false MagiskPatcherController [PATCH] [INFO] Executing boot_patch.sh... @ InProgress(progress=0.65, label=Pushed magisk64)
            call shell chmod +x /data/local/tmp/80bee_patcher/* @ InProgress(progress=0.7, label=Setting permissions...)
            call live cd /data/local/tmp/80bee_patcher && export KEEPVERITY=t @ InProgress(progress=0.75, label=Patching kernel image...)
            sink error=false MagiskPatcherController [PATCH] [PROMPT] Patching boot image... @ InProgress(progress=0.75, label=Patching kernel image...)
            sink error=false MagiskPatcherController [PATCH] [INFO] Locating patched image... @ InProgress(progress=0.75, label=Patching kernel image...)
            call shell ls /data/local/tmp/80bee_patcher/new-boot.img @ InProgress(progress=0.8, label=Locating patched image...)
            sink error=false MagiskPatcherController [PATCH] [INFO] Pulling patched image to host... @ InProgress(progress=0.8, label=Locating patched image...)
            call pull /data/local/tmp/80bee_patcher/new-boot.img @ InProgress(progress=0.85, label=Pulling patched image...)
            sink error=false MagiskPatcherController [PATCH] [INFO] Cleaning staging area... @ InProgress(progress=0.85, label=Pulling patched image (0.0 MB)...)
            call shell rm -rf /data/local/tmp/80bee_patcher @ InProgress(progress=0.95, label=Cleaning up...)
            sink error=false MagiskPatcherController [PATCH] [SUCCESS] Successfully patched and retrieved! @ InProgress(progress=0.95, label=Cleaning up...)
        """.trimIndent()
    }
}
