package com.lsengine.studio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.lsengine.engine.runtime.FrameDriver;
import com.lsengine.engine.runtime.LsRuntime;

/**
 * Поверхность предпросмотра: рисует кадры приложения на LS и передаёт касания
 * в движок. Работает в отдельном потоке, как настоящий игровой цикл.
 */
public class LsSurfaceView extends SurfaceView implements SurfaceHolder.Callback, LsRuntime.Surface {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private FrameDriver driver;
    private Thread renderThread;
    private volatile boolean running;
    private volatile Canvas currentCanvas;
    private int surfaceWidth;
    private int surfaceHeight;
    private float density = 1f;

    public LsSurfaceView(Context context) {
        super(context);
        init();
    }

    public LsSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        getHolder().addCallback(this);
        setFocusable(true);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** Источник кадров: виртуальная машина или нативный AOT-рантайм. */
    public void setDriver(FrameDriver driver) {
        this.driver = driver;
    }

    public FrameDriver driver() {
        return driver;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startRendering();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRendering();
    }

    public void startRendering() {
        if (renderThread != null) {
            return;
        }
        running = true;
        renderThread = new Thread(() -> {
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = (now - last) / 1_000_000_000f;
                last = now;
                Canvas canvas = null;
                try {
                    canvas = getHolder().lockCanvas();
                    if (canvas != null) {
                        currentCanvas = canvas;
                        if (driver != null) {
                            driver.frame(now / 1_000_000_000.0);
                        }
                    }
                } catch (Exception ignored) {
                    // Поверхность могла быть уничтожена — просто пропускаем кадр.
                } finally {
                    if (canvas != null) {
                        try {
                            getHolder().unlockCanvasAndPost(canvas);
                        } catch (Exception ignored) {
                            // игнорируем
                        }
                    }
                    currentCanvas = null;
                }
                long sleep = 16_666_667L - (System.nanoTime() - now);
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "ls-render");
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.start();
    }

    public void stopRendering() {
        running = false;
        if (renderThread != null) {
            renderThread.interrupt();
            renderThread = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (driver == null) {
            return true;
        }
        int action = event.getActionMasked();
        int mapped;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            mapped = LsRuntime.TOUCH_DOWN;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            mapped = LsRuntime.TOUCH_UP;
        } else {
            mapped = LsRuntime.TOUCH_MOVE;
        }
        driver.onTouch(event.getX(), event.getY(), mapped);
        return true;
    }

    // ---------------- LsRuntime.Surface ----------------

    @Override
    public void clear(int color) {
        Canvas canvas = currentCanvas;
        if (canvas == null) {
            return;
        }
        canvas.drawColor(color);
    }

    @Override
    public void rect(float x, float y, float w, float h, int color) {
        Canvas canvas = currentCanvas;
        if (canvas == null) {
            return;
        }
        paint.setColor(color);
        canvas.drawRect(x, y, x + w, y + h, paint);
    }

    @Override
    public void circle(float x, float y, float r, int color) {
        Canvas canvas = currentCanvas;
        if (canvas == null) {
            return;
        }
        paint.setColor(color);
        canvas.drawCircle(x, y, r, paint);
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, int color) {
        Canvas canvas = currentCanvas;
        if (canvas == null) {
            return;
        }
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1f, density));
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    @Override
    public void text(float x, float y, String text, float size, int color) {
        Canvas canvas = currentCanvas;
        if (canvas == null || text == null) {
            return;
        }
        textPaint.setColor(color);
        textPaint.setTextSize(size);
        canvas.drawText(text, x, y, textPaint);
    }

    @Override
    public int width() {
        return surfaceWidth > 0 ? surfaceWidth : getWidth();
    }

    @Override
    public int height() {
        return surfaceHeight > 0 ? surfaceHeight : getHeight();
    }

    /** Цвет по умолчанию для фона. */
    public static int defaultBackground() {
        return Color.rgb(16, 16, 24);
    }
}
