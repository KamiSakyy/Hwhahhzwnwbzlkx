package com.ari.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.KeyPair;

/** App prefs: server address, auth token, my identity/prekey pairs (private!). */
public class Store {
    private final SharedPreferences p;

    public Store(Context c) { p = c.getSharedPreferences("ari", Context.MODE_PRIVATE); }

    public String server() { return p.getString("server", ""); }
    public void setServer(String s) { p.edit().putString("server", s).apply(); }

    public String token() { return p.getString("token", ""); }
    public int myId() { return p.getInt("my_id", 0); }
    public String myName() { return p.getString("my_name", ""); }
    public String myEmail() { return p.getString("my_email", ""); }

    public void setAuth(String token, int id, String name, String email) {
        p.edit().putString("token", token).putInt("my_id", id)
                .putString("my_name", name).putString("my_email", email).apply();
    }

    public void logout() {
        p.edit().remove("token").remove("my_id").remove("my_name").remove("my_email").apply();
    }

    public long lastSync() { return p.getLong("last_sync", 0); }
    public void setLastSync(long v) { p.edit().putLong("last_sync", v).apply(); }

    public String pendingPrelude(int peerId) { return p.getString("prelude_" + peerId, null); }
    public void setPendingPrelude(int peerId, String v) {
        if (v == null) p.edit().remove("prelude_" + peerId).apply();
        else p.edit().putString("prelude_" + peerId, v).apply();
    }

    /** Generate (once) my identity + signed-prekey EC pairs; returns public bundle. */
    public synchronized String[] ensureKeys() {
        if (!p.contains("id_pub")) {
            KeyPair id = Crypto.genEC();
            KeyPair pre = Crypto.genEC();
            p.edit()
                    .putString("id_pub", Crypto.pubB64(id.getPublic()))
                    .putString("id_priv", Crypto.privB64(id.getPrivate()))
                    .putString("pre_pub", Crypto.pubB64(pre.getPublic()))
                    .putString("pre_priv", Crypto.privB64(pre.getPrivate()))
                    .apply();
        }
        return new String[]{p.getString("id_pub", ""), p.getString("pre_pub", "")};
    }

    public KeyPair identityPair() { return Crypto.pairFrom(p.getString("id_pub", ""), p.getString("id_priv", "")); }
    public KeyPair prekeyPair() { return Crypto.pairFrom(p.getString("pre_pub", ""), p.getString("pre_priv", "")); }
}
