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

        fun emitState(state: ConnectionState) {
            _connectionState.value = state
        }

        override suspend fun runShellCommand(command: String): String {
            shellCommands.add(command)
            return shellResponder(command)
        }

        override suspend fun runShellCommandWithInput(command: String, input: InputStream): String = ""

        override suspend fun runShellCommandLive(command: String, onLineRead: (String) -> Unit) {
            shellCommands.add(command)
            onLineRead("Patching boot image...")
        }

        override suspend fun pushFile(
            input: InputStream,
            size: Long,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?
        ) {
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
        connectionProvider: () -> ConnectionManager?
    ): MagiskPatcherController {
        return MagiskPatcherController(
            scope = scope,
            connectionProvider = connectionProvider,
            ioDispatcher = Dispatchers.Unconfined,
            cacheDirProvider = { cacheDir },
            logSink = { _, _, _ -> }
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
        assertTrue(controller.state.value.patchLogs.any { it.type == "ERROR" && it.message.contains("Recon failed") })
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
        Thread.sleep(5)
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
}
