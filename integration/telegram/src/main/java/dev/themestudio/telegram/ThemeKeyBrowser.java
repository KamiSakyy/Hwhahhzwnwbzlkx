package dev.themestudio.telegram;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;
import org.telegram.ui.Cells.TextColorCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;

/** Browses the upstream runtime registry, not the old extracted subset of defaults. */
final class ThemeKeyBrowser implements AutoCloseable {
    private final Context context;
    private final Theme.ResourcesProvider colors;
    private final Consumer<String> selection;
    private AlertDialog dialog;

    ThemeKeyBrowser(Context context, Theme.ResourcesProvider colors, Consumer<String> selection) {
        this.context = context;
        this.colors = colors;
        this.selection = selection;
    }

    void show() {
        close();
        ArrayList<String> all = new ArrayList<>();
        for (int i = 0; i < Theme.colorsCount; i++) {
            String name = ThemeColors.getStringName(i);
            if (name != null && !name.equals("wallpaperFileOffset")) all.add(name);
        }
        Collections.sort(all);
        ArrayList<String> visible = new ArrayList<>(all);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor search = new EditTextBoldCursor(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        search.setHint("Поиск ключа: bubble, text, reply…");
        search.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        content.addView(search, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48)));
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        RecyclerListView.SelectionAdapter adapter = new RecyclerListView.SelectionAdapter() {
            @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }
            @Override public int getItemCount() { return visible.size(); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return new RecyclerListView.Holder(new TextColorCell(context));
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                String name = visible.get(position);
                int color = colors.getColor(ThemeColors.stringKeyToInt(name));
                ((TextColorCell) holder.itemView).setTextAndColor(name, color, position < visible.size() - 1);
                holder.itemView.setContentDescription(name + ", " + dev.themestudio.core.ColorSession.formatArgb(color));
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= visible.size()) return;
            String name = visible.get(position);
            close();
            selection.accept(name);
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(340)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                visible.clear();
                for (String name : all) if (name.toLowerCase(Locale.ROOT).contains(query)) visible.add(name);
                adapter.notifyDataSetChanged();
                list.scrollToPosition(0);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        AlertDialog current = new AlertDialog.Builder(context)
                .setTitle("Цвета темы · " + all.size())
                .setView(content)
                .setNegativeButton("Закрыть", (d, which) -> d.dismiss())
                .create();
        current.setOnDismissListener(d -> { if (dialog == current) dialog = null; });
        dialog = current;
        current.show();
    }

    @Override public void close() { if (dialog != null) dialog.dismiss(); }
}
