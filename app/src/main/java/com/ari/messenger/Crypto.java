package com.ari.messenger;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Low-level crypto: P-256 ECDH (X3DH/Double-Ratchet DH), HKDF, AES-256-GCM. */
public final class Crypto {
    private Crypto() {}

    public static String b64(byte[] d) { return Base64.encodeToString(d, Base64.NO_WRAP); }
    public static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    // ---- EC P-256 keys -------------------------------------------------
    public static KeyPair genEC() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            return g.generateKeyPair();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String pubB64(PublicKey k) { return b64(k.getEncoded()); }          // X.509
    public static String privB64(PrivateKey k) { return b64(k.getEncoded()); }        // PKCS#8

    public static PublicKey pubFrom(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(b64)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static PrivateKey privFrom(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(unb64(b64)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static KeyPair pairFrom(String pub, String priv) { return new KeyPair(pubFrom(pub), privFrom(priv)); }

    public static byte[] dh(PrivateKey priv, PublicKey pub) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            byte[] s = ka.generateSecret();
            // hash the raw secret to a fixed 32 bytes (ECDH secret length varies)
            return MessageDigest.getInstance("SHA-256").digest(s);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- HKDF / HMAC ----------------------------------------------------
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) {
        if (salt == null) salt = new byte[32];
        byte[] prk = hmac(salt, ikm);
        byte[] okm = new byte[0];
        byte[] t = new byte[0];
        int done = 0;
        byte counter = 1;
        try {
            while (done < len) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                if (info != null) mac.update(info);
                mac.update(counter++);
                t = mac.doFinal();
                int take = Math.min(t.length, len - done);
                byte[] grown = new byte[okm.length + take];
                System.arraycopy(okm, 0, grown, 0, okm.length);
                System.arraycopy(t, 0, grown, okm.length, take);
                okm = grown;
                done += take;
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return okm;
    }

    // ---- AES-256-GCM ----------------------------------------------------
    public static byte[] aesEncrypt(byte[] key32, byte[] plain, byte[] aad) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] aesDecrypt(byte[] key32, byte[] ivAndCt, byte[] aad) {
        try {
            byte[] iv = new byte[12];
            System.arraycopy(ivAndCt, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ivAndCt, 12, ivAndCt.length - 12);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static String str(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
