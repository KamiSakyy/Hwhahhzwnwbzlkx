package com.ari.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local storage: decrypted messages + Double Ratchet session states. */
public class Db extends SQLiteOpenHelper {
    private static Db inst;

    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private Db(Context c) { super(c, "ari_local.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "server_id INTEGER UNIQUE, peer_id INTEGER, peer_name TEXT, mine INTEGER," +
                "kind TEXT, text TEXT, media_path TEXT, ts INTEGER)");
        d.execSQL("CREATE TABLE sessions(peer_id INTEGER PRIMARY KEY, state TEXT)");
        d.execSQL("CREATE TABLE peers(id INTEGER PRIMARY KEY, username TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int o, int n) {}

    // ---- peers ----------------------------------------------------------
    public synchronized void savePeer(int id, String username) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("username", username);
        getWritableDatabase().insertWithOnConflict("peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String peerName(int id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT username FROM peers WHERE id=?", new String[]{"" + id});
        try { return c.moveToFirst() ? c.getString(0) : ("user" + id); } finally { c.close(); }
    }

    // ---- ratchet sessions -------------------------------------------------
    public synchronized void saveSession(int peerId, String state) {
        ContentValues v = new ContentValues();
        v.put("peer_id", peerId); v.put("state", state);
        getWritableDatabase().insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String loadSession(int peerId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT state FROM sessions WHERE peer_id=?", new String[]{"" + peerId});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    public synchronized void dropSession(int peerId) {
        getWritableDatabase().delete("sessions", "peer_id=?", new String[]{"" + peerId});
    }

    // ---- messages -----------------------------------------------------------
    public static class Msg {
        public long id; public long serverId; public int peerId; public String peerName;
        public boolean mine; public String kind; public String text; public String mediaPath; public long ts;
    }

    public synchronized boolean hasServerId(long serverId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM msgs WHERE server_id=?", new String[]{"" + serverId});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    public synchronized long addMsg(long serverId, int peerId, String peerName, boolean mine,
                                    String kind, String text, String mediaPath, long ts) {
        ContentValues v = new ContentValues();
        v.put("server_id", serverId); v.put("peer_id", peerId); v.put("peer_name", peerName);
        v.put("mine", mine ? 1 : 0); v.put("kind", kind); v.put("text", text);
        v.put("media_path", mediaPath); v.put("ts", ts);
        return getWritableDatabase().insertWithOnConflict("msgs", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized List<Msg> messagesWith(int peerId) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM msgs WHERE peer_id=? ORDER BY COALESCE(server_id,id) ASC", new String[]{"" + peerId});
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally { c.close(); }
        return out;
    }

    public static class ChatRow {
        public int peerId; public String peerName; public String lastText; public String lastKind; public long ts; public int unreadHint;
    }

    public synchronized List<ChatRow> chatList() {
        List<ChatRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT peer_id, peer_name, text, kind, MAX(ts) AS mts FROM msgs GROUP BY peer_id ORDER BY mts DESC", null);
        try {
            while (c.moveToNext()) {
                ChatRow r = new ChatRow();
                r.peerId = c.getInt(0); r.peerName = c.getString(1);
                r.lastText = c.getString(2); r.lastKind = c.getString(3); r.ts = c.getLong(4);
                out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    public synchronized long maxServerId() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(server_id),0) FROM msgs", null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    public synchronized void wipeAll() {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("msgs", null, null);
        d.delete("sessions", null, null);
        d.delete("peers", null, null);
    }

    private Msg read(Cursor c) {
        Msg m = new Msg();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.serverId = c.getLong(c.getColumnIndexOrThrow("server_id"));
        m.peerId = c.getInt(c.getColumnIndexOrThrow("peer_id"));
        m.peerName = c.getString(c.getColumnIndexOrThrow("peer_name"));
        m.mine = c.getInt(c.getColumnIndexOrThrow("mine")) == 1;
        m.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        m.text = c.getString(c.getColumnIndexOrThrow("text"));
        m.mediaPath = c.getString(c.getColumnIndexOrThrow("media_path"));
        m.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        return m;
    }
}
