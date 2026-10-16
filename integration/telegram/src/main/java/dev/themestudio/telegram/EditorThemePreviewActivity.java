package dev.themestudio.telegram;

import android.content.Context;
import android.util.SparseIntArray;
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
    private final SparseIntArray overrides = new SparseIntArray();
    private final Theme.ResourcesProvider editorColors = new Theme.ResourcesProvider() {
        @Override
        public int getColor(int key) {
            int index = overrides.indexOfKey(key);
            return index >= 0 ? overrides.valueAt(index) : Theme.getColor(key);
        }
    };

    public EditorThemePreviewActivity(Theme.ThemeInfo themeInfo) {
        super(themeInfo);
        setResourceProvider(editorColors);
    }

    @Override
    public View createView(Context context) {
        // Official ActionBar, RecyclerListView, DialogCell and ChatMessageCell.
        return super.createView(context);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        // The upstream override otherwise hides these in some preview modes.
        return getThemeDescriptionsInternal();
    }

    public void setEditorColor(String keyName, int argb) {
        int key = ThemeColors.stringKeyToInt(keyName);
        if (key < 0) {
            throw new IllegalArgumentException("Unknown Telegram theme key: " + keyName);
        }
        overrides.put(key, argb);
        refreshOfficialViews();
    }

    public void resetEditorColor(String keyName) {
        int key = ThemeColors.stringKeyToInt(keyName);
        if (key < 0) {
            throw new IllegalArgumentException("Unknown Telegram theme key: " + keyName);
        }
        overrides.delete(key);
        refreshOfficialViews();
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
