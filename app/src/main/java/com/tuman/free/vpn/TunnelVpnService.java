package com.tuman.free.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.tuman.free.MainActivity;
import com.tuman.free.R;
import com.tuman.free.core.Engine;
import com.tuman.free.core.Prefs;
import com.tuman.free.core.Strategies;
import com.tuman.free.core.Strategy;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;

/**
 * Foreground VPN service that owns the tunnel.
 *
 * <p>Pipeline: {@code apps -> TUN -> }{@link TunBridge}{@code -> SOCKS5 -> tpws -> internet}.
 * No root, no iptables, no NFQUEUE - tpws tampers with the stream as a normal
 * userspace proxy, which is why this works on a stock phone in any app.
 */
public final class TunnelVpnService extends VpnService implements NetGuard {

    public static final String ACTION_START = "com.tuman.free.action.START";
    public static final String ACTION_STOP = "com.tuman.free.action.STOP";
    public static final String EXTRA_STRATEGY = "com.tuman.free.extra.STRATEGY";

    private static final String CHANNEL_ID = "tuman_tunnel";
    private static final int NOTIF_ID = 0x5141;
    private static final int ACCENT = 0xFF2DE2E6;

    /** Interface address of the tunnel. Chosen to be far away from common LAN ranges. */
    private static final String TUN_ADDR = "10.124.0.1";
    private static final int TUN_PREFIX = 24;
    private static final String TUN_ADDR6 = "fd00::1";
    private static final int TUN_PREFIX6 = 128;
    private static final int MTU = 1500;

    private ParcelFileDescriptor pfd;
    private TunBridge bridge;
    private TpwsRunner tpws;
    private Thread worker;
    private Thread watchdog;
    private volatile boolean live;
    /** Set by every stop path so a start that is already in flight cannot resurrect the tunnel. */
    private volatile boolean stopRequested;
    private volatile int boundPort = -1;

    // ------------------------------------------------------------------- service
    // NOTE: onBind() is deliberately not overridden - VpnService provides the
    // binder the system needs to attach the tunnel.

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            Engine.setStopping();
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        goForeground(getString(R.string.notif_starting));

        String requested = intent != null ? intent.getStringExtra(EXTRA_STRATEGY) : null;
        if (requested == null) requested = new Prefs(this).strategy();
        final String strategyId = requested;

