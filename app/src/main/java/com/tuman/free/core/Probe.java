package com.tuman.free.core;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * Connectivity probes executed <i>through the local tpws SOCKS5 endpoint</i>.
 *
 * <p>Probing from the app itself is not enough: our own UID is excluded from the
 * tunnel, so a plain request would measure the direct (still blocked) connection.
 * Going through tpws measures exactly the path real apps take.
 *
 * <p>Success means "we received an HTTP status line". A DPI that blocks by SNI
 * kills the TLS handshake, which surfaces as an IOException long before any code.
 */
public final class Probe {

    public static final String[] TARGETS = {
            "https://www.google.com/generate_204",
            "https://www.youtube.com/generate_204",
            "https://discord.com/"
    };

    public static final int[] WEIGHTS = {1, 2, 2};

    public static final int CONNECT_TIMEOUT = 4000;
    public static final int READ_TIMEOUT = 4500;

    private Probe() {
    }

    public static int maxScore() {
        int m = 0;
        for (int w : WEIGHTS) m += w;
        return m;
    }

    public static final class Target {
        public final String url;
        public final boolean ok;
        public final int code;
        public final long ms;
        public final String error;
        public final int weight;

        Target(String url, boolean ok, int code, long ms, String error, int weight) {
            this.url = url;
            this.ok = ok;
            this.code = code;
            this.ms = ms;
            this.error = error;
            this.weight = weight;
        }
    }

    public static final class Result {
        public final Target[] targets;
        public final int score;
        public final long totalMs;

        Result(Target[] targets, int score, long totalMs) {
            this.targets = targets;
            this.score = score;
            this.totalMs = totalMs;
        }

        public boolean perfect() {
            return score == maxScore();
        }
    }

    /** Probes every target through the SOCKS5 endpoint listening on loopback. */
    public static Result run(int socksPort) {
        long t0 = System.currentTimeMillis();
        Target[] out = new Target[TARGETS.length];
        int score = 0;
        for (int i = 0; i < TARGETS.length; i++) {
            out[i] = once(socksPort, TARGETS[i], WEIGHTS[i]);
            if (out[i].ok) score += WEIGHTS[i];
        }
        return new Result(out, score, System.currentTimeMillis() - t0);
    }

    public static Target once(int socksPort, String url, int weight) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            Proxy px = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            c = (HttpURLConnection) new URL(url).openConnection(px);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            return new Target(url, true, code, ms, null, weight);
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            String msg = t.getMessage();
            if (msg == null || msg.length() == 0) msg = t.getClass().getSimpleName();
            return new Target(url, false, 0, ms, msg, weight);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
