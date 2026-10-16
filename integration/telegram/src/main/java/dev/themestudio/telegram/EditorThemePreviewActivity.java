package dev.themestudio.telegram;

import android.content.Context;
import android.graphics.drawable.Drawable;
import dev.themestudio.core.ColorSession;
import java.util.Collections;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.ui.ThemePreviewActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;
import org.telegram.ui.ActionBar.ThemeDescription;

import java.util.ArrayList;

/**
 * Integration source, NOT connected to the old lightweight application.
 * Must run in Telegram's initialized fragment/application environment.
 * Rendering and preview fixtures belong entirely to official ThemePreviewActivity.
 * No replacement drawing, no stub ChatMessageCell, no APK acceptance implied.
 */
public final class EditorThemePreviewActivity extends ThemePreviewActivity {
    private EditorTouchLayer touchLayer;
    private java.util.function.Consumer<MessageElementResolver.Hit> selectionListener;
    private final ColorSession session = new ColorSession(Collections.emptyMap(), 100);
    private final Theme.ResourcesProvider editorColors = new Theme.ResourcesProvider() {
        @Override
        public int getColor(int key) {
            String name = ThemeColors.getStringName(key);
            Integer color = name == null ? null : session.getOverride(name);
            return color != null ? color : Theme.getColor(key);
        }

        @Override
        public Drawable getDrawable(String key) {
            return Theme.getThemeDrawable(key);
        }
    };

    public EditorThemePreviewActivity(Theme.ThemeInfo themeInfo) {
        super(themeInfo);
        setResourceProvider(editorColors);
        session.setListener(this::refreshOfficialViews);
    }

    @Override
    public View createView(Context context) {
        // Official ActionBar, RecyclerListView, DialogCell and ChatMessageCell.
        View officialView = super.createView(context);
        touchLayer = new EditorTouchLayer(context, officialView);
        touchLayer.setSelectionListener(selectionListener == null ? null : selectionListener::accept);
        fragmentView = touchLayer;
        return touchLayer;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        // The upstream override otherwise hides these in some preview modes.
        return getThemeDescriptionsInternal();
    }

    public void setElementSelectionListener(java.util.function.Consumer<MessageElementResolver.Hit> listener) {
        selectionListener = listener;
        if (touchLayer != null) {
            touchLayer.setSelectionListener(listener == null ? null : listener::accept);
        }
    }

    public ColorSession getEditorSession() {
        return session;
    }

    public void beginColorEdit(String keyName) {
        if (ThemeColors.stringKeyToInt(keyName) < 0) {
            throw new IllegalArgumentException("Unknown Telegram theme key: " + keyName);
        }
        session.begin(keyName);
    }

    @Override
    public void onFragmentDestroy() {
        if (session.isEditing()) {
            session.cancel();
        }
        session.setListener(() -> {});
        setElementSelectionListener(null);
        touchLayer = null;
        super.onFragmentDestroy();
    }

    private void refreshOfficialViews() {
        if (fragmentView == null) {
            return;
        }
        for (ThemeDescription description : getThemeDescriptionsInternal()) {
            // Do not persist edits into the global installed Telegram theme.
            description.setColor(editorColors.getColor(description.getCurrentKey()), false, false);
        }
        invalidateTree(fragmentView);
    }

    private static void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateTree(group.getChildAt(i));
            }
        }
    }
}
