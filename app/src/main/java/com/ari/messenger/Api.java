package com.ari.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal HTTP client (HttpURLConnection — zero extra deps, tiny APK). */
public class Api {
    public interface Cb<T> { void onOk(T v); void onErr(String e); }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final String base;
    private final String token;

    public Api(Context c) {
        Store s = new Store(c);
        String srv = s.server();
        this.base = srv.endsWith("/") ? srv.substring(0, srv.length() - 1) : srv;
        this.token = s.token();
    }

    public static String normalizeServer(String s) {
        s = s.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String errBody(HttpURLConnection c) {
        try {
            byte[] b = readAll(c.getErrorStream());
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            return o.optString("detail", "HTTP " + c.getResponseCode());
        } catch (Exception e) { return "HTTP error"; }
    }

    public void postJson(String path, JSONObject body, Cb<JSONObject> cb) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(path, "POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
                if (c.getResponseCode() / 100 != 2) throw new Exception(errBody(c));
                JSONObject r = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
                MAIN.post(() -> cb.onOk(r));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr(String.valueOf(e.getMessage())));
            }
        });
    }

    public void getJson(String path, Cb<JSONObject> cb) { getJsonRaw(path, cb); }
    public void getJsonRaw(String path, Cb<JSONObject> cb) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(path, "GET");
                if (c.getResponseCode() / 100 != 2) throw new Exception(errBody(c));
                JSONObject r = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
                MAIN.post(() -> cb.onOk(r));
            } catch (Exception e) { MAIN.post(() -> cb.onErr(String.valueOf(e.getMessage()))); }
        });
    }

    /** GET that returns a JSONArray wrapped as {"_array": ...} for convenience. */
    public void getArray(String path, Cb<org.json.JSONArray> cb) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(path, "GET");
                if (c.getResponseCode() / 100 != 2) throw new Exception(errBody(c));
                org.json.JSONArray r = new org.json.JSONArray(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
                MAIN.post(() -> cb.onOk(r));
            } catch (Exception e) { MAIN.post(() -> cb.onErr(String.valueOf(e.getMessage()))); }
        });
    }

    public void postRaw(String path, byte[] data, Cb<JSONObject> cb) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(path, "POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
                if (c.getResponseCode() / 100 != 2) throw new Exception(errBody(c));
                JSONObject r = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
                MAIN.post(() -> cb.onOk(r));
            } catch (Exception e) { MAIN.post(() -> cb.onErr(String.valueOf(e.getMessage()))); }
        });
    }

    public void getRaw(String path, Cb<byte[]> cb) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(path, "GET");
                if (c.getResponseCode() / 100 != 2) throw new Exception(errBody(c));
                byte[] r = readAll(c.getInputStream());
                MAIN.post(() -> cb.onOk(r));
            } catch (Exception e) { MAIN.post(() -> cb.onErr(String.valueOf(e.getMessage()))); }
        });
    }
}
