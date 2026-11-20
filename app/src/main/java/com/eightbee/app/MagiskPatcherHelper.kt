package com.eightbee.app

import android.util.Log
import com.eightbee.app.features.MAX_BOOT_IMAGE_BYTES
import com.eightbee.app.features.copyBounded
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class BootImageInfo(
    val headerVersion: Int,
    val kernelSize: Int,
    val ramdiskSize: Int
) {
    val isInitBoot: Boolean get() = kernelSize == 0
    val inferredPartition: String get() = if (isInitBoot) "init_boot" else "boot"
}

data class BootExtractionResult(
    val bootFile: File,
    val buildWarning: String? = null
)

data class ReconTargetResolution(
    val apiLevel: Int,
    val targetPartition: String
)

object MagiskPatcherHelper {
    private const val TAG = "MagiskPatcherHelper"
    private val ANDROID_BOOT_MAGIC = "ANDROID!".toByteArray(StandardCharsets.US_ASCII)
    private const val BOOT_HEADER_PROBE_SIZE = 64
    private const val MAX_ANDROID_INFO_BYTES = 64 * 1024
    private const val MAX_MAGISK_COMPONENT_BYTES = 128L * 1024 * 1024
    private const val ARCHIVE_ENTRY_OVERFLOW_MSG = "Archive image entry exceeds the 512 MB limit."
    private val PIXEL_6_SERIES = setOf("oriole", "raven", "bluejay")
    private val FACTORY_FILENAME_REGEX = Regex("^([A-Za-z0-9_]+)-([A-Za-z0-9.]+)-factory-", RegexOption.IGNORE_CASE)

    fun resolveReconTarget(
        codename: String,
        abi: String,
        firstApiLevelRaw: String,
        boardApiLevelRaw: String = "",
        vendorApiLevelRaw: String = ""
    ): ReconTargetResolution {
        val cleanCodename = codename.trim()
        if (cleanCodename.isEmpty()) {
            throw IllegalArgumentException("Failed to read device codename (ro.product.device was empty).")
        }
        if (!abi.contains("arm64-v8a")) {
            throw IllegalArgumentException("Unsupported Architecture: $abi. This tool requires ARM64.")
        }
        val apiLevel = listOf(firstApiLevelRaw, boardApiLevelRaw, vendorApiLevelRaw)
            .firstNotNullOfOrNull { raw -> raw.trim().toIntOrNull()?.takeIf { it > 0 } }
            ?: throw IllegalArgumentException(
                "Unable to determine device first API level (ro.product.first_api_level was empty). Reconnect ADB and retry."
            )
        val isPixel6 = cleanCodename.lowercase() in PIXEL_6_SERIES
        val targetPartition = if (!isPixel6 && apiLevel >= 33) "init_boot" else "boot"
        return ReconTargetResolution(apiLevel = apiLevel, targetPartition = targetPartition)
    }

    fun isZipMagic(headerBytes: ByteArray, length: Int = headerBytes.size): Boolean {
        return length >= 4 &&
            headerBytes[0] == 0x50.toByte() &&
            headerBytes[1] == 0x4B.toByte() &&
            headerBytes[2] == 0x03.toByte() &&
            headerBytes[3] == 0x04.toByte()
    }

