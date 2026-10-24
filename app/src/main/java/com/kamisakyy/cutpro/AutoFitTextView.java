package com.kamisakyy.cutpro;

import android.content.Context;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * TextView that shrinks its own text until it fits the available width (and height),
 * so labels never get clipped on small screens or with large system font scale.
 */
public class AutoFitTextView extends TextView {

    private static final float MIN_TEXT_SIZE_SP = 6.5f;

    private float baseTextPx = -1f;
    private boolean fitting;
    private int lastWidthLimit = -1;

    public AutoFitTextView(Context context) {
        super(context);
        init();
    }

    public AutoFitTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AutoFitTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setIncludeFontPadding(false);
        setMaxLines(1);
        setEllipsize(null);
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        if (!fitting) {
            baseTextPx = getTextSize();
        }
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        if (!fitting) {
            baseTextPx = getTextSize();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!fitting) {
            fitting = true;
            try {
                fit(widthMeasureSpec, heightMeasureSpec);
            } catch (Throwable ignored) {
            } finally {
                fitting = false;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void fit(int widthMeasureSpec, int heightMeasureSpec) {
        CharSequence text = getText();
        if (text == null || text.length() == 0) {
            return;
        }
        if (baseTextPx <= 0) {
            baseTextPx = getTextSize();
        }

        int widthLimit = MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight();
        if (widthLimit <= 0) {
            return;
        }
        lastWidthLimit = widthLimit;

        int heightLimit = MeasureSpec.getSize(heightMeasureSpec)
                - getPaddingTop() - getPaddingBottom();
        boolean limitHeight = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
                && heightLimit > 0;

        float density = getResources().getDisplayMetrics().scaledDensity;
        float minPx = Math.max(1f, MIN_TEXT_SIZE_SP * density);

        TextPaint paint = getPaint();
        float size = baseTextPx;
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);

        float width = paint.measureText(text, 0, text.length());
        float height = paint.descent() - paint.ascent();

        if (width <= widthLimit && (!limitHeight || height <= heightLimit)) {
            if (Math.abs(getTextSize() - size) > 0.01f) {
                super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            return;
        }

        while ((width > widthLimit || (limitHeight && height > heightLimit)) && size > minPx) {
            size -= Math.max(0.5f * density, size * 0.06f);
            if (size < minPx) {
                size = minPx;
            }
            paint.setTextSize(size);
            width = paint.measureText(text, 0, text.length());
            height = paint.descent() - paint.ascent();
        }
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }
}
