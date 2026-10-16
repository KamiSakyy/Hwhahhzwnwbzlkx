package com.tuman.free.vpn;

import android.os.ParcelFileDescriptor;

import com.tuman.free.core.Engine;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart of the no-root mode.
 *
 * <p>Reads raw IP packets from the TUN device handed to us by {@link android.net.VpnService},
 * terminates TCP locally and relays the payload into tpws running as a SOCKS5 server
 * on loopback. DNS is relayed by {@link UdpRelay}; everything else on UDP is dropped so
 * QUIC cannot sneak past the desynchronisation.
 */
public final class TunBridge {

    private static final int MAX_SESSIONS = 192;
    private static final long SWEEP_INTERVAL_MS = 1000L;

    private final FileInputStream in;
    private final TunWriter writer;
    private final UdpRelay udp;
    private final NetGuard guard;
    private final int socksPort;
    private final boolean dnsOnlyUdp;

    private final ConcurrentHashMap<Long, TcpSession> sessions = new ConcurrentHashMap<>();

    private volatile boolean running;
    private Thread reader;
    private Thread sweeper;

    public TunBridge(ParcelFileDescriptor pfd, int socksPort, NetGuard guard, boolean dnsOnlyUdp) {
        this.in = new FileInputStream(pfd.getFileDescriptor());
        this.writer = new TunWriter(pfd);
        this.guard = guard;
        this.socksPort = socksPort;
        this.dnsOnlyUdp = dnsOnlyUdp;
        this.udp = new UdpRelay(writer, guard);
    }

    public void start() {
        if (running) return;
        running = true;
        writer.start();
        udp.start();
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "tun-reader");
        reader.setDaemon(true);
        reader.start();

        sweeper = new Thread(new Runnable() {
            @Override
            public void run() {
                sweepLoop();
            }
        }, "tun-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    public void stop() {
        running = false;
        for (TcpSession s : sessions.values()) s.close();
        sessions.clear();
        udp.stop();
        writer.stop();
        interrupt(reader);
        interrupt(sweeper);
        reader = null;
        sweeper = null;
    }

    /** Drops every live session - used after tpws has been restarted. */
    public void resetSessions() {
        for (TcpSession s : sessions.values()) s.close();
        sessions.clear();
    }

    public boolean isRunning() {
        return running && writer.isRunning();
    }

    public long droppedPackets() {
        return writer.dropped();
    }

    void onSessionClosed(long key) {
        sessions.remove(key);
    }

    // ------------------------------------------------------------------- reader

    private void readLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            int len;
            try {
                len = in.read(buf);
            } catch (IOException e) {
                break;
            } catch (Throwable t) {
                break;
            }
            if (len < 0) break;
            if (len < 20) continue;
            try {
                handlePacket(buf, len);
            } catch (Throwable t) {
                Engine.log("! packet: " + t);
            }
        }
        running = false;
    }

    private void handlePacket(byte[] buf, int len) {
        int ver = (buf[0] >> 4) & 0x0F;
        if (ver != 4) return;                       // IPv6 is pulled in only to be dropped
        int ihl = (buf[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl) return;
        int total = PacketUtil.u16(buf, 2);
        if (total < ihl || total > len) total = len;

        int proto = buf[9] & 0xFF;
        if (proto == PacketUtil.PROTO_TCP) {
            handleTcp(buf, total, ihl);
        } else if (proto == PacketUtil.PROTO_UDP) {
            handleUdp(buf, total, ihl);
        }
        // ICMP and everything else is intentionally ignored.
    }

    private void handleTcp(byte[] buf, int total, int ihl) {
        if (total < ihl + 20) return;

        int srcPort = PacketUtil.u16(buf, ihl);
        int dstPort = PacketUtil.u16(buf, ihl + 2);
        long seq = PacketUtil.u32(buf, ihl + 4);
        long ack = PacketUtil.u32(buf, ihl + 8);
        int tcpHdr = ((buf[ihl + 12] >> 4) & 0x0F) * 4;
        if (tcpHdr < 20 || ihl + tcpHdr > total) return;
        int flags = buf[ihl + 13] & 0xFF;
        int window = PacketUtil.u16(buf, ihl + 14);

        int dataOff = ihl + tcpHdr;
        int dataLen = total - dataOff;
        if (dataLen < 0) dataLen = 0;

        int srcIp = PacketUtil.ipAt(buf, 12);
        int dstIp = PacketUtil.ipAt(buf, 16);
        long key = key(srcPort, dstIp, dstPort);

        TcpSession s = sessions.get(key);
        if (s != null && (s.isClosed() || !s.matches(srcIp, srcPort, dstIp, dstPort))) {
            sessions.remove(key, s);
            s = null;
        }

        if (s == null) {
            boolean syn = (flags & PacketUtil.F_SYN) != 0 && (flags & PacketUtil.F_ACK) == 0;
            if (!syn) {
                // Orphan segment - the tunnel was restarted under the app's feet.
                // A RST makes it reconnect immediately instead of hanging.
                rst(dstIp, dstPort, srcIp, srcPort, ack, PacketUtil.nextSeq(seq, dataLen));
                return;
            }
            if (sessions.size() >= MAX_SESSIONS) {
                rst(dstIp, dstPort, srcIp, srcPort, PacketUtil.nextSeq(seq, 1L),
                        PacketUtil.nextSeq(seq, 1L));
                return;
            }
            s = new TcpSession(this, writer, guard, socksPort, key,
                    srcIp, srcPort, dstIp, dstPort, seq);
            sessions.put(key, s);
            s.onSyn();
            return;                                  // the SYN itself carries nothing else
        }

        s.onClientPacket(seq, ack, flags, window, buf, dataOff, dataLen);
    }

    private void handleUdp(byte[] buf, int total, int ihl) {
        if (total < ihl + 8) return;
        int dstPort = PacketUtil.u16(buf, ihl + 2);
        boolean dns = dstPort == UdpRelay.DNS_PORT;
        if (!dns && dnsOnlyUdp) return;              // QUIC / HTTP3 / game traffic
        if (dns) Engine.dnsQueries.incrementAndGet();
        udp.relay(Arrays.copyOf(buf, total));
    }

    private void rst(int fromIp, int fromPort, int toIp, int toPort, long seq, long ack) {
        writer.write(PacketUtil.buildTcp(fromIp, fromPort, toIp, toPort, seq, ack,
                PacketUtil.F_RST | PacketUtil.F_ACK, 0, null, 0, 0));
    }

    /**
     * Session key. Every packet leaving the tunnel carries the same source address
     * (the one we assigned to the interface), so {@code (srcPort, dstIp, dstPort)}
     * is a unique 64-bit tuple; {@link TcpSession#matches} double-checks it.
     */
    static long key(int srcPort, int dstIp, int dstPort) {
        return ((long) (srcPort & 0xFFFF) << 48)
                | (((long) dstIp & 0xFFFFFFFFL) << 16)
                | (long) (dstPort & 0xFFFF);
    }

    // ------------------------------------------------------------------ sweeper

    private void sweepLoop() {
        while (running) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();
            for (TcpSession s : sessions.values()) {
                try {
                    s.onTimer(now);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void interrupt(Thread t) {
        if (t == null) return;
        try {
            t.interrupt();
        } catch (Throwable ignored) {
        }
    }
}