    fun inspectBootImageHeader(file: File): BootImageInfo {
        if (!file.exists() || file.length() < BOOT_HEADER_PROBE_SIZE) {
            throw IllegalArgumentException("File is too small to be an Android boot image.")
        }
        val header = ByteArray(BOOT_HEADER_PROBE_SIZE)
        FileInputStream(file).use { fis ->
            var read = 0
            while (read < BOOT_HEADER_PROBE_SIZE) {
                val n = fis.read(header, read, BOOT_HEADER_PROBE_SIZE - read)
                if (n <= 0) break
                read += n
            }
            if (read < BOOT_HEADER_PROBE_SIZE) {
                throw IllegalArgumentException("Failed to read Android boot image header.")
            }
        }
        for (i in ANDROID_BOOT_MAGIC.indices) {
            if (header[i] != ANDROID_BOOT_MAGIC[i]) {
                throw IllegalArgumentException("Invalid image: missing ANDROID! boot header magic.")
            }
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val kernelSize = buf.getInt(8)
        val headerVersion = buf.getInt(40)
        val ramdiskSize = if (headerVersion >= 3) buf.getInt(12) else buf.getInt(16)
        return BootImageInfo(
            headerVersion = headerVersion,
            kernelSize = kernelSize,
            ramdiskSize = ramdiskSize
        )
    }

    fun validateBootImageForPartition(file: File, targetPartition: String?): BootImageInfo {
        val info = inspectBootImageHeader(file)
        if (info.kernelSize == 0 && info.ramdiskSize == 0) {
            throw IllegalArgumentException("Invalid boot image header: kernel and ramdisk sizes are both 0.")
        }
        if (targetPartition == "init_boot" && info.kernelSize != 0) {
            throw IllegalArgumentException(
                "Partition mismatch: selected image is boot.img (kernel_size=${info.kernelSize}), but this device requires init_boot.img."
            )
        }
        if (targetPartition == "boot" && info.kernelSize == 0) {
            throw IllegalArgumentException(
                "Partition mismatch: selected image is init_boot.img (kernel_size=0), but this device requires boot.img."
            )
        }
        return info
    }

    fun extractBootImageFromZip(
        zipFile: File,
        cacheDir: File,
        targetPartition: String? = null,
        expectedCodename: String? = null,
        expectedBuildId: String? = null,
        onProgress: (String, Float) -> Unit
    ): BootExtractionResult {
        return FileInputStream(zipFile).use { fis ->
            extractBootImageFromStream(
                inputStream = fis,
                cacheDir = cacheDir,
                targetPartition = targetPartition,
                expectedCodename = expectedCodename,
                expectedBuildId = expectedBuildId,
                totalInputBytes = zipFile.length(),
                sourceFileName = zipFile.name,
                onProgress = onProgress
            )
        }
    }

    fun extractBootImageFromStream(
        inputStream: InputStream,
        cacheDir: File,
        targetPartition: String? = null,
        expectedCodename: String? = null,
        expectedBuildId: String? = null,
        totalInputBytes: Long = -1L,
        sourceFileName: String = "",
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): BootExtractionResult {
        val desiredImageName = when (targetPartition) {
            "init_boot" -> "init_boot.img"
            "boot" -> "boot.img"
            else -> null
        }

        val countingStream = CountingInputStream(BufferedInputStream(inputStream, 64 * 1024))
        val outerZis = ZipInputStream(countingStream)
        var extractedFile: File? = null
        var fallbackBootFile: File? = null
        var buildWarning = checkArchiveFilenameBuildMismatch(sourceFileName, expectedCodename, expectedBuildId)

        fun scanZip(zis: ZipInputStream, progressBase: Float, progressSpan: Float, allowInnerZip: Boolean): Boolean {
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue

                val entryName = entry.name.substringAfterLast('/')
                if (allowInnerZip && totalInputBytes > 0) {
                    val scanProgress = (0.05f + (countingStream.bytesRead.toFloat() / totalInputBytes) * 0.45f)
                        .coerceIn(0.05f, 0.5f)
                    onProgress("SCANNING_ARCHIVE", scanProgress)
                }

                when {
                    allowInnerZip && entryName.startsWith("image-") && entryName.endsWith(".zip") -> {
                        checkInnerZipBuildMismatch(entryName, expectedCodename, expectedBuildId)?.let {
                            buildWarning = it
                        }
                        onProgress("STREAMING_INNER_ARCHIVE", 0.5f)
                        val nonClosingInnerSource = object : FilterInputStream(zis) {
                            override fun close() {
                                // Keep outerZis open until outer streaming finishes
                            }
                        }
                        val innerZis = ZipInputStream(BufferedInputStream(nonClosingInnerSource, 64 * 1024))
                        if (scanZip(innerZis, progressBase = 0.7f, progressSpan = 0.25f, allowInnerZip = false)) {
                            return true
                        }
                    }
                    entryName == "android-info.txt" -> {
                        validateAndroidInfo(readCappedText(zis), expectedCodename)
                    }
                    isTargetImageEntry(entryName, desiredImageName) -> {
                        onProgress("EXTRACTING_BOOT_IMAGE", progressBase)
                        val outFile = File(cacheDir, "${entryName.substringBeforeLast('.')}_${System.currentTimeMillis()}.img")
                        copyEntryToFile(zis, outFile, entry.size) { frac ->
                            onProgress("EXTRACTING_BOOT_IMAGE", progressBase + frac * progressSpan)
                        }
                        fallbackBootFile?.delete()
                        fallbackBootFile = null
                        if (desiredImageName != null || entryName == "init_boot.img") {
                            extractedFile = outFile
                            return true
                        }
                        fallbackBootFile = outFile
                    }
                }
            }
            return false
        }

        try {
            scanZip(outerZis, progressBase = 0.6f, progressSpan = 0.35f, allowInnerZip = true)

            val finalFile = extractedFile ?: fallbackBootFile
                ?: throw IllegalArgumentException(
                    if (desiredImageName != null) {
                        "Could not find $desiredImageName inside firmware archive."
                    } else {
                        "Could not find boot.img or init_boot.img inside firmware archive."
                    }
                )

            validateBootImageForPartition(finalFile, targetPartition)
            return BootExtractionResult(finalFile, buildWarning)
        } catch (e: Exception) {
            extractedFile?.delete()
            fallbackBootFile?.delete()
            throw e
        } finally {
            try {
                outerZis.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing archive stream", e)
            }
        }
    }

    private fun isTargetImageEntry(entryName: String, desiredImageName: String?): Boolean {
        return if (desiredImageName != null) {
            entryName == desiredImageName
        } else {
            entryName == "init_boot.img" || entryName == "boot.img"
        }
    }

