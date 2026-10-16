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
    private Runnable rollbackCurrent;

    public ColorEditorController(Context context, EditorThemePreviewActivity preview) {
        this.context = context;
        this.preview = preview;
        preview.setElementSelectionListener(this::openElement);
    }

    public void setSelectionEnabled(boolean enabled) {
        preview.setElementSelectionListener(enabled ? this::openElement : null);
        if (!enabled) dismissCurrent();
    }

    private void openElement(MessageElementResolver.Hit hit) {
        if (preview.isEditorDestroyed()) return;
        if (hit.candidateKeys.size() == 1) {
            open(hit.key);
            return;
        }
        dismissCurrent();
        CharSequence[] items = new CharSequence[hit.candidateKeys.size()];
        for (int i = 0; i < items.length; i++) {
            String key = hit.candidateKeys.get(i);
            int value = preview.getResourceProvider().getColor(ThemeColors.stringKeyToInt(key));
            items[i] = describeKey(key) + " · " + ColorSession.formatArgb(value) + "\n" + key;
        }
        BottomSheet chooser = new BottomSheet.Builder(context)
                .setTitle(hit.region == MessageElementResolver.Region.REPLY
                        ? "Ответ: выберите свойство" : "Сообщение: свойство или состояние")
                .setItems(items, (d, which) -> {
                    if (dialog == d && !preview.isEditorDestroyed()
                            && which >= 0 && which < hit.candidateKeys.size()) open(hit.candidateKeys.get(which));
                }).create();
        chooser.setOnDismissListener(d -> { if (dialog == chooser) dialog = null; });
        dialog = chooser;
        chooser.show();
    }

    private static String describeKey(String key) {
        if (key.endsWith("BubbleSelected")) return "Фон при выделении";
        if (key.endsWith("BubbleShadow")) return "Тень сообщения";
        if (key.endsWith("Bubble")) return "Фон сообщения";
        if (key.contains("MediaMessageSelectedText")) return "Подпись ответа при выделении";
        if (key.contains("MediaMessageText")) return "Подпись вложения в ответе";
        if (key.contains("ReplyNameText")) return "Имя в ответе";
        if (key.contains("ReplyMessageText")) return "Текст ответа";
        if (key.endsWith("ReplyLine2")) return "Вторая линия ответа";
        if (key.endsWith("ReplyLine")) return "Линия ответа";
        if (key.endsWith("TimeSelectedText")) return "Время при выделении";
        if (key.endsWith("TimeText")) return "Время сообщения";
        if (key.contains("messageLink")) return "Ссылки";
        if (key.contains("messageText")) return "Текст сообщения";
        return key;
    }

    private void dismissCurrent() {
        BottomSheet previous = dialog;
        Runnable rollback = rollbackCurrent;
        dialog = null;
        rollbackCurrent = null;
        // Dismiss animations are asynchronous. Finish the old transaction BEFORE
        // opening another one, not in a delayed callback that could cancel new edits.
        if (rollback != null) rollback.run();
        if (previous != null) previous.dismiss();
    }

    public void open(String keyName) {
        dismissCurrent();
        int key = ThemeColors.stringKeyToInt(keyName);
        if (key < 0) throw new IllegalArgumentException("Unknown Telegram theme key: " + keyName);
        try {
            showColorEditor(keyName, key);
        } catch (RuntimeException failure) {
            dismissCurrent();
            throw failure;
        }
    }

    private void showColorEditor(String keyName, int key) {
        ColorSession session = preview.getEditorSession();
        preview.beginColorEdit(keyName);
        int[] color = {preview.getResourceProvider().getColor(key)};
        boolean[] synchronizing = {false};
        boolean[] committed = {false};
        Runnable rollback = () -> {
            if (committed[0]) return;
            committed[0] = true;
            if (session.isEditing()) session.cancel();
        };
        rollbackCurrent = rollback;

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

        RgbaFields rgba = new RgbaFields(context);
        ColorPicker[] picker = new ColorPicker[1];
        Runnable syncControls = () -> {
            synchronizing[0] = true;
            try {
                picker[0].setColor(color[0], 0);
                rgba.setColor(color[0]);
                alpha.setProgress(Color.alpha(color[0]) / 255f);
                alphaLabel.setText("Непрозрачность: " + Color.alpha(color[0]) + " / 255");
                hex.setText(ColorSession.formatArgb(color[0]));
            } finally {
                synchronizing[0] = false;
            }
        };
        picker[0] = new ColorPicker(context, false, (rgb, num, applyNow) -> {
            if (synchronizing[0] || committed[0]) return;
            color[0] = (color[0] & 0xff000000) | (rgb & 0x00ffffff);
            session.preview(color[0]);
            syncControls.run();
        });
        picker[0].setType(0, false, 1, 1, false, 0, false);
        content.addView(picker[0], new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(273)));
        content.addView(alphaLabel, new LinearLayout.LayoutParams(-1, -2));
        content.addView(alpha, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(44)));
        content.addView(hex, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48)));
        content.addView(rgba, new LinearLayout.LayoutParams(-1, -2));
        rgba.setListener(value -> {
            if (synchronizing[0] || committed[0]) return;
            color[0] = value;
            session.preview(value);
            syncControls.run();
        });
        alpha.setDelegate((stop, progress) -> {
            if (synchronizing[0] || committed[0]) return;
            int a = Math.max(0, Math.min(255, Math.round(progress * 255)));
            color[0] = (color[0] & 0x00ffffff) | (a << 24);
            session.preview(color[0]);
            syncControls.run();
        });
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (synchronizing[0] || committed[0]) return;
                try {
                    color[0] = ColorSession.parseArgb(s.toString());
                    hex.setError(null);
                    session.preview(color[0]);
                    // Do not rewrite the focused EditText/cursor during typing.
                    synchronizing[0] = true;
                    picker[0].setColor(color[0], 0);
                    rgba.setColor(color[0]);
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
            if (committed[0] || preview.isEditorDestroyed()) return;
            if (!rgba.validateForApply()) return;
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
            if (committed[0] || preview.isEditorDestroyed()) return;
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
            rollback.run();
            if (dialog == current) {
                dialog = null;
                rollbackCurrent = null;
            }
        });
        dialog = current;
        current.show();
    }

    @Override
    public void close() {
        preview.setElementSelectionListener(null);
        dismissCurrent();
    }
}
