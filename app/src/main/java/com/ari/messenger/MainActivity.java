package com.ari.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Chat list + user search by @username. Auto-syncs (poll) every 3 seconds. */
public class MainActivity extends AppCompatActivity {
    private ChatAdapter adapter;
    private TextView empty;
    private ChatEngine engine;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            engine.sync(() -> refresh());
            poll.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        engine = new ChatEngine(this);
        Store store = new Store(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("@" + store.myName() + " · " + shortServer(store.server()));
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_profile) {
                new AlertDialog.Builder(this)
                        .setTitle("👤 Профиль")
                        .setMessage("@" + store.myName() + "\n📧 " + store.myEmail()
                                + "\n🌐 " + store.server()
                                + "\n\n🔐 Ключи шифрования хранятся только на этом устройстве.")
                        .setPositiveButton("OK", null)
                        .show();
            } else if (id == R.id.menu_server) {
                startActivity(new Intent(this, ServerActivity.class).putExtra("change", true));
            } else if (id == R.id.menu_logout) {
                store.logout();
                Db.get(this).wipeAll();
                store.setLastSync(0);
                startActivity(new Intent(this, ServerActivity.class));
                finish();
            }
            return true;
        });

        empty = findViewById(R.id.emptyText);
        RecyclerView list = findViewById(R.id.chatList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter((peerId, peerName) -> openChat(peerId, peerName));
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.searchInput);
        findViewById(R.id.searchBtn).setOnClickListener(v -> doSearch(search.getText().toString()));
        search.setOnEditorActionListener((v, action, ev) -> { doSearch(search.getText().toString()); return true; });

        refresh();
    }

    private String shortServer(String s) {
        return s.replace("https://", "").replace("http://", "");
    }

    private void doSearch(String q) {
        if (q.trim().isEmpty()) return;
        new Api(this).getArray("/users/search?q=" + android.net.Uri.encode(q), new Api.Cb<JSONArray>() {
            @Override public void onOk(JSONArray arr) {
                List<String> names = new ArrayList<>();
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.optJSONObject(i);
                    if (u != null) {
                        names.add("👤 @" + u.optString("username"));
                        ids.add(u.optInt("id"));
                    }
                }
                if (names.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Никого не нашлось 😕", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("🔍 Найдено")
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            int peerId = ids.get(which);
                            String peerName = names.get(which).replace("👤 @", "");
                            Db.get(MainActivity.this).savePeer(peerId, peerName);
                            openChat(peerId, peerName);
                        })
                        .show();
            }
            @Override public void onErr(String e) {
                Toast.makeText(MainActivity.this, "Поиск не удался: " + e, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openChat(int peerId, String peerName) {
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra("peer_id", peerId)
                .putExtra("peer_name", peerName));
    }

    private void refresh() {
        List<Db.ChatRow> rows = Db.get(this).chatList();
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        poll.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        poll.removeCallbacks(tick);
    }
}
