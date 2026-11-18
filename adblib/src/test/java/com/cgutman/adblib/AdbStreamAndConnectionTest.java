package com.cgutman.adblib;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AdbStreamAndConnectionTest {

    @Test
    public void readDrainsQueuedPayloadsAfterCloseBeforeThrowingEof() throws Exception {
        AdbStream stream = new AdbStream(null, 1);
        byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "world".getBytes(StandardCharsets.UTF_8);

        stream.addPayload(part1);
        stream.addPayload(part2);
        stream.notifyClose();

        assertArrayEquals(part1, stream.read());
        assertArrayEquals(part2, stream.read());

        try {
            stream.read();
            fail("Expected IOException once readQueue is drained on closed stream");
        } catch (IOException expected) {
            assertEquals("Stream closed", expected.getMessage());
        }
    }

    @Test
    public void generateOpenHandlesNonAsciiUtf8Destinations() throws Exception {
        String dest = "exec:cat '/sdcard/café_测试.img'";
        AdbMessage msg = AdbProtocol.generateOpen(7, dest);
        byte[] expected = (dest + "\u0000").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, msg.getPayload());
    }

    @Test
    public void openReturnsShortLivedStreamWithQueuedDataWhenOkayWrteClseArriveFast() throws Exception {
        PipedOutputStream deviceToHostOut = new PipedOutputStream();
        PipedInputStream deviceToHostIn = new PipedInputStream(deviceToHostOut, 8192);
        BlockingQueue<AdbMessage> sentMessages = new LinkedBlockingQueue<>();

        AdbChannel fakeChannel = new AdbChannel() {
            @Override
            public void readx(byte[] buffer, int length) throws IOException {
                int offset = 0;
                while (offset < length) {
                    int n = deviceToHostIn.read(buffer, offset, length - offset);
                    if (n < 0) throw new IOException("EOF");
                    offset += n;
                }
            }

            @Override
            public void writex(AdbMessage message) throws IOException {
                sentMessages.add(message);
                if (message.getCommand() == AdbProtocol.CMD_CNXN) {
                    writePacket(deviceToHostOut, AdbProtocol.generateMessage(
                        AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, 4096,
                        "device::\u0000".getBytes(StandardCharsets.UTF_8)
                    ));
                } else if (message.getCommand() == AdbProtocol.CMD_OPEN) {
                    int localId = message.getArg0();
                    int remoteId = 42;
                    // Respond immediately with OKAY, WRTE, and CLSE back-to-back.
                    writePacket(deviceToHostOut, AdbProtocol.generateReady(remoteId, localId));
                    writePacket(deviceToHostOut, AdbProtocol.generateWrite(
                        remoteId, localId, "33\n".getBytes(StandardCharsets.UTF_8)
                    ));
                    writePacket(deviceToHostOut, AdbProtocol.generateClose(remoteId, localId));
                }
            }

            @Override
            public void close() throws IOException {
                deviceToHostIn.close();
                deviceToHostOut.close();
            }
        };

        AdbConnection conn = AdbConnection.create(fakeChannel, null);
        conn.connect();

        AdbStream stream = conn.open("shell:getprop ro.product.first_api_level");
        byte[] data = stream.read();
        assertEquals("33\n", new String(data, StandardCharsets.UTF_8));
        try {
            stream.read();
            fail("Expected EOF after draining");
        } catch (IOException expected) {
            assertEquals("Stream closed", expected.getMessage());
        }

        conn.close();
    }

    @Test
    public void openThrowsConnectExceptionWhenRejectedWithClse() throws Exception {
        PipedOutputStream deviceToHostOut = new PipedOutputStream();
        PipedInputStream deviceToHostIn = new PipedInputStream(deviceToHostOut, 8192);

        AdbChannel fakeChannel = new AdbChannel() {
            @Override
            public void readx(byte[] buffer, int length) throws IOException {
                int offset = 0;
                while (offset < length) {
                    int n = deviceToHostIn.read(buffer, offset, length - offset);
                    if (n < 0) throw new IOException("EOF");
                    offset += n;
                }
            }

            @Override
            public void writex(AdbMessage message) throws IOException {
                if (message.getCommand() == AdbProtocol.CMD_CNXN) {
                    writePacket(deviceToHostOut, AdbProtocol.generateMessage(
                        AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, 4096,
                        "device::\u0000".getBytes(StandardCharsets.UTF_8)
                    ));
                } else if (message.getCommand() == AdbProtocol.CMD_OPEN) {
                    int localId = message.getArg0();
                    writePacket(deviceToHostOut, AdbProtocol.generateClose(0, localId));
                }
            }

            @Override
            public void close() throws IOException {
                deviceToHostIn.close();
                deviceToHostOut.close();
            }
        };

        AdbConnection conn = AdbConnection.create(fakeChannel, null);
        conn.connect();

        try {
            conn.open("shell:bad");
            fail("Expected ConnectException when peer rejects OPEN with CLSE");
        } catch (ConnectException expected) {
            // Expected
        } finally {
            conn.close();
        }
    }

    private static void writePacket(PipedOutputStream out, AdbMessage msg) throws IOException {
        synchronized (out) {
            out.write(msg.getMessage());
            if (msg.getPayload() != null) {
                out.write(msg.getPayload());
            }
            out.flush();
        }
    }
}
