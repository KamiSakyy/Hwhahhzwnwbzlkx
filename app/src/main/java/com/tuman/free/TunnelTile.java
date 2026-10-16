package com.tuman.free;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.tuman.free.core.Engine;
import com.tuman.free.core.Prefs;
import com.tuman.free.vpn.TunnelVpnService;

/** Quick Settings tile: one tap to raise or drop the tunnel. */
public class TunnelTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (Engine.isActive()) {
            send(TunnelVpnService.ACTION_STOP);
        } else {
            Intent prepare = null;
            try {
                prepare = VpnService.prepare(this);
            } catch (Throwable ignored) {
            }
            if (prepare != null) {
                openApp();
                return;
            }
            if (!send(TunnelVpnService.ACTION_START)) openApp();
        }
        refresh();
    }

    private boolean send(String action) {
        try {
            Intent i = new Intent(this, TunnelVpnService.class);
            i.setAction(action);
            if (TunnelVpnService.ACTION_START.equals(action)) {
                i.putExtra(TunnelVpnService.EXTRA_STRATEGY, new Prefs(this).strategy());
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            return true;
        } catch (Throwable t) {
            Engine.log("! tile: " + t.getMessage());
            return false;
        }
    }

    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable t) {
            Engine.log("! tile: " + t.getMessage());
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = Engine.isRunning();
        try {
            tile.setLabel(getString(R.string.tile_label));
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setContentDescription(getString(on ? R.string.st_running : R.string.st_stopped));
            }
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }
}
