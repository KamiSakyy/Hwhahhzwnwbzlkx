package com.lsengine.engine.runtime;

import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.vm.Builtins;
import com.lsengine.engine.vm.Table;
import com.lsengine.engine.vm.Value;
import com.lsengine.engine.vm.Vm;
import com.lsengine.engine.vm.VmException;

import java.util.ArrayList;
import java.util.List;

/**
 * Среда исполнения LS: виртуальная машина + API движка (рисование, ввод,
 * время, звук, простые UI-виджеты) + игровой цикл.
 *
 * Здесь нет ни «интерпретации исходника», ни Android-зависимостей: платформа
 * подключается через интерфейс {@link Surface}.
 */
public final class LsRuntime implements FrameDriver {

    public interface Surface {
        void clear(int color);

        void rect(float x, float y, float w, float h, int color);

        void circle(float x, float y, float r, int color);

        void line(float x1, float y1, float x2, float y2, int color);

        void text(float x, float y, String text, float size, int color);

        int width();

        int height();
    }

    public interface Host {
        void log(String message);

        void beep(int frequency, int millis);
    }

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int TOUCH_MOVE = 2;

    private static final class Button {
        float x;
        float y;
        float w;
        float h;
        String label;
        Value callback;
        boolean clicked;
    }

    private final Vm vm = new Vm();
    private final List<Button> buttons = new ArrayList<>();
    private Surface surface;
    private Host host;
    private String appName = "LS App";
    private String appVersion = "1.0";
    private double lastTime;
    private double startTime;
    private int frameCount;
    private float touchX;
    private float touchY;
    private boolean touchDown;
    private boolean touchPressed;
    private boolean touchReleased;
    private boolean running;

    public LsRuntime() {
        Builtins.install(vm);
        installRuntimeTables();
    }

    public Vm vm() {
        return vm;
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public void setAppInfo(String name, String version) {
        this.appName = name == null ? "LS App" : name;
        this.appVersion = version == null ? "1.0" : version;
    }

    public void setLogSink(java.util.function.Consumer<String> sink) {
        vm.setPrinter(sink::accept);
    }

    /** Загружает байт-код и выполняет верхний уровень программы. */
    public void load(Chunk chunk) {
        vm.load(chunk);
        startTime = System.nanoTime() / 1_000_000_000.0;
        lastTime = startTime;
        frameCount = 0;
        running = true;
        vm.start();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        vm.stop();
    }

    /** Один кадр: обновление состояния, update(dt), очистка экрана и draw(). */
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
        updateGlobals(dt, nowSeconds);

        Value update = vm.globals().get("update");
        if (update.isFunction() || update.isNative()) {
            try {
                vm.call(update, new Value[]{Value.of(dt)});
            } catch (VmException e) {
                log("update: " + e.getMessage());
                running = false;
                return;
            }
        }

        Value draw = vm.globals().get("draw");
        if (draw.isFunction() || draw.isNative()) {
            buttons.clear();
            if (surface != null) {
                surface.clear(0xFF101018);
            }
            try {
                vm.call(draw, new Value[0]);
            } catch (VmException e) {
                log("draw: " + e.getMessage());
                running = false;
                return;
            }
            drawButtons();
        }

        touchPressed = false;
        touchReleased = false;
    }

    private void drawButtons() {
        if (surface == null) {
            return;
        }
        for (Button b : buttons) {
            surface.rect(b.x, b.y, b.w, b.h, b.clicked ? 0xFF3D7BFF : 0xFF2A2A3C);
            surface.text(b.x + 16, b.y + b.h / 2f, b.label, Math.max(14, b.h * 0.42f), 0xFFFFFFFF);
        }
    }

    private void updateGlobals(double dt, double nowSeconds) {
        Table g = vm.globals();
        Table app = tableOf(g, "app");
        app.set("width", Value.of(surface == null ? 0 : surface.width()));
        app.set("height", Value.of(surface == null ? 0 : surface.height()));
        app.set("name", Value.of(appName));
        app.set("version", Value.of(appVersion));

        Table time = tableOf(g, "time");
        time.set("now", Value.of(nowSeconds - startTime));
        time.set("dt", Value.of(dt));
        time.set("frame", Value.of((double) frameCount));

        Table input = tableOf(g, "input");
        input.set("x", Value.of((double) touchX));
        input.set("y", Value.of((double) touchY));
        input.set("down", Value.of(touchDown));
        input.set("pressed", Value.of(touchPressed));
        input.set("released", Value.of(touchReleased));
    }

