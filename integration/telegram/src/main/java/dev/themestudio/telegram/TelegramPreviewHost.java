package dev.themestudio.telegram;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;

/**
 * Host for an ALREADY INITIALIZED Telegram runtime; not an offline bootstrap.
 * Deliberately does not invoke ApplicationLoader/postInitApplication or pretend
 * that native dependencies can be replaced by a dummy library.
 */
public final class TelegramPreviewHost implements AutoCloseable {
    private final FrameLayout container;
    private final INavigationLayout navigation;
    private final EditorThemePreviewActivity preview;
    private final ColorEditorController colorEditor;
    private final EditorMenuController editorMenu;
    private boolean closed;

    public TelegramPreviewHost(Activity activity, FrameLayout container, Theme.ThemeInfo theme) {
        this(activity, container, theme, java.util.Collections.emptyMap());
    }

    public TelegramPreviewHost(Activity activity, FrameLayout container, Theme.ThemeInfo theme,
            java.util.Map<String, Integer> initialColors) {
        requireMainThread();
        if (ApplicationLoader.applicationContext == null || ApplicationLoader.applicationHandler == null) {
            throw new IllegalStateException("Official Telegram runtime is not initialized; standalone bootstrap is not implemented yet");
        }
        if (theme == null || container.getChildCount() != 0) {
            throw new IllegalArgumentException("A real theme and empty preview container are required");
        }
        this.container = container;
        navigation = INavigationLayout.newLayout(activity, false);
        preview = new EditorThemePreviewActivity(theme, initialColors);
        container.addView(navigation.getView(), new FrameLayout.LayoutParams(-1, -1));
        if (!navigation.presentFragment(preview)) {
            container.removeView(navigation.getView());
            navigation.removeAllFragments();
            throw new IllegalStateException("Official preview could not be presented");
        }
        colorEditor = new ColorEditorController(activity, preview);
        editorMenu = new EditorMenuController(activity, preview, colorEditor);
        preview.addEditorCleanup(editorMenu::close);
        preview.addEditorCleanup(colorEditor::close);
        navigation.setFragmentStackChangedListener(() -> {
            if (!closed && navigation.getFragmentStack().isEmpty()) activity.finish();
        });
    }

    public boolean isOpen() { requireMainThread(); return !closed && !preview.isEditorDestroyed(); }

    public EditorThemePreviewActivity getPreview() { requireOpen(); return preview; }
    public void onResume() { requireOpen(); navigation.onResume(); }
    public void onPause() { requireOpen(); navigation.onPause(); }
    public void onLowMemory() { requireOpen(); navigation.onLowMemory(); }

    @Override
    public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        editorMenu.close();
        colorEditor.close();
        navigation.onPause();
        navigation.removeAllFragments();
        container.removeView(navigation.getView());
        closed = true;
    }

    private void requireOpen() { requireMainThread(); if (closed || preview.isEditorDestroyed()) throw new IllegalStateException("Preview host is closed or its fragment was destroyed"); }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Telegram UI must run on the main thread");
    }
}
