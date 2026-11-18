package com.eightbee.app

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MagiskPatcherHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildBootHeader(
        kernelSize: Int,
        ramdiskSize: Int,
        headerVersion: Int = 4,
        totalSize: Int = 4096
    ): ByteArray {
        val bytes = ByteArray(totalSize)
        val magic = "ANDROID!".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, bytes, 0, magic.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(8, kernelSize)
        if (headerVersion >= 3) {
            buf.putInt(12, ramdiskSize)
        } else {
            buf.putInt(16, ramdiskSize)
        }
        buf.putInt(40, headerVersion)
        return bytes
    }

    private fun buildNestedFactoryZip(
        innerZipName: String,
        androidInfoContent: String,
        bootImgBytes: ByteArray,
        initBootImgBytes: ByteArray
    ): ByteArray {
        val innerBaos = ByteArrayOutputStream()
        ZipOutputStream(innerBaos).use { innerZos ->
            innerZos.putNextEntry(ZipEntry("android-info.txt"))
            innerZos.write(androidInfoContent.toByteArray(StandardCharsets.UTF_8))
            innerZos.closeEntry()

            innerZos.putNextEntry(ZipEntry("boot.img"))
            innerZos.write(bootImgBytes)
            innerZos.closeEntry()

            innerZos.putNextEntry(ZipEntry("init_boot.img"))
            innerZos.write(initBootImgBytes)
            innerZos.closeEntry()
        }

        val outerBaos = ByteArrayOutputStream()
        ZipOutputStream(outerBaos).use { outerZos ->
            outerZos.putNextEntry(ZipEntry("pixel-factory/$innerZipName"))
            outerZos.write(innerBaos.toByteArray())
            outerZos.closeEntry()
        }
        return outerBaos.toByteArray()
    }

    @Test
    fun validateBootImageForPartition_distinguishesBootAndInitBoot() {
        val cache = tempFolder.newFolder("cache")
        val bootImg = File(cache, "boot.img").apply {
            writeBytes(buildBootHeader(kernelSize = 32768, ramdiskSize = 1024))
        }
        val initBootImg = File(cache, "init_boot.img").apply {
            writeBytes(buildBootHeader(kernelSize = 0, ramdiskSize = 16384))
        }
        val invalidImg = File(cache, "bad.img").apply {
            writeBytes("NOT_A_BOOT_IMAGE".repeat(8).toByteArray())
        }

        val bootInfo = MagiskPatcherHelper.validateBootImageForPartition(bootImg, "boot")
        assertFalse(bootInfo.isInitBoot)

        val initBootInfo = MagiskPatcherHelper.validateBootImageForPartition(initBootImg, "init_boot")
        assertTrue(initBootInfo.isInitBoot)

        var threwOnInitBootForBoot = false
        try {
            MagiskPatcherHelper.validateBootImageForPartition(bootImg, "init_boot")
        } catch (e: IllegalArgumentException) {
            threwOnInitBootForBoot = true
        }
        assertTrue(threwOnInitBootForBoot)

        var threwOnBootForInitBoot = false
        try {
            MagiskPatcherHelper.validateBootImageForPartition(initBootImg, "boot")
        } catch (e: IllegalArgumentException) {
            threwOnBootForInitBoot = true
        }
        assertTrue(threwOnBootForInitBoot)

        var threwOnInvalidMagic = false
        try {
            MagiskPatcherHelper.validateBootImageForPartition(invalidImg, "boot")
        } catch (e: IllegalArgumentException) {
            threwOnInvalidMagic = true
        }
        assertTrue(threwOnInvalidMagic)
    }

    @Test
    fun extractBootImageFromStream_streamsNestedZipAndValidatesBoardAndBuild() {
        val cache = tempFolder.newFolder("stream_cache")
        val zipBytes = buildNestedFactoryZip(
            innerZipName = "image-shiba-ap2a.240805.005.zip",
            androidInfoContent = "require board=shiba\n",
            bootImgBytes = buildBootHeader(kernelSize = 32768, ramdiskSize = 1024),
            initBootImgBytes = buildBootHeader(kernelSize = 0, ramdiskSize = 16384)
        )

        val result = MagiskPatcherHelper.extractBootImageFromStream(
            inputStream = ByteArrayInputStream(zipBytes),
            cacheDir = cache,
            targetPartition = "init_boot",
            expectedCodename = "shiba",
            expectedBuildId = "AP2A.240805.005",
            totalInputBytes = zipBytes.size.toLong(),
            sourceFileName = "shiba-ap2a.240805.005-factory-12345678.zip"
        )
        assertTrue(result.bootFile.exists())
        assertNull(result.buildWarning)
        assertFalse(File(cache, "inner_factory_payload.zip").exists())

        val mismatchBuildResult = MagiskPatcherHelper.extractBootImageFromStream(
            inputStream = ByteArrayInputStream(zipBytes),
            cacheDir = cache,
            targetPartition = "init_boot",
            expectedCodename = "shiba",
            expectedBuildId = "AP3A.241005.015"
        )
        assertNotNull(mismatchBuildResult.buildWarning)

        var threwOnWrongCodename = false
        try {
            MagiskPatcherHelper.extractBootImageFromStream(
                inputStream = ByteArrayInputStream(zipBytes),
                cacheDir = cache,
                targetPartition = "init_boot",
                expectedCodename = "husky",
                expectedBuildId = "AP2A.240805.005"
            )
        } catch (e: IllegalArgumentException) {
            threwOnWrongCodename = true
        }
        assertTrue(threwOnWrongCodename)
    }

    @Test
    fun extractBootImageFromStream_cleansUpOnPartitionHeaderMismatch() {
        val cache = tempFolder.newFolder("cleanup_cache")
        // Put a boot.img header inside init_boot.img to simulate a corrupt/mismatched entry
        val badZipBytes = buildNestedFactoryZip(
            innerZipName = "image-shiba-ap2a.240805.005.zip",
            androidInfoContent = "require board=shiba\n",
            bootImgBytes = buildBootHeader(kernelSize = 32768, ramdiskSize = 1024),
            initBootImgBytes = buildBootHeader(kernelSize = 32768, ramdiskSize = 1024)
        )

        var threw = false
        try {
            MagiskPatcherHelper.extractBootImageFromStream(
                inputStream = ByteArrayInputStream(badZipBytes),
                cacheDir = cache,
                targetPartition = "init_boot",
                expectedCodename = "shiba",
                expectedBuildId = "AP2A.240805.005"
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, cache.listFiles()?.size ?: 0)
    }

    @Test
    fun resolveReconTarget_choosesPartitionAndRejectsMissingApiLevel() {
        val pixel8 = MagiskPatcherHelper.resolveReconTarget(
            codename = "shiba",
            abi = "arm64-v8a",
            firstApiLevelRaw = "34"
        )
        assertEquals(34, pixel8.apiLevel)
        assertEquals("init_boot", pixel8.targetPartition)

        // Pixel 6 series always uses boot even if first/vendor API >= 33
        val pixel6 = MagiskPatcherHelper.resolveReconTarget(
            codename = "Raven",
            abi = "arm64-v8a",
            firstApiLevelRaw = "31"
        )
        assertEquals(31, pixel6.apiLevel)
        assertEquals("boot", pixel6.targetPartition)

        val fallbackBoard = MagiskPatcherHelper.resolveReconTarget(
            codename = "husky",
            abi = "arm64-v8a",
            firstApiLevelRaw = "",
            boardApiLevelRaw = "34"
        )
        assertEquals(34, fallbackBoard.apiLevel)
        assertEquals("init_boot", fallbackBoard.targetPartition)

        var threwEmptyApi = false
        try {
            MagiskPatcherHelper.resolveReconTarget(
                codename = "shiba",
                abi = "arm64-v8a",
                firstApiLevelRaw = "",
                boardApiLevelRaw = "",
                vendorApiLevelRaw = ""
            )
        } catch (e: IllegalArgumentException) {
            threwEmptyApi = true
        }
        assertTrue(threwEmptyApi)

        var threwBlankCodename = false
        try {
            MagiskPatcherHelper.resolveReconTarget(
                codename = "  ",
                abi = "arm64-v8a",
                firstApiLevelRaw = "34"
            )
        } catch (e: IllegalArgumentException) {
            threwBlankCodename = true
        }
        assertTrue(threwBlankCodename)

        var threwNonArm64 = false
        try {
            MagiskPatcherHelper.resolveReconTarget(
                codename = "shiba",
                abi = "armeabi-v7a",
                firstApiLevelRaw = "34"
            )
        } catch (e: IllegalArgumentException) {
            threwNonArm64 = true
        }
        assertTrue(threwNonArm64)
    }
}
