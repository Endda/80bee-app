package com.eightbee.app.features

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal const val MAX_SIDELOAD_APK_ENTRIES = 200
internal const val MAX_SIDELOAD_BYTES = 4L * 1024 * 1024 * 1024
internal const val MAX_BOOT_IMAGE_BYTES = 512L * 1024 * 1024
internal const val MAX_MAGISK_APK_BYTES = 256L * 1024 * 1024
internal const val MAX_SNAPSHOT_BYTES = 5 * 1024 * 1024

internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    budget: Long,
    overflowMessage: String = "Archive is too large to sideload."
): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        copied += read
        if (copied > budget) throw IOException(overflowMessage)
        output.write(buffer, 0, read)
    }
    return copied
}

internal fun readUriTextBounded(
    context: Context,
    uri: Uri,
    maxBytes: Int = MAX_SNAPSHOT_BYTES
): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Unable to open selected snapshot file.")
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("Snapshot file exceeds the 5 MB limit.")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}

internal fun getUriFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
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

internal fun getUriSize(context: Context, uri: Uri): Long {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx != -1 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx)
                }
            }
        }
    }
    return -1L
}

internal fun parsePmListPackages(output: String): List<String> {
    return output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.substringAfter("package:") }
        .filter { it.isNotEmpty() }
        .toList()
}