    private fun copyEntryToFile(
        input: InputStream,
        outFile: File,
        expectedSize: Long,
        onFraction: (Float) -> Unit
    ) {
        if (expectedSize > MAX_BOOT_IMAGE_BYTES) {
            throw IOException(ARCHIVE_ENTRY_OVERFLOW_MSG)
        }
        try {
            outFile.outputStream().use { output ->
                copyBounded(
                    input = input,
                    output = output,
                    budget = MAX_BOOT_IMAGE_BYTES,
                    overflowMessage = ARCHIVE_ENTRY_OVERFLOW_MSG,
                    bufferSize = 256 * 1024
                ) { totalCopied ->
                    if (expectedSize > 0) {
                        onFraction((totalCopied.toFloat() / expectedSize).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
    }

    private fun readCappedText(input: InputStream): String {
        val buffer = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        var remaining = MAX_ANDROID_INFO_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun validateAndroidInfo(content: String, expectedCodename: String?) {
        if (expectedCodename.isNullOrBlank()) return
        val boardLine = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("require board=", ignoreCase = true) }
            ?: return
        val boardsRaw = boardLine.substringAfter('=').trim()
        if (boardsRaw.isEmpty()) return
        val boards = boardsRaw.split('|', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (boards.isNotEmpty() && boards.none { it.equals(expectedCodename, ignoreCase = true) }) {
            throw IllegalArgumentException(
                "Device mismatch: archive is for '${boards.joinToString("/")}', but connected device is '$expectedCodename'."
            )
        }
    }

    private fun validateCodenameAndBuild(
        archiveCodename: String,
        archiveBuild: String,
        expectedCodename: String?,
        expectedBuildId: String?,
        mismatchSubject: String
    ): String? {
        if (!expectedCodename.isNullOrBlank() && !archiveCodename.equals(expectedCodename, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Device mismatch: $mismatchSubject '$archiveCodename', but connected device is '$expectedCodename'."
            )
        }
        if (!expectedBuildId.isNullOrBlank() &&
            !expectedBuildId.contains(archiveBuild, ignoreCase = true) &&
            !archiveBuild.equals(expectedBuildId, ignoreCase = true)
        ) {
            return "Warning: archive build ($archiveBuild) does not match device Build ID ($expectedBuildId). Verify you downloaded the matching factory image."
        }
        return null
    }

    private fun checkInnerZipBuildMismatch(
        innerZipName: String,
        expectedCodename: String?,
        expectedBuildId: String?
    ): String? {
        // Expected pattern: image-<codename>-<build>.zip
        val parts = innerZipName.removePrefix("image-").removeSuffix(".zip").split('-', limit = 2)
        if (parts.size < 2) return null
        return validateCodenameAndBuild(parts[0], parts[1], expectedCodename, expectedBuildId, "firmware image is for")
    }

    private fun checkArchiveFilenameBuildMismatch(
        sourceFileName: String,
        expectedCodename: String?,
        expectedBuildId: String?
    ): String? {
        if (sourceFileName.isBlank() || expectedBuildId.isNullOrBlank()) return null
        // Factory zip filenames look like: <codename>-<build>-factory-<hash>.zip
        val match = FACTORY_FILENAME_REGEX.find(sourceFileName) ?: return null
        return validateCodenameAndBuild(match.groupValues[1], match.groupValues[2], expectedCodename, expectedBuildId, "filename indicates")
    }

    private class CountingInputStream(private val delegate: InputStream) : FilterInputStream(delegate) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    fun extractMagiskComponents(apkFile: File, cacheDir: File): Map<String, File> {
        val targets = mapOf(
            "assets/boot_patch.sh" to "boot_patch.sh",
            "assets/util_functions.sh" to "util_functions.sh",
            "assets/stub.apk" to "stub.apk",
            "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
            "lib/arm64-v8a/libmagiskinit.so" to "magiskinit",
            "lib/arm64-v8a/libmagisk64.so" to "magisk64",
            "lib/arm64-v8a/libmagisk.so" to "magisk",
            "lib/arm64-v8a/libmagisk32.so" to "magisk32",
            "lib/arm64-v8a/libinit-ld.so" to "init-ld"
        )
        val extractedFiles = mutableMapOf<String, File>()
        val zipFile = java.util.zip.ZipFile(apkFile)
        zipFile.use { zip ->
            for ((zipPath, targetName) in targets) {
                val entry = zip.getEntry(zipPath) ?: continue
                if (entry.size > MAX_MAGISK_COMPONENT_BYTES) {
                    throw IOException("Magisk component $targetName exceeds size limit.")
                }
                val outFile = File(cacheDir, targetName)
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        copyBounded(
                            input = input,
                            output = output,
                            budget = MAX_MAGISK_COMPONENT_BYTES,
                            overflowMessage = "Magisk component $targetName exceeds size limit."
                        )
                    }
                }
                extractedFiles[targetName] = outFile
            }
        }
        
        // Validate required components
        val required = listOf("boot_patch.sh", "util_functions.sh", "stub.apk", "magiskboot", "magiskinit")
        val missing = required.filter { !extractedFiles.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw Exception("Missing required Magisk components: ${missing.joinToString()}")
        }
        if (!extractedFiles.containsKey("magisk64") && !extractedFiles.containsKey("magisk")) {
            throw Exception("Missing core magisk binary (neither magisk64 nor magisk was found)")
        }
        return extractedFiles
    }
}
