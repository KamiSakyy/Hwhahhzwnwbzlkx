package com.kamisakyy.cutpro;

import android.content.Context;
import android.graphics.Bitmap;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scrollable, zoomable clip strip with thumbnails and a playhead. */
public class TimelineView extends View {

    public interface Listener {
        void onSeekTo(long timeMs, int clipIndex);

        void onClipSelected(int clipIndex);
    }

    private static final int[] CLIP_COLORS = {
            0xFF1E3A36, 0xFF2A2438, 0xFF33262A, 0xFF22303A, 0xFF2E2A20
    };

    private final Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect();
    private final Path clipPath = new Path();

    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int pad;
    private final int touchSlop;

    private Project project;
    private int selectedIndex = -1;
    private long playheadMs;
    private float pxPerMs = 0.05f;
    private int scrollX;
    private Listener listener;

    private float downX;
    private float lastX;
    private boolean dragging;

    public TimelineView(Context context) {
        this(context, null);
    }

    public TimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        pad = dp(10);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(10));
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);

        playheadPaint.setColor(0xFF00E5A0);
        playheadPaint.setStyle(Paint.Style.FILL);

        scrimPaint.setColor(0x99000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF00E5A0);
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
        recomputeScale();
        invalidate();
    }

    public void setSelectedIndex(int index) {
        if (selectedIndex != index) {
            selectedIndex = index;
            invalidate();
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setPlayhead(long timeMs) {
        if (Math.abs(playheadMs - timeMs) < 8) {
            return;
        }
        playheadMs = Math.max(0, timeMs);
        invalidate();
    }

    public long getPlayhead() {
        return playheadMs;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void ensureVisible(long timeMs) {
        if (getWidth() <= 0) {
            return;
        }
        float x = pad + timeMs * pxPerMs;
        int margin = (int) dp(26);
        if (x < scrollX + margin) {
            scrollX = (int) (x - margin);
        } else if (x > scrollX + getWidth() - margin) {
            scrollX = (int) (x - getWidth() + margin);
        }
        clampScroll();
        invalidate();
    }

    private void recomputeScale() {
        int available = getWidth() - pad * 2;
        long total = project == null ? 0 : project.durationMs();
        if (available <= 0 || total <= 0) {
            return;
        }
        float fit = available / (float) total;
        pxPerMs = Math.max(fit, dp(46) / 1000f);
        clampScroll();
    }

    private void clampScroll() {
        int content = contentWidth();
        int max = Math.max(0, content - getWidth());
        if (scrollX < 0) {
            scrollX = 0;
        }
        if (scrollX > max) {
            scrollX = max;
        }
    }

    private int contentWidth() {
        if (project == null) {
            return 0;
        }
        return (int) (pad * 2 + project.durationMs() * pxPerMs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeScale();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (project == null || project.clips.isEmpty()) {
            return;
        }

        float top = dp(8);
        float bottom = getHeight() - dp(8);
        float radius = dp(9);
        long time = 0;

        for (int i = 0; i < project.clips.size(); i++) {
            Project.Clip clip = project.clips.get(i);
            long duration = clip.outputDurationMs();
            float x = pad + time * pxPerMs - scrollX;
            float width = Math.max(dp(30), duration * pxPerMs);
            rect.set(x, top, x + width, bottom);

            if (rect.right > 0 && rect.left < getWidth()) {
                clipPaint.setColor(CLIP_COLORS[i % CLIP_COLORS.length]);
                canvas.drawRoundRect(rect, radius, radius, clipPaint);

                Bitmap bitmap = thumbs.get(clip.uri);
                if (bitmap != null && !bitmap.isRecycled()) {
                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                    canvas.clipPath(clipPath);
                    drawCenterCrop(canvas, bitmap, rect);
                    canvas.drawRect(rect.left, rect.bottom - dp(22), rect.right, rect.bottom,
                            scrimPaint);
                    canvas.restore();
                } else {
                    canvas.drawRect(rect.left, rect.bottom - dp(22), rect.right, rect.bottom,
                            scrimPaint);
                }

                if (i == selectedIndex) {
                    borderPaint.setStrokeWidth(dp(2));
                    canvas.drawRoundRect(rect, radius, radius, borderPaint);
                }

                canvas.save();
                canvas.clipRect(rect.left + dp(4), rect.top,
                        rect.right - dp(4), rect.bottom);
                canvas.drawText("#" + (i + 1), rect.left + dp(7), rect.top + dp(19), textPaint);
                canvas.drawText(formatDuration(duration), rect.left + dp(7),
                        rect.bottom - dp(7), textPaint);
                canvas.restore();
            }
            time += duration;
        }

        float playheadX = pad + playheadMs * pxPerMs - scrollX;
        if (playheadX >= 0 && playheadX <= getWidth()) {
            canvas.drawRect(playheadX - dp(1), 0, playheadX + dp(1), getHeight(), playheadPaint);
            canvas.drawCircle(playheadX, dp(7), dp(5), playheadPaint);
        }
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target) {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float targetAspect = target.width() / target.height();
        float sourceAspect = bw / bh;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (project == null || project.clips.isEmpty()) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                lastX = downX;
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - lastX;
                if (Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    scrollX -= (int) dx;
                    clampScroll();
                    invalidate();
                }
                lastX = event.getX();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (!dragging) {
                    float x = event.getX() + scrollX - pad;
                    long time = (long) (x / pxPerMs);
                    time = Math.max(0, Math.min(time, project.durationMs()));
                    int index = project.clipIndexAt(time);
                    if (index >= 0) {
                        selectedIndex = index;
                        invalidate();
                        if (listener != null) {
                            listener.onSeekTo(time, index);
                            listener.onClipSelected(index);
                        }
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void loadThumbnail(final String uriString) {
        if (uriString == null || thumbs.containsKey(uriString)) {
            return;
        }
        final Context appContext = getContext().getApplicationContext();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    bitmap = appContext.getContentResolver().loadThumbnail(
                            Uri.parse(uriString), new Size(320, 180), null);
                } catch (Throwable ignored) {
                }
                if (bitmap != null) {
                    final Bitmap result = bitmap;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            thumbs.put(uriString, result);
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long tenths = (ms % 1000L) / 100L;
        return String.format(java.util.Locale.US, "%d:%02d.%d", minutes, seconds, tenths);
    }
}
