package com.tsuyu.messenger.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tsuyu.messenger.net.TsuyuServerClient;

/**
 * Presence via our own FastAPI server heartbeat (every 25 s while running).
 * The server marks the user offline automatically when heartbeats stop.
 * внешнего облака presence has been removed completely.
 */
public final class PresenceManager {
    private static final long HEARTBEAT_MS = 25_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String uid;
    private boolean beating;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!beating) return;
            if (uid != null && !isGhostMode()) TsuyuServerClient.presenceHeartbeat();
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    public PresenceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean isGhostMode() {
        if (uid == null) return false;
        com.tsuyu.messenger.util.Settings.ensure(context);
        return com.tsuyu.messenger.util.Settings.getBoolean("ghost_" + uid, false);
    }

    public synchronized void setGhostMode(String accountUid, boolean enabled) {
        uid = accountUid;
        com.tsuyu.messenger.util.Settings.ensure(context);
        com.tsuyu.messenger.util.Settings.putBoolean("ghost_" + accountUid, enabled);
        if (enabled) {
            stopBeating();
            // Server-side: simply stop heartbeating; peer sees us offline after TTL.
        } else {
            start(accountUid);
        }
    }

    public synchronized void start(String accountUid) {
        uid = accountUid;
        if (isGhostMode()) return;
        if (beating) return;
        beating = true;
        TsuyuServerClient.presenceHeartbeat();
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    public synchronized void signOut() {
        stopBeating();
        uid = null;
    }

    private void stopBeating() {
        beating = false;
        handler.removeCallbacks(heartbeat);
    }
}
