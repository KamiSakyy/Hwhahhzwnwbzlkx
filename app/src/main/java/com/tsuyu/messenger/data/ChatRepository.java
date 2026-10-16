package com.tsuyu.messenger.data;

import android.os.Handler;
import android.os.Looper;

import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.net.TsuyuServerClient;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-to-one repository backed ONLY by our own FastAPI server.
 * Plaintext bodies are NEVER written to the server: payloads are sealed
 * with Signal (Double Ratchet) first, and the relay stores ciphertext only.
 * внешнего облака has been removed completely.
 */
public final class ChatRepository {
    public interface Result<T> {
        void success(T value);
        void error(String message);
    }

    /** Notified on a background thread once a message is committed, so the peer's phone wakes up. */
    public interface PushDispatcher {
        void onMessageSent(String chatId, String senderUid, String recipientUid, String messageId);
    }

    private static volatile PushDispatcher pushDispatcher;

    /** Registered once by the application so every send path (UI, notification reply) pushes. */
    public static void setPushDispatcher(PushDispatcher dispatcher) {
        pushDispatcher = dispatcher;
    }

    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ChatRepository() { }

    public static String chatId(String uidA, String uidB) {
        try {
            TreeSet<String> ids = new TreeSet<>();
            ids.add(uidA);
            ids.add(uidB);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((ids.first() + ":" + ids.last()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void openOrCreate(String myUid, String peerUid, Result<String> result) {
        if (myUid == null || peerUid == null || myUid.equals(peerUid)) {
            result.error("Нельзя открыть чат с этим аккаунтом.");
            return;
        }
        // Chats are implicit on our server: the deterministic id is all we need.
        result.success(chatId(myUid, peerUid));
    }

    /**
     * Публичный ключ собеседника спрашиваем у сети один раз за сеанс — дальше берём из памяти.
     * Иначе на каждое сообщение уходил лишний круг ожидания, и «отправка» ощущалась медленной.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> keyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Map<String, Object> peerKeyBundle(String peerUid) throws Exception {
        Map<String, Object> cached = keyCache.get(peerUid);
        if (cached != null) return cached;
        Map<String, Object> bundle = TsuyuServerClient.fetchKeysSync(peerUid);
        if (bundle != null && !bundle.isEmpty()) {
            keyCache.put(peerUid, bundle);
            return bundle;
        }
        return null;
    }

    /** Synchronous bundle access for UI flows (profile safety-code screen, call setup). */
    public Map<String, Object> fetchPeerKeyBundleSync(String peerUid) throws Exception {
        return peerKeyBundle(peerUid);
    }

    public void sendText(String chatId, String senderUid, String recipientUid,
                         SignalE2ee crypto, String text, String replyTo, Result<String> result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("version", 1);
            payload.put("kind", "text");
            payload.put("text", text);
            if (replyTo != null) payload.put("replyTo", replyTo);
            sendPayload(chatId, senderUid, recipientUid, crypto,
                    payload.toString().getBytes(StandardCharsets.UTF_8), result);
        } catch (Exception e) {
            result.error("Не удалось подготовить сообщение.");
        }
    }

    public void sendPayload(String chatId, String senderUid, String recipientUid,
                            SignalE2ee crypto, byte[] payload, Result<String> result) {
        sendPayload(chatId, senderUid, recipientUid, crypto, payload, null, result);
    }

    /** Encrypts first, then sends ciphertext to our relay. No plaintext ever leaves the device. */
    public void sendPayload(String chatId, String senderUid, String recipientUid,
                            SignalE2ee crypto, byte[] payload, String clientId, Result<String> result) {
        cryptoExecutor.execute(() -> {
            try {
                Map<String, Object> publicBundle = peerKeyBundle(recipientUid);
                if (publicBundle == null || crypto == null) {
                    mainHandler.post(() -> result.error(
                            "Собеседник ещё не опубликовал ключи безопасности. Попросите его открыть Tsuyu."));
                    return;
                }
                SignalE2ee.Encrypted encrypted;
                try {
                    encrypted = crypto.encrypt(recipientUid, publicBundle, payload);
                } catch (Exception e) {
                    mainHandler.post(() -> result.error(safeMessage(e)));
                    return;
                }
                TsuyuServerClient.sendMessage(chatId, senderUid, recipientUid,
                        null, encrypted.cipher, encrypted.signalType, "text", clientId,
                        new TsuyuServerClient.Callback<String>() {
                            @Override public void onSuccess(String serverMsgId) {
                                if (pushDispatcher != null) {
                                    try { pushDispatcher.onMessageSent(chatId, senderUid, recipientUid, serverMsgId); }
                                    catch (Exception ignored) { }
                                }
                                mainHandler.post(() -> result.success(serverMsgId));
                            }
                            @Override public void onError(String serverErr) {
                                mainHandler.post(() -> result.error(
                                        "Не удалось отправить сообщение. Проверьте интернет и попробуйте снова."));
                            }
                        });
            } catch (Exception e) {
                mainHandler.post(() -> result.error(safeMessage(e)));
            }
        });
    }

    public void editText(String chatId, String messageId, String senderUid, String peerUid,
                         SignalE2ee crypto, String text, Result<Void> result) {
        cryptoExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("version", 1);
                payload.put("kind", "text");
                payload.put("text", text);
                Map<String, Object> bundle = peerKeyBundle(peerUid);
                if (bundle == null || crypto == null) {
                    mainHandler.post(() -> result.error("Нет ключей безопасности собеседника."));
                    return;
                }
                SignalE2ee.Encrypted encrypted = crypto.encrypt(peerUid, bundle,
                        payload.toString().getBytes(StandardCharsets.UTF_8));
                TsuyuServerClient.editMessage(messageId, encrypted.cipher, encrypted.signalType,
                        new TsuyuServerClient.Callback<Void>() {
                            @Override public void onSuccess(Void v) {
                                mainHandler.post(() -> result.success(null));
                            }
                            @Override public void onError(String msg) {
                                mainHandler.post(() -> result.error(
                                        "Не удалось изменить сообщение. Проверьте интернет."));
                            }
                        });
            } catch (Exception e) {
                mainHandler.post(() -> result.error(safeMessage(e)));
            }
        });
    }

    public void deleteMessage(String chatId, String messageId, Result<Void> result) {
        TsuyuServerClient.deleteMessage(messageId, new TsuyuServerClient.Callback<Void>() {
            @Override public void onSuccess(Void v) { result.success(null); }
            @Override public void onError(String msg) { result.success(null); }
        });
    }

    public void setReaction(String chatId, String messageId, String uid, boolean enabled) {
        TsuyuServerClient.setReaction(chatId, messageId, "❤", enabled);
    }

    public void setTyping(String chatId, String uid, boolean typing) {
        TsuyuServerClient.setTyping(chatId, typing);
    }

    public void markRead(String chatId, String uid) {
        TsuyuServerClient.markRead(chatId);
    }

    private static String safeMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String text = cause.getLocalizedMessage();
        if (text != null && text.startsWith("Код безопасности собеседника")) return text;
        return "Не удалось отправить сообщение. Проверьте интернет и попробуйте снова.";
    }
}
