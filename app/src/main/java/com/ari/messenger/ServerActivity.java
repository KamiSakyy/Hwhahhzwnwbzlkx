package com.ari.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/** Step 0: enter YOUR self-hosted server address. Nothing works without it. */
public class ServerActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store store = new Store(this);
        if (!store.server().isEmpty() && !store.token().isEmpty()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (!store.server().isEmpty() && getIntent().getBooleanExtra("change", false) == false) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_server);
        EditText input = findViewById(R.id.serverInput);
        if (!store.server().isEmpty()) input.setText(store.server());
        ProgressBar progress = findViewById(R.id.serverProgress);
        Button cont = findViewById(R.id.serverContinue);
        cont.setOnClickListener(v -> {
            String srv = Api.normalizeServer(input.getText().toString());
            if (srv.length() < 10 || !srv.contains(".")) {
                Toast.makeText(this, "Введи адрес сервера, например you.up.railway.app", Toast.LENGTH_LONG).show();
                return;
            }
            store.setServer(srv);
            progress.setVisibility(View.VISIBLE);
            cont.setEnabled(false);
            new Api(this).getJson("/health", new Api.Cb<JSONObject>() {
                @Override public void onOk(JSONObject r) {
                    startActivity(new Intent(ServerActivity.this, AuthActivity.class));
                    finish();
                }
                @Override public void onErr(String e) {
                    progress.setVisibility(View.GONE);
                    cont.setEnabled(true);
                    Toast.makeText(ServerActivity.this,
                            "Сервер не отвечает: " + e + "\nПроверь адрес (Railway должен быть задеплоен)",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
