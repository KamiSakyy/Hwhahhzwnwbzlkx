package com.tuman.free.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Thin wrapper around SharedPreferences. No DataStore dependency needed. */
public final class Prefs {

    public static final String MODE_ALL = "all";
    public static final String MODE_INCLUDE = "include";
    public static final String MODE_EXCLUDE = "exclude";

    private static final String NAME = "tuman";
    private static final String DEF_DNS = "1.1.1.1,8.8.8.8";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- strategy

    public String strategy() {
        return sp.getString("strategy", Strategies.DEFAULT_ID);
    }

    public void strategy(String id) {
        sp.edit().putString("strategy", id == null ? Strategies.DEFAULT_ID : id).apply();
    }

    public String customArgs() {
        return sp.getString("custom_args", "");
    }

    public void customArgs(String v) {
        sp.edit().putString("custom_args", v == null ? "" : v).apply();
    }

    // ------------------------------------------------------------- behaviour

    /** Drop every UDP datagram that is not DNS. Kills QUIC/HTTP3 so apps fall back to TCP. */
    public boolean blockUdp() {
        return sp.getBoolean("block_udp", true);
    }

    public void blockUdp(boolean v) {
        sp.edit().putBoolean("block_udp", v).apply();
    }

    /** Route IPv6 into the tunnel and drop it, so nothing leaks past tpws. */
    public boolean blockIpv6() {
        return sp.getBoolean("block_ipv6", true);
    }

    public void blockIpv6(boolean v) {
        sp.edit().putBoolean("block_ipv6", v).apply();
    }

    public boolean watchdog() {
        return sp.getBoolean("watchdog", true);
    }

    public void watchdog(boolean v) {
        sp.edit().putBoolean("watchdog", v).apply();
    }

    public boolean verboseLog() {
        return sp.getBoolean("verbose_log", false);
    }

    public void verboseLog(boolean v) {
        sp.edit().putBoolean("verbose_log", v).apply();
    }

    public boolean autostart() {
        return sp.getBoolean("autostart", false);
    }

    public void autostart(boolean v) {
        sp.edit().putBoolean("autostart", v).apply();
    }

    // ------------------------------------------------------------------- dns

    public String dnsRaw() {
        return sp.getString("dns", DEF_DNS);
    }

    public void dnsRaw(String v) {
        sp.edit().putString("dns", v == null || v.trim().length() == 0 ? DEF_DNS : v.trim()).apply();
    }

    public List<String> dnsServers() {
        List<String> out = new ArrayList<>();
        for (String s : dnsRaw().split(",")) {
            String t = s.trim();
            if (t.length() == 0) continue;
            if (!out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) out.add("1.1.1.1");
        if (out.size() > 4) out = out.subList(0, 4);
        return out;
    }

    // --------------------------------------------------------- split tunnel

    public String tunnelMode() {
        return sp.getString("tunnel_mode", MODE_ALL);
    }

    public void tunnelMode(String v) {
        sp.edit().putString("tunnel_mode", v == null ? MODE_ALL : v).apply();
    }

    public Set<String> tunnelApps() {
        Set<String> s = sp.getStringSet("tunnel_apps", Collections.<String>emptySet());
        return new HashSet<>(s);
    }

    public void tunnelApps(Set<String> apps) {
        sp.edit().putStringSet("tunnel_apps", new HashSet<>(apps)).apply();
    }

    // ------------------------------------------------------------------ misc

    public int lastTab() {
        return sp.getInt("last_tab", 0);
    }

    public void lastTab(int v) {
        sp.edit().putInt("last_tab", v).apply();
    }
}
