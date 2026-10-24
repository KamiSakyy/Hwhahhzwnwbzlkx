package com.kamisakyy.cutpro;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** FrameLayout that keeps the selected project aspect ratio inside the available space. */
public class AspectFrameLayout extends FrameLayout {

    private float aspect = 16f / 9f;

    public AspectFrameLayout(Context context) {
        super(context);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAspectRatio(float ratio) {
        if (ratio > 0f && Math.abs(ratio - aspect) > 0.001f) {
            aspect = ratio;
            requestLayout();
        }
    }

    public float getAspectRatio() {
        return aspect;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        if (availableWidth <= 0 || availableHeight <= 0
                || widthMode == MeasureSpec.UNSPECIFIED
                || heightMode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int width = availableWidth;
        int height = Math.round(width / aspect);
        if (height > availableHeight) {
            height = availableHeight;
            width = Math.round(height * aspect);
        }
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, height), MeasureSpec.EXACTLY));
    }
}
