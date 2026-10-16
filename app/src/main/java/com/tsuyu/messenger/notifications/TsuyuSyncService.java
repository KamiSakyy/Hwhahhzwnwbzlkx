package com.tsuyu.messenger.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.LocalMessageCache;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;
import com.tsuyu.messenger.net.TsuyuServerClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background sync against OUR OWN FastAPI server only: a foreground service
 * polls /api/dialogs every 15 s and posts local notifications for new
 * incoming messages. No external push services anywhere.
 */
public final class TsuyuSyncService extends Service {
    public static final String EXTRA_UID = "uid";
    public static final String ACTION_STOP = "com.tsuyu.messenger.STOP_SYNC";
    private static final String SERVICE_CHANNEL = "tsuyu_background_connection";
    private static final int SERVICE_NOTIFICATION_ID = 4401;
    private static final long POLL_MS = 15_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> lastSeenMessage = new HashMap<>();
    private volatile boolean running;
    private boolean baselineDone;
    private String uid;

    @Override public void onCreate() {
        super.onCreate();
        ensureServiceChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        com.tsuyu.messenger.util.Settings.ensure(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            com.tsuyu.messenger.util.Settings.remove("sync_uid");
            stopSelf();
            return START_NOT_STICKY;
        }
        uid = intent == null ? com.tsuyu.messenger.util.Settings.getString("sync_uid", null)
                : intent.getStringExtra(EXTRA_UID);
        if (uid == null || uid.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        com.tsuyu.messenger.util.Settings.putString("sync_uid", uid);
        // Session of OUR server must be valid in this process too.
        if (NotificationReplyReceiver.sessionUid(this) == null) { stopSelf(); return START_NOT_STICKY; }
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification());
        if (!running) {
            running = true;
            worker.execute(this::pollLoop);
        }
        return START_STICKY;
    }

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (Throwable ignored) { }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private void pollOnce() throws Exception {
        JSONObject resp = TsuyuServerClient.getJson("/api/dialogs");
        JSONArray dialogs = resp.optJSONArray("dialogs");
        if (dialogs == null) { baselineDone = true; return; }
        String active = com.tsuyu.messenger.util.Settings.getString("active_chat", "");
        for (int i = 0; i < dialogs.length(); i++) {
            JSONObject d = dialogs.optJSONObject(i);
            if (d == null) continue;
            String chatId = d.optString("chat_id");
            JSONObject last = d.optJSONObject("last");
            if (chatId.isEmpty() || last == null) continue;
            String messageId = last.optString("id");
            if (messageId.isEmpty()) continue;
            String previous = lastSeenMessage.put(chatId, messageId);
            if (!baselineDone || messageId.equals(previous)) continue;
            if (uid.equals(last.optString("senderUid"))) continue;
            if (chatId.equals(active)) continue;
            ChatMessage message = new ChatMessage();
            message.id = messageId;
            message.senderUid = last.optString("senderUid");
            message.cipher = last.optString("cipher", null);
            message.signalType = last.optInt("signalType", 1);
            message.sentAt = last.optLong("sentAt", last.optLong("created_at", 0L));
            if (NotificationSounds.claimNotification(this, chatId, messageId)) {
                renderIncoming(chatId, messageId, message, 0);
            }
        }
        baselineDone = true;
    }

    private void renderIncoming(String chatId, String messageId, ChatMessage message, int attempt) {
        String peerUid = message.senderUid;
        String title = "Tsuyu";
        Bitmap avatar = null;
        String body = NotificationCenter.placeholder(this);
        long when = message.sentAt;
        try {
            JSONObject user = TsuyuServerClient.getJson("/api/profile?uid=" + peerUid).optJSONObject("user");
            if (user != null) {
                Profile profile = new Profile();
                profile.username = user.optString("username");
                profile.displayName = user.optString("display_name");
                profile.avatarBase64 = user.optString("avatar_url");
                if (profile.username != null || profile.displayName != null) title = profile.displayNameOrUsername();
                if (profile.avatarBase64 != null && !profile.avatarBase64.isEmpty()) {
                    byte[] bytes = Base64.decode(profile.avatarBase64, Base64.NO_WRAP);
                    avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            }
            SignalE2ee crypto = new SignalE2ee(this, uid);
            byte[] plain = crypto.decrypt(peerUid, message.signalType, message.cipher);
            String payload = new String(plain, StandardCharsets.UTF_8);
            new LocalMessageCache(this, uid).put(chatId, messageId, payload);
            body = NotificationCenter.describePayload(this, payload);
        } catch (Exception e) {
            // Never show ciphertext: retry shortly, the session may still be syncing.
            if (attempt < 2) {
                final int nextAttempt = attempt + 1;
                worker.execute(() -> {
                    try { Thread.sleep(4000L * nextAttempt); } catch (InterruptedException ignored) { return; }
                    renderIncoming(chatId, messageId, message, nextAttempt);
                });
                return;
            }
        }
        if (when <= 0) when = System.currentTimeMillis();
        boolean decrypted = !body.equals(NotificationCenter.placeholder(this));
        if (decrypted || attempt >= 2) {
            NotificationCenter.post(this, chatId, peerUid, title, avatar, body, when, messageId, true);
            return;
        }
        NotificationCenter.post(this, chatId, peerUid, title, avatar, NotificationCenter.decrypting(this), when, messageId, true);
    }

    private Notification serviceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 4401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Tsuyu")
                .setContentText("Уведомления о новых сообщениях включены")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pending)
                .build();
    }

    private void ensureServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(SERVICE_CHANNEL, "Новые сообщения", NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("Помогает получать новые сообщения, пока приложение закрыто");
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override public void onDestroy() {
        running = false;
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
