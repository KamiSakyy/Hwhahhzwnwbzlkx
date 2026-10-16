package dev.themestudio.telegram;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.function.IntConsumer;

/** Numeric controls for the editor sheet, NOT a replacement Telegram renderer. */
final class RgbaFields extends LinearLayout {
    private final EditTextBoldCursor[] fields = new EditTextBoldCursor[4];
    private boolean synchronizing;
    private int notifyingIndex = -1;
    private IntConsumer listener;

    RgbaFields(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        String[] names = {"R", "G", "B", "A"};
        String[] descriptions = {"Красный, от 0 до 255", "Зелёный, от 0 до 255",
                "Синий, от 0 до 255", "Непрозрачность, от 0 до 255"};
        for (int i = 0; i < fields.length; i++) {
            final int channel = i;
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            LayoutParams columnParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) columnParams.setMarginStart(AndroidUtilities.dp(12));
            addView(column, columnParams);
            TextView label = new TextView(context);
            label.setText(names[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            column.addView(label, new LayoutParams(-1, -2));
            EditTextBoldCursor input = new EditTextBoldCursor(context);
            fields[i] = input;
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            input.setContentDescription(descriptions[i]);
            input.setSelectAllOnFocus(true);
            column.addView(input, new LayoutParams(-1, AndroidUtilities.dp(48)));
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (synchronizing) return;
                    Integer value = readColor(false);
                    if (value == null || listener == null) return;
                    notifyingIndex = channel;
                    try { listener.accept(value); } finally { notifyingIndex = -1; }
                }
                @Override public void afterTextChanged(Editable editable) {}
            });
        }
        setColor(0xff000000);
    }

    void setListener(IntConsumer listener) { this.listener = listener; }

    void setColor(int color) {
        int[] values = {Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color)};
        synchronizing = true;
        try {
            for (int i = 0; i < fields.length; i++) {
                fields[i].setError(null);
                // Keep the cursor and a partially formatted value (e.g. 005) in the
                // field that triggered this notification; other controls stay in sync.
                if (i == notifyingIndex) continue;
                String value = Integer.toString(values[i]);
                if (!value.contentEquals(fields[i].getText())) fields[i].setText(value);
            }
        } finally {
            synchronizing = false;
        }
    }

    boolean validateForApply() { return readColor(true) != null; }

    private Integer readColor(boolean requestFocus) {
        int[] values = new int[4];
        EditTextBoldCursor firstInvalid = null;
        for (int i = 0; i < fields.length; i++) {
            String text = fields[i].getText().toString();
            boolean valid = text.matches("[0-9]{1,3}");
            if (valid) { values[i] = Integer.parseInt(text); valid = values[i] <= 255; }
            fields[i].setError(valid ? null : (text.isEmpty() && !requestFocus ? null : "0–255"));
            if (!valid && firstInvalid == null) firstInvalid = fields[i];
        }
        if (firstInvalid != null) {
            if (requestFocus) firstInvalid.requestFocus();
            return null;
        }
        return Color.argb(values[3], values[0], values[1], values[2]);
    }
}
