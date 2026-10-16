package com.tuman.free.vpn;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Serialised, non-blocking writer for the TUN device.
 *
 * <p>Writing straight to the tunnel from the packet-handling threads is a classic
 * deadlock: if the kernel queue is full, {@code write(2)} blocks until somebody
 * reads from the other end - and that somebody is very often the thread that is
 * currently blocked trying to write. A single dedicated writer with a bounded
 * queue removes the coupling entirely; when the queue overflows the packet is
 * dropped and TCP retransmits it.
 */
public final class TunWriter {

    private static final int QUEUE_CAPACITY = 4096;

    private final FileOutputStream out;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long dropped = 0L;
    private volatile long written = 0L;

    public TunWriter(ParcelFileDescriptor pfd) {
        out = new FileOutputStream(pfd.getFileDescriptor());
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "tun-writer");
        thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public long dropped() {
        return dropped;
    }

    public long written() {
        return written;
    }

    /** Hands the packet to the writer thread. Never blocks. */
    public boolean write(byte[] pkt) {
        if (!running || pkt == null) return false;
        boolean ok = queue.offer(pkt);
        if (!ok) dropped++;
        return ok;
    }

    public void stop() {
        running = false;
        thread.interrupt();
        queue.clear();
    }

    private void loop() {
        while (running) {
            byte[] pkt;
            try {
                pkt = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            if (!running) break;
            try {
                out.write(pkt);
                written++;
            } catch (IOException e) {
                running = false;
                break;
            } catch (Throwable t) {
                running = false;
                break;
            }
        }
    }
}
