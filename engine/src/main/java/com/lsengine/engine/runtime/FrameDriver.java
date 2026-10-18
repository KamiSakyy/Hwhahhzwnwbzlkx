package com.lsengine.engine.runtime;

/**
 * Игровой цикл: источник кадров и ввода.
 *
 * Реализуется как виртуальной машиной (LsRuntime), так и нативным AOT-рантаймом
 * (AotHost) — приложение не знает, что именно исполняется.
 */
public interface FrameDriver {

    /** Один кадр: время в секундах с момента запуска. */
    void frame(double nowSeconds);

    /** Касание экрана: координаты и действие (down/up/move). */
    void onTouch(float x, float y, int action);
}
