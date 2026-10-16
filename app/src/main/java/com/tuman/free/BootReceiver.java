package com.tuman.free;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.tuman.free.core.Engine;
import com.tuman.free.core.Prefs;
import com.tuman.free.vpn.TunnelVpnService;

/**
 * Restores the tunnel after a reboot / app update when the user asked for it.
 *
 * <p>Android 12+ may refuse a foreground-service start from a background
 * broadcast; that is caught and logged instead of crashing.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        final Context ctx = context.getApplicationContext();
        final Prefs prefs = new Prefs(ctx);
        if (!prefs.autostart()) return;

        try {
            Intent i = new Intent(ctx, TunnelVpnService.class);
            i.setAction(TunnelVpnService.ACTION_START);
            i.putExtra(TunnelVpnService.EXTRA_STRATEGY, prefs.strategy());
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            Engine.log("\u2192 \u0410\u0432\u0442\u043e\u0437\u0430\u043f\u0443\u0441\u043a \u043f\u043e\u0441\u043b\u0435 " + action);
        } catch (Throwable t) {
            Engine.log("! \u0410\u0432\u0442\u043e\u0437\u0430\u043f\u0443\u0441\u043a \u0437\u0430\u0431\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u0430\u043d \u0441\u0438\u0441\u0442\u0435\u043c\u043e\u0439: " + t.getMessage());
        }
    }
}
