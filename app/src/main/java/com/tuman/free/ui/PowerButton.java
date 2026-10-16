package com.tuman.free.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The big round power control.
 *
 * <p>Drawn by hand so it costs nothing in APK size: a soft glow, a gradient ring
 * and the power glyph. Three visual states - idle, working (a sweeping arc while
 * the tunnel is coming up) and active (a breathing gradient ring).
 */
public class PowerButton extends View {

    private static final int IDLE = 0;
    private static final int BUSY = 1;
    private static final int ON = 2;

    private static final int COL_ACCENT = 0xFF2DE2E6;
    private static final int COL_ACCENT2 = 0xFF5B8CFF;
    private static final int COL_TRACK = 0xFF1E2B47;
    private static final int COL_DISC_A = 0xFF16213A;
    private static final int COL_DISC_B = 0xFF0C1220;
    private static final int COL_GLYPH_OFF = 0xFF5C6E8C;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final RectF discRect = new RectF();
    private final RectF glyph = new RectF();

    private int mode = IDLE;
    private float phase = 0f;
    private ValueAnimator animator;

    public PowerButton(Context context) {
        super(context);
        init();
    }

    public PowerButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PowerButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(COL_TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    public void setIdle() {
        setMode(IDLE);
    }

    public void setBusy() {
        setMode(BUSY);
    }

    public void setActive() {
        setMode(ON);
    }

    public boolean isOn() {
        return mode == ON;
    }

    private void setMode(int next) {
        if (mode == next) return;
        mode = next;
        syncAnimator();
        invalidate();
    }

    private void syncAnimator() {
        boolean need = mode != IDLE && isAttachedToWindowCompat() && getWidth() > 0;
        if (need) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(mode == BUSY ? 900L : 2200L);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        phase = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
            }
            animator.setDuration(mode == BUSY ? 900L : 2200L);
            if (!animator.isStarted()) animator.start();
        } else if (animator != null) {
            animator.cancel();
        }
    }

    private boolean isAttachedToWindowCompat() {
        try {
            return isAttachedToWindow();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(cx, cy);
        if (r <= 1f) return;

        float stroke = r * 0.085f;
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        glyphPaint.setStrokeWidth(r * 0.085f);

        float ring = r - stroke;
        ringRect.set(cx - ring, cy - ring, cx + ring, cy + ring);

        float disc = ring - stroke * 1.6f;
        discRect.set(cx - disc, cy - disc, cx + disc, cy + disc);

        discPaint.setShader(new RadialGradient(cx, cy - disc * 0.35f, disc * 1.5f,
                COL_DISC_A, COL_DISC_B, Shader.TileMode.CLAMP));

        arcPaint.setShader(new SweepGradient(cx, cy,
                new int[]{COL_ACCENT, COL_ACCENT2, COL_ACCENT},
                new float[]{0f, 0.5f, 1f}));

        glowPaint.setShader(new RadialGradient(cx, cy, r,
                new int[]{0x552DE2E6, 0x222DE2E6, 0x00000000},
                new float[]{0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));

        syncAnimator();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        if (r <= 1f) return;

        if (mode == ON) {
            glowPaint.setAlpha((int) (120 + 70 * (0.5f + 0.5f * (float) Math.sin(phase * 6.283185f))));
            canvas.drawCircle(cx, cy, r, glowPaint);
        }

        canvas.drawOval(ringRect, trackPaint);

        if (mode == ON) {
            canvas.save();
            canvas.rotate(phase * 360f, cx, cy);
            canvas.drawArc(ringRect, 0f, 360f, false, arcPaint);
            canvas.restore();
        } else if (mode == BUSY) {
            canvas.save();
            canvas.rotate(phase * 360f, cx, cy);
            canvas.drawArc(ringRect, 0f, 110f, false, arcPaint);
            canvas.restore();
        }

        canvas.drawOval(discRect, discPaint);

        glyphPaint.setColor(mode == IDLE ? COL_GLYPH_OFF : COL_ACCENT);
        float ir = r * 0.30f;
        glyph.set(cx - ir, cy - ir, cx + ir, cy + ir);
        canvas.drawArc(glyph, -55f, 290f, false, glyphPaint);
        canvas.drawLine(cx, cy - ir * 1.12f, cx, cy - ir * 0.10f, glyphPaint);
    }
}
