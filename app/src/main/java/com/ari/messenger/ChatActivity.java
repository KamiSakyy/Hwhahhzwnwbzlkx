package com.ari.messenger;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** One dialogue: text, photo, video, video-circles ⭕ and voice notes 🎤 — all E2EE. */
public class ChatActivity extends AppCompatActivity {
    private int peerId;
    private String peerName;
    private MsgAdapter adapter;
    private RecyclerView list;
    private ChatEngine engine;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            engine.sync(() -> refresh(true));
            poll.postDelayed(this, 2500);
        }
    };

    private String pendingKind = "image";
    private MediaRecorder recorder;
    private File voiceFile;
    private boolean recording = false;

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) onPicked(uri, pendingKind);
            });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_chat);
        peerId = getIntent().getIntExtra("peer_id", 0);
        peerName = getIntent().getStringExtra("peer_name");
        engine = new ChatEngine(this);
        Db.get(this).savePeer(peerId, peerName);

        MaterialToolbar toolbar = findViewById(R.id.chatToolbar);
        toolbar.setTitle("@" + peerName);
        toolbar.setSubtitle("был(а) недавно");
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.msgList);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        adapter = new MsgAdapter(this);
        list.setAdapter(adapter);

        EditText input = findViewById(R.id.msgInput);
        findViewById(R.id.sendBtn).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            input.setText("");
            try {
                JSONObject payload = new JSONObject();
                payload.put("kind", "text");
                payload.put("text", text);
                engine.sendPayload(peerId, peerName, payload, new ChatEngine.Done() {
                    @Override public void onOk() { refresh(true); }
                    @Override public void onErr(String e) { toast("Не отправилось: " + e); }
                });
            } catch (Exception e) { toast(e.getMessage()); }
        });

        findViewById(R.id.attachBtn).setOnClickListener(v -> showAttachMenu());
        refresh(false);
    }

    private void showAttachMenu() {
        String[] items = {"📷 Фото", "🎬 Видео", "⭕ Кружок (видео)", recording ? "⏹ Голосовое: стоп и отправить" : "🎤 Голосовое: записать"};
        new AlertDialog.Builder(this)
                .setTitle("Прикрепить")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: pendingKind = "image"; picker.launch("image/*"); break;
                        case 1: pendingKind = "video"; picker.launch("video/*"); break;
                        case 2: pendingKind = "circle"; picker.launch("video/*"); break;
                        case 3: toggleVoice(); break;
                    }
                })
                .show();
    }

    private void toggleVoice() {
        if (!recording) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                toast("Разреши микрофон и нажми ещё раз 🎤");
                return;
            }
            try {
                voiceFile = new File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setOutputFile(voiceFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                recording = true;
                toast("🔴 Запись… нажми 📎 → «Голосовое: стоп» чтобы отправить");
            } catch (Exception e) { toast("Микрофон недоступен: " + e.getMessage()); }
        } else {
            try {
                recorder.stop();
                recorder.release();
                recorder = null;
                recording = false;
                sendFile(voiceFile, "voice", "m4a");
            } catch (Exception e) { toast("Запись не удалась"); }
        }
    }

    private void onPicked(Uri uri, String kind) {
        try {
            String mime = getContentResolver().getType(uri);
            String ext = extFor(mime, kind);
            File tmp = new File(getCacheDir(), "pick_" + System.currentTimeMillis() + "." + ext);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            sendFile(tmp, kind, ext);
        } catch (Exception e) { toast("Не удалось прочитать файл: " + e.getMessage()); }
    }

    private String extFor(String mime, String kind) {
        if (mime == null) return "image".equals(kind) ? "jpg" : "mp4";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("webm")) return "webm";
        if (mime.contains("quicktime")) return "mov";
        return "image".equals(kind) ? "jpg" : "mp4";
    }

    private void sendFile(File f, String kind, String ext) {
        toast("🔐 Шифрую и отправляю…");
        engine.sendMedia(peerId, peerName, kind, f, ext, new ChatEngine.Done() {
            @Override public void onOk() { refresh(true); }
            @Override public void onErr(String e) { toast("Медиа не отправилось: " + e); }
        });
    }

    private void refresh(boolean scroll) {
        List<Db.Msg> msgs = Db.get(this).messagesWith(peerId);
        adapter.setMsgs(msgs);
        if (scroll && !msgs.isEmpty()) list.scrollToPosition(msgs.size() - 1);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); poll.post(tick); }
    @Override protected void onPause() {
        super.onPause();
        poll.removeCallbacks(tick);
        if (recording && recorder != null) {
            try { recorder.stop(); recorder.release(); } catch (Exception ignored) {}
            recorder = null; recording = false;
        }
    }
}
