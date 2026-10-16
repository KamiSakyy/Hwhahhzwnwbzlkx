package com.tuman.free.vpn;

import com.tuman.free.core.Engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

/**
 * One emulated TCP connection.
 *
 * <p>The app that owns the socket talks to <i>us</i>: we answer its SYN with a
 * SYN-ACK, acknowledge its data and stream our own data back with correct
 * sequence numbers. The payload is relayed into a local SOCKS5 connection
 * handled by tpws, which is what actually performs the DPI desynchronisation.
 *
 * <p>Unlike a naive bridge this implementation keeps real send state:
 * an unacknowledged byte buffer, the peer's advertised window, duplicate-ACK
 * fast retransmit and an RTO timer driven by {@link TunBridge#onTimer()}. Without
 * that, a single slow reader on the far end silently loses data and the TLS
 * session stalls forever.
 */
public final class TcpSession {

    /** Advertised to the client in the SYN-ACK; also caps the segments we emit. */
    static final int MSS = 1400;

    private static final int WINDOW_CAP = 128 * 1024;
    private static final int MAX_UNACKED = 128 * 1024;
    private static final int MAX_DOWN_QUEUE = 128 * 1024;
    private static final int ADV_WINDOW_MAX = 65535;

    private static final long CONNECT_TIMEOUT = 10_000L;
    private static final long IDLE_TIMEOUT = 240_000L;
    private static final long CONNECT_DEADLINE = 25_000L;
    private static final long STALL_TIMEOUT = 60_000L;
    private static final long RTO_MIN = 500L;
    private static final long RTO_MAX = 8_000L;

    private static final Random RND = new Random();
    private static final String SOCKS_HOST = "127.0.0.1";

    private static final class Chunk {
        final byte[] data;
        int acked;

        Chunk(byte[] data) {
            this.data = data;
        }

        int avail() {
            return data.length - acked;
        }
    }

    final long key;
    final int srcIp;      // client (the app)
    final int srcPort;
    final int dstIp;      // remote server
    final int dstPort;

    private final TunBridge bridge;
    private final TunWriter writer;
    private final NetGuard guard;
    private final int socksPort;
    private final Object lock = new Object();

    private final long iss;
    private final long createdAt = System.currentTimeMillis();

    private long rcvNxt;              // next sequence number expected from the client
    private long sndUna;              // oldest byte we sent that is not acknowledged yet
    private long sndNxt;              // next sequence number we will use
    private long peerWindow = ADV_WINDOW_MAX;

    private final ArrayDeque<Chunk> unacked = new ArrayDeque<>();
    private int unackedBytes;
    private final ArrayDeque<byte[]> downQueue = new ArrayDeque<>();
    private int downQueueBytes;

    private Socket sock;
    private InputStream in;
    private OutputStream out;

    private volatile boolean closed;
    private volatile boolean established;
    private boolean counted;
    private boolean clientFin;
    private boolean serverFin;
    private boolean finSent;
    private boolean outputShutdown;
    private boolean clientStalled;
    private int dupAcks;
    private long rto = RTO_MIN;
    private long lastActivity = createdAt;
    private long lastSend = 0L;

    private Thread upThread;
    private Thread downThread;

    TcpSession(TunBridge bridge, TunWriter writer, NetGuard guard, int socksPort,
               long key, int srcIp, int srcPort, int dstIp, int dstPort, long synSeq) {
        this.bridge = bridge;
        this.writer = writer;
        this.guard = guard;
        this.socksPort = socksPort;
        this.key = key;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.dstIp = dstIp;
        this.dstPort = dstPort;

        this.iss = ((long) RND.nextInt()) & 0xFFFFFFFFL;
        this.rcvNxt = PacketUtil.nextSeq(synSeq, 1L);
        this.sndUna = PacketUtil.nextSeq(iss, 1L);
        this.sndNxt = this.sndUna;
        this.counted = true;
        Engine.sessionOpened();
    }

    boolean matches(int sIp, int sPort, int dIp, int dPort) {
        return sIp == srcIp && sPort == srcPort && dIp == dstIp && dPort == dstPort;
    }

    boolean isClosed() {
        return closed;
    }

    String describe() {
        return PacketUtil.ipToStr(dstIp) + ":" + dstPort;
    }

    // ------------------------------------------------------------------ handshake

