package com.kamisakyy.cutpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CapCut-style timeline: fixed centre playhead, drag anywhere to scrub, pinch to zoom,
 * and a dedicated trim mode with two draggable handles on the source strip.
 */
public class TimelineView extends View {

    public interface Listener {
        /** Tap on the strip: jump to this project time. */
        void onSeek(long timeMs);

        /** Continuous scrub while the finger is down. */
        void onScrub(long timeMs);

        void onClipSelected(int index);

        /** Trim handle drag. {@code finished} is true on finger up. */
        void onTrim(int index, long trimStartMs, long trimEndMs, boolean finished);

        /** Preview a source frame inside the clip while trimming. */
        void onTrimPreview(int index, long sourceMs);

        /** Tapped the music lane under the strip. */
        void onMusicTap();

        /** Clip dragged to another position on the strip. */
        void onReorder(int fromIndex, int toIndex);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_SCROLL = 1;
    private static final int MODE_TRIM_LEFT = 2;
    private static final int MODE_TRIM_RIGHT = 3;
    private static final int MODE_EDGE_LEFT = 4;
    private static final int MODE_EDGE_RIGHT = 5;

    private static final int[] CLIP_COLORS = {
            0xFF1B2B2A, 0xFF241F2E, 0xFF2C2124, 0xFF1D2730, 0xFF28241B
    };

    private final Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF tileRect = new RectF();
    private final RectF trimRect = new RectF();
    private final Rect srcRect = new Rect();
    private final Path clipPath = new Path();

    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;
    private final int touchSlop;

    private Project project;
    private int selectedIndex = -1;
    private long positionMs;
    private float pxPerMs = 0.06f;
    private Listener listener;

    private boolean trimMode;
    private float trimPad;
    private float trimPx;
    private long trimSourceMs = 1;
    private float trimXStart;
    private float trimXEnd;
    private long trimStartMs;
    private long trimEndMs;

    private boolean musicTap;
    private boolean reordering;
    private int reorderFrom = -1;
    private int reorderTo = -1;
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (touchMode != MODE_NONE || project == null || project.clips.isEmpty()) {
                return;
            }
            int index = indexAtX(downX);
            if (index < 0) {
                return;
            }
            reordering = true;
            reorderFrom = index;
            reorderTo = index;
            selectedIndex = index;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    private long mainTrimStart;
    private long mainTrimEnd;
    private int mainTrimIndex = -1;

    private int touchMode = MODE_NONE;
    private float downX;
    private float lastX;
    private boolean moved;
    private boolean userScrolling;

    private final VelocityTracker velocityTracker = VelocityTracker.obtain();
    private boolean flinging;
    private double flingVelocity;
    private long flingLastMs;
    private long lastHapticMs;
    private long lastSnapValue = Long.MIN_VALUE;
    private long lastUpTime;
    private float lastUpX;
    private boolean doubleTap;
    private float dragChipX;
    private String dragChipText;

    /** Inertia after a flick: keeps gliding so long projects are easy to reach. */
    private final Runnable flingStep = new Runnable() {
        @Override
        public void run() {
            if (!flinging) {
                return;
            }
            long now = System.currentTimeMillis();
            long dt = Math.max(1, Math.min(48, now - flingLastMs));
            flingLastMs = now;
            positionMs += (long) (flingVelocity * dt / 1000d);
            flingVelocity *= 0.9d;
            clampPosition();
            invalidate();
            if (listener != null) {
                listener.onScrub(positionMs);
            }
            boolean atStart = positionMs <= 0;
            boolean atEnd = project != null && positionMs >= project.durationMs();
            if (Math.abs(flingVelocity) < 70 || atStart || atEnd) {
                flinging = false;
                userScrolling = false;
                if (listener != null) {
                    listener.onSeek(positionMs);
                }
                return;
            }
            postOnAnimation(this);
        }
    };

    public TimelineView(Context context) {
        this(context, null);
    }

    public TimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        textPaint.setColor(0xFFF2F2F5);
        textPaint.setTextSize(dp(9));
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);

        rulerPaint.setColor(0xFF5C5C66);
        rulerPaint.setTextSize(dp(8));

