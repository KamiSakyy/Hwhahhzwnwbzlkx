package dev.themestudio.telegram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Extends official ActionBars, retaining each original callback (including Back). */
final class EditorMenuController implements AutoCloseable {
    private static final int MENU = 0x545300;
    private static final int KEYS = MENU + 1, UNDO = MENU + 2, REDO = MENU + 3;
    private final ArrayList<Registration> registrations = new ArrayList<>();
    private final Set<ActionBar> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Context context;
    private final EditorThemePreviewActivity preview;
    private final ThemeKeyBrowser browser;

    private static final class Registration {
        ActionBar bar;
        ActionBarMenuItem item;
        ActionBar.ActionBarMenuOnItemClick previous, installed;
    }

    EditorMenuController(Context context, EditorThemePreviewActivity preview, ColorEditorController editor) {
        this.context = context;
        this.preview = preview;
        browser = new ThemeKeyBrowser(context, preview.getResourceProvider(), editor::open);
        attach(preview.getActionBar());
        visit(preview.getFragmentView());
    }

    private void visit(View view) {
        if (view instanceof ActionBar) attach((ActionBar) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i));
        }
    }

    private void attach(ActionBar bar) {
        if (bar == null || !seen.add(bar)) return;
        Registration r = new Registration();
        r.bar = bar;
        r.previous = bar.getActionBarMenuOnItemClick();
        r.item = bar.createMenu().addItem(MENU, R.drawable.ic_ab_other);
        r.item.setContentDescription("Инструменты редактора темы");
        r.item.addSubItem(KEYS, "Все цвета темы");
        r.item.addSubItem(UNDO, "Отменить изменение");
        r.item.addSubItem(REDO, "Вернуть изменение");
        r.installed = new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == KEYS) {
                    browser.show();
                } else if (id == UNDO || id == REDO) {
                    if (preview.getEditorSession().isEditing()) {
                        Toast.makeText(context, "Сначала примените или отмените текущий цвет", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean changed = id == UNDO ? preview.getEditorSession().undo() : preview.getEditorSession().redo();
                    if (!changed) Toast.makeText(context, "В истории нет изменений", Toast.LENGTH_SHORT).show();
                } else if (r.previous != null) {
                    r.previous.onItemClick(id);
                }
            }
            @Override public boolean canOpenMenu() { return r.previous == null || r.previous.canOpenMenu(); }
        };
        bar.setActionBarMenuOnItemClick(r.installed);
        registrations.add(r);
    }

    @Override public void close() {
        browser.close();
        for (Registration r : registrations) {
            if (r.bar.getActionBarMenuOnItemClick() == r.installed) r.bar.setActionBarMenuOnItemClick(r.previous);
            if (r.item.getParent() instanceof ViewGroup) ((ViewGroup) r.item.getParent()).removeView(r.item);
        }
        registrations.clear();
        seen.clear();
    }
}