    /** Answers the client's SYN and kicks off the SOCKS5 connection in the background. */
    void onSyn() {
        byte[] synAck = PacketUtil.buildTcp(dstIp, dstPort, srcIp, srcPort,
                iss, rcvNxt, PacketUtil.F_SYN | PacketUtil.F_ACK, ADV_WINDOW_MAX, null, 0, MSS);
        writer.write(synAck);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!connect()) return;
                Thread d = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        downLoop();
                    }
                }, "tun-down");
                d.setDaemon(true);
                synchronized (lock) {
                    downThread = d;
                }
                d.start();
                upLoop();
            }
        }, "tun-up");
        t.setDaemon(true);
        synchronized (lock) {
            upThread = t;
        }
        t.start();
    }

    private boolean connect() {
        Socket s = new Socket();
        try {
            guard.protectSocket(s);
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(64 * 1024);
            s.setSendBufferSize(64 * 1024);
            s.connect(new InetSocketAddress(SOCKS_HOST, socksPort), (int) CONNECT_TIMEOUT);

            InputStream si = s.getInputStream();
            OutputStream so = s.getOutputStream();

            // SOCKS5 greeting: version 5, one method, "no authentication".
            so.write(new byte[]{5, 1, 0});
            so.flush();
            byte[] r = new byte[2];
            if (!readFully(si, r, 2)) throw new IOException("short greeting reply");
            if (r[0] != 5) throw new IOException("bad socks version " + (r[0] & 0xFF));
            if (r[1] != 0) throw new IOException("auth method " + (r[1] & 0xFF) + " unsupported");

            // SOCKS5 CONNECT to the original destination, addressed by IPv4.
            byte[] req = new byte[10];
            req[0] = 5;
            req[1] = 1;              // CONNECT
            req[2] = 0;              // reserved
            req[3] = 1;              // ATYP = IPv4
            PacketUtil.wIp(req, 4, dstIp);
            PacketUtil.w16(req, 8, dstPort);
            so.write(req);
            so.flush();

            byte[] hdr = new byte[4];
            if (!readFully(si, hdr, 4)) throw new IOException("short reply");
            if (hdr[1] != 0) throw new IOException("reply code " + (hdr[1] & 0xFF));
            int atyp = hdr[3] & 0xFF;
            int addrLen;
            if (atyp == 1) {
                addrLen = 4;
            } else if (atyp == 4) {
                addrLen = 16;
            } else if (atyp == 3) {
                int l = si.read();
                if (l < 0) throw new IOException("truncated domain reply");
                addrLen = l;
            } else {
                throw new IOException("bad atyp " + atyp);
            }
            byte[] tail = new byte[addrLen + 2];
            if (!readFully(si, tail, addrLen + 2)) throw new IOException("truncated reply");

            synchronized (lock) {
                if (closed) {
                    quietClose(s);
                    return false;
                }
                sock = s;
                in = si;
                out = so;
                established = true;
                lock.notifyAll();
            }
            return true;
        } catch (Throwable t) {
            Engine.log("! " + describe() + " \u2192 socks: " + t.getMessage());
            quietClose(s);
            close();
            return false;
        }
    }

    private static boolean readFully(InputStream is, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = is.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Fed by the TUN reader thread with one parsed segment.
     *
     * @param pkt     the raw packet (payload lives at {@code dataOff})
     * @param dataLen payload length, 0 for pure control segments
     */
    void onClientPacket(long seq, long ackNum, int flags, int window,
                        byte[] pkt, int dataOff, int dataLen) {
        if (closed) return;
        synchronized (lock) {
            if (closed) return;
            lastActivity = System.currentTimeMillis();
            peerWindow = window & 0xFFFFL;

            if ((flags & PacketUtil.F_RST) != 0) {
                closeLocked();
                return;
            }

            handleAckLocked(ackNum);

            boolean accepted = true;
            if (dataLen > 0) accepted = handleDataLocked(seq, pkt, dataOff, dataLen);

            if ((flags & PacketUtil.F_FIN) != 0 && !clientFin && accepted) handleFinLocked();

            flushLocked();
        }
    }

    private void handleAckLocked(long ackNum) {
        long inflight = PacketUtil.seqLen(sndNxt, sndUna);
        int d = PacketUtil.seqCmp(ackNum, sndUna);
        if (d > 0) {
            long adv = PacketUtil.seqLen(ackNum, sndUna);
            if (adv > inflight) adv = inflight;
            trimLocked((int) adv);
            sndUna = PacketUtil.nextSeq(sndUna, adv);
            if (PacketUtil.seqCmp(sndNxt, sndUna) < 0) sndNxt = sndUna;
            dupAcks = 0;
            rto = RTO_MIN;
            lastSend = 0L;
            lock.notifyAll();
        } else if (d == 0 && inflight > 0L) {
            dupAcks++;
            if (dupAcks >= 3) {
                dupAcks = 0;
                retransmitLocked();
            }
        }
    }

    /** @return true when the payload was queued (false means "drop it, no ACK sent") */
    private boolean handleDataLocked(long seq, byte[] pkt, int dataOff, int dataLen) {
        int d = PacketUtil.seqCmp(seq, rcvNxt);
        int skip = 0;
        if (d < 0) {
            long overlap = PacketUtil.seqLen(rcvNxt, seq);
            if (overlap >= (long) dataLen) {
                sendAckLocked();          // stale retransmission, nothing new
                return true;
            }
            skip = (int) overlap;
        } else if (d > 0) {
            sendAckLocked();              // hole before this segment: pull it back
            return false;
        }

        int take = dataLen - skip;
        if (downQueueBytes + take > MAX_DOWN_QUEUE) {
            // No room. Deliberately not advancing rcvNxt and not ACKing makes the
            // peer retransmit later, which is exactly the backpressure we want.
            clientStalled = true;
            return false;
        }

        byte[] copy = new byte[take];
        System.arraycopy(pkt, dataOff + skip, copy, 0, take);
        downQueue.addLast(copy);
        downQueueBytes += take;
        rcvNxt = PacketUtil.nextSeq(seq, (long) dataLen);
        sendAckLocked();
        lock.notifyAll();
        return true;
    }

    private void handleFinLocked() {
        clientFin = true;
        rcvNxt = PacketUtil.nextSeq(rcvNxt, 1L);
        sendAckLocked();
        lock.notifyAll();
    }

    // ------------------------------------------------------------------ outbound

    private void flushLocked() {
        if (closed || !established) return;

        long inflight = PacketUtil.seqLen(sndNxt, sndUna);
        long win = peerWindow > (long) WINDOW_CAP ? (long) WINDOW_CAP : peerWindow;
        long avail = win - inflight;
        long pending = (long) unackedBytes - inflight;

        if (avail > 0L && pending > 0L) {
            long toSend = Math.min(avail, pending);
            long sent = 0L;
            while (sent < toSend) {
                int segLen = (int) Math.min((long) MSS, toSend - sent);
                byte[] seg = gatherLocked(inflight + sent, segLen);
                if (seg == null || seg.length == 0) break;
                long seq = PacketUtil.nextSeq(sndNxt, sent);
                byte[] pkt = PacketUtil.buildTcp(dstIp, dstPort, srcIp, srcPort,
                        seq, rcvNxt, PacketUtil.F_PSH | PacketUtil.F_ACK,
                        advWindowLocked(), seg, seg.length, 0);
                if (!writer.write(pkt)) {
                    closeLocked();
                    return;
                }
                sent += seg.length;
                lastSend = System.currentTimeMillis();
            }
            if (sent > 0L) sndNxt = PacketUtil.nextSeq(sndNxt, sent);
        }
        maybeFinLocked();
    }

    /** Emits FIN once everything we received from tpws has actually been handed over. */
    private void maybeFinLocked() {
        if (!serverFin || finSent || closed) return;
        if ((long) unackedBytes != PacketUtil.seqLen(sndNxt, sndUna)) return;
        byte[] pkt = PacketUtil.buildTcp(dstIp, dstPort, srcIp, srcPort,
                sndNxt, rcvNxt, PacketUtil.F_FIN | PacketUtil.F_ACK,
                advWindowLocked(), null, 0, 0);
        if (!writer.write(pkt)) {
            closeLocked();
            return;
        }
        sndNxt = PacketUtil.nextSeq(sndNxt, 1L);
        finSent = true;
        lastSend = System.currentTimeMillis();
    }

    private void retransmitLocked() {
        if (PacketUtil.seqLen(sndNxt, sndUna) == 0L) return;
        sndNxt = sndUna;
        rto = Math.min(rto * 2L, RTO_MAX);
        flushLocked();
    }

    private byte[] gatherLocked(long offset, int len) {
        byte[] out = new byte[len];
        int outPos = 0;
        long pos = 0L;
        for (Chunk c : unacked) {
            int avail = c.avail();
            if (avail <= 0) continue;
            if (pos + avail <= offset) {
                pos += avail;
                continue;
            }
            int start = (int) Math.max(offset - pos, 0L);
            int take = Math.min(avail - start, len - outPos);
            if (take > 0) {
                System.arraycopy(c.data, c.acked + start, out, outPos, take);
                outPos += take;
            }
            pos += avail;
            if (outPos >= len) break;
        }
        if (outPos < len) return Arrays.copyOf(out, outPos);
        return out;
    }

    private void trimLocked(int n) {
        int left = n;
        while (left > 0 && !unacked.isEmpty()) {
            Chunk c = unacked.peekFirst();
            int avail = c.avail();
            if (avail <= left) {
                left -= avail;
                unackedBytes -= avail;
                unacked.pollFirst();
            } else {
                c.acked += left;
                unackedBytes -= left;
                left = 0;
            }
        }
    }

    private int advWindowLocked() {
        int free = MAX_DOWN_QUEUE - downQueueBytes;
        if (free < 0) free = 0;
        if (free > ADV_WINDOW_MAX) free = ADV_WINDOW_MAX;
        if (free == 0) clientStalled = true;
        else clientStalled = false;
        return free;
    }

    private void sendAckLocked() {
        byte[] pkt = PacketUtil.buildTcp(dstIp, dstPort, srcIp, srcPort,
                sndNxt, rcvNxt, PacketUtil.F_ACK, advWindowLocked(), null, 0, 0);
        writer.write(pkt);
    }

    private void sendWindowUpdateLocked() {
        if (!clientStalled) return;
        sendAckLocked();
    }

    // --------------------------------------------------------------------- loops

    private void upLoop() {
        byte[] tmp = new byte[16384];
        try {
            while (!closed) {
                synchronized (lock) {
                    long waitStart = 0L;
                    while (!closed && unackedBytes >= MAX_UNACKED) {
                        long now = System.currentTimeMillis();
                        if (waitStart == 0L) waitStart = now;
                        else if (now - waitStart > STALL_TIMEOUT) {
                            closeLocked();
                            break;
                        }
                        lock.wait(300L);
                    }
                }
                if (closed) break;

                InputStream is = in;
                if (is == null) break;
                int n = is.read(tmp);
                if (n < 0) {
                    onServerEof();
                    break;
                }
                if (n == 0) continue;

                byte[] copy = Arrays.copyOf(tmp, n);
                synchronized (lock) {
                    if (closed) break;
                    unacked.addLast(new Chunk(copy));
                    unackedBytes += n;
                    lastActivity = System.currentTimeMillis();
                    flushLocked();
                }
                Engine.addDown(n);
            }
        } catch (IOException e) {
            // peer went away or we were closed on purpose
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Engine.log("! " + describe() + " up: " + t);
        } finally {
            onServerEof();
        }
    }

    private void onServerEof() {
        synchronized (lock) {
            if (closed) return;
            serverFin = true;
            flushLocked();
        }
    }

    private void downLoop() {
        try {
            while (!closed) {
                byte[] data = null;
                boolean doShutdown = false;
                synchronized (lock) {
                    while (!closed && downQueue.isEmpty() && !(clientFin && !outputShutdown)) {
                        lock.wait(300L);
                    }
                    if (closed) break;
                    if (!downQueue.isEmpty()) {
                        data = downQueue.pollFirst();
                        if (data != null) downQueueBytes -= data.length;
                        if (downQueueBytes < MAX_DOWN_QUEUE / 2) sendWindowUpdateLocked();
                        lock.notifyAll();
                    } else if (clientFin && !outputShutdown) {
                        doShutdown = true;
                    }
                }
                if (doShutdown) {
                    outputShutdown = true;
                    Socket s = sock;
                    if (s != null) {
                        try {
                            s.shutdownOutput();
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
                if (data == null) continue;

                OutputStream os = out;
                if (os == null) break;
                os.write(data);
                Engine.addUp(data.length);
            }
        } catch (IOException e) {
            // connection reset by the remote side
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Engine.log("! " + describe() + " down: " + t);
        } finally {
            close();
        }
    }

    // ------------------------------------------------------------------- timing

    /** Called periodically by the bridge sweeper. Handles RTO and reaping. */
    void onTimer(long now) {
        synchronized (lock) {
            if (closed) return;

            if (!established && now - createdAt > CONNECT_DEADLINE) {
                closeLocked();
                return;
            }
            if (now - lastActivity > IDLE_TIMEOUT) {
                closeLocked();
                return;
            }

            long inflight = PacketUtil.seqLen(sndNxt, sndUna);
            if (inflight > 0L && lastSend > 0L && now - lastSend > rto) {
                retransmitLocked();
            } else if (inflight > 0L || serverFin) {
                flushLocked();
            }

            if (finSent && (clientFin || now - lastActivity > 20_000L)) closeLocked();
        }
    }

    // -------------------------------------------------------------------- close

    public void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private void closeLocked() {
        if (closed) return;
        closed = true;

        if (established && !finSent && !clientFin) {
            byte[] rst = PacketUtil.buildTcp(dstIp, dstPort, srcIp, srcPort,
                    sndNxt, rcvNxt, PacketUtil.F_RST | PacketUtil.F_ACK, 0, null, 0, 0);
            writer.write(rst);
        }

        lock.notifyAll();

        Socket s = sock;
        sock = null;
        in = null;
        out = null;
        quietClose(s);

        unacked.clear();
        unackedBytes = 0;
        downQueue.clear();
        downQueueBytes = 0;

        if (counted) {
            counted = false;
            Engine.sessionClosed();
        }
        bridge.onSessionClosed(key);

        Thread a = upThread;
        Thread b = downThread;
        upThread = null;
        downThread = null;
        if (a != null && a != Thread.currentThread()) a.interrupt();
        if (b != null && b != Thread.currentThread()) b.interrupt();
    }

    private static void quietClose(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
