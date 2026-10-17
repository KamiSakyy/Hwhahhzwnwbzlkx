package com.tsuyu.messenger.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Высокопроизводительный 2026 HTTP/REST и WebSocket клиент для прямого подключения
 * к серверу Tsuyu (Railway, Docker, VPS).
 */
public final class TsuyuServerClient {
    private static final String TAG = "TsuyuServerClient";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static String serverBaseUrl = "http://10.0.2.2:8080";
    private static String authToken = "";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static void setServerUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String clean = url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        serverBaseUrl = clean;
        Log.i(TAG, "Установлен адрес сервера: " + serverBaseUrl);
    }

    public static void register(String username, String password, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject resp = postJson("/api/auth/register", body);
                String token = resp.optString("token");
                if (!token.isEmpty()) setAuthToken(token);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка регистрации на сервере", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void login(String username, String password, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject resp = postJson("/api/auth/login", body);
                String token = resp.optString("token");
                if (!token.isEmpty()) setAuthToken(token);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка входа на сервере", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Pre-flight check: is there really a Tsuyu server at this address? */
    public static void checkServer(Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/health");
                if (!"tsuyu".equals(resp.optString("app"))) {
                    mainHandler.post(() -> callback.onError(
                            "По этому адресу отвечает не сервер Tsuyu. Проверьте адрес."));
                    return;
                }
                if (!"ok".equals(resp.optString("status"))) {
                    mainHandler.post(() -> callback.onError(
                            "Сервер Tsuyu не готов (нет базы данных). Проверьте деплой."));
                    return;
                }
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(
                        "Сервер не найден. Проверьте адрес сервера и что он задеплоен на Railway."));
            }
        });
    }

    public static void getProfile(String uid, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/profile?uid=" + uid);
                JSONObject user = resp.optJSONObject("user");
                mainHandler.post(() -> callback.onSuccess(user != null ? user : new JSONObject()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void saveProfile(String displayName, String username, String about, String avatarUrl, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("display_name", displayName);
                body.put("username", username);
                body.put("about", about);
                if (avatarUrl != null) body.put("avatar_url", avatarUrl);
                postJson("/api/profile", body);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void saveProfile(String displayName, String username, String about, Callback<Void> callback) {
        saveProfile(displayName, username, about, null, callback);
    }

    public static void searchUsers(String query, Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"));
                JSONArray arr = resp.optJSONArray("users");
                mainHandler.post(() -> callback.onSuccess(arr != null ? arr : new JSONArray()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static String getServerUrl() {
        return serverBaseUrl;
    }

    public static void setAuthToken(String token) {
        authToken = token == null ? "" : token;
    }

    public static String getAuthToken() {
        return authToken;
    }

    public static void sendMessage(String chatId, String senderUid, String recipientUid,
                                   String payload, String cipher, int signalType, String kind, String msgId,
                                   Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("chat_id", chatId);
                body.put("sender_uid", senderUid);
                body.put("recipient_uid", recipientUid);
                body.put("payload", payload);
                if (cipher != null) body.put("cipher", cipher);
                body.put("signalType", signalType);
                body.put("kind", kind);
                if (msgId != null) body.put("id", msgId);

                JSONObject resp = postJson("/api/messages/send", body);
                String id = resp.optString("messageId", msgId);
                mainHandler.post(() -> callback.onSuccess(id));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки на сервер", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void fetchMessages(String peerUid, Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/messages?peer_uid=" + peerUid);
                JSONArray msgs = resp.optJSONArray("messages");
                mainHandler.post(() -> callback.onSuccess(msgs == null ? new JSONArray() : msgs));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Full FastAPI surface (our self-hosted server — NO Внешний сервис anywhere)
    // ------------------------------------------------------------------
    public static void uploadKeys(String uid, java.util.Map<String, Object> bundle, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                for (java.util.Map.Entry<String, Object> e : bundle.entrySet()) body.put(e.getKey(), e.getValue());
                postJson("/api/keys/" + uid, body);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Synchronous bundle fetch (called from crypto executor threads). */
    public static java.util.Map<String, Object> fetchKeysSync(String uid) throws Exception {
        JSONObject resp = getJson("/api/keys/" + uid);
        JSONObject bundle = resp.optJSONObject("bundle");
        if (bundle == null) bundle = resp;
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        java.util.Iterator<String> it = bundle.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, bundle.get(k));
        }
        return out;
    }

    public static void presenceHeartbeat() {
        executor.execute(() -> {
            try { postJson("/api/presence", new JSONObject()); } catch (Exception ignored) { }
        });
    }

    public static JSONObject presenceSync(String uid) throws Exception {
        return getJson("/api/presence/" + uid);
    }

    public static void setTyping(String chatId, boolean typing) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                b.put("typing", typing);
                postJson("/api/typing", b);
            } catch (Exception ignored) { }
        });
    }

    public static void markRead(String chatId) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                postJson("/api/receipts", b);
            } catch (Exception ignored) { }
        });
    }

    public static void setReaction(String chatId, String messageId, String emoji, boolean enabled) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                b.put("message_id", messageId);
                b.put("emoji", emoji);
                b.put("enabled", enabled);
                postJson("/api/reactions", b);
            } catch (Exception ignored) { }
        });
    }

    public static void votePoll(String chatId, String messageId, int option) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                b.put("message_id", messageId);
                b.put("option", option);
                postJson("/api/polls/vote", b);
            } catch (Exception ignored) { }
        });
    }

    /** typing + receipts + reactions + polls for a chat, one request. Sync. */
    public static JSONObject chatStateSync(String chatId) throws Exception {
        return getJson("/api/chats/" + chatId + "/state");
    }

    public static void dialogs(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/dialogs");
                JSONArray arr = resp.optJSONArray("dialogs");
                mainHandler.post(() -> callback.onSuccess(arr == null ? new JSONArray() : arr));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void editMessage(String id, String cipher, int signalType, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", id);
                b.put("cipher", cipher);
                b.put("signalType", signalType);
                postJson("/api/messages/edit", b);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void deleteMessage(String id, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", id);
                postJson("/api/messages/delete", b);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Delete a message: scope "me" (only for me) or "everyone" (for both). */
    public static void deleteMessageScoped(String id, String scope, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", id);
                b.put("scope", scope == null ? "everyone" : scope);
                postJson("/api/messages/delete", b);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Clear a whole chat history: scope "me" or "everyone". */
    public static void clearChat(String chatId, String scope, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                b.put("scope", scope == null ? "me" : scope);
                postJson("/api/chats/clear", b);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ---- contacts / settings / blocks / chat prefs (all on OUR server) ------
    public static void contacts(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/contacts");
                JSONArray arr = resp.optJSONArray("contacts");
                mainHandler.post(() -> callback.onSuccess(arr == null ? new JSONArray() : arr));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void setContact(String uid, boolean enabled) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("uid", uid);
                b.put("enabled", enabled);
                postJson("/api/contacts", b);
            } catch (Exception ignored) { }
        });
    }

    public static void saveSettings(JSONObject settings) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("settings", settings);
                postJson("/api/settings", b);
            } catch (Exception ignored) { }
        });
    }

    public static void fetchSettings(Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/settings");
                JSONObject s = resp.optJSONObject("settings");
                mainHandler.post(() -> callback.onSuccess(s == null ? new JSONObject() : s));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void setBlock(String uid, boolean blocked, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("uid", uid);
                b.put("blocked", blocked);
                postJson("/api/block", b);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void blocks(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/blocks");
                JSONArray arr = resp.optJSONArray("blocked");
                mainHandler.post(() -> callback.onSuccess(arr == null ? new JSONArray() : arr));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Pin / mute / archive / keep-unread for one chat — synced across devices. */
    public static void setChatPrefs(String chatId, Boolean pinned, Boolean muted,
                                    Boolean archived, Boolean keepUnread) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat_id", chatId);
                if (pinned != null) b.put("pinned", pinned.booleanValue());
                if (muted != null) b.put("muted", muted.booleanValue());
                if (archived != null) b.put("archived", archived.booleanValue());
                if (keepUnread != null) b.put("keep_unread", keepUnread.booleanValue());
                postJson("/api/chats/prefs", b);
            } catch (Exception ignored) { }
        });
    }

    public static JSONObject chatPrefsSync() throws Exception {
        JSONObject resp = getJson("/api/chats/prefs");
        JSONObject prefs = resp.optJSONObject("prefs");
        return prefs == null ? new JSONObject() : prefs;
    }

    // ---- calls signaling (store-and-forward via our server) ---------------
    public static void sendCallSignal(String toUid, String chatId, String kind, JSONObject payload,
                                      Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("to_uid", toUid);
                b.put("chat_id", chatId);
                b.put("kind", kind);
                b.put("payload", payload);
                JSONObject resp = postJson("/api/calls/send", b);
                mainHandler.post(() -> callback.onSuccess(resp.optString("id")));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void callInbox(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/calls/inbox");
                JSONArray arr = resp.optJSONArray("calls");
                mainHandler.post(() -> callback.onSuccess(arr == null ? new JSONArray() : arr));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void callAck(String id) {
        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", id);
                postJson("/api/calls/ack", b);
            } catch (Exception ignored) { }
        });
    }

    /** Generic call-node KV (P2P call negotiation data). Sync versions. */
    public static void postCallNodeSync(String chatId, String callId, JSONObject fields) throws Exception {
        JSONObject b = new JSONObject();
        b.put("chat_id", chatId);
        b.put("call_id", callId);
        b.put("fields", fields);
        postJson("/api/calls/node", b);
    }

    public static JSONObject getCallNodeSync(String chatId, String callId) throws Exception {
        return getJson("/api/calls/node?chat_id=" + chatId + "&call_id=" + callId);
    }

    // ---- files -------------------------------------------------------------
    public static String uploadFileSync(byte[] data) throws Exception {
        java.net.URL url = new java.net.URL(serverBaseUrl + "/api/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        if (!authToken.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        try (java.io.OutputStream os = conn.getOutputStream()) { os.write(data); }
        int code = conn.getResponseCode();
        java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return new JSONObject(sb.toString()).optString("file_id");
    }

    public static byte[] downloadFileSync(String fileId) throws Exception {
        java.net.URL url = new java.net.URL(serverBaseUrl + "/api/files/" + fileId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (!authToken.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
        try (java.io.InputStream is = conn.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        URL url = new URL(serverBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        if (!authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);

        byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        if (code < 200 || code >= 300) {
            String err = sb.toString();
            try {
                JSONObject errObj = new JSONObject(err);
                String d = errObj.optString("detail", errObj.optString("error", ""));
                throw new Exception(d.isEmpty() ? "HTTP " + code : d);
            } catch (Exception ignored) {
                throw new Exception("Ошибка сервера HTTP " + code);
            }
        }

        return new JSONObject(sb.toString());
    }

    public static JSONObject getJson(String endpoint) throws Exception {
        URL url = new URL(serverBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (!authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        if (code < 200 || code >= 300) {
            String raw = sb.toString();
            try {
                JSONObject errObj = new JSONObject(raw);
                String d = errObj.optString("detail", errObj.optString("error", ""));
                if (!d.isEmpty()) throw new Exception(d);
            } catch (Exception ignored) { }
            throw new Exception("HTTP " + code);
        }

        return new JSONObject(sb.toString());
    }
}
