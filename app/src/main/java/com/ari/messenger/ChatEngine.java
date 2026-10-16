package com.ari.messenger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * E2EE engine: simplified X3DH session bootstrap + Double Ratchet transport,
 * message/media payload handling and server sync. Server sees only ciphertext.
 */
public class ChatEngine {
    public interface Done { void onOk(); void onErr(String e); }

    private final Context ctx;
    private final Store store;
    private final Db db;
    private final Api api;

    public ChatEngine(Context c) {
        ctx = c.getApplicationContext();
        store = new Store(ctx);
        db = Db.get(ctx);
        api = new Api(ctx);
    }

    // ---- X3DH --------------------------------------------------------------
    private static byte[] x3dh(byte[] dh1, byte[] dh2, byte[] dh3) {
        byte[] ikm = new byte[dh1.length + dh2.length + dh3.length];
        System.arraycopy(dh1, 0, ikm, 0, dh1.length);
        System.arraycopy(dh2, 0, ikm, dh1.length, dh2.length);
        System.arraycopy(dh3, 0, ikm, dh1.length + dh2.length, dh3.length);
        return Crypto.hkdf(new byte[32], ikm, Crypto.utf8("ARI-X3DH"), 32);
    }

    /** Encrypt a payload for a peer; creates the session (X3DH) if needed. */
    public void encryptFor(int peerId, String peerName, String payload, Api.Cb<JSONObject> cb) {
        String saved = db.loadSession(peerId);
        if (saved != null) {
            try {
                Ratchet r = Ratchet.deserialize(saved);
                Ratchet.Encrypted e = r.encrypt(payload);
                db.saveSession(peerId, r.serialize());
                JSONObject out = new JSONObject();
                out.put("ciphertext", e.bodyB64);
                out.put("header", e.header.toString());
                String prelude = store.pendingPrelude(peerId);
                if (prelude != null) out.put("prelude", prelude);
                cb.onOk(out);
                return;
            } catch (Exception ex) {
                cb.onErr("session error: " + ex.getMessage());
                return;
            }
        }
        // No session: fetch peer key bundle, run X3DH as Alice
        api.getJson("/users/" + peerName + "/bundle", new Api.Cb<JSONObject>() {
            @Override public void onOk(JSONObject bundle) {
                try {
                    String bobIdentity = bundle.getString("identity_key");
                    String bobPrekey = bundle.getString("signed_prekey");
                    java.security.KeyPair eph = Crypto.genEC();
                    byte[] dh1 = Crypto.dh(store.identityPair().getPrivate(), Crypto.pubFrom(bobPrekey));
                    byte[] dh2 = Crypto.dh(eph.getPrivate(), Crypto.pubFrom(bobIdentity));
                    byte[] dh3 = Crypto.dh(eph.getPrivate(), Crypto.pubFrom(bobPrekey));
                    byte[] sk = x3dh(dh1, dh2, dh3);
                    Ratchet r = Ratchet.initAlice(sk, bobPrekey);
                    JSONObject prelude = new JSONObject();
                    prelude.put("ika", store.identityPair().getPublic() != null
                            ? Crypto.pubB64(store.identityPair().getPublic()) : "");
                    prelude.put("eka", Crypto.pubB64(eph.getPublic()));
                    store.setPendingPrelude(peerId, prelude.toString());
                    Ratchet.Encrypted e = r.encrypt(payload);
                    db.saveSession(peerId, r.serialize());
                    JSONObject out = new JSONObject();
                    out.put("ciphertext", e.bodyB64);
                    out.put("header", e.header.toString());
                    out.put("prelude", prelude.toString());
                    cb.onOk(out);
                } catch (Exception ex) {
                    cb.onErr("X3DH failed: " + ex.getMessage());
                }
            }
            @Override public void onErr(String e) { cb.onErr(e); }
        });
    }

    /** Decrypt an incoming message; builds the Bob session from prelude if needed. */
    public String decryptFrom(int senderId, String headerStr, String bodyB64, String preludeStr) throws Exception {
        String saved = db.loadSession(senderId);
        if (saved == null && preludeStr != null && !preludeStr.equals("null")) {
            buildBobSession(senderId, preludeStr);
            saved = db.loadSession(senderId);
        }
        if (saved == null) throw new Exception("no session");
        Ratchet r = Ratchet.deserialize(saved);
        try {
            String plain = r.decrypt(new JSONObject(headerStr), bodyB64);
            db.saveSession(senderId, r.serialize());
            return plain;
        } catch (Exception e) {
            // peer reinstalled / reset keys -> rebuild from prelude and retry once
            if (preludeStr != null && !preludeStr.equals("null")) {
                buildBobSession(senderId, preludeStr);
                Ratchet r2 = Ratchet.deserialize(db.loadSession(senderId));
                String plain = r2.decrypt(new JSONObject(headerStr), bodyB64);
                db.saveSession(senderId, r2.serialize());
                return plain;
            }
            throw e;
        }
    }

    private void buildBobSession(int senderId, String preludeStr) throws Exception {
        JSONObject p = new JSONObject(preludeStr);
        String ika = p.getString("ika"), eka = p.getString("eka");
        byte[] dh1 = Crypto.dh(store.prekeyPair().getPrivate(), Crypto.pubFrom(ika));
        byte[] dh2 = Crypto.dh(store.identityPair().getPrivate(), Crypto.pubFrom(eka));
        byte[] dh3 = Crypto.dh(store.prekeyPair().getPrivate(), Crypto.pubFrom(eka));
        Ratchet r = Ratchet.initBob(x3dh(dh1, dh2, dh3), store.prekeyPair());
        db.saveSession(senderId, r.serialize());
    }

