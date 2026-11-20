package com.eightbee.app

import com.eightbee.app.connection.FastbootReply
import com.eightbee.app.connection.FastbootTransport
import com.eightbee.app.connection.FastbootUsbClient
import com.eightbee.app.ui.parseBatteryDumpsys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

class BatteryAndFastbootTest {

    private class FakeFastbootTransport(
        private val replies: ArrayDeque<ByteArray>,
        private val maxWriteChunk: Int = Int.MAX_VALUE
    ) : FastbootTransport {
        val writtenChunks = mutableListOf<ByteArray>()
        val readTimeouts = mutableListOf<Int>()
        var closed = false

        override fun bulkWrite(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val count = minOf(length, maxWriteChunk)
            writtenChunks.add(buffer.copyOfRange(offset, offset + count))
            return count
        }

        override fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            readTimeouts.add(timeoutMs)
            val next = replies.removeFirstOrNull() ?: return -1
            val count = minOf(length, next.size)
            System.arraycopy(next, 0, buffer, 0, count)
            return count
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun parseBatteryDumpsys_parsesStandardFieldsAndCharging() {
        val raw = """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              Wireless powered: false
              status: 2
              health: 2
              present: true
              level: 84
              scale: 100
              voltage: 4215
              temperature: 315
        """.trimIndent()

        val metrics = parseBatteryDumpsys(raw)
        assertEquals(84, metrics.level)
        assertEquals(31.5, metrics.temp, 0.001)
        assertEquals(4.215, metrics.voltage, 0.0001)
        assertTrue(metrics.isCharging)
        assertEquals("Good", metrics.healthString)
    }

    @Test
    fun parseBatteryDumpsys_handlesAlternateTempKeyAndDischargingStates() {
        val raw = """
            AC powered: false
            USB powered: false
            Wireless powered: false
            level: 19
            temp: 460
            voltage: 3680
            health: 3
        """.trimIndent()

        val metrics = parseBatteryDumpsys(raw)
        assertEquals(19, metrics.level)
        assertEquals(46.0, metrics.temp, 0.001)
        assertEquals(3.68, metrics.voltage, 0.0001)
        assertFalse(metrics.isCharging)
        assertEquals("Overheat", metrics.healthString)
    }

    @Test
    fun fastbootSendCommand_collectsInfoPacketsBeforeOkayAndUsesFlashTimeout() {
        val transport = FakeFastbootTransport(
            ArrayDeque(
                listOf(
                    "INFOerasing boot_a".toByteArray(StandardCharsets.US_ASCII),
                    "INFOwriting boot_a\u0000\u0000".toByteArray(StandardCharsets.US_ASCII),
                    "OKAYdone".toByteArray(StandardCharsets.US_ASCII)
                )
            )
        )
        val client = FastbootUsbClient(transport)

        val reply = client.sendCommand("flashing unlock")

        assertEquals("OKAY", reply.status)
        assertEquals("done", reply.message)
        assertEquals(listOf("erasing boot_a", "writing boot_a"), reply.info)
        assertEquals(
            listOf("flashing unlock"),
            transport.writtenChunks.map { String(it, StandardCharsets.UTF_8) }
        )
        assertTrue(transport.readTimeouts.all { it == FastbootUsbClient.FLASH_TIMEOUT_MS })
    }

    @Test
    fun fastbootFlashImage_streamsInChunksAndCompletesProtocolHandshake() {
        val payload = ByteArray(FastbootUsbClient.MAX_USB_TRANSFER + 250) { (it % 251).toByte() }
        val transport = FakeFastbootTransport(
            ArrayDeque(
                listOf(
                    String.format(Locale.US, "DATA%08x", payload.size).toByteArray(StandardCharsets.US_ASCII),
                    "OKAY".toByteArray(StandardCharsets.US_ASCII),
                    "OKAY".toByteArray(StandardCharsets.US_ASCII)
                )
            )
        )
        val client = FastbootUsbClient(transport)

        val result = client.flashImage("init_boot_a", ByteArrayInputStream(payload), payload.size.toLong())

        assertEquals("Successfully flashed init_boot_a!", result)
        assertEquals(4, transport.writtenChunks.size)
        assertEquals(
            String.format(Locale.US, "download:%08x", payload.size),
            String(transport.writtenChunks[0], StandardCharsets.UTF_8)
        )
        assertArrayEquals(payload, transport.writtenChunks[1] + transport.writtenChunks[2])
        assertEquals("flash:init_boot_a", String(transport.writtenChunks[3], StandardCharsets.UTF_8))
    }

    @Test
    fun fastbootFlashImage_rejectsInvalidSizesWithoutWriting() {
        val transport = FakeFastbootTransport(ArrayDeque())
        val client = FastbootUsbClient(transport)

        val zeroReply = client.flashImage("boot_a", ByteArrayInputStream(ByteArray(0)), 0L)
        assertTrue(zeroReply.startsWith("Download failed: invalid image size"))
        assertTrue(transport.writtenChunks.isEmpty())
    }

    @Test
    fun fastbootClient_throwsOnMalformedResponseAndAfterClose() {
        val transport = FakeFastbootTransport(
            ArrayDeque(listOf("OK".toByteArray(StandardCharsets.US_ASCII)))
        )
        val client = FastbootUsbClient(transport)

        assertThrows(IOException::class.java) {
            client.sendCommand("getvar:product")
        }

        client.close()
        assertTrue(transport.closed)
        assertThrows(IOException::class.java) {
            client.sendCommand("getvar:product")
        }
    }

    @Test
    fun fastbootReply_formatDisplayFormatsInfoOkayAndFailConsistently() {
        val infoOkay = FastbootReply(
            status = "OKAY",
            message = "unlocked: yes",
            info = listOf("product: raven")
        )
        assertEquals("INFO: product: raven\nOKAY: unlocked: yes", infoOkay.formatDisplay())

        val bareOkay = FastbootReply(status = "OKAY", message = "")
        assertEquals("OKAY", bareOkay.formatDisplay())

        val fail = FastbootReply(status = "FAIL", message = "command not allowed")
        assertEquals("FAIL: command not allowed", fail.formatDisplay())
        assertFalse(fail.isOkay)
    }
}
