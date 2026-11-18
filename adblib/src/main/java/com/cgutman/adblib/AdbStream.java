package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class abstracts the underlying ADB streams
 * @author Cameron Gutman
 */
public class AdbStream implements Closeable {
	
	/** The AdbConnection object that the stream communicates over */
	private AdbConnection adbConn;
	
	/** The local ID of the stream */
	private int localId;
	
	/** The remote ID of the stream */
	private int remoteId;
	
	/** Indicates whether a write is currently allowed */
	private AtomicBoolean writeReady;
	
	/** A queue of data from the target's write packets */
	private Queue<byte[]> readQueue;
	
	/** Indicates whether the connection is closed already */
	private volatile boolean isClosed;

	/** Indicates whether the remote peer accepted the stream open (CMD_OKAY) */
	private volatile boolean isOpened;
	
	/**
	 * Creates a new AdbStream object on the specified AdbConnection
	 * with the given local ID.
	 * @param adbConn AdbConnection that this stream is running on
	 * @param localId Local ID of the stream
	 */
	public AdbStream(AdbConnection adbConn, int localId)
	{
		this.adbConn = adbConn;
		this.localId = localId;
		this.readQueue = new ConcurrentLinkedQueue<byte[]>();
		this.writeReady = new AtomicBoolean(false);
		this.isClosed = false;
		this.isOpened = false;
	}
	
	/**
	 * Called by the connection thread to indicate newly received data.
	 * @param payload Data inside the write message
	 */
	void addPayload(byte[] payload)
	{
		synchronized (readQueue) {
			readQueue.add(payload);
			readQueue.notifyAll();
		}
	}
	
	/**
	 * Called by the connection thread to send an OKAY packet, allowing the
	 * other side to continue transmission.
	 * @throws java.io.IOException If the connection fails while sending the packet
	 */
	public void sendReady() throws IOException
	{
		/* Generate and send a READY packet */
        adbConn.channel.writex(AdbProtocol.generateReady(localId, remoteId));
	}

	/**
	 * Called by the connection thread to update the remote ID for this stream
	 * @param remoteId New remote ID
	 */
	void updateRemoteId(int remoteId)
	{
		this.remoteId = remoteId;
	}

	/**
	 * Called by the connection thread to indicate the stream is okay to send data.
	 */
	void readyForWrite()
	{
		writeReady.set(true);
	}

	/**
	 * Called by the connection thread when the remote peer accepts the stream open.
	 */
	void markOpened()
	{
		this.isOpened = true;
	}

	/**
	 * Returns whether the remote peer has acknowledged opening this stream.
	 */
	boolean isOpened()
	{
		return isOpened;
	}

	/**
	 * Called by the connection thread to notify that the stream was closed by the peer.
	 */
	void notifyClose()
	{
		/* We don't call close() because it sends another CLOSE */
		isClosed = true;

		/* Unwait readers and writers */
		synchronized (this) {
			notifyAll();
		}
		synchronized (readQueue) {
			readQueue.notifyAll();
		}
	}

	/**
	 * Reads a pending write payload from the other side.
	 * @return Byte array containing the payload of the write
	 * @throws InterruptedException If we are unable to wait for data
	 * @throws java.io.IOException If the stream fails while waiting
	 */
	public byte[] read() throws InterruptedException, IOException {
		synchronized (readQueue) {
			byte[] data;
			/* Drain any queued packets before reporting stream closure */
			while ((data = readQueue.poll()) == null) {
				if (isClosed) {
					throw new IOException("Stream closed");
				}
				readQueue.wait();
			}
			return data;
		}
	}

	/**
	 * Sends a write packet with a given String payload.
	 * @param payload Payload in the form of a String
	 * @throws java.io.IOException If the stream fails while sending data
	 * @throws InterruptedException If we are unable to wait to send data
	 */
	public void write(String payload) throws IOException, InterruptedException {
		/* ADB needs null-terminated strings */
		write((payload + "\0").getBytes("UTF-8"));
	}

	/**
	 * Sends a write packet with a given byte array payload.
	 * @param payload Payload in the form of a byte array
	 * @throws java.io.IOException If the stream fails while sending data
	 * @throws InterruptedException If we are unable to wait to send data
	 */
	public void write(byte[] payload) throws IOException, InterruptedException
	{
        synchronized (this) {
			/* Make sure we're ready for a write */
            while (!isClosed && !writeReady.compareAndSet(true, false))
                wait();

            if (isClosed) {
                throw new IOException("Stream closed");
            }
        }

		/* Generate a WRITE packet and send it */
        adbConn.channel.writex(AdbProtocol.generateWrite(localId, remoteId, payload));
	}

	/**
	 * Closes the stream. This sends a close message to the peer.
	 * @throws java.io.IOException If the stream fails while sending the close message.
	 */
	@Override
	public void close() throws IOException {
		synchronized (this) {
			/* This may already be closed by the remote host */
			if (isClosed)
				return;
			
			/* Notify readers/writers that we've closed */
			notifyClose();
		}

        try {
            adbConn.channel.writex(AdbProtocol.generateClose(localId, remoteId));
        } catch (IOException e) {
            /* If the channel is dead, we don't care about sending the close packet */
        }
	}

	/**
	 * Retreives whether the stream is closed or not
	 * @return True if the stream is close, false if not
	 */
	public boolean isClosed() {
		return isClosed;
	}
}
