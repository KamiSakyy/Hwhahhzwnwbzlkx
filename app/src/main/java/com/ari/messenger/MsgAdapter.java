package com.ari.messenger;

import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.H> {
    private final List<Db.Msg> msgs = new ArrayList<>();
    private final ChatEngine engine;
    private MediaPlayer player;

    public MsgAdapter(android.content.Context c) { engine = new ChatEngine(c); }

    public void setMsgs(List<Db.Msg> m) {
        msgs.clear();
        msgs.addAll(m);
        notifyDataSetChanged();
    }

    @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new H(LayoutInflater.from(p.getContext()).inflate(R.layout.item_message, p, false));
    }

    @Override public void onBindViewHolder(@NonNull H h, int pos) {
        Db.Msg m = msgs.get(pos);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
        if (m.mine) {
            h.root.setGravity(Gravity.END);
            h.bubble.setBackgroundResource(R.drawable.bubble_out);
        } else {
            h.root.setGravity(Gravity.START);
            h.bubble.setBackgroundResource(R.drawable.bubble_in);
        }
        h.bubble.setLayoutParams(lp);
        h.time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(m.ts * 1000)));

        h.image.setVisibility(View.GONE);
        h.video.setVisibility(View.GONE);
        h.videoLabel.setVisibility(View.GONE);
        h.text.setVisibility(View.VISIBLE);

        String kind = m.kind == null ? "text" : m.kind;
        if ("text".equals(kind) || "locked".equals(kind)) {
            h.text.setText(m.text);
            return;
        }
        // media: m.text holds the decrypted payload JSON (media_id + AES key)
        h.text.setText(ChatEngine.displayText(parse(m.text)) + "  ⬇");
        h.itemView.setOnClickListener(v -> loadMedia(h, m));
        if ("image".equals(kind) || "video".equals(kind) || "circle".equals(kind)) {
            loadMedia(h, m); // auto-load thumbnails/players
        }
    }

    private JSONObject parse(String s) {
        try { return new JSONObject(s == null ? "{}" : s); } catch (Exception e) { return new JSONObject(); }
    }

    private void loadMedia(H h, Db.Msg m) {
        JSONObject payload = parse(m.text);
        engine.fetchMedia(payload, new Api.Cb<File>() {
            @Override public void onOk(File f) {
                String kind = m.kind;
                h.text.setVisibility(View.GONE);
                if ("image".equals(kind)) {
                    h.image.setVisibility(View.VISIBLE);
                    h.image.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
                } else if ("video".equals(kind) || "circle".equals(kind)) {
                    h.videoLabel.setVisibility(View.VISIBLE);
                    h.videoLabel.setText("circle".equals(kind) ? "⭕ Кружок · нажми для просмотра" : "🎬 Видео · нажми для просмотра");
                    h.video.setVisibility(View.VISIBLE);
                    h.video.setVideoPath(f.getAbsolutePath());
                    h.video.setOnClickListener(v -> { if (h.video.isPlaying()) h.video.pause(); else h.video.start(); });
                } else if ("voice".equals(kind)) {
                    h.text.setVisibility(View.VISIBLE);
                    h.text.setText("🎤 Голосовое · нажми ▶");
                    h.itemView.setOnClickListener(v -> playVoice(h.itemView, f));
                }
            }
            @Override public void onErr(String e) {
                Toast.makeText(h.itemView.getContext(), "Медиа не загрузилось: " + e, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playVoice(View v, File f) {
        try {
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            Toast.makeText(v.getContext(), "🎤 Играет голосовое…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(v.getContext(), "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public int getItemCount() { return msgs.size(); }

    static class H extends RecyclerView.ViewHolder {
        LinearLayout root, bubble;
        TextView text, time, videoLabel;
        ImageView image;
        VideoView video;
        H(View v) {
            super(v);
            root = v.findViewById(R.id.bubbleRoot);
            bubble = v.findViewById(R.id.bubble);
            text = v.findViewById(R.id.msgText);
            time = v.findViewById(R.id.msgTime);
            image = v.findViewById(R.id.msgImage);
            video = v.findViewById(R.id.msgVideo);
            videoLabel = v.findViewById(R.id.msgVideoLabel);
        }
    }
}