    // ---- Sending -------------------------------------------------------------
    /** Send an already-built payload JSON ({kind:...}). Stores locally on success. */
    public void sendPayload(int peerId, String peerName, JSONObject payload, Done done) {
        encryptFor(peerId, peerName, payload.toString(), new Api.Cb<JSONObject>() {
            @Override public void onOk(JSONObject enc) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("to_id", peerId);
                    body.put("ciphertext", enc.getString("ciphertext"));
                    body.put("header", enc.getString("header"));
                    if (enc.has("prelude")) body.put("prelude", enc.getString("prelude"));
                    api.postJson("/messages", body, new Api.Cb<JSONObject>() {
                        @Override public void onOk(JSONObject resp) {
                            store.setPendingPrelude(peerId, null);
                            long sid = resp.optLong("id", 0);
                            String kind = payload.optString("kind", "text");
                            db.addMsg(sid, peerId, peerName, true, kind,
                                    "text".equals(kind) ? payload.optString("text", "") : payload.toString(),
                                    null, System.currentTimeMillis() / 1000);
                            if (sid > store.lastSync()) store.setLastSync(sid);
                            done.onOk();
                        }
                        @Override public void onErr(String e) { done.onErr(e); }
                    });
                } catch (Exception e) { done.onErr(e.getMessage()); }
            }
            @Override public void onErr(String e) { done.onErr(e); }
        });
    }

    public static String displayText(JSONObject payload) {
        String kind = payload.optString("kind", "text");
        switch (kind) {
            case "text": return payload.optString("text", "");
            case "image": return "📷 Фото";
            case "video": return "🎬 Видео";
            case "circle": return "⭕ Кружок";
            case "voice": return "🎤 Голосовое сообщение";
            default: return "📎 Вложение";
        }
    }

    // ---- Sync ------------------------------------------------------------------
    public interface SyncListener { void onNewMessages(); }

    public void sync(SyncListener listener) {
        api.getArray("/messages/sync?since=" + store.lastSync(), new Api.Cb<JSONArray>() {
            @Override public void onOk(JSONArray arr) {
                boolean changed = false;
                long maxId = store.lastSync();
                int myId = store.myId();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    long sid = m.optLong("id");
                    if (sid > maxId) maxId = sid;
                    if (db.hasServerId(sid)) continue;
                    int sender = m.optInt("sender_id");
                    int recipient = m.optInt("recipient_id");
                    boolean mine = sender == myId;
                    int peerId = mine ? recipient : sender;
                    String peerName = mine ? m.optString("recipient_name") : m.optString("sender_name");
                    db.savePeer(peerId, peerName);
                    if (mine) continue; // my own messages are stored at send time
                    try {
                        String prelude = m.isNull("prelude") ? null : m.optString("prelude");
                        String plain = decryptFrom(sender, m.optString("header"),
                                m.optString("ciphertext"), prelude);
                        JSONObject payload = new JSONObject(plain);
                        String kind = payload.optString("kind", "text");
                        db.addMsg(sid, peerId, peerName, false, kind,
                                "text".equals(kind) ? payload.optString("text", "") : plain,
                                null, m.optLong("created_at"));
                    } catch (Exception e) {
                        db.addMsg(sid, peerId, peerName, false, "locked",
                                "🔒 Не удалось расшифровать", null, m.optLong("created_at"));
                    }
                    changed = true;
                }
                store.setLastSync(maxId);
                if (changed && listener != null) listener.onNewMessages();
            }
            @Override public void onErr(String e) { /* offline: keep polling later */ }
        });
    }

    // ---- Media (E2EE: random AES key inside the encrypted payload) --------------
    public void sendMedia(int peerId, String peerName, String kind, File file, String ext, Done done) {
        try {
            byte[] plain = readFile(file);
            byte[] key = Crypto.randomBytes(32);
            byte[] encBytes = Crypto.aesEncrypt(key, plain, null);
            api.postRaw("/media", encBytes, new Api.Cb<JSONObject>() {
                @Override public void onOk(JSONObject up) {
                    int mediaId = up.optInt("id");
                    JSONObject share = new JSONObject();
                    try { share.put("user_id", peerId); } catch (Exception ignored) {}
                    api.postJson("/media/" + mediaId + "/share", share, new Api.Cb<JSONObject>() {
                        @Override public void onOk(JSONObject ok) {
                            try {
                                JSONObject payload = new JSONObject();
                                payload.put("kind", kind);
                                payload.put("media_id", mediaId);
                                payload.put("key", Crypto.b64(key));
                                payload.put("ext", ext);
                                sendPayload(peerId, peerName, payload, done);
                            } catch (Exception e) { done.onErr(e.getMessage()); }
                        }
                        @Override public void onErr(String e) { done.onErr(e); }
                    });
                }
                @Override public void onErr(String e) { done.onErr(e); }
            });
        } catch (Exception e) { done.onErr(e.getMessage()); }
    }

    /** Download + decrypt a media payload into a local cache file. */
    public void fetchMedia(JSONObject payload, Api.Cb<File> cb) {
        int mediaId = payload.optInt("media_id");
        String ext = payload.optString("ext", "bin");
        File out = new File(ctx.getCacheDir(), "media_" + mediaId + "." + ext);
        if (out.exists() && out.length() > 0) { cb.onOk(out); return; }
        api.getRaw("/media/" + mediaId, new Api.Cb<byte[]>() {
            @Override public void onOk(byte[] encBytes) {
                try {
                    byte[] plain = Crypto.aesDecrypt(Crypto.unb64(payload.optString("key")), encBytes, null);
                    try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(plain); }
                    cb.onOk(out);
                } catch (Exception e) { cb.onErr(e.getMessage()); }
            }
            @Override public void onErr(String e) { cb.onErr(e); }
        });
    }

    private static byte[] readFile(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while ((n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        return data;
    }
}
