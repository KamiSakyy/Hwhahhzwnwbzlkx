package com.ari.messenger;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.H> {
    public interface OnOpen { void open(int peerId, String peerName); }

    private final List<Db.ChatRow> rows = new ArrayList<>();
    private final OnOpen listener;

    public ChatAdapter(OnOpen l) { listener = l; }

    public void setRows(List<Db.ChatRow> r) {
        rows.clear();
        rows.addAll(r);
        notifyDataSetChanged();
    }

    @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new H(LayoutInflater.from(p.getContext()).inflate(R.layout.item_chat, p, false));
    }

    @Override public void onBindViewHolder(@NonNull H h, int pos) {
        Db.ChatRow r = rows.get(pos);
        h.name.setText("@" + r.peerName);
        h.avatar.setText(r.peerName.isEmpty() ? "?" : r.peerName.substring(0, 1).toUpperCase(Locale.ROOT));
        h.time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(r.ts * 1000)));
        String preview;
        if ("text".equals(r.lastKind)) preview = r.lastText == null ? "" : r.lastText;
        else {
            switch (r.lastKind == null ? "" : r.lastKind) {
                case "image": preview = "📷 Фото"; break;
                case "video": preview = "🎬 Видео"; break;
                case "circle": preview = "⭕ Кружок"; break;
                case "voice": preview = "🎤 Голосовое"; break;
                case "locked": preview = "🔒 Зашифровано"; break;
                default: preview = "📎 Вложение";
            }
        }
        h.preview.setText(preview);
        h.itemView.setOnClickListener(v -> listener.open(r.peerId, r.peerName));
    }

    @Override public int getItemCount() { return rows.size(); }

    static class H extends RecyclerView.ViewHolder {
        TextView avatar, name, time, preview;
        H(View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.chatName);
            time = v.findViewById(R.id.chatTime);
            preview = v.findViewById(R.id.chatPreview);
        }
    }
}