        Thread previous = worker;
        if (previous != null && previous != Thread.currentThread()) {
            previous.interrupt();
            try {
                previous.join(3000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                startTunnel(strategyId);
            }
        }, "tunnel-start");
        t.setDaemon(true);
        worker = t;
        t.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        shutdown();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        Engine.log("! VPN \u043e\u0442\u043e\u0437\u0432\u0430\u043d \u0441\u0438\u0441\u0442\u0435\u043c\u043e\u0439");
        stopRequested = true;
        shutdown();
        stopSelf();
        super.onRevoke();
    }

    /** Swiping the app away from recents must not kill the tunnel. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!live) stopSelf();
    }

    // ---------------------------------------------------------------- NetGuard

    @Override
    public boolean protectSocket(Socket socket) {
        try {
            return protect(socket);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean protectDatagram(DatagramSocket socket) {
        try {
            return protect(socket);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------- tunnel

    private void startTunnel(String strategyId) {
        try {
            stopRequested = false;
            shutdownInternal();

            Prefs prefs = new Prefs(this);
            Strategy strategy = Strategies.resolve(this, strategyId);
            Engine.beginStart(strategy.name);
            Engine.log("\u2192 \u0417\u0430\u043f\u0443\u0441\u043a: " + strategy.name);
            Engine.log("  \u0430\u0440\u0433\u0443\u043c\u0435\u043d\u0442\u044b: " + strategy.argsString());

            // 1. tpws first: if the daemon refuses to start there is no point in
            //    stealing the VPN slot from whatever the user had before.
            TpwsRunner runner = new TpwsRunner();
            int port = -1;
            for (int candidate : TpwsRunner.PORT_CANDIDATES) {
                if (runner.start(this, this, candidate, strategy.args, prefs.verboseLog())) {
                    port = candidate;
                    break;
                }
            }
            if (port < 0) {
                Engine.fail("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c tpws");
                stopSelf();
                return;
            }
            boundPort = port;
            tpws = runner;
            Engine.log("\u2713 tpws \u0441\u043b\u0443\u0448\u0430\u0435\u0442 127.0.0.1:" + port);

            // 2. The VPN interface itself.
            ParcelFileDescriptor fd = establishVpn(prefs);
            if (fd == null) {
                Engine.fail("VPN-\u0438\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441 \u043d\u0435 \u0441\u043e\u0437\u0434\u0430\u043d");
                runner.stop();
                tpws = null;
                stopSelf();
                return;
            }
            pfd = fd;

            // A stop may have arrived while we were bringing the tunnel up. Honour it
            // instead of publishing a RUNNING state that no longer matches reality.
            if (stopRequested) {
                Engine.log("! \u0417\u0430\u043f\u0443\u0441\u043a \u043e\u0442\u043c\u0435\u043d\u0451\u043d: \u043f\u043e\u0441\u0442\u0443\u043f\u0438\u043b\u0430 \u043a\u043e\u043c\u0430\u043d\u0434\u0430 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0438");
                shutdownInternal();
                stopSelf();
                return;
            }

            // 3. Bridge between the interface and the SOCKS endpoint.
            TunBridge b = new TunBridge(fd, port, this, prefs.blockUdp());
            b.start();
            bridge = b;

            live = true;
            Engine.setRunning(strategy.name, port);
            goForeground(getString(R.string.notif_running, strategy.name));
            Engine.log("\u2713 \u0422\u0443\u043d\u043d\u0435\u043b\u044c \u043f\u043e\u0434\u043d\u044f\u0442");

            // 4. Keep tpws alive.
            Thread w = new Thread(new Runnable() {
                @Override
                public void run() {
                    watchdogLoop();
                }
            }, "tpws-watchdog");
            w.setDaemon(true);
            watchdog = w;
            w.start();
        } catch (Throwable t) {
            Engine.fail("\u041e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u043f\u0443\u0441\u043a\u0430: " + t);
            shutdown();
            stopSelf();
        }
    }

    private void watchdogLoop() {
        int restarts = 0;
        while (live) {
            TpwsRunner r = tpws;
            if (r == null) break;
            int code = r.awaitExit();
            if (!live) break;

            Engine.log("! tpws \u0437\u0430\u0432\u0435\u0440\u0448\u0438\u043b\u0441\u044f (\u043a\u043e\u0434 " + code + ")");
            Prefs prefs = new Prefs(this);
            if (!prefs.watchdog()) {
                Engine.fail("tpws \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d, watchdog \u0432\u044b\u043a\u043b\u044e\u0447\u0435\u043d");
                break;
            }
            if (restarts >= 10) {
                Engine.fail("\u041f\u0440\u0435\u0432\u044b\u0448\u0435\u043d \u043b\u0438\u043c\u0438\u0442 \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u043a\u043e\u0432 tpws");
                break;
            }
            restarts++;
            Engine.restarts.set(restarts);
            Engine.log("\u21ba \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u043a #" + restarts + " \u0447\u0435\u0440\u0435\u0437 1 \u0441");

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                break;
            }
            if (!live) break;

            TunBridge b = bridge;
            if (b != null) b.resetSessions();

            Strategy strategy = Strategies.resolve(this, new Prefs(this).strategy());
            boolean ok = false;
            for (int candidate : TpwsRunner.PORT_CANDIDATES) {
                if (r.start(this, this, candidate, strategy.args, prefs.verboseLog())) {
                    boundPort = candidate;
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                Engine.fail("tpws \u043d\u0435 \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u0442\u0438\u043b\u0441\u044f");
                break;
            }
            Engine.log("\u2713 tpws \u0441\u043d\u043e\u0432\u0430 \u0432 \u0440\u0430\u0431\u043e\u0442\u0435");
        }
    }

    private ParcelFileDescriptor establishVpn(Prefs prefs) {
        ParcelFileDescriptor fd = tryEstablish(prefs, true, true);
        if (fd == null) {
            Engine.log("! \u0441\u0431\u043e\u0440\u043a\u0430 VPN \u0441 IPv6 \u043d\u0435 \u043f\u0440\u043e\u0448\u043b\u0430, \u043f\u043e\u0432\u0442\u043e\u0440 \u0431\u0435\u0437 \u043d\u0435\u0433\u043e");
            fd = tryEstablish(prefs, false, true);
        }
        if (fd == null) {
            Engine.log("! \u043f\u043e\u0432\u0442\u043e\u0440 \u0441 \u043c\u0438\u043d\u0438\u043c\u0430\u043b\u044c\u043d\u043e\u0439 \u043a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0435\u0439");
            fd = tryEstablish(prefs, false, false);
        }
        return fd;
    }

    private ParcelFileDescriptor tryEstablish(Prefs prefs, boolean withIpv6, boolean withSplit) {
        try {
            Builder b = new Builder();
            b.setSession(getString(R.string.app_name));
            b.addAddress(TUN_ADDR, TUN_PREFIX);
            b.addRoute("0.0.0.0", 0);
            b.setMtu(MTU);
            b.setBlocking(true);

            List<String> dns = prefs.dnsServers();
            int added = 0;
            for (String s : dns) {
                try {
                    b.addDnsServer(s);
                    added++;
                } catch (Throwable t) {
                    Engine.log("! DNS " + s + " \u043e\u0442\u043a\u043b\u043e\u043d\u0451\u043d");
                }
            }
            if (added == 0) b.addDnsServer("1.1.1.1");

            if (withIpv6 && prefs.blockIpv6()) {
                // Pull IPv6 into the tunnel so nothing can leak around tpws,
                // then drop it: apps fall back to IPv4, which we do fix.
                try {
                    b.addAddress(TUN_ADDR6, TUN_PREFIX6);
                    b.addRoute("::", 0);
                } catch (Throwable t) {
                    Engine.log("! IPv6-\u043c\u0430\u0440\u0448\u0440\u0443\u0442 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d: " + t.getMessage());
                }
            }

            if (withSplit) applySplitTunnel(b, prefs);

            return b.establish();
        } catch (Throwable t) {
            Engine.log("! establish: " + t);
            return null;
        }
    }

    private void applySplitTunnel(Builder b, Prefs prefs) {
        String mode = prefs.tunnelMode();
        Set<String> apps = prefs.tunnelApps();
        String self = getPackageName();
        try {
            if (Prefs.MODE_INCLUDE.equals(mode) && !apps.isEmpty()) {
                // Allow-list: our own package is absent from it, so our traffic
                // (and tpws traffic, same UID) stays outside the tunnel.
                for (String pkg : apps) {
                    if (pkg == null || pkg.equals(self)) continue;
                    try {
                        b.addAllowedApplication(pkg);
                    } catch (Throwable t) {
                        Engine.log("! app " + pkg + ": " + t.getMessage());
                    }
                }
                return;
            }
            b.addDisallowedApplication(self);
            if (Prefs.MODE_EXCLUDE.equals(mode)) {
                for (String pkg : apps) {
                    if (pkg == null || pkg.equals(self)) continue;
                    try {
                        b.addDisallowedApplication(pkg);
                    } catch (Throwable t) {
                        Engine.log("! app " + pkg + ": " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Engine.log("! split tunnel: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ teardown

    private void shutdown() {
        Engine.setStopping();
        shutdownInternal();
        // Drop the ongoing notification ourselves: relying on the system to do it
        // when the service dies leaves a ghost entry on some OEM builds.
        // The boolean overload is deprecated since API 33 but it is the only one
        // that exists across our whole supported range (24..35).
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Engine.setStopped();
    }

    private void shutdownInternal() {
        live = false;

        Thread w = watchdog;
        watchdog = null;
        if (w != null) w.interrupt();

        TunBridge b = bridge;
        bridge = null;
        if (b != null) {
            try {
                b.stop();
            } catch (Throwable ignored) {
            }
        }

        TpwsRunner r = tpws;
        tpws = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
            }
        }

        ParcelFileDescriptor fd = pfd;
        pfd = null;
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException ignored) {
            } catch (Throwable ignored) {
            }
        }
        boundPort = -1;
    }

    // -------------------------------------------------------------- notification

    private void goForeground(String text) {
        createChannel();
        Notification n = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TunnelVpnService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= 21) b.setColor(ACCENT);

        // The int/CharSequence/PendingIntent overload is deprecated but is the only
        // one that exists on every API level we support, so it stays.
        b.addAction(R.drawable.ic_stop, getString(R.string.notif_stop), stop);

        return b.build();
    }
}
