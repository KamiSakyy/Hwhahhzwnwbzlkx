package com.tsuyu.messenger.call;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tsuyu.messenger.net.TsuyuServerClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Сигнализация звонков — только через наш FastAPI сервер:
 * приглашения лежат в ящике вызовов сервера (store-and-forward),
 * экран входящего поднимает поллинг/пуш-цикл. Внешний сервис и FCM удалены полностью.
 */
public final class CallSignal {

    public static final String TYPE_CALL = "call";
    public static final String TYPE_ANSWER = "call_answer";
    public static final String TYPE_END = "call_end";

    private CallSignal() { }

    /** Приглашение: записываем в ящик вызовов собеседника на нашем сервере. */
    public static void announce(Context context, String chatId, String callId, String fromUid,
                                String toUid, boolean video) {
        if (chatId == null || callId == null || toUid == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("callId", callId);
            payload.put("from", fromUid);
            payload.put("video", video);
            payload.put("at", System.currentTimeMillis());
            TsuyuServerClient.sendCallSignal(toUid, chatId, TYPE_CALL, payload,
                    new TsuyuServerClient.Callback<String>() {
                        @Override public void onSuccess(String id) { }
                        @Override public void onError(String e) { }
                    });
        } catch (Exception ignored) { }
    }

    /** Абонент взял трубку — вызывающий узнаёт об этом через свой ящик. */
    public static void answer(Context context, String chatId, String callId, String uid, String peerUid) {
        signal(peerUid, chatId, callId, uid, TYPE_ANSWER);
    }

    /** Отбой/отклонение: гасим экран вызова у собеседника. */
    public static void cancel(Context context, String chatId, String callId, String uid,
                              String peerUid, String reason) {
        signal(peerUid, chatId, callId, uid, TYPE_END);
    }

    /** Отказ из уведомления, когда экран вызова даже не открывался. */
    public static void declineRemote(Context context, String chatId, String callId, String uid, String peerUid) {
        if (chatId == null || callId == null || uid == null) return;
        new Thread(() -> {
            try {
                JSONObject fields = new JSONObject();
                fields.put("state", "declined");
                fields.put("endedBy", uid);
                fields.put("endedAt", System.currentTimeMillis());
                TsuyuServerClient.postCallNodeSync(chatId, callId, fields);
            } catch (Exception ignored) { }
        }, "call-decline").start();
        signal(peerUid, chatId, callId, uid, TYPE_END);
    }

    private static void signal(String toUid, String chatId, String callId, String fromUid, String kind) {
        if (toUid == null || chatId == null || callId == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("callId", callId);
            payload.put("from", fromUid);
            payload.put("at", System.currentTimeMillis());
            TsuyuServerClient.sendCallSignal(toUid, chatId, kind, payload,
                    new TsuyuServerClient.Callback<String>() {
                        @Override public void onSuccess(String id) { }
                        @Override public void onError(String e) { }
                    });
        } catch (Exception ignored) { }
    }

    /** Одно приглашение из ящика вызовов. */
    public static final class Invite {
        public String callId;
        public String from;
        public String chatId;
        public boolean video;
        public long at;
    }

    /** Ручка поллинга ящика: stop() останавливает цикл. */
    public static final class InboxWatcher {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Set<String> seen = new HashSet<>();
        private volatile boolean stopped;
        private final Runnable loop;
        InboxWatcher(String myUid, Callback callback) {
            loop = new Runnable() {
                @Override public void run() {
                    if (stopped) return;
                    TsuyuServerClient.callInbox(new TsuyuServerClient.Callback<JSONArray>() {
                        @Override public void onSuccess(JSONArray calls) {
                            long now = System.currentTimeMillis();
                            for (int i = 0; i < calls.length(); i++) {
                                JSONObject c = calls.optJSONObject(i);
                                if (c == null) continue;
                                if (!TYPE_CALL.equals(c.optString("kind"))) continue;
                                JSONObject payload = c.optJSONObject("payload");
                                if (payload == null) continue;
                                String id = payload.optString("callId");
                                String from = payload.optString("from", c.optString("from_uid"));
                                long at = payload.optLong("at", c.optLong("created_at", now));
                                if (id == null || id.isEmpty() || from == null || from.equals(myUid)) continue;
                                if (now - at > 60_000L) continue;
                                if (!seen.add(id)) continue;
                                Invite invite = new Invite();
                                invite.callId = id;
                                invite.from = from;
                                invite.chatId = c.optString("chat_id");
                                invite.video = payload.optBoolean("video", false);
                                invite.at = at;
                                callback.onInvite(invite);
                                TsuyuServerClient.callAck(c.optString("id"));
                            }
                        }
                        @Override public void onError(String e) { }
                    });
                    if (!stopped) handler.postDelayed(this, 3000L);
                }
            };
        }
        void start() { handler.post(loop); }
        public void stop() {
            stopped = true;
            handler.removeCallbacks(loop);
        }
    }

    /**
     * Следит за своим ящиком вызовов на нашем сервере и отдаёт свежие
     * приглашения (не старше минуты), пока приложение открыто.
     */
    public static InboxWatcher watchInbox(final String myUid, final Callback callback) {
        InboxWatcher watcher = new InboxWatcher(myUid, callback);
        watcher.start();
        return watcher;
    }

    public interface Callback {
        void onInvite(Invite invite);
    }
}
