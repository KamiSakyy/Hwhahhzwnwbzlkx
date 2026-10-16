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
    }

    @Override protected void onSaveInstanceState(Bundle out) {
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
