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
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import android.view.View;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;
import org.telegram.ui.Components.ColorPicker;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.SeekBarView;

/** Official Telegram picker/sheet/slider. Never creates a replacement chat UI. */
public final class ColorEditorController implements AutoCloseable {
    private final Context context;
    private final EditorThemePreviewActivity preview;
    private BottomSheet dialog;

    public ColorEditorController(Context context, EditorThemePreviewActivity preview) {
        this.context = context;
        this.preview = preview;
        preview.setElementSelectionListener(hit -> open(hit.key));
    }

    public void setSelectionEnabled(boolean enabled) {
        preview.setElementSelectionListener(enabled ? hit -> open(hit.key) : null);
        if (!enabled && dialog != null) dialog.dismiss();
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
        content.addView(picker[0], new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(273)));
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
        // Keep actions visible while the color controls scroll on short screens/with IME.
        LinearLayout sheetContent = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = Math.min(AndroidUtilities.dp(460), Math.round(getResources().getDisplayMetrics().heightPixels * .62f));
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
                    cap = Math.min(cap, View.MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY));
            }
        };
        sheetContent.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        sheetContent.addView(actions, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(64)));
        // Same Telegram BottomSheet/ColorPicker widgets, not platform AlertDialog.
        // No dim: the user must see the actual preview colors behind the editor.
        BottomSheet current = new BottomSheet.Builder(context, true)
                .setTitle(keyName)
                .setCustomView(sheetContent)
                .setApplyBottomPadding(true)
                .create();
        current.setDimBehind(false);
        // A drag of the wheel/alpha slider must not dismiss the sheet accidentally.
        current.setCanDismissWithSwipe(false);
        ButtonWithCounterView apply = new ButtonWithCounterView(context, null);
        apply.setText("Применить", false);
        apply.setOnClickListener(v -> {
            try {
                session.preview(ColorSession.parseArgb(hex.getText().toString()));
            } catch (IllegalArgumentException e) {
                hex.setError("Введите цвет в формате #AARRGGBB");
                return;
            }
            session.commit(); committed[0] = true; current.dismiss();
        });
        ButtonWithCounterView reset = new ButtonWithCounterView(context, false, null);
        reset.setText("Сброс", false);
        reset.setContentDescription("Вернуть стандартный цвет Telegram");
        reset.setOnClickListener(v -> {
            session.previewDefault(); session.commit(); committed[0] = true; current.dismiss();
        });
        ButtonWithCounterView cancel = new ButtonWithCounterView(context, false, null);
        cancel.setText("Отмена", false);
        cancel.setOnClickListener(v -> current.dismiss());
        for (ButtonWithCounterView button : new ButtonWithCounterView[]{cancel, reset, apply}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(48), 1);
            params.setMarginStart(AndroidUtilities.dp(4));
            params.setMarginEnd(AndroidUtilities.dp(4));
            actions.addView(button, params);
        }
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
