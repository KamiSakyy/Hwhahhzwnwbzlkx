package com.lsengine.engine.runtime;

import com.lsengine.engine.aot.AotRuntime;
import com.lsengine.engine.aot.LsPlatform;

import java.util.ArrayList;
import java.util.List;

/**
 * Хост нативного AOT-рантайма.
 *
 * Здесь нет ни байт-кода, ни интерпретатора: программа уже скомпилирована в
 * машинный код, а оболочка лишь передаёт рисование, ввод и время.
 */
public final class AotHost implements LsPlatform, FrameDriver {

    private static final class Button {
        float x;
        float y;
        float w;
        float h;
        String label;
        boolean clicked;
    }

    private final AotRuntime runtime;
    private final List<Button> buttons = new ArrayList<>();
    private LsRuntime.Surface surface;
    private LsRuntime.Host host;
    private double lastTime;
    private double startTime;
    private int frameCount;
    private float touchX;
    private float touchY;
    private boolean touchDown;
    private boolean touchPressed;
    private boolean touchReleased;
    private boolean running;

    public AotHost(AotRuntime runtime) {
        this.runtime = runtime;
    }

    public void setSurface(LsRuntime.Surface surface) {
        this.surface = surface;
    }

    public void setHost(LsRuntime.Host host) {
        this.host = host;
    }

    /** Выполняет верхний уровень программы (точка входа машинного кода). */
    public void start() {
        startTime = System.nanoTime() / 1_000_000_000.0;
        lastTime = startTime;
        frameCount = 0;
        running = true;
        runtime.run();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
    }

    public int frameCount() {
        return frameCount;
    }

    /** Один кадр: update(dt), очистка экрана и draw(). */
    @Override
    public void frame(double nowSeconds) {
        if (!running) {
            return;
        }
        double dt = nowSeconds - lastTime;
        lastTime = nowSeconds;
        if (dt < 0) {
            dt = 0;
        }
        if (dt > 0.25) {
            dt = 0.25;
        }
        frameCount++;
        runtime.call("update", dt);
        if (surface != null) {
            surface.clear(0xFF101014);
        }
        runtime.call("draw");
    }

    /** Обработка касания экрана. */
    @Override
    public void onTouch(float x, float y, int action) {
        touchX = x;
        touchY = y;
        if (action == LsRuntime.TOUCH_DOWN) {
            touchDown = true;
            touchPressed = true;
        } else if (action == LsRuntime.TOUCH_UP) {
            touchDown = false;
            touchReleased = true;
            for (Button button : buttons) {
                if (inside(button, x, y)) {
                    button.clicked = true;
                }
            }
        } else if (action == LsRuntime.TOUCH_MOVE) {
            touchDown = true;
        }
    }

    private static boolean inside(Button button, float x, float y) {
        return x >= button.x && x <= button.x + button.w
                && y >= button.y && y <= button.y + button.h;
    }

    private Button findButton(String label) {
        for (Button button : buttons) {
            if (button.label.equals(label)) {
                return button;
            }
        }
        return null;
    }

    // ---------------- платформа ----------------

    @Override
    public void clear(int color) {
        if (surface != null) {
            surface.clear(color);
        }
    }

    @Override
    public void rect(float x, float y, float w, float h, int color) {
        if (surface != null) {
            surface.rect(x, y, w, h, color);
        }
    }

    @Override
    public void circle(float x, float y, float r, int color) {
        if (surface != null) {
            surface.circle(x, y, r, color);
        }
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, int color) {
        if (surface != null) {
            surface.line(x1, y1, x2, y2, color);
        }
    }

    @Override
    public void text(float x, float y, String text, float size, int color) {
        if (surface != null) {
            surface.text(x, y, text, size, color);
        }
    }

    @Override
    public float inputX() {
        return touchX;
    }

    @Override
    public float inputY() {
        return touchY;
    }

    @Override
    public boolean inputDown() {
        return touchDown;
    }

    @Override
    public boolean inputPressed() {
        boolean value = touchPressed;
        touchPressed = false;
        return value;
    }

    @Override
    public boolean inputReleased() {
        boolean value = touchReleased;
        touchReleased = false;
        return value;
    }

    @Override
    public double timeNow() {
        return System.nanoTime() / 1_000_000_000.0 - startTime;
    }

    @Override
    public long timeFrame() {
        return frameCount;
    }

    @Override
    public double timeDt() {
        return lastTime - startTime;
    }

    @Override
    public boolean uiButton(float x, float y, float w, float h, String label) {
        Button button = findButton(label);
        if (button == null) {
            button = new Button();
            button.label = label;
            buttons.add(button);
        }
        button.x = x;
        button.y = y;
        button.w = w;
        button.h = h;
        boolean clicked = button.clicked;
        button.clicked = false;
        return clicked;
    }

    @Override
    public void beep(int frequency, int millis) {
        if (host != null) {
            host.beep(frequency, millis);
        }
    }

    @Override
    public int appWidth() {
        return surface == null ? 0 : surface.width();
    }

    @Override
    public int appHeight() {
        return surface == null ? 0 : surface.height();
    }
}