        playheadPaint.setColor(0xFF00E5A0);

        scrimPaint.setColor(0xB3000000);
        dimPaint.setColor(0xCC0A0A0C);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF00E5A0);

        handlePaint.setColor(0xFF00E5A0);
        gripPaint.setColor(0xFF06231B);
        gripPaint.setStrokeWidth(dp(1.2f));
        gripPaint.setStrokeCap(Paint.Cap.ROUND);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (trimMode) {
                            return false;
                        }
                        float focusX = detector.getFocusX();
                        float cx = getWidth() / 2f;
                        double focusTime = positionMs + (focusX - cx) / pxPerMs;
                        pxPerMs = Math.max(0.012f, Math.min(0.9f,
                                pxPerMs * detector.getScaleFactor()));
                        positionMs = (long) (focusTime - (focusX - cx) / pxPerMs);
                        clampPosition();
                        invalidate();
                        if (listener != null) {
                            listener.onScrub(positionMs);
                        }
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setProject(Project project) {
        this.project = project;
        if (project != null) {
            for (Project.Clip clip : project.clips) {
                loadThumbnail(clip.uri);
            }
        }
        clampPosition();
        invalidate();
    }

    public void setSelectedIndex(int index) {
        if (selectedIndex != index) {
            selectedIndex = index;
            syncTrimBounds();
            invalidate();
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setTrimMode(boolean enabled) {
        if (trimMode != enabled) {
            trimMode = enabled;
            syncTrimBounds();
            invalidate();
        }
    }

    public boolean isTrimMode() {
        return trimMode;
    }

    public void setPosition(long timeMs) {
        if (userScrolling) {
            return;
        }
        long clamped = Math.max(0, timeMs);
        if (project != null) {
            clamped = Math.min(clamped, project.durationMs());
        }
        if (Math.abs(clamped - positionMs) < 4) {
            return;
        }
        positionMs = clamped;
        invalidate();
    }

    public long getPosition() {
        return positionMs;
    }

    private void clampPosition() {
        if (project == null) {
            positionMs = 0;
            return;
        }
        positionMs = Math.max(0, Math.min(positionMs, project.durationMs()));
    }

    private void syncTrimBounds() {
        Project.Clip clip = selectedClip();
        if (clip == null) {
            trimStartMs = 0;
            trimEndMs = 0;
            return;
        }
        trimStartMs = Math.max(0, clip.trimStartMs);
        trimEndMs = Math.max(trimStartMs + 1, clip.trimEndMs);
    }

    private Project.Clip selectedClip() {
        if (project == null || selectedIndex < 0 || selectedIndex >= project.clips.size()) {
            return null;
        }
        return project.clips.get(selectedIndex);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        textPaint.setColor(0xFFF2F2F5);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(9));
        if (project == null) {
            return;
        }
        if (trimMode) {
            drawTrimMode(canvas);
        } else {
            drawTimeline(canvas);
        }
    }

    private void drawTimeline(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float rulerBottom = dp(16);
        float top = rulerBottom + dp(3);
        boolean hasMusic = project.musicUri != null && !project.musicUri.isEmpty();
        float bottom = h - dp(hasMusic ? 24 : 4);
        long duration = project.durationMs();

        // Ruler
        long visibleStart = positionMs - (long) (cx / pxPerMs);
        long visibleEnd = positionMs + (long) ((w - cx) / pxPerMs);
        long step = pxPerMs >= 0.25f ? 500L : pxPerMs >= 0.09f ? 1000L : 5000L;
        long first = Math.max(0, (visibleStart / step) * step);
        for (long t = first; t <= visibleEnd; t += step) {
            float x = cx + (t - positionMs) * pxPerMs;
            float tickHeight = (t % (step * 5) == 0) ? dp(5) : dp(3);
            canvas.drawRect(x - dp(0.5f), rulerBottom - tickHeight, x + dp(0.5f),
                    rulerBottom, rulerPaint);
            if (t % (step * 5) == 0 && t <= duration) {
                canvas.drawText(shortTime(t), x + dp(3), rulerBottom - dp(1.5f), rulerPaint);
            }
        }
        canvas.drawRect(0, rulerBottom + dp(0.5f), w, rulerBottom + dp(1), rulerPaint);

        if (project.clips.isEmpty()) {
            textPaint.setColor(0xFF8A8A96);
            textPaint.setTextSize(dp(10));
            textPaint.setFakeBoldText(false);
            canvas.drawText("Добавьте видео или фото кнопкой «+»", dp(14), top + dp(22), textPaint);
            textPaint.setColor(0xFFF2F2F5);
            textPaint.setFakeBoldText(true);
            textPaint.setTextSize(dp(9));
            drawPlayhead(canvas, cx, h, rulerBottom);
            return;
        }

        float radius = dp(7);
        for (int i = 0; i < project.clips.size(); i++) {
            Project.Clip clip = project.clips.get(i);
            long clipStart = project.startOfClip(i);
            long clipDuration = clip.outputDurationMs();
            float x = cx + (clipStart - positionMs) * pxPerMs;
            float width = Math.max(dp(16), clipDuration * pxPerMs);
            float right = x + width;
            if (right < -dp(8) || x > w + dp(8)) {
                continue;
            }
            rect.set(x, top, right, bottom);
            clipPaint.setColor(CLIP_COLORS[i % CLIP_COLORS.length]);
            canvas.drawRoundRect(rect, radius, radius, clipPaint);

            Bitmap bitmap = thumbs.get(normalize(clip.uri));
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                drawTiles(canvas, bitmap, rect, dp(18));
                canvas.restore();
            }

            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawRect(x, bottom - dp(15), right, bottom, scrimPaint);
            canvas.restore();

            if (reordering && i == reorderFrom) {
                clipPaint.setColor(0x6600E5A0);
                canvas.drawRoundRect(rect, radius, radius, clipPaint);
            }
            if (i == selectedIndex && !trimMode) {
                borderPaint.setStrokeWidth(dp(2f));
                canvas.drawRoundRect(rect, radius, radius, borderPaint);
                if (!reordering) {
                    drawEdgeHandles(canvas, rect, top, bottom);
                }
            }

            if (width > dp(34)) {
                canvas.save();
                canvas.clipRect(x + dp(5), top, right - dp(4), bottom - dp(2));
                canvas.drawText("#" + (i + 1), x + dp(6), bottom - dp(5), textPaint);
                canvas.restore();
            }
            if (width > dp(46) && Math.abs(clip.speed - 1f) > 0.01f) {
                String badge = speedLabel(clip.speed);
                textPaint.setTextSize(dp(8));
                float badgeWidth = textPaint.measureText(badge) + dp(8);
                float badgeRight = right - dp(4);
                rect.set(badgeRight - badgeWidth, top + dp(3), badgeRight, top + dp(15));
                clipPaint.setColor(0xCC00E5A0);
                canvas.drawRoundRect(rect, dp(4), dp(4), clipPaint);
                textPaint.setColor(0xFF06231B);
                canvas.drawText(badge, rect.left + dp(4), rect.bottom - dp(3), textPaint);
                textPaint.setColor(0xFFF2F2F5);
                textPaint.setTextSize(dp(9));
            }
        }

        if (reordering && reorderTo >= 0) {
            float markerX = reorderTo >= project.clips.size()
                    ? boundaryX(project.clips.size())
                    : boundaryX(reorderTo) - (reorderTo > reorderFrom ? 0 : dp(2));
            canvas.drawRect(markerX - dp(1.5f), top, markerX + dp(1.5f), bottom, playheadPaint);
        }
        if (hasMusic) {
            drawMusicLane(canvas, rulerBottom, bottom, h, cx);
        }
        drawPlayhead(canvas, cx, h, rulerBottom);
        drawDragChip(canvas, Math.max(dp(28), Math.min(getWidth() - dp(28), dragChipX)),
                Math.max(top + dp(16), rulerBottom + dp(18)));
    }

    /** Floating label with the trim length while a handle is being dragged. */
    private void drawDragChip(Canvas canvas, float x, float y) {
        if (dragChipText == null) {
            return;
        }
        textPaint.setTextSize(dp(10));
        textPaint.setFakeBoldText(true);
        float width = textPaint.measureText(dragChipText) + dp(16);
        rect.set(x - width / 2f, y - dp(11), x + width / 2f, y + dp(11));
        clipPaint.setColor(0xF200E5A0);
        canvas.drawRoundRect(rect, dp(9), dp(9), clipPaint);
        textPaint.setColor(0xFF06231B);
        canvas.drawText(dragChipText, rect.centerX() - textPaint.measureText(dragChipText) / 2f,
                rect.centerY() + dp(3.5f), textPaint);
        textPaint.setColor(0xFFF2F2F5);
        textPaint.setTextSize(dp(9));
    }

    private static String speedLabel(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.05f) {
            return ((int) Math.round(speed)) + "x";
        }
        return String.format(java.util.Locale.US, "%.1fx", speed);
    }

    private void drawEdgeHandles(Canvas canvas, RectF clipRect, float top, float bottom) {
        float width = dp(4.5f);
        float height = dp(24);
        float cy = (top + bottom) / 2f;
        rect.set(clipRect.left - width / 2f, cy - height / 2f,
                clipRect.left + width / 2f, cy + height / 2f);
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), handlePaint);
        rect.set(clipRect.right - width / 2f, cy - height / 2f,
                clipRect.right + width / 2f, cy + height / 2f);
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), handlePaint);
    }

    private void drawMusicLane(Canvas canvas, float rulerBottom, float stripBottom,
                               float h, float cx) {
        float top = stripBottom + dp(3);
        float bottom = h - dp(3);
        rect.set(dp(2), top, getWidth() - dp(2), bottom);
        clipPaint.setColor(0xFF16241F);
        canvas.drawRoundRect(rect, dp(4), dp(4), clipPaint);

        String name = project.musicName == null ? "Музыка" : project.musicName;
        canvas.save();
        canvas.clipRect(rect);
        float barWidth = dp(2);
        float gap = dp(2);
        int index = 0;
        for (float x = rect.left + dp(2); x < rect.right - dp(2); x += barWidth + gap) {
            float phase = (x + positionMs * pxPerMs * 0.35f) * 0.11f;
            float wave = (float) (Math.sin(phase) * 0.5 + Math.sin(phase * 2.3) * 0.3);
            float barHeight = Math.max(dp(2), (rect.height() - dp(4)) * (0.35f + Math.abs(wave) * 0.6f));
            float centerY = rect.centerY();
            gripPaint.setColor(index % 5 == 0 ? 0xFF00E5A0 : 0xFF2E7A63);
            canvas.drawRect(x, centerY - barHeight / 2f, x + barWidth,
                    centerY + barHeight / 2f, gripPaint);
            index++;
        }
        canvas.restore();
        gripPaint.setColor(0xFF06231B);

        textPaint.setTextSize(dp(8));
        textPaint.setColor(0xFFBFEFE0);
        canvas.save();
        canvas.clipRect(rect.left + dp(4), rect.top, rect.right - dp(4), rect.bottom);
        canvas.drawText(name, rect.left + dp(5), rect.bottom - dp(3), textPaint);
        canvas.restore();
        textPaint.setColor(0xFFF2F2F5);
        textPaint.setTextSize(dp(9));
    }

    private void drawPlayhead(Canvas canvas, float cx, float h, float rulerBottom) {
        canvas.drawRect(cx - dp(0.8f), rulerBottom, cx + dp(0.8f), h - dp(2), playheadPaint);
        canvas.drawRoundRect(cx - dp(4), rulerBottom - dp(1), cx + dp(4), rulerBottom + dp(7),
                dp(2), dp(2), playheadPaint);
    }

    private void drawTiles(Canvas canvas, Bitmap bitmap, RectF target, float minTile) {
        float tileWidth = Math.max(minTile, target.height() * 1.6f);
        float x = target.left;
        while (x < target.right) {
            float width = Math.min(tileWidth, target.right - x);
            tileRect.set(x, target.top, x + width, target.bottom);
            drawCenterCrop(canvas, bitmap, tileRect);
            x += tileWidth;
        }
    }

    private void drawTrimMode(Canvas canvas) {
        Project.Clip clip = selectedClip();
        float w = getWidth();
        float h = getHeight();
        float top = dp(18);
        float bottom = h - dp(24);

        if (clip == null) {
            textPaint.setColor(0xFF8A8A96);
            textPaint.setFakeBoldText(false);
            textPaint.setTextSize(dp(10));
            canvas.drawText("Выберите клип", dp(14), top + dp(18), textPaint);
            return;
        }

        long source = Math.max(1000, clip.sourceDurationMs);
        trimSourceMs = source;
        trimPad = dp(16);
        trimPx = Math.max(0.0001f, (w - trimPad * 2) / source);
        trimXStart = trimPad + trimStartMs * trimPx;
        trimXEnd = trimPad + trimEndMs * trimPx;

        trimRect.set(trimPad, top, w - trimPad, bottom);
        clipPaint.setColor(0xFF1B1B21);
        canvas.drawRoundRect(trimRect, dp(7), dp(7), clipPaint);

        Bitmap bitmap = thumbs.get(normalize(clip.uri));
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(trimRect, dp(7), dp(7), Path.Direction.CW);
        canvas.clipPath(clipPath);
        if (bitmap != null && !bitmap.isRecycled()) {
            drawTiles(canvas, bitmap, trimRect, dp(18));
        }
        canvas.drawRect(trimRect.left, trimRect.top, trimXStart, trimRect.bottom, dimPaint);
        canvas.drawRect(trimXEnd, trimRect.top, trimRect.right, trimRect.bottom, dimPaint);
        canvas.restore();

        for (float x : new float[]{trimXStart, trimXEnd}) {
            float left = x - dp(5);
            float right = x + dp(5);
            rect.set(left, trimRect.top, right, trimRect.bottom);
            canvas.drawRoundRect(rect, dp(3), dp(3), handlePaint);
            float gy1 = trimRect.centerY() - dp(8);
            for (int k = 0; k < 3; k++) {
                float gy = gy1 + k * dp(6);
                canvas.drawLine(left + dp(2.6f), gy, right - dp(2.6f), gy, gripPaint);
            }
        }

        textPaint.setTextSize(dp(9));
        textPaint.setFakeBoldText(true);
        textPaint.setColor(0xFFF2F2F5);
        canvas.drawText(shortTime(trimStartMs), trimRect.left, trimRect.bottom + dp(14), textPaint);
        String end = shortTime(trimEndMs);
        canvas.drawText(end, trimRect.right - textPaint.measureText(end),
                trimRect.bottom + dp(14), textPaint);
        String length = longTime(trimEndMs - trimStartMs);
        textPaint.setColor(0xFF00E5A0);
        canvas.drawText(length, trimRect.centerX() - textPaint.measureText(length) / 2f,
                trimRect.top - dp(5), textPaint);
        textPaint.setColor(0xFFF2F2F5);
        if (dragChipText != null) {
            drawDragChip(canvas,
                    Math.max(dp(30), Math.min(w - dp(30), dragChipX)),
                    trimRect.top - dp(16));
        }
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target) {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float targetAspect = target.width() / Math.max(1f, target.height());
        float sourceAspect = bw / Math.max(1f, bh);
        float cropW = bw;
        float cropH = bh;
        if (sourceAspect > targetAspect) {
            cropW = bh * targetAspect;
        } else {
            cropH = bw / targetAspect;
        }
        float cx = bw / 2f;
        float cy = bh / 2f;
        srcRect.set((int) (cx - cropW / 2f), (int) (cy - cropH / 2f),
                (int) (cx + cropW / 2f), (int) (cy + cropH / 2f));
        canvas.drawBitmap(bitmap, srcRect, target, null);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (project == null) {
            return false;
        }
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            return true;
        }
        if (trimMode) {
            return handleTrimTouch(event);
        }
        return handleTimelineTouch(event);
    }

    private boolean handleTimelineTouch(MotionEvent event) {
        float cx = getWidth() / 2f;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            flinging = false;
            removeCallbacks(flingStep);
        }
        velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                lastX = downX;
                moved = false;
                doubleTap = false;
                dragChipText = null;
                userScrolling = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                long now = System.currentTimeMillis();
                if (now - lastUpTime < 320 && Math.abs(downX - lastUpX) < dp(44)) {
                    // Double tap: toggle between overview and frame-accurate zoom.
                    doubleTap = true;
                    lastUpTime = 0;
                    pxPerMs = pxPerMs > 0.12f ? 0.05f : 0.22f;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    invalidate();
                    return true;
                }
                musicTap = project.musicUri != null && !project.musicUri.isEmpty()
                        && event.getY() > getHeight() - dp(26);
                if (musicTap) {
                    return true;
                }
                postDelayed(longPress, 300);
                int[] edges = selectedClipEdges();
                float reach = dp(26);
                if (edges != null && Math.abs(downX - cx) > dp(12)) {
                    if (Math.abs(downX - edges[0]) <= reach) {
                        touchMode = MODE_EDGE_LEFT;
                        mainTrimIndex = selectedIndex;
                        syncTrimBounds();
                        mainTrimStart = trimStartMs;
                        mainTrimEnd = trimEndMs;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        return true;
                    }
                    if (Math.abs(downX - edges[1]) <= reach) {
                        touchMode = MODE_EDGE_RIGHT;
                        mainTrimIndex = selectedIndex;
                        syncTrimBounds();
                        mainTrimStart = trimStartMs;
                        mainTrimEnd = trimEndMs;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        return true;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() > 1) {
                    removeCallbacks(longPress);
                    removeCallbacks(flingStep);
                    return true;
                }
                if (doubleTap || musicTap) {
                    return true;
                }
                if (reordering) {
                    int next = Math.max(0, Math.min(indexAtX(event.getX()),
                            project.clips.size() - 1));
                    if (next != reorderTo) {
                        reorderTo = next;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                    return true;
                }
                float travel = event.getX() - downX;
                if (Math.abs(travel) > dp(3)) {
                    removeCallbacks(longPress);
                }
                if (touchMode == MODE_EDGE_LEFT || touchMode == MODE_EDGE_RIGHT) {
                    Project.Clip clip = selectedClip();
                    if (clip == null || mainTrimIndex != selectedIndex) {
                        return true;
                    }
                    long delta = (long) ((event.getX() - downX) / pxPerMs
                            * Math.max(0.1f, clip.speed));
                    moved = true;
                    if (touchMode == MODE_EDGE_LEFT) {
                        long raw = Math.max(0, mainTrimStart + delta);
                        raw = Math.min(raw, trimEndMs - 250);
                        trimStartMs = snapTrimEdge(raw, clip);
                    } else {
                        long raw = Math.min(clip.sourceDurationMs, mainTrimEnd + delta);
                        raw = Math.max(raw, trimStartMs + 250);
                        trimEndMs = snapTrimEdge(raw, clip);
                    }
                    float edgeX = touchMode == MODE_EDGE_LEFT
                            ? cx + (project.startOfClip(selectedIndex) - positionMs) * pxPerMs
                            : cx + (project.startOfClip(selectedIndex) - positionMs) * pxPerMs
                            + Math.max(dp(16), (trimEndMs - trimStartMs)
                            / Math.max(0.1f, clip.speed) * pxPerMs);
                    dragChipX = edgeX;
                    dragChipText = longTime(trimEndMs - trimStartMs);
                    invalidate();
                    if (listener != null) {
                        listener.onTrim(selectedIndex, trimStartMs, trimEndMs, false);
                    }
                    return true;
                }
                float dx = event.getX() - lastX;
                if (Math.abs(travel) > dp(2)) {
                    moved = true;
                }
                if (moved) {
                    positionMs -= (long) (dx / pxPerMs);
                    clampPosition();
                    invalidate();
                    if (listener != null) {
                        listener.onScrub(positionMs);
                    }
                }
                lastX = event.getX();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                removeCallbacks(longPress);
                dragChipText = null;
                if (doubleTap) {
                    doubleTap = false;
                    userScrolling = false;
                    invalidate();
                    return true;
                }
                if (musicTap) {
                    musicTap = false;
                    userScrolling = false;
                    if (listener != null) {
                        listener.onMusicTap();
                    }
                    return true;
                }
                if (reordering) {
                    int from = reorderFrom;
                    int to = reorderTo;
                    reordering = false;
                    reorderFrom = -1;
                    reorderTo = -1;
                    userScrolling = false;
                    if (from >= 0 && to >= 0 && from != to && listener != null) {
                        listener.onReorder(from, to);
                    }
                    invalidate();
                    return true;
                }
                if (touchMode == MODE_EDGE_LEFT || touchMode == MODE_EDGE_RIGHT) {
                    touchMode = MODE_NONE;
                    mainTrimIndex = -1;
                    userScrolling = false;
                    if (listener != null) {
                        listener.onTrim(selectedIndex, trimStartMs, trimEndMs, true);
                    }
                    invalidate();
                    return true;
                }
                if (!moved) {
                    long time = positionMs + (long) ((event.getX() - cx) / pxPerMs);
                    time = Math.max(0, Math.min(time, project.durationMs()));
                    positionMs = time;
                    int index = project.clipIndexAt(time);
                    if (index >= 0) {
                        selectedIndex = index;
                        if (listener != null) {
                            listener.onSeek(time);
                            listener.onClipSelected(index);
                        }
                    }
                    lastUpTime = System.currentTimeMillis();
                    lastUpX = event.getX();
                    userScrolling = false;
                } else {
                    velocityTracker.computeCurrentVelocity(1000);
                    float vx = velocityTracker.getXVelocity();
                    if (listener != null) {
                        listener.onSeek(positionMs);
                    }
                    int index = project.clipIndexAt(positionMs);
                    if (index >= 0 && index != selectedIndex) {
                        selectedIndex = index;
                        if (listener != null) {
                            listener.onClipSelected(index);
                        }
                    }
                    if (Math.abs(vx) > dp(240) && pxPerMs > 0f) {
                        flingVelocity = -vx / pxPerMs;
                        flingLastMs = System.currentTimeMillis();
                        flinging = true;
                        postOnAnimation(flingStep);
                    } else {
                        userScrolling = false;
                    }
                }
                velocityTracker.clear();
                syncTrimBounds();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                removeCallbacks(longPress);
                removeCallbacks(flingStep);
                reordering = false;
                musicTap = false;
                doubleTap = false;
                dragChipText = null;
                userScrolling = false;
                touchMode = MODE_NONE;
                velocityTracker.clear();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** Snaps a trim edge to project boundaries and the playhead, with a light tick. */
    private long snapTrimEdge(long sourceMs, Project.Clip clip) {
        if (project == null || clip == null) {
            return sourceMs;
        }
        long clipStart = project.startOfClip(selectedIndex);
        float speed = Math.max(0.1f, clip.speed);
        long tolerance = (long) (dp(10) / Math.max(0.0001f, pxPerMs) * speed);
        long best = sourceMs;
        long bestDelta = tolerance + 1;
        for (int i = 0; i <= project.clips.size(); i++) {
            long boundary = i == project.clips.size()
                    ? project.durationMs() : project.startOfClip(i);
            long candidate = clip.trimStartMs
                    + Math.round((boundary - clipStart) * speed);
            long delta = Math.abs(candidate - sourceMs);
            if (delta <= tolerance && delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }
        long playhead = clip.trimStartMs + Math.round((positionMs - clipStart) * speed);
        long delta = Math.abs(playhead - sourceMs);
        if (delta <= tolerance && delta < bestDelta) {
            bestDelta = delta;
            best = playhead;
        }
        if (best != sourceMs) {
            hapticSnap(best);
        }
        return best;
    }

    private void hapticSnap(long value) {
        if (value == lastSnapValue) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHapticMs < 70) {
            return;
        }
        lastHapticMs = now;
        lastSnapValue = value;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private int indexAtX(float x) {
        if (project == null || project.clips.isEmpty()) {
            return -1;
        }
        float cx = getWidth() / 2f;
        long time = positionMs + (long) ((x - cx) / pxPerMs);
        time = Math.max(0, Math.min(time, project.durationMs()));
        return project.clipIndexAt(time);
    }

    private float boundaryX(int clipIndex) {
        if (project == null) {
            return 0;
        }
        float cx = getWidth() / 2f;
        long time = project.startOfClip(clipIndex);
        return cx + (time - positionMs) * pxPerMs;
    }

    /** Screen x of the selected clip's left and right edges, or null when off screen. */
    private int[] selectedClipEdges() {
        Project.Clip clip = selectedClip();
        if (clip == null || project == null) {
            return null;
        }
        long start = project.startOfClip(selectedIndex);
        long duration = clip.outputDurationMs();
        float cx = getWidth() / 2f;
        float left = cx + (start - positionMs) * pxPerMs;
        float right = left + Math.max(dp(16), duration * pxPerMs);
        if (right < 0 || left > getWidth()) {
            return null;
        }
        return new int[]{(int) left, (int) right};
    }

    private boolean handleTrimTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                moved = false;
                dragChipText = null;
                if (Math.abs(downX - trimXStart) <= dp(30)) {
                    touchMode = MODE_TRIM_LEFT;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (Math.abs(downX - trimXEnd) <= dp(30)) {
                    touchMode = MODE_TRIM_RIGHT;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else {
                    touchMode = MODE_SCROLL;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (touchMode == MODE_TRIM_LEFT || touchMode == MODE_TRIM_RIGHT) {
                    long target = (long) ((event.getX() - trimPad) / trimPx);
                    Project.Clip clip = selectedClip();
                    if (clip == null) {
                        return true;
                    }
                    long tolerance = (long) (dp(9) / Math.max(0.0001f, trimPx));
                    if (Math.abs(target) <= tolerance) {
                        target = 0;
                        hapticSnap(0);
                    } else if (Math.abs(target - trimSourceMs) <= tolerance) {
                        target = trimSourceMs;
                        hapticSnap(trimSourceMs);
                    }
                    if (touchMode == MODE_TRIM_LEFT) {
                        long maxStart = Math.max(0, trimEndMs - 250);
                        trimStartMs = Math.max(0, Math.min(target, maxStart));
                        dragChipX = trimXStart;
                    } else {
                        long minEnd = Math.min(trimSourceMs, trimStartMs + 250);
                        trimEndMs = Math.max(minEnd, Math.min(target, trimSourceMs));
                        dragChipX = trimXEnd;
                    }
                    dragChipText = longTime(trimEndMs - trimStartMs);
                    invalidate();
                    if (listener != null) {
                        listener.onTrim(selectedIndex, trimStartMs, trimEndMs, false);
                        listener.onTrimPreview(selectedIndex,
                                touchMode == MODE_TRIM_LEFT ? trimStartMs : trimEndMs);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (touchMode == MODE_TRIM_LEFT || touchMode == MODE_TRIM_RIGHT) {
                    if (listener != null) {
                        listener.onTrim(selectedIndex, trimStartMs, trimEndMs, true);
                    }
                } else if (listener != null && selectedIndex >= 0) {
                    long source = (long) ((event.getX() - trimPad) / trimPx);
                    listener.onTrimPreview(selectedIndex, Math.max(0, source));
                }
                touchMode = MODE_NONE;
                dragChipText = null;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                touchMode = MODE_NONE;
                dragChipText = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------
    // Thumbnails
    // ------------------------------------------------------------------

    private static String normalize(String uri) {
        return uri == null ? "" : uri;
    }

    private void loadThumbnail(final String uriString) {
        final String key = normalize(uriString);
        if (key.isEmpty() || thumbs.containsKey(key)) {
            return;
        }
        thumbs.put(key, null);
        final Context appContext = getContext().getApplicationContext();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    bitmap = appContext.getContentResolver().loadThumbnail(
                            Uri.parse(key), new Size(360, 360), null);
                } catch (Throwable ignored) {
                }
                if (bitmap == null) {
                    // Photos imported from the picker live in app storage (file://).
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 2;
                        bitmap = BitmapFactory.decodeFile(Uri.parse(key).getPath(), options);
                    } catch (Throwable ignored) {
                    }
                }
                if (bitmap != null) {
                    final Bitmap result = bitmap;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            thumbs.put(key, result);
                            invalidate();
                        }
                    });
                }
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String shortTime(long ms) {
        long seconds = Math.max(0, ms) / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String longTime(long ms) {
        long total = Math.max(0, ms);
        return String.format(java.util.Locale.US, "%.1f с", total / 1000f);
    }
}
