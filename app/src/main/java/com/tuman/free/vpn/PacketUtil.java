package com.tuman.free.vpn;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal IPv4 / TCP / UDP packet builder and parser helpers.
 *
 * <p>IP addresses are handled as plain {@code int} values so the hot packet path
 * does not allocate. Sequence numbers are kept as unsigned 32-bit values inside a
 * {@code long} and compared with {@link #seqCmp(long, long)}, which implements the
 * usual modular (RFC 1982) arithmetic.
 */
public final class PacketUtil {

    public static final int PROTO_ICMP = 1;
    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public static final int F_FIN = 0x01;
    public static final int F_SYN = 0x02;
    public static final int F_RST = 0x04;
    public static final int F_PSH = 0x08;
    public static final int F_ACK = 0x10;

    private static final AtomicInteger IP_ID = new AtomicInteger(1);

    private PacketUtil() {
    }

    // ------------------------------------------------------------------ basic

    public static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    public static void w16(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 8);
        b[i + 1] = (byte) v;
    }

    public static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24) | ((long) (b[i + 1] & 0xFF) << 16)
                | ((long) (b[i + 2] & 0xFF) << 8) | (long) (b[i + 3] & 0xFF);
    }

    public static void w32(byte[] b, int i, long v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    public static int ipAt(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    public static void wIp(byte[] b, int i, int ip) {
        b[i] = (byte) (ip >>> 24);
        b[i + 1] = (byte) (ip >>> 16);
        b[i + 2] = (byte) (ip >>> 8);
        b[i + 3] = (byte) ip;
    }

    public static String ipToStr(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /** RFC 1071 internet checksum over {@code d[off .. off+len)}. */
    public static int checksum(byte[] d, int off, int len) {
        long sum = 0L;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (d[i] & 0xFF) << 8;
        while ((sum >>> 16) != 0L) sum = (sum & 0xFFFFL) + (sum >>> 16);
        return (~((int) sum)) & 0xFFFF;
    }

    /** Signed modular difference of two TCP sequence numbers: >0 when {@code a} is after {@code b}. */
    public static int seqCmp(long a, long b) {
        return (int) (a - b);
    }

    /** Unsigned modular magnitude of {@code a - b}. */
    public static long seqLen(long a, long b) {
        return (a - b) & 0xFFFFFFFFL;
    }

    public static long nextSeq(long seq, long delta) {
        return (seq + delta) & 0xFFFFFFFFL;
    }

    // -------------------------------------------------------------- checksums

    private static int l4Checksum(byte[] pkt, int l4Off, int l4Len, int proto, int csumField) {
        int total = 12 + l4Len;
        byte[] buf = new byte[total];
        System.arraycopy(pkt, 12, buf, 0, 8);          // src ip + dst ip
        buf[8] = 0;
        buf[9] = (byte) proto;
        w16(buf, 10, l4Len);
        System.arraycopy(pkt, l4Off, buf, 12, l4Len);
        buf[12 + csumField] = 0;                        // the checksum field itself
        buf[13 + csumField] = 0;
        return checksum(buf, 0, total);
    }

    // ---------------------------------------------------------------- builders

    /**
     * Builds a complete IPv4 packet carrying one TCP segment.
     *
     * @param mss when &gt; 0 an MSS option is appended (used for the SYN-ACK only)
     */
    public static byte[] buildTcp(int srcIp, int srcPort, int dstIp, int dstPort,
                                  long seq, long ack, int flags, int window,
                                  byte[] payload, int payloadLen, int mss) {
        int optLen = mss > 0 ? 4 : 0;
        int tcpHdr = 20 + optLen;
        int total = 20 + tcpHdr + payloadLen;
        byte[] p = new byte[total];

        p[0] = 0x45;                                     // version 4, IHL 5
        p[1] = 0;
        w16(p, 2, total);
        w16(p, 4, IP_ID.incrementAndGet() & 0xFFFF);
        w16(p, 6, 0x4000);                               // don't fragment
        p[8] = 64;                                       // ttl
        p[9] = PROTO_TCP;
        w16(p, 10, 0);
        wIp(p, 12, srcIp);
        wIp(p, 16, dstIp);
        w16(p, 10, checksum(p, 0, 20));

        w16(p, 20, srcPort);
        w16(p, 22, dstPort);
        w32(p, 24, seq);
        w32(p, 28, ack);
        p[32] = (byte) ((tcpHdr / 4) << 4);
        p[33] = (byte) flags;
        w16(p, 34, window & 0xFFFF);
        w16(p, 36, 0);
        w16(p, 38, 0);
        if (mss > 0) {
            p[40] = 2;                                    // kind = MSS
            p[41] = 4;                                    // length
            w16(p, 42, mss);
        }
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, p, 20 + tcpHdr, payloadLen);
        }
        w16(p, 36, l4Checksum(p, 20, tcpHdr + payloadLen, PROTO_TCP, 16));
        return p;
    }

    /** Builds a complete IPv4 packet carrying one UDP datagram. */
    public static byte[] buildUdp(int srcIp, int srcPort, int dstIp, int dstPort,
                                  byte[] payload, int payloadLen) {
        int udpLen = 8 + payloadLen;
        int total = 20 + udpLen;
        byte[] p = new byte[total];

        p[0] = 0x45;
        p[1] = 0;
        w16(p, 2, total);
        w16(p, 4, IP_ID.incrementAndGet() & 0xFFFF);
        w16(p, 6, 0x4000);
        p[8] = 64;
        p[9] = PROTO_UDP;
        w16(p, 10, 0);
        wIp(p, 12, srcIp);
        wIp(p, 16, dstIp);
        w16(p, 10, checksum(p, 0, 20));

        w16(p, 20, srcPort);
        w16(p, 22, dstPort);
        w16(p, 24, udpLen);
        w16(p, 26, 0);
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, p, 28, payloadLen);
        }
        int c = l4Checksum(p, 20, udpLen, PROTO_UDP, 6);
        if (c == 0) c = 0xFFFF;                           // 0 means "no checksum" in UDP
        w16(p, 26, c);
        return p;
    }
}