    private Table tableOf(Table g, String name) {
        Value v = g.get(name);
        if (v.isTable()) {
            return v.asTable();
        }
        Table t = new Table();
        g.set(name, Value.of(t));
        return t;
    }

    /** Обработка касания. */
    public void onTouch(float x, float y, int action) {
        touchX = x;
        touchY = y;
        if (action == TOUCH_DOWN) {
            touchDown = true;
            touchPressed = true;
        } else if (action == TOUCH_UP) {
            touchDown = false;
            touchReleased = true;
        }
        updateGlobals(0, lastTime);

        Value handler = vm.globals().get("onTouch");
        if (handler.isFunction() || handler.isNative()) {
            String name = action == TOUCH_DOWN ? "down" : (action == TOUCH_UP ? "up" : "move");
            try {
                vm.call(handler, new Value[]{Value.of((double) x), Value.of((double) y), Value.of(name)});
            } catch (VmException e) {
                log("onTouch: " + e.getMessage());
            }
        }

        if (action == TOUCH_DOWN) {
            for (int i = buttons.size() - 1; i >= 0; i--) {
                Button b = buttons.get(i);
                if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) {
                    b.clicked = true;
                    if (b.callback != null && !b.callback.isNil()) {
                        try {
                            vm.call(b.callback, new Value[0]);
                        } catch (VmException e) {
                            log("кнопка: " + e.getMessage());
                        }
                    }
                    break;
                }
            }
        }
    }

    public void log(String message) {
        if (host != null) {
            host.log(message);
        } else {
            vm.printLine(message);
        }
    }

    // ---------------- таблицы API движка ----------------

    private void installRuntimeTables() {
        Table g = vm.globals();

        Table app = new Table();
        app.set("width", Value.of(0));
        app.set("height", Value.of(0));
        app.set("name", Value.of(appName));
        app.set("version", Value.of(appVersion));
        g.set("app", Value.of(app));

        Table draw = new Table();
        draw.set("clear", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (surface != null) {
                surface.clear(color(args, 0, 0xFF000000));
            }
            return Value.nil();
        }));
        draw.set("rect", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (surface != null) {
                surface.rect(f(args, 0), f(args, 1), f(args, 2), f(args, 3), color(args, 4, 0xFFFFFFFF));
            }
            return Value.nil();
        }));
        draw.set("circle", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (surface != null) {
                surface.circle(f(args, 0), f(args, 1), f(args, 2), color(args, 3, 0xFFFFFFFF));
            }
            return Value.nil();
        }));
        draw.set("line", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (surface != null) {
                surface.line(f(args, 0), f(args, 1), f(args, 2), f(args, 3), color(args, 4, 0xFFFFFFFF));
            }
            return Value.nil();
        }));
        draw.set("text", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (surface != null) {
                String text = args.length > 2 ? args[2].toString() : "";
                float size = args.length > 3 ? (float) args[3].asNumber(16) : 16f;
                surface.text(f(args, 0), f(args, 1), text, size, color(args, 4, 0xFFFFFFFF));
            }
            return Value.nil();
        }));
        g.set("draw", Value.of(draw));

        Table input = new Table();
        input.set("x", Value.of(0));
        input.set("y", Value.of(0));
        input.set("down", Value.of(false));
        input.set("pressed", Value.of(false));
        input.set("released", Value.of(false));
        g.set("input", Value.of(input));

        Table time = new Table();
        time.set("now", Value.of(0));
        time.set("dt", Value.of(0));
        time.set("frame", Value.of(0));
        g.set("time", Value.of(time));

        Table ui = new Table();
        ui.set("button", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            Button b = new Button();
            b.x = f(args, 0);
            b.y = f(args, 1);
            b.w = f(args, 2);
            b.h = f(args, 3);
            b.label = args.length > 4 ? args[4].toString() : "";
            b.callback = args.length > 5 ? args[5] : Value.nil();
            buttons.add(b);
            return Value.of(false);
        }));
        g.set("ui", Value.of(ui));

        Table sound = new Table();
        sound.set("beep", Value.of((com.lsengine.engine.vm.NativeFn) (v, args) -> {
            if (host != null) {
                host.beep(args.length > 0 ? (int) args[0].asNumber(440) : 440,
                        args.length > 1 ? (int) args[1].asNumber(120) : 120);
            }
            return Value.nil();
        }));
        g.set("sound", Value.of(sound));
    }

    private static float f(Value[] args, int i) {
        return i < args.length ? (float) args[i].asNumber(0) : 0f;
    }

    private static int color(Value[] args, int i, int fallback) {
        if (i >= args.length) {
            return fallback;
        }
        return ColorUtil.parse(args[i], fallback);
    }
}
