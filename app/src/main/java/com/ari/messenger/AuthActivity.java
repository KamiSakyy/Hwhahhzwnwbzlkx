package com.ari.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

/** Login / Registration. On success: generate E2EE keys and upload the public bundle. */
public class AuthActivity extends AppCompatActivity {
    private boolean registerMode = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_auth);
        Store store = new Store(this);
        ((TextView) findViewById(R.id.authServer)).setText("🌐 " + store.server());

        EditText email = findViewById(R.id.emailInput);
        EditText username = findViewById(R.id.usernameInput);
        EditText password = findViewById(R.id.passwordInput);
        TextInputLayout usernameLayout = findViewById(R.id.usernameLayout);
        TextView title = findViewById(R.id.authTitle);
        Button main = findViewById(R.id.authButton);
        Button switchBtn = findViewById(R.id.authSwitch);
        ProgressBar progress = findViewById(R.id.authProgress);

        switchBtn.setOnClickListener(v -> {
            registerMode = !registerMode;
            usernameLayout.setVisibility(registerMode ? View.VISIBLE : View.GONE);
            title.setText(registerMode ? "Регистрация в ARI" : "Вход в ARI");
            main.setText(registerMode ? "Создать аккаунт" : "Войти");
            switchBtn.setText(registerMode ? "Уже есть аккаунт? Войти" : "Нет аккаунта? Регистрация");
        });
        findViewById(R.id.authServerBtn).setOnClickListener(v -> {
            startActivity(new Intent(this, ServerActivity.class).putExtra("change", true));
            finish();
        });

        main.setOnClickListener(v -> {
            String em = email.getText().toString().trim();
            String pw = password.getText().toString();
            String un = username.getText().toString().trim();
            if (em.isEmpty() || pw.isEmpty() || (registerMode && un.isEmpty())) {
                Toast.makeText(this, "Заполни все поля", Toast.LENGTH_SHORT).show();
                return;
            }
            progress.setVisibility(View.VISIBLE);
            main.setEnabled(false);
            try {
                JSONObject body = new JSONObject();
                body.put("email", em);
                body.put("password", pw);
                if (registerMode) body.put("username", un);
                String path = registerMode ? "/auth/register" : "/auth/login";
                new Api(this).postJson(path, body, new Api.Cb<JSONObject>() {
                    @Override public void onOk(JSONObject r) {
                        try {
                            JSONObject user = r.getJSONObject("user");
                            store.setAuth(r.getString("token"), user.getInt("id"),
                                    user.getString("username"), user.getString("email"));
                            uploadKeys(store, progress, main);
                        } catch (Exception e) {
                            fail(progress, main, e.getMessage());
                        }
                    }
                    @Override public void onErr(String e) { fail(progress, main, e); }
                });
            } catch (Exception e) {
                fail(progress, main, e.getMessage());
            }
        });
    }

    private void uploadKeys(Store store, ProgressBar progress, Button main) {
        String[] bundle = store.ensureKeys(); // identity + signed prekey (public)
        try {
            JSONObject body = new JSONObject();
            body.put("identity_key", bundle[0]);
            body.put("signed_prekey", bundle[1]);
            new Api(this).postJson("/keys", body, new Api.Cb<JSONObject>() {
                @Override public void onOk(JSONObject r) {
                    startActivity(new Intent(AuthActivity.this, MainActivity.class));
                    finish();
                }
                @Override public void onErr(String e) { fail(progress, main, "Ключи не загрузились: " + e); }
            });
        } catch (Exception e) {
            fail(progress, main, e.getMessage());
        }
    }

    private void fail(ProgressBar p, Button b, String e) {
        p.setVisibility(View.GONE);
        b.setEnabled(true);
        Toast.makeText(this, e, Toast.LENGTH_LONG).show();
    }
}
