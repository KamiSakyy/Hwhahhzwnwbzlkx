package com.tuman.free.vpn;

import com.tuman.free.core.Engine;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Relays UDP datagrams out of the tunnel.
 *
 * <p>In practice this is the DNS path: without it nothing would ever resolve,
 * because Android sends the resolver's queries through the very interface we own.
 * A query is forwarded to the address the app actually asked for, with two public
 * resolvers as a fallback, and the answer is rewritten back so the client sees the
 * server it expected.
 *
 * <p>Anything that is not DNS is dropped on purpose when UDP blocking is on: that
 * kills QUIC/HTTP3 and forces apps onto TCP, which is the only transport tpws can
 * desynchronise.
 */
public final class UdpRelay {

    public static final int DNS_PORT = 53;

    private static final int FALLBACK_A = 0x01010101;   // 1.1.1.1
    private static final int FALLBACK_B = 0x08080808;   // 8.8.8.8
    private static final int TIMEOUT_MS = 3500;

    private final TunWriter writer;
    private final NetGuard guard;
    private ThreadPoolExecutor pool;

    public UdpRelay(TunWriter writer, NetGuard guard) {
        this.writer = writer;
        this.guard = guard;
    }

    public void start() {
        if (pool != null) return;
        pool = new ThreadPoolExecutor(2, 16, 20L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(256),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "udp-relay");
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    public void stop() {
        ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
    }

    /** Takes ownership of {@code pkt} (already copied out of the reader buffer). */
    public void relay(final byte[] pkt) {
        ThreadPoolExecutor p = pool;
        if (p == null) return;
        try {
            p.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        relayOne(pkt);
                    } catch (Throwable t) {
                        Engine.log("! udp: " + t);
                    }
                }
            });
        } catch (Throwable ignored) {
            // queue rejected / shut down
        }
    }

    private void relayOne(byte[] pkt) {
        int len = pkt.length;
        if (len < 28) return;
        int ihl = (pkt[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8) return;
        int total = PacketUtil.u16(pkt, 2);
        if (total > len || total < ihl + 8) total = len;

        int srcPort = PacketUtil.u16(pkt, ihl);
        int dstPort = PacketUtil.u16(pkt, ihl + 2);
        int udpLen = PacketUtil.u16(pkt, ihl + 4);
        if (udpLen < 8 || ihl + udpLen > total) return;

        int payloadLen = udpLen - 8;
        if (payloadLen <= 0) return;

        int srcIp = PacketUtil.ipAt(pkt, 12);
        int dstIp = PacketUtil.ipAt(pkt, 16);

        byte[] q = new byte[payloadLen];
        System.arraycopy(pkt, ihl + 8, q, 0, payloadLen);

        byte[] resp = resolve(dstIp, dstPort, q);
        if (resp == null) return;
        writer.write(PacketUtil.buildUdp(dstIp, dstPort, srcIp, srcPort, resp, resp.length));
    }

    private byte[] resolve(int dstIp, int dstPort, byte[] q) {
        int[] targets = dstPort == DNS_PORT
                ? new int[]{dstIp, FALLBACK_A, FALLBACK_B}
                : new int[]{dstIp};
        for (int i = 0; i < targets.length; i++) {
            if (i > 0 && targets[i] == dstIp) continue;
            byte[] r = once(targets[i], dstPort, q);
            if (r != null) return r;
        }
        return null;
    }

    private byte[] once(int ip, int port, byte[] q) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            guard.protectDatagram(s);
            s.setSoTimeout(TIMEOUT_MS);
            byte[] raw = new byte[4];
            PacketUtil.wIp(raw, 0, ip);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByAddress(raw), port));
            byte[] buf = new byte[4096];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            s.receive(rp);
            if (rp.getLength() <= 0) return null;
            return Arrays.copyOf(buf, rp.getLength());
        } catch (Throwable t) {
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
