package com.cgutman.adblib;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;

/**
 * Created by xudong on 2/21/14.
 */
public class UsbChannel implements AdbChannel {

    private final UsbDeviceConnection mDeviceConnection;
    private final UsbEndpoint mEndpointOut;
    private final UsbEndpoint mEndpointIn;
    private final UsbInterface mInterface;

    private final int defaultTimeout = 1000;

    private final LinkedList<UsbRequest> mInRequestPool = new LinkedList<UsbRequest>();

    // return an IN request to the pool
    public void releaseInRequest(UsbRequest request) {
        synchronized (mInRequestPool) {
            mInRequestPool.add(request);
        }
    }


    // get an IN request from the pool
    public UsbRequest getInRequest() {
        synchronized (mInRequestPool) {
            if (mInRequestPool.isEmpty()) {
                UsbRequest request = new UsbRequest();
                request.initialize(mDeviceConnection, mEndpointIn);
                return request;
            } else {
                return mInRequestPool.removeFirst();
            }
        }
    }


    @Override
    public void readx(byte[] buffer, int length) throws IOException {
        int offset = 0;
        int maxTransfer = 16384; // 16KB limit

        while (offset < length) {
            int chunkLen = Math.min(length - offset, maxTransfer);
            UsbRequest usbRequest = getInRequest();
            ByteBuffer expected = ByteBuffer.allocate(chunkLen).order(ByteOrder.LITTLE_ENDIAN);
            usbRequest.setClientData(expected);

            if (!usbRequest.queue(expected, chunkLen)) {
                throw new IOException("fail to queue read UsbRequest of size " + chunkLen);
            }

            while (true) {
                UsbRequest wait = mDeviceConnection.requestWait();
                if (wait == null) {
                    throw new IOException("Connection.requestWait returned null");
                }

                ByteBuffer clientData = (ByteBuffer) wait.getClientData();
                wait.setClientData(null);

                if (wait.getEndpoint() == mEndpointOut) {
                    // a write UsbRequest completed, ignore
                } else if (expected == clientData) {
                    releaseInRequest(wait);
                    break;
                } else {
                    throw new IOException("unexpected behavior");
                }
            }

            expected.flip();
            int remaining = expected.remaining();
            if (remaining == 0) {
                throw new IOException("USB read returned 0 bytes");
            }
            expected.get(buffer, offset, remaining);
            offset += remaining;
        }
    }

    private void writex(byte[] buffer) throws IOException {
        int offset = 0;
        int maxTransfer = 16384; // 16KB limit

        while (offset < buffer.length) {
            int chunkLen = Math.min(buffer.length - offset, maxTransfer);
            byte[] tmp = new byte[chunkLen];
            System.arraycopy(buffer, offset, tmp, 0, chunkLen);

            int transferred = mDeviceConnection.bulkTransfer(mEndpointOut, tmp, chunkLen, defaultTimeout);
            if (transferred < 0) {
                throw new IOException("bulk transfer fail (write returned " + transferred + ")");
            }
            if (transferred == 0) {
                throw new IOException("bulk transfer timed out / sent 0 bytes");
            }
            offset += transferred;
        }
    }

    @Override
    public synchronized void writex(AdbMessage message) throws IOException {
        // Keep header and payload as separate transfers for older adbd compatibility,
        // synchronized so concurrent callers cannot interleave header/payload packets.
        writex(message.getMessage());
        byte[] payload = message.getPayload();
        if (payload != null && payload.length > 0) {
            writex(payload);
        }
    }


    @Override
    public void close() throws IOException {
        try {
            mDeviceConnection.releaseInterface(mInterface);
        } catch (Exception e) {
            // Ignore
        }
        try {
            mDeviceConnection.close();
        } catch (Exception e) {
            // Ignore
        }
    }

    public UsbChannel(UsbDeviceConnection connection, UsbInterface intf) throws IOException {
        mDeviceConnection = connection;
        mInterface = intf;

        if (!mDeviceConnection.claimInterface(mInterface, true)) {
            throw new IOException("failed to claim interface");
        }

        UsbEndpoint epOut = null;
        UsbEndpoint epIn = null;
        // look for our bulk endpoints
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    epOut = ep;
                } else {
                    epIn = ep;
                }
            }
        }
        if (epOut == null || epIn == null) {
            throw new IllegalArgumentException("not all endpoints found");
        }
        mEndpointOut = epOut;
        mEndpointIn = epIn;
    }

}
