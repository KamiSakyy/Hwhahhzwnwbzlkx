package com.tsuyu.messenger.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Avatar bytes are fetched ONCE per version from /api/avatar/{uid} and cached
 * in memory + disk. Dialog lists never carry base64 avatars anymore — that
 * was the main reason the chat list felt slow.
 */
public final class AvatarCache {
    private static final Map<String, String> MEMORY = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarCache() { }

    private static String key(String uid, String version) { return uid + "@" + version; }

    private static File file(Context ctx, String uid, String version) {
        File dir = new File(ctx.getCacheDir(), "avatars");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, uid + "_" + version + ".bin");
    }

    /** Cached avatar (base64) or null if not loaded yet. Never touches network. */
    public static String peek(Context ctx, String uid, String version) {
        if (uid == null || version == null || version.isEmpty()) return null;
        String hit = MEMORY.get(key(uid, version));
        if (hit != null) return hit;
        try {
            File f = file(ctx, uid, version);
            if (f.isFile()) {
                byte[] data = readAll(f);
                String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                MEMORY.put(key(uid, version), b64);
                return b64;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Downloads the avatar in background (once), then runs onReady on UI thread. */
    public static void load(Context ctx, String uid, String version, Runnable onReady) {
        if (uid == null || version == null || version.isEmpty()) return;
        if (peek(ctx, uid, version) != null) { if (onReady != null) onReady.run(); return; }
        if (!LOADING.add(key(uid, version))) return;
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        TsuyuServerClient.getServerUrl() + "/api/avatar/" + uid);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                String token = TsuyuServerClient.getAuthToken();
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                if (conn.getResponseCode() / 100 == 2) {
                    byte[] data;
                    try (java.io.InputStream is = conn.getInputStream();
                         java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                        data = out.toByteArray();
                    }
                    try (FileOutputStream out = new FileOutputStream(file(app, uid, version))) {
                        out.write(data);
                    }
                    MEMORY.put(key(uid, version),
                            Base64.encodeToString(data, Base64.NO_WRAP));
                    if (onReady != null) MAIN.post(onReady);
                }
            } catch (Exception ignored) {
            } finally {
                LOADING.remove(key(uid, version));
            }
        });
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
