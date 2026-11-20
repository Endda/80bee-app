package com.eightbee.app.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class FastbootReply(
    val status: String,
    val message: String,
    val info: List<String> = emptyList()
) {
    val isOkay: Boolean get() = status == "OKAY"

    fun formatDisplay(): String = buildString {
        info.forEach { line ->
            append("INFO: ")
            append(line)
            append('\n')
        }
        append(status)
        if (message.isNotBlank()) {
            append(": ")
            append(message)
        }
    }
}

internal interface FastbootTransport : Closeable {
    fun bulkWrite(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int
}

private class UsbFastbootTransport(
    private val usbInterface: UsbInterface,
    private val connection: UsbDeviceConnection
) : FastbootTransport {

    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint

    init {
        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    foundIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    foundOut = ep
                }
            }
        }
        inEndpoint = foundIn ?: throw IllegalArgumentException("No bulk IN endpoint found on Fastboot interface.")
        outEndpoint = foundOut ?: throw IllegalArgumentException("No bulk OUT endpoint found on Fastboot interface.")

        if (!connection.claimInterface(usbInterface, true)) {
            throw IOException("Failed to claim Fastboot USB interface.")
        }
    }

    override fun bulkWrite(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        return connection.bulkTransfer(outEndpoint, buffer, offset, length, timeoutMs)
    }

    override fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return connection.bulkTransfer(inEndpoint, buffer, length, timeoutMs)
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Minimal USB Fastboot protocol client with chunked data transfers and INFO-loop response handling.
 */
class FastbootUsbClient internal constructor(
    private val transport: FastbootTransport
) : Closeable {

    constructor(
        usbInterface: UsbInterface,
        connection: UsbDeviceConnection
    ) : this(UsbFastbootTransport(usbInterface, connection))

    companion object {
        internal const val MAX_USB_TRANSFER = 16384 // 16 KB chunks stay within usbfs transfer limits
        internal const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000
        internal const val DATA_TRANSFER_TIMEOUT_MS = 10_000
        internal const val DATA_COMPLETE_TIMEOUT_MS = 30_000
        internal const val FLASH_TIMEOUT_MS = 60_000
        private const val RESPONSE_BUFFER_SIZE = 256
        private const val MAX_INFO_PACKETS = 500
    }

    private val lock = Any()

    @Volatile
    private var closed = false

    fun sendCommand(command: String, timeoutMs: Int = timeoutForCommand(command)): FastbootReply {
        synchronized(lock) {
            checkNotClosed()
            val cmdBytes = command.toByteArray(StandardCharsets.UTF_8)
            writeAll(cmdBytes, cmdBytes.size, DEFAULT_COMMAND_TIMEOUT_MS)
            return readResponseLoop(timeoutMs)
        }
    }

    fun flashImage(partition: String, stream: InputStream, size: Long): String {
        if (size <= 0L || size > 0xFFFFFFFFL) {
            return "Download failed: invalid image size ($size bytes)"
        }
        synchronized(lock) {
            checkNotClosed()
            val downloadCmd = String.format(Locale.US, "download:%08x", size)
            val cmdBytes = downloadCmd.toByteArray(StandardCharsets.UTF_8)
            writeAll(cmdBytes, cmdBytes.size, DEFAULT_COMMAND_TIMEOUT_MS)

            val downloadReply = readResponseLoop(DEFAULT_COMMAND_TIMEOUT_MS)
            if (downloadReply.status != "DATA") {
                return "Download failed: ${downloadReply.status} ${downloadReply.message}".trim()
            }

            val buffer = ByteArray(MAX_USB_TRANSFER)
            var totalSent = 0L
            while (totalSent < size) {
                val toRead = minOf(buffer.size.toLong(), size - totalSent).toInt()
                val bytesRead = stream.read(buffer, 0, toRead)
                if (bytesRead == -1) {
                    throw IOException("Unexpected end of image stream ($totalSent / $size bytes)")
                }
                if (bytesRead > 0) {
                    writeAll(buffer, bytesRead, DATA_TRANSFER_TIMEOUT_MS)
                    totalSent += bytesRead
                }
            }

            val uploadReply = readResponseLoop(DATA_COMPLETE_TIMEOUT_MS)
            if (uploadReply.status != "OKAY") {
                return "Upload failed: ${uploadReply.status} ${uploadReply.message}".trim()
            }

            val flashCmdBytes = "flash:$partition".toByteArray(StandardCharsets.UTF_8)
            writeAll(flashCmdBytes, flashCmdBytes.size, DEFAULT_COMMAND_TIMEOUT_MS)

            val flashReply = readResponseLoop(FLASH_TIMEOUT_MS)
            if (flashReply.status != "OKAY") {
                return "Flash failed: ${flashReply.status} ${flashReply.message}".trim()
            }

            return "Successfully flashed $partition!"
        }
    }

    private fun writeAll(buffer: ByteArray, length: Int, timeoutMs: Int) {
        var offset = 0
        while (offset < length) {
            val chunkLen = minOf(length - offset, MAX_USB_TRANSFER)
            val transferred = transport.bulkWrite(buffer, offset, chunkLen, timeoutMs)
            if (transferred < 0) {
                throw IOException("Fastboot USB write failed ($transferred) at offset $offset/$length")
            }
            if (transferred == 0) {
                throw IOException("Fastboot USB write timed out (0 bytes sent) at offset $offset/$length")
            }
            offset += transferred
        }
    }

    private fun readResponseLoop(timeoutMs: Int): FastbootReply {
        val infoMessages = mutableListOf<String>()
        val buf = ByteArray(RESPONSE_BUFFER_SIZE)
        var infoCount = 0

        while (true) {
            val bytesRead = transport.bulkRead(buf, buf.size, timeoutMs)
            if (bytesRead < 0) {
                throw IOException("Fastboot USB read failed or timed out ($bytesRead)")
            }
            if (bytesRead < 4) {
                throw IOException("Fastboot response too short ($bytesRead bytes)")
            }

            val tag = String(buf, 0, 4, StandardCharsets.US_ASCII)
            val msg = String(buf, 4, bytesRead - 4, StandardCharsets.UTF_8).trimEnd('\u0000')

            when (tag) {
                "INFO" -> {
                    infoMessages.add(msg)
                    if (++infoCount >= MAX_INFO_PACKETS) {
                        throw IOException("Too many consecutive Fastboot INFO packets")
                    }
                }
                "OKAY", "FAIL", "DATA" -> {
                    return FastbootReply(tag, msg, infoMessages)
                }
                else -> {
                    throw IOException("Unexpected Fastboot response '$tag': $msg")
                }
            }
        }
    }

    private fun timeoutForCommand(command: String): Int {
        return when {
            command.startsWith("flash:") ||
                command.startsWith("erase:") ||
                command.startsWith("format:") ||
                command.startsWith("oem ") ||
                command.startsWith("flashing ") -> FLASH_TIMEOUT_MS
            else -> DEFAULT_COMMAND_TIMEOUT_MS
        }
    }

    private fun checkNotClosed() {
        if (closed) {
            throw IOException("Fastboot USB client is closed")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }
}
