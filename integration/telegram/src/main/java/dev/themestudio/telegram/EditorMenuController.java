package dev.themestudio.telegram;

import android.content.Context;
import android.widget.Toast;

/** Editor commands live OUTSIDE the reference preview. Never changes Telegram ActionBars. */
final class EditorMenuController implements AutoCloseable {
    private final Context context;
    private final EditorThemePreviewActivity preview;
    private final ThemeKeyBrowser browser;

    EditorMenuController(Context context, EditorThemePreviewActivity preview, ColorEditorController editor) {
        this.context = context;
        this.preview = preview;
        browser = new ThemeKeyBrowser(context, preview.getResourceProvider(), editor::open);
    }

    void showKeys() { browser.show(); }

    void changeHistory(boolean undo) {
        if (preview.getEditorSession().isEditing()) {
            Toast.makeText(context, "Сначала примените или отмените текущий цвет", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean changed = undo ? preview.getEditorSession().undo() : preview.getEditorSession().redo();
        if (!changed) Toast.makeText(context, "В истории нет изменений", Toast.LENGTH_SHORT).show();
    }

    @Override public void close() { browser.close(); }
}
