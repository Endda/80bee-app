package com.eightbee.app

import com.cgutman.adblib.AdbChannel
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbMessage
import com.cgutman.adblib.AdbProtocol
import com.eightbee.app.connection.ConnectionState
import com.eightbee.app.connection.FastbootTransport
import com.eightbee.app.connection.FastbootUsbClient
import com.eightbee.app.connection.OtgConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class OtgConnectionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeAdbServer(
        private val onOpen: (destination: String, server: FakeAdbServer, localId: Int, remoteId: Int) -> Unit
    ) : AutoCloseable {
        private val deviceToHostOut = PipedOutputStream()
        private val deviceToHostIn = PipedInputStream(deviceToHostOut, 65536)
        private val nextRemoteId = java.util.concurrent.atomic.AtomicInteger(100)
        private val streamDestinations = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val openedDestinations = CopyOnWriteArrayList<String>()
        val receivedWrites = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val closedStreams = CopyOnWriteArrayList<String>()

        val connection: AdbConnection

        init {
            val channel = object : AdbChannel {
                override fun readx(buffer: ByteArray, length: Int) {
                    var offset = 0
                    while (offset < length) {
                        val n = deviceToHostIn.read(buffer, offset, length - offset)
                        if (n < 0) throw IOException("EOF")
                        offset += n
                    }
                }

                override fun writex(message: AdbMessage) {
                    when (message.command) {
                        AdbProtocol.CMD_CNXN -> {
                            sendPacket(
                                AdbProtocol.generateMessage(
                                    AdbProtocol.CMD_CNXN,
                                    AdbProtocol.CONNECT_VERSION,
                                    4096,
                                    "device::\u0000".toByteArray(StandardCharsets.UTF_8)
                                )
                            )
                        }
                        AdbProtocol.CMD_OPEN -> {
                            val localId = message.arg0
                            val remoteId = nextRemoteId.getAndIncrement()
                            val rawDest = message.payload?.let {
                                String(it, 0, (it.size - 1).coerceAtLeast(0), StandardCharsets.UTF_8)
                            }.orEmpty()
                            openedDestinations.add(rawDest)
                            streamDestinations[remoteId] = rawDest
                            onOpen(rawDest, this@FakeAdbServer, localId, remoteId)
                        }
                        AdbProtocol.CMD_WRTE -> {
                            val remoteId = message.arg1
                            val dest = streamDestinations[remoteId].orEmpty()
                            val payload = message.payload?.copyOf() ?: ByteArray(0)
                            receivedWrites.add(dest to payload)
                            sendPacket(AdbProtocol.generateReady(remoteId, message.arg0))
                        }
                        AdbProtocol.CMD_CLSE -> {
                            val remoteId = message.arg1
                            val dest = streamDestinations[remoteId].orEmpty()
                            closedStreams.add(dest)
                        }
                    }
                }

                override fun close() {
                    deviceToHostIn.close()
                    deviceToHostOut.close()
                }
            }
            connection = AdbConnection.create(channel, null)
            connection.connect()
        }

        fun sendOkay(remoteId: Int, localId: Int) {
            sendPacket(AdbProtocol.generateReady(remoteId, localId))
        }

        fun sendWrite(remoteId: Int, localId: Int, data: ByteArray) {
            sendPacket(AdbProtocol.generateWrite(remoteId, localId, data))
        }

        fun sendClose(remoteId: Int, localId: Int) {
            sendPacket(AdbProtocol.generateClose(remoteId, localId))
        }

        fun sendTextAndClose(remoteId: Int, localId: Int, vararg chunks: String) {
            sendOkay(remoteId, localId)
            chunks.forEach { chunk ->
                sendWrite(remoteId, localId, chunk.toByteArray(StandardCharsets.UTF_8))
            }
            sendClose(remoteId, localId)
        }

        private fun sendPacket(msg: AdbMessage) {
            synchronized(deviceToHostOut) {
                deviceToHostOut.write(msg.message)
                if (msg.payload != null) {
                    deviceToHostOut.write(msg.payload)
                }
                deviceToHostOut.flush()
            }
        }

        override fun close() {
            runCatching { connection.close() }
        }
    }

    private class FakeFastbootTransport(
        private val replies: ArrayDeque<ByteArray>,
        private val failOnWrite: Boolean = false
    ) : FastbootTransport {
        val written = mutableListOf<ByteArray>()

        override fun bulkWrite(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (failOnWrite) throw RuntimeException("usb write boom")
            written.add(buffer.copyOfRange(offset, offset + length))
            return length
        }

        override fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            val next = replies.removeFirstOrNull() ?: return -1
            val count = minOf(length, next.size)
            System.arraycopy(next, 0, buffer, 0, count)
            return count
        }

        override fun close() {}
    }

    private fun createManager(
        adbConnection: AdbConnection? = null,
        fastbootClient: FastbootUsbClient? = null
    ): OtgConnectionManager {
        return OtgConnectionManager(
            filesDirProvider = { tempFolder.root },
            ioDispatcher = Dispatchers.Unconfined,
            cryptoProvider = { null },
            logSink = { _, _, _ -> },
            initialAdbConnection = adbConnection,
            initialFastbootClient = fastbootClient
        )
    }

    @Test
    fun runShellCommand_readsTrimmedOutputAndWrapsNonIoFailures() = runBlocking {
        FakeAdbServer { dest, server, localId, remoteId ->
            when (dest) {
                "shell:echo ok" -> server.sendTextAndClose(remoteId, localId, "  hello ", "world \n")
                "shell:reject" -> server.sendClose(0, localId) // ConnectException (which is an IOException)
            }
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            assertTrue(manager.isAvailable())
            assertEquals("hello world", manager.runShellCommand("echo ok"))

            try {
                manager.runShellCommand("reject")
                fail("Expected IOException when stream open is rejected")
            } catch (_: IOException) {
            }
            manager.close()
        }

        val disconnected = createManager()
        assertFalse(disconnected.isAvailable())
        try {
            disconnected.runShellCommand("echo ok")
            fail("Expected IOException when ADB is disconnected")
        } catch (expected: IOException) {
            assertEquals("ADB not connected.", expected.message)
        }
        disconnected.close()
    }

    @Test
    fun runShellCommandWithInput_streamsChunkedStdinAndReadsOutput() = runBlocking {
        val payload = ByteArray(5000) { ('a'.code + (it % 26)).toByte() }
        FakeAdbServer { _, server, localId, remoteId ->
            server.sendOkay(remoteId, localId)
            // Delay response slightly so writerJob transmits chunks before EOF
            Thread {
                Thread.sleep(60)
                server.sendWrite(remoteId, localId, "done\n".toByteArray(StandardCharsets.UTF_8))
                server.sendClose(remoteId, localId)
            }.start()
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            val result = manager.runShellCommandWithInput("cat", ByteArrayInputStream(payload))

            assertEquals("done", result)
            val combined = ByteArrayOutputStream()
            server.receivedWrites.filter { it.first == "shell:cat" }.forEach { combined.write(it.second) }
            assertArrayEquals(payload, combined.toByteArray())
            manager.close()
        }
    }

    @Test
    fun runShellCommandLive_splitsChunksIntoLinesAndFlushesTrailingLine() = runBlocking {
        FakeAdbServer { _, server, localId, remoteId ->
            server.sendTextAndClose(remoteId, localId, "line1\nli", "ne2\nlast")
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            val lines = mutableListOf<String>()
            manager.runShellCommandLive("logcat") { lines.add(it) }

            assertEquals(listOf("line1", "line2", "last"), lines)
            manager.close()
        }
    }

    @Test
    fun pushFile_writesChunksReportsProgressClosesStreamAndVerifiesRemoteSize() = runBlocking {
        val payload = ByteArray(4500) { (it % 251).toByte() }
        FakeAdbServer { dest, server, localId, remoteId ->
            when {
                dest.startsWith("exec:dd ") -> server.sendOkay(remoteId, localId)
                dest.startsWith("shell:stat -c %s ") ->
                    server.sendTextAndClose(remoteId, localId, "${payload.size}\n")
            }
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            val progressUpdates = mutableListOf<Pair<Long, Long>>()

            manager.pushFile(
                input = ByteArrayInputStream(payload),
                size = payload.size.toLong(),
                remotePath = "/data/local/tmp/test.bin"
            ) { sent, total ->
                progressUpdates.add(sent to total)
            }

            assertEquals(listOf(4096L to 4500L, 4500L to 4500L), progressUpdates)
            val pushed = ByteArrayOutputStream()
            server.receivedWrites.filter { it.first.startsWith("exec:dd ") }.forEach { pushed.write(it.second) }
            assertArrayEquals(payload, pushed.toByteArray())
            assertTrue(server.closedStreams.any { it.startsWith("exec:dd ") })
            manager.close()
        }
    }

    @Test
    fun pushFile_throwsWhenSizeVerificationFails() = runBlocking {
        val payload = "abc".toByteArray(StandardCharsets.UTF_8)
        FakeAdbServer { dest, server, localId, remoteId ->
            when {
                dest.startsWith("exec:dd ") -> server.sendOkay(remoteId, localId)
                dest.startsWith("shell:stat -c %s ") -> server.sendTextAndClose(remoteId, localId, "1\n")
            }
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            try {
                manager.pushFile(ByteArrayInputStream(payload), payload.size.toLong(), "/data/local/tmp/bad.bin")
                fail("Expected IOException on size verification mismatch")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("Push size verification failed"))
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun pullFile_writesDestinationAndDeletesOnSizeMismatch() = runBlocking {
        val goodData = "payload-bytes".toByteArray(StandardCharsets.UTF_8)
        FakeAdbServer { dest, server, localId, remoteId ->
            when {
                dest == "shell:stat -c %s '/sdcard/good.bin'" ->
                    server.sendTextAndClose(remoteId, localId, "${goodData.size}\n")
                dest == "exec:cat '/sdcard/good.bin'" -> {
                    server.sendOkay(remoteId, localId)
                    server.sendWrite(remoteId, localId, goodData)
                    server.sendClose(remoteId, localId)
                }
                dest == "shell:stat -c %s '/sdcard/short.bin'" ->
                    server.sendTextAndClose(remoteId, localId, "100\n")
                dest == "exec:cat '/sdcard/short.bin'" -> {
                    server.sendOkay(remoteId, localId)
                    server.sendWrite(remoteId, localId, goodData)
                    server.sendClose(remoteId, localId)
                }
            }
        }.use { server ->
            val manager = createManager(adbConnection = server.connection)
            val goodDest = File(tempFolder.root, "good.bin")
            val pulledProgress = mutableListOf<Long>()
            manager.pullFile("/sdcard/good.bin", goodDest, maxBytes = 1024L) { pulledProgress.add(it) }
            assertArrayEquals(goodData, goodDest.readBytes())
            assertEquals(listOf(goodData.size.toLong()), pulledProgress)

            val shortDest = File(tempFolder.root, "short.bin")
            try {
                manager.pullFile("/sdcard/short.bin", shortDest, maxBytes = 1024L)
                fail("Expected IOException when pulled bytes != stat size")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("Pull size verification failed"))
                assertFalse(shortDest.exists())
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun fastbootMethods_delegateWrapUnexpectedExceptionsAndDisconnectResetsState() = runBlocking {
        val okTransport = FakeFastbootTransport(
            ArrayDeque(listOf("OKAYunlocked".toByteArray(StandardCharsets.US_ASCII)))
        )
        val okClient = FastbootUsbClient(okTransport)
        val manager = createManager(fastbootClient = okClient)

        assertTrue(manager.isAvailable())
        val reply = manager.sendFastbootCommand("getvar:unlocked")
        assertEquals("OKAY", reply.status)
        assertEquals("unlocked", reply.message)

        val failingTransport = FakeFastbootTransport(ArrayDeque(), failOnWrite = true)
        val failingManager = createManager(fastbootClient = FastbootUsbClient(failingTransport))
        try {
            failingManager.sendFastbootCommand("getvar:all")
            fail("Expected IOException wrapping runtime exception")
        } catch (expected: IOException) {
            assertEquals("Error executing Fastboot via OTG: usb write boom", expected.message)
        }

        try {
            failingManager.flashFastbootImage("boot_a", ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3L)
            fail("Expected IOException wrapping runtime exception")
        } catch (expected: IOException) {
            assertEquals("Error flashing via OTG: usb write boom", expected.message)
        }

        manager.disconnect().join()
        assertFalse(manager.isAvailable())
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertFalse(manager.isAvailableFlow.value)

        failingManager.close()
        manager.close()
    }
}
