package com.ari.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/**
 * Double Ratchet (Signal protocol, DH = P-256 ECDH instead of X25519 so it
 * runs on stock Android JCA). Root chain = HKDF, message keys = HMAC chain,
 * skipped-message-key store for out-of-order delivery, AES-256-GCM per msg.
 *
 * Session bootstrap = simplified X3DH (see ChatEngine.x3dhAlice/Bob):
 * SK = HKDF(DH(IKa, SPKb) || DH(EKa, IKb) || DH(EKa, SPKb)).
 */
public class Ratchet {
    private static final int MAX_SKIP = 200;

    byte[] rootKey;
    byte[] sendCK, recvCK;
    KeyPair dhs;          // my current ratchet keypair
    String dhr;           // peer ratchet public key (b64), null until known
    int Ns, Nr, PN;
    final Map<String, String> skipped = new HashMap<>(); // "pub:n" -> msgKey b64

    // ---- KDFs ------------------------------------------------------------
    private static byte[][] kdfRK(byte[] rk, byte[] dhOut) {
        byte[] out = Crypto.hkdf(rk, dhOut, Crypto.utf8("ARI-Ratchet-Root"), 64);
        byte[] a = new byte[32], b = new byte[32];
        System.arraycopy(out, 0, a, 0, 32);
        System.arraycopy(out, 32, b, 0, 32);
        return new byte[][]{a, b};
    }

    private static byte[][] kdfCK(byte[] ck) {
        byte[] mk = Crypto.hmac(ck, new byte[]{0x01});
        byte[] next = Crypto.hmac(ck, new byte[]{0x02});
        return new byte[][]{next, mk};
    }

    // ---- Init -------------------------------------------------------------
    public static Ratchet initAlice(byte[] sk, String bobPrekeyPubB64) {
        Ratchet r = new Ratchet();
        r.dhs = Crypto.genEC();
        r.dhr = bobPrekeyPubB64;
        byte[][] x = kdfRK(sk, Crypto.dh(r.dhs.getPrivate(), Crypto.pubFrom(bobPrekeyPubB64)));
        r.rootKey = x[0];
        r.sendCK = x[1];
        r.recvCK = null;
        return r;
    }

    public static Ratchet initBob(byte[] sk, KeyPair bobPrekeyPair) {
        Ratchet r = new Ratchet();
        r.dhs = bobPrekeyPair;
        r.dhr = null;
        r.rootKey = sk;
        return r;
    }

    // ---- Encrypt -----------------------------------------------------------
    public static class Encrypted {
        public JSONObject header;
        public String bodyB64;
    }

    public Encrypted encrypt(String plaintext) {
        byte[][] x = kdfCK(sendCK);
        sendCK = x[0];
        byte[] mk = x[1];
        JSONObject header = new JSONObject();
        try {
            header.put("dh", Crypto.pubB64(dhs.getPublic()));
            header.put("pn", PN);
            header.put("n", Ns);
        } catch (Exception e) { throw new RuntimeException(e); }
        Ns++;
        Encrypted e = new Encrypted();
        e.header = header;
        e.bodyB64 = Crypto.b64(Crypto.aesEncrypt(mk, Crypto.utf8(plaintext), Crypto.utf8(header.toString())));
        return e;
    }

    // ---- Decrypt -----------------------------------------------------------
    public String decrypt(JSONObject header, String bodyB64) throws Exception {
        String dhPub = header.getString("dh");
        int n = header.getInt("n");
        int pn = header.getInt("pn");

        String skipKey = dhPub + ":" + n;
        if (skipped.containsKey(skipKey)) {
            byte[] mk = Crypto.unb64(skipped.remove(skipKey));
            return Crypto.str(Crypto.aesDecrypt(mk, Crypto.unb64(bodyB64), Crypto.utf8(header.toString())));
        }
        if (!dhPub.equals(dhr)) {
            skipMessageKeys(pn);
            ratchetStep(dhPub);
        }
        skipMessageKeys(n);
        byte[][] x = kdfCK(recvCK);
        recvCK = x[0];
        byte[] mk = x[1];
        Nr++;
        return Crypto.str(Crypto.aesDecrypt(mk, Crypto.unb64(bodyB64), Crypto.utf8(header.toString())));
    }

    private void skipMessageKeys(int until) throws Exception {
        if (Nr + MAX_SKIP < until) throw new Exception("too many skipped messages");
        if (recvCK == null) return;
        while (Nr < until) {
            byte[][] x = kdfCK(recvCK);
            recvCK = x[0];
            skipped.put(dhr + ":" + Nr, Crypto.b64(x[1]));
            Nr++;
        }
    }

    private void ratchetStep(String newDhr) {
        PN = Ns;
        Ns = 0;
        Nr = 0;
        dhr = newDhr;
        byte[][] a = kdfRK(rootKey, Crypto.dh(dhs.getPrivate(), Crypto.pubFrom(dhr)));
        rootKey = a[0];
        recvCK = a[1];
        dhs = Crypto.genEC();
        byte[][] b = kdfRK(rootKey, Crypto.dh(dhs.getPrivate(), Crypto.pubFrom(dhr)));
        rootKey = b[0];
        sendCK = b[1];
    }

    // ---- (De)serialization to JSON (stored in local SQLite) ----------------
    public String serialize() {
        try {
            JSONObject o = new JSONObject();
            o.put("rk", Crypto.b64(rootKey));
            o.put("sck", sendCK == null ? JSONObject.NULL : Crypto.b64(sendCK));
            o.put("rck", recvCK == null ? JSONObject.NULL : Crypto.b64(recvCK));
            o.put("dhspub", Crypto.pubB64(dhs.getPublic()));
            o.put("dhspriv", Crypto.privB64(dhs.getPrivate()));
            o.put("dhr", dhr == null ? JSONObject.NULL : dhr);
            o.put("ns", Ns); o.put("nr", Nr); o.put("pn", PN);
            JSONObject sk = new JSONObject();
            for (Map.Entry<String, String> e : skipped.entrySet()) sk.put(e.getKey(), e.getValue());
            o.put("skipped", sk);
            return o.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static Ratchet deserialize(String s) {
        try {
            JSONObject o = new JSONObject(s);
            Ratchet r = new Ratchet();
            r.rootKey = Crypto.unb64(o.getString("rk"));
            r.sendCK = o.isNull("sck") ? null : Crypto.unb64(o.getString("sck"));
            r.recvCK = o.isNull("rck") ? null : Crypto.unb64(o.getString("rck"));
            r.dhs = Crypto.pairFrom(o.getString("dhspub"), o.getString("dhspriv"));
            r.dhr = o.isNull("dhr") ? null : o.getString("dhr");
            r.Ns = o.getInt("ns"); r.Nr = o.getInt("nr"); r.PN = o.getInt("pn");
            JSONObject sk = o.getJSONObject("skipped");
            JSONArray names = sk.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                r.skipped.put(k, sk.getString(k));
            }
            return r;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
