package com.lsengine.studio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

import com.lsengine.studio.R;

/**
 * Редактор кода LS: моноширинный шрифт, нумерация строк, автоотступ и
 * подсветка синтаксиса.
 */
public class LsCodeEditor extends AppCompatEditText {

    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean highlighting;
    private final Runnable highlightTask = this::runHighlight;

    public LsCodeEditor(Context context) {
        super(context);
        init();
    }

    public LsCodeEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setTypeface(Typeface.MONOSPACE);
        setHorizontallyScrolling(false);
        setTextSize(13f);
        int gutter = (int) (46 * getResources().getDisplayMetrics().density);
        setPadding(gutter, (int) (10 * getResources().getDisplayMetrics().density),
                (int) (12 * getResources().getDisplayMetrics().density),
                (int) (10 * getResources().getDisplayMetrics().density));
        gutterPaint.setColor(0xFF1B1B26);
        lineNumberPaint.setColor(0xFF4A4A63);
        lineNumberPaint.setTypeface(Typeface.MONOSPACE);
        lineNumberPaint.setTextSize(12f);
        lineNumberPaint.setTextAlign(Paint.Align.RIGHT);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleHighlight();
            }
        });
    }

    private void scheduleHighlight() {
        if (highlighting) {
            return;
        }
        removeCallbacks(highlightTask);
        postDelayed(highlightTask, 120);
    }

    private void runHighlight() {
        highlighting = true;
        try {
            SyntaxHighlighter.highlight(this);
        } catch (Exception ignored) {
            // подсветка не должна ломать редактирование
        } finally {
            highlighting = false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // фон поля нумерации
        float gutterWidth = getPaddingLeft();
        canvas.drawRect(0, 0, gutterWidth, getHeight(), gutterPaint);

        Layout layout = getLayout();
        if (layout != null) {
            int firstLine = layout.getLineForVertical(0);
            int lastLine = layout.getLineForVertical(getHeight() - 1);
            float x = gutterWidth - 12 * getResources().getDisplayMetrics().density;
            for (int line = firstLine; line <= lastLine; line++) {
                float baseline = layout.getLineBaseline(line);
                canvas.drawText(String.valueOf(line + 1), x, baseline, lineNumberPaint);
            }
        }
        super.onDraw(canvas);
    }

    /** Подсвечивает строку с ошибкой. */
    public void markErrorLine(int line) {
        setTag(R.id.tag_error_line, line);
        invalidate();
    }
}
