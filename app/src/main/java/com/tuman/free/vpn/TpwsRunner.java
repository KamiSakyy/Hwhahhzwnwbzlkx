package com.tuman.free.vpn;

import android.content.Context;

import com.tuman.free.core.Engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Launches and supervises the bundled {@code tpws} daemon in SOCKS5 mode.
 *
 * <p>tpws is the userspace transparent proxy from bol-van/zapret. Running it with
 * {@code --socks} turns it into a local SOCKS5 server that applies DPI
 * desynchronisation to every connection it relays. The TUN bridge in this app
 * feeds it the device traffic, which is what makes the whole thing work without
 * root and without iptables.
 *
 * <p><b>Binary placement.</b> The executable ships as {@code jniLibs/<abi>/libtpws.so}.
 * The package installer extracts it into the app's {@code nativeLibraryDir}, which is
 * one of the few locations Android 10+ still allows {@code exec()} from (W^X: files in
 * the writable app home directory can no longer be executed by apps targeting API 29+).
 * A copy into {@code filesDir} is kept only as a fallback.
 */
public final class TpwsRunner {

    public static final String LIB_NAME = "libtpws.so";

    /** Ports tried in order, so a busy one can never brick the tunnel. */
    public static final int[] PORT_CANDIDATES = {19821, 19822, 19823, 19824, 19825};

    private final Object lock = new Object();
    private Process process;
    private Thread logThread;
    private int port = -1;
    private volatile boolean stopping;

    public int port() {
        return port;
    }

    public boolean isAlive() {
        synchronized (lock) {
            Process p = process;
            if (p == null) return false;
            try {
                p.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }
    }

    /**
     * Starts tpws and waits until it actually accepts connections on {@code port}.
     *
     * @return true when the SOCKS5 endpoint is ready to be used
     */
    public boolean start(Context ctx, NetGuard guard, int port, String[] args, boolean verbose) {
        synchronized (lock) {
            stopLocked();
            this.port = port;
            stopping = false;
        }

        File bin = locate(ctx);
        if (bin == null) {
            Engine.log("\u2717 \u0411\u0438\u043d\u0430\u0440\u043d\u0438\u043a tpws \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d");
            return false;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("--socks");
        cmd.add("--port=" + port);
        cmd.add("--bind-addr=127.0.0.1");
        cmd.add(verbose ? "--debug=1" : "--debug=0");
        cmd.addAll(Arrays.asList(args));

        StringBuilder sb = new StringBuilder("$ tpws");
        for (int i = 1; i < cmd.size(); i++) sb.append(' ').append(cmd.get(i));
        Engine.log(sb.toString());

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(ctx.getFilesDir());
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (Throwable t) {
            Engine.log("\u2717 \u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c tpws: " + t);
            return false;
        }

        synchronized (lock) {
            process = p;
            logThread = new Thread(new LogPump(p), "tpws-log");
            logThread.setDaemon(true);
            logThread.start();
        }

        if (!waitForListening(guard, 6000L)) {
            String reason = stopping ? "" : " (\u043f\u043e\u0440\u0442 " + port + " \u043d\u0435 \u043e\u0442\u043a\u0440\u044b\u043b\u0441\u044f)";
            Engine.log("\u2717 tpws \u043d\u0435 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u043b\u0441\u044f" + reason);
            stop();
            return false;
        }
        return true;
    }

    public void stop() {
        synchronized (lock) {
            stopping = true;
            stopLocked();
        }
    }

    private void stopLocked() {
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
        Thread t = logThread;
        logThread = null;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Blocks until the supervised process exits. Returns its exit code. */
    public int awaitExit() {
        Process p;
        synchronized (lock) {
            p = process;
        }
        if (p == null) return -1;
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // ------------------------------------------------------------------ internals

    private boolean waitForListening(NetGuard guard, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stopping) return false;
            if (!isAlive()) return false;
            Socket s = new Socket();
            try {
                guard.protectSocket(s);
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Throwable ignored) {
                // not listening yet
            } finally {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                Thread.sleep(70L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Finds an executable tpws. The primary location is the read-only
     * nativeLibraryDir; if for any reason it is not usable we fall back to a
     * private copy that we can chmod ourselves.
     */
    private File locate(Context ctx) {
        String abi = primaryAbi();
        File primary = new File(ctx.getApplicationInfo().nativeLibraryDir, LIB_NAME);
        if (primary.isFile()) {
            if (primary.canExecute()) return primary;
            File copy = copyToPrivate(ctx, primary);
            if (copy != null) return copy;
            return primary;
        }
        File copy = new File(ctx.getFilesDir(), "tpws");
        if (copy.isFile() && copy.canExecute()) return copy;
        Engine.log("\u2717 " + LIB_NAME + " \u043e\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u0435\u0442 \u0432 "
                + ctx.getApplicationInfo().nativeLibraryDir + " (abi=" + abi + ")");
        return null;
    }

    private File copyToPrivate(Context ctx, File src) {
        File dst = new File(ctx.getFilesDir(), "tpws");
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = new FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            try {
                os.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Engine.log("! \u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435 tpws: " + t.getMessage());
            return null;
        } finally {
            close(is);
            close(os);
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dst.setReadable(true, false);
        return dst.canExecute() ? dst : null;
    }

    private static String primaryAbi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** Streams tpws stdout/stderr into the in-app log. */
    private static final class LogPump implements Runnable {
        private final Process p;

        LogPump(Process p) {
            this.p = p;
        }

        @Override
        public void run() {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() == 0) continue;
                    Engine.log("[tpws] " + line);
                }
            } catch (Throwable ignored) {
                // stream closed on stop()
            } finally {
                close(r);
            }
        }
    }
}
