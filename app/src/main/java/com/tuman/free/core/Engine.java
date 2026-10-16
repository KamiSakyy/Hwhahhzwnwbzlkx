package com.tuman.free.core;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single source of truth for the tunnel state.
 *
 * <p>The VPN service and the UI live in the same process, so plain static state
 * plus a listener list is enough - no DI framework, no coroutines, no LiveData.
 * Every callback is dispatched on the main looper.
 */
public final class Engine {

    public static final int STOPPED = 0;
    public static final int STARTING = 1;
    public static final int RUNNING = 2;
    public static final int STOPPING = 3;
    public static final int ERROR = 4;

    public interface Listener {
        void onEngineChanged();

        void onLogLine(String line);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final LinkedList<String> LOG = new LinkedList<>();
    private static final int MAX_LOG = 1200;
    private static final Object LOG_LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static volatile int state = STOPPED;
    private static volatile String detail = "";
    private static volatile String strategyName = "";
    private static volatile long startedAt = 0L;
    private static volatile int socksPort = 0;

    public static final AtomicLong bytesUp = new AtomicLong();
    public static final AtomicLong bytesDown = new AtomicLong();
    public static final AtomicLong dnsQueries = new AtomicLong();
    public static final AtomicInteger activeSessions = new AtomicInteger();
    public static final AtomicInteger totalSessions = new AtomicInteger();
    public static final AtomicInteger restarts = new AtomicInteger();

    private Engine() {
    }

    // ------------------------------------------------------------------ state

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static int getState() {
        return state;
    }

    public static boolean isActive() {
        return state == RUNNING || state == STARTING || state == STOPPING;
    }

    public static boolean isRunning() {
        return state == RUNNING;
    }

    public static String getDetail() {
        return detail;
    }

    public static String getStrategyName() {
        return strategyName;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static int getSocksPort() {
        return socksPort;
    }

    public static void beginStart(String name) {
        strategyName = name == null ? "" : name;
        detail = "";
        startedAt = 0L;
        bytesUp.set(0L);
        bytesDown.set(0L);
        dnsQueries.set(0L);
        activeSessions.set(0);
        totalSessions.set(0);
        restarts.set(0);
        setState(STARTING);
    }

    public static void setRunning(String name, int port) {
        strategyName = name == null ? "" : name;
        socksPort = port;
        startedAt = System.currentTimeMillis();
        detail = "";
        setState(RUNNING);
    }

    public static void setStopping() {
        setState(STOPPING);
    }

    public static void setStopped() {
        socksPort = 0;
        startedAt = 0L;
        detail = "";
        activeSessions.set(0);
        setState(STOPPED);
    }

    public static void fail(String message) {
        detail = message == null ? "" : message;
        socksPort = 0;
        startedAt = 0L;
        activeSessions.set(0);
        setState(ERROR);
        log("\u2717 " + detail);
    }

    private static void setState(int next) {
        if (state == next) {
            notifyChanged();
            return;
        }
        state = next;
        notifyChanged();
    }

    // ------------------------------------------------------------------- logs

    public static void log(String line) {
        if (line == null) return;
        String stamped;
        synchronized (LOG_LOCK) {
            stamped = TS.format(new Date()) + "  " + line;
            LOG.addLast(stamped);
            while (LOG.size() > MAX_LOG) LOG.removeFirst();
        }
        for (Listener l : LISTENERS) {
            final Listener fl = l;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    fl.onLogLine(stamped);
                }
            });
        }
    }

    public static ArrayList<String> logSnapshot() {
        synchronized (LOG_LOCK) {
            return new ArrayList<>(LOG);
        }
    }

    public static void clearLog() {
        synchronized (LOG_LOCK) {
            LOG.clear();
        }
        notifyChanged();
    }

    // ------------------------------------------------------------------ stats

    public static void addUp(long n) {
        bytesUp.addAndGet(n);
    }

    public static void addDown(long n) {
        bytesDown.addAndGet(n);
    }

    public static void sessionOpened() {
        activeSessions.incrementAndGet();
        totalSessions.incrementAndGet();
    }

    public static void sessionClosed() {
        int v = activeSessions.decrementAndGet();
        if (v < 0) activeSessions.set(0);
    }

    private static void notifyChanged() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onEngineChanged();
            }
        });
    }
}
