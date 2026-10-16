package dev.themestudio.telegram;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.themestudio.core.ColorSession;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;
import org.telegram.ui.Components.ColorPicker;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.SeekBarView;

/** Official Telegram picker/dialog/slider. Never creates a replacement chat UI. */
public final class ColorEditorController implements AutoCloseable {
    private final Context context;
    private final EditorThemePreviewActivity preview;
    private AlertDialog dialog;

    public ColorEditorController(Context context, EditorThemePreviewActivity preview) {
        this.context = context;
        this.preview = preview;
        preview.setElementSelectionListener(hit -> open(hit.key));
    }

    public void open(String keyName) {
        if (dialog != null) dialog.dismiss();
        int key = ThemeColors.stringKeyToInt(keyName);
        if (key < 0) throw new IllegalArgumentException("Unknown Telegram theme key: " + keyName);
        ColorSession session = preview.getEditorSession();
        preview.beginColorEdit(keyName);
        int[] color = {preview.getResourceProvider().getColor(key)};
        boolean[] synchronizing = {false};
        boolean[] committed = {false};

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        TextView alphaLabel = new TextView(context);
        alphaLabel.setTextSize(14);
        alphaLabel.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        SeekBarView alpha = new SeekBarView(context);
        alpha.setContentDescription("Непрозрачность цвета");
        EditTextBoldCursor hex = new EditTextBoldCursor(context);
        hex.setSingleLine(true);
        hex.setTextSize(16);
        hex.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        hex.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        hex.setHint("#AARRGGBB");
        hex.setContentDescription("Цвет в формате AARRGGBB");

        ColorPicker[] picker = new ColorPicker[1];
        Runnable syncControls = () -> {
            synchronizing[0] = true;
            try {
                picker[0].setColor(color[0], 0);
                alpha.setProgress(Color.alpha(color[0]) / 255f);
                alphaLabel.setText("Непрозрачность: " + Color.alpha(color[0]) + " / 255");
                hex.setText(ColorSession.formatArgb(color[0]));
            } finally {
                synchronizing[0] = false;
            }
        };
        picker[0] = new ColorPicker(context, false, (rgb, num, applyNow) -> {
            if (synchronizing[0]) return;
            color[0] = (color[0] & 0xff000000) | (rgb & 0x00ffffff);
            session.preview(color[0]);
            syncControls.run();
        });
        picker[0].setType(0, false, 1, 1, false, 0, false);
        content.addView(picker[0], new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(290)));
        content.addView(alphaLabel, new LinearLayout.LayoutParams(-1, -2));
        content.addView(alpha, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(44)));
        content.addView(hex, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48)));
        alpha.setDelegate((stop, progress) -> {
            if (synchronizing[0]) return;
            int a = Math.max(0, Math.min(255, Math.round(progress * 255)));
            color[0] = (color[0] & 0x00ffffff) | (a << 24);
            session.preview(color[0]);
            syncControls.run();
        });
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (synchronizing[0]) return;
                try {
                    color[0] = ColorSession.parseArgb(s.toString());
                    hex.setError(null);
                    session.preview(color[0]);
                    // Do not rewrite the focused EditText/cursor during typing.
                    synchronizing[0] = true;
                    picker[0].setColor(color[0], 0);
                    alpha.setProgress(Color.alpha(color[0]) / 255f);
                    alphaLabel.setText("Непрозрачность: " + Color.alpha(color[0]) + " / 255");
                } catch (IllegalArgumentException ignored) {
                    // Partial input is allowed; Apply validates before committing.
                } finally {
                    synchronizing[0] = false;
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        syncControls.run();
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog current = new AlertDialog.Builder(context)
                .setTitle(keyName)
                .setView(scroll)
                .setPositiveButton("Применить", (d, which) -> {
                    try {
                        session.preview(ColorSession.parseArgb(hex.getText().toString()));
                    } catch (IllegalArgumentException e) {
                        hex.setError("Введите цвет в формате #AARRGGBB");
                        return;
                    }
                    session.commit(); committed[0] = true; d.dismiss();
                })
                .setNegativeButton("Отмена", (d, which) -> d.dismiss())
                .setNeutralButton("Стандартный", (d, which) -> {
                    session.previewDefault(); session.commit(); committed[0] = true; d.dismiss();
                })
                .create();
        current.setDismissDialogByButtons(false);
        current.setOnDismissListener(d -> {
            if (!committed[0] && session.isEditing()) session.cancel();
            if (dialog == current) dialog = null;
        });
        dialog = current;
        current.show();
    }

    @Override
    public void close() {
        preview.setElementSelectionListener(null);
        if (dialog != null) dialog.dismiss();
    }
}
