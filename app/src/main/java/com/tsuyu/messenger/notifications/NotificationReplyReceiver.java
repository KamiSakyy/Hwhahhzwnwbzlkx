package com.tsuyu.messenger.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatRepository;

public final class NotificationReplyReceiver extends BroadcastReceiver {
    public static final String KEY_TEXT = "tsuyu_reply_text";
    public static final String ACTION_MARK_READ = "com.tsuyu.messenger.MARK_READ";

    @Override
    public void onReceive(Context context, Intent intent) {
        String chatId = intent.getStringExtra("chatId");
        String peerUid = intent.getStringExtra("peerUid");
        if (chatId == null) return;
        if (ACTION_MARK_READ.equals(intent.getAction())) {
            markRead(context, chatId);
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence reply = results.getCharSequence(KEY_TEXT);
        if (reply == null || reply.toString().trim().isEmpty() || peerUid == null) return;
        PendingResult pending = goAsync();
        try {
            String uid = sessionUid(context);
            if (uid == null) {
                pending.finish();
                return;
            }
            SignalE2ee crypto = new SignalE2ee(context, uid);
            ChatRepository repository = new ChatRepository();
            repository.sendText(chatId, uid, peerUid, crypto, reply.toString(), null,
                    new ChatRepository.Result<String>() {
                        @Override public void success(String value) { pending.finish(); }
                        @Override public void error(String message) { pending.finish(); }
                    });
        } catch (Exception e) {
            pending.finish();
        }
    }

    /** Marks the chat read straight from the notification without opening the app. */
    private void markRead(Context context, String chatId) {
        NotificationCenter.clear(context, chatId);
        try {
            if (sessionUid(context) == null) return;
            com.tsuyu.messenger.net.TsuyuServerClient.markRead(chatId);
        } catch (Exception ignored) { }
    }

    /** Session of OUR server (uid + token + address) from app settings. */
    static String sessionUid(Context context) {
        com.tsuyu.messenger.util.Settings.ensure(context);
        String server = com.tsuyu.messenger.util.Settings.getString("server_url", "");
        if (!server.isEmpty()) com.tsuyu.messenger.net.TsuyuServerClient.setServerUrl(server);
        String token = com.tsuyu.messenger.util.Settings.getString("auth_token", "");
        if (!token.isEmpty()) com.tsuyu.messenger.net.TsuyuServerClient.setAuthToken(token);
        String uid = com.tsuyu.messenger.util.Settings.getString("auth_uid", "");
        return uid == null || uid.isEmpty() ? null : uid;
    }
}
