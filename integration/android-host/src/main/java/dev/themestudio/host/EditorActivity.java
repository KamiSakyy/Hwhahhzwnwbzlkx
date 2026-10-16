package dev.themestudio.host;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import dev.themestudio.telegram.TelegramPreviewHost;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

/**
 * Source-stage entry point. Uses original ApplicationLoader and real native
 * Telegram runtime; never substitutes a fake preview if initialization fails.
 * Android compilation, merged manifest and runtime validation are still pending.
 */
public final class EditorActivity extends Activity {
    private static final String PURE = "editor.purePreview";
    private static final String COLORS = "editor.confirmedColors";
    private TelegramPreviewHost host;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Same initialization entry point used by official LaunchActivity.
        // Native/libs/controllers must be present. No login UI is instantiated.
        ApplicationLoader.postInitApplication();
        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(R.layout.activity_editor);
        FrameLayout container = findViewById(R.id.official_preview_container);
        java.util.Map<String, Integer> restored = new java.util.TreeMap<>();
        Bundle savedColors = state == null ? null : state.getBundle(COLORS);
        if (savedColors != null) {
            for (String key : savedColors.keySet()) restored.put(key, savedColors.getInt(key));
        }
        host = new TelegramPreviewHost(this, container, Theme.getCurrentTheme(), restored);
        findViewById(R.id.editor_keys).setOnClickListener(v -> { if (host.isOpen()) host.showThemeKeys(); });
        findViewById(R.id.editor_undo).setOnClickListener(v -> { if (host.isOpen()) host.undo(); });
        findViewById(R.id.editor_redo).setOnClickListener(v -> { if (host.isOpen()) host.redo(); });
        findViewById(R.id.editor_pure).setOnClickListener(v -> {
            findViewById(R.id.editor_tools).setVisibility(View.GONE);
            host.setEditingEnabled(false);
            android.widget.Toast.makeText(this, R.string.ts_pure_hint, android.widget.Toast.LENGTH_LONG).show();
        });
        if (state != null && state.getBoolean(PURE)) {
            findViewById(R.id.editor_tools).setVisibility(View.GONE);
            host.setEditingEnabled(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
    }

    @Override public void onBackPressed() {
        View tools = findViewById(R.id.editor_tools);
        if (tools != null && tools.getVisibility() == View.GONE) {
            tools.setVisibility(View.VISIBLE);
            if (host != null && host.isOpen()) host.setEditingEnabled(true);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean(PURE, findViewById(R.id.editor_tools).getVisibility() == View.GONE);
        if (host != null && host.isOpen()) {
            Bundle colors = new Bundle();
            for (java.util.Map.Entry<String, Integer> entry : host.getPreview().getEditorSession().committedSnapshot().entrySet()) {
                colors.putInt(entry.getKey(), entry.getValue());
            }
            out.putBundle(COLORS, colors);
        }
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        if (host != null && host.isOpen()) host.onResume();
    }

    @Override protected void onPause() {
        if (host != null && host.isOpen()) host.onPause();
        super.onPause();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (host != null && host.isOpen()) host.onLowMemory();
    }

    @Override protected void onDestroy() {
        if (host != null) {
            host.close();
            host = null;
        }
        Theme.applyPreviousTheme();
        super.onDestroy();
    }
}
