package com.lsengine.engine.aot;

/**
 * Платформа для нативного AOT-рантайма.
 *
 * Реализуется на стороне приложения: рисование, ввод, время, размеры окна.
 * Обратные вызовы идут из машинного кода сгенерированной библиотеки.
 */
public interface LsPlatform {

    void clear(int color);

    void rect(float x, float y, float w, float h, int color);

    void circle(float x, float y, float r, int color);

    void line(float x1, float y1, float x2, float y2, int color);

    void text(float x, float y, String text, float size, int color);

    float inputX();

    float inputY();

    boolean inputDown();

    boolean inputPressed();

    boolean inputReleased();

    double timeNow();

    long timeFrame();

    double timeDt();

    boolean uiButton(float x, float y, float w, float h, String label);

    void beep(int frequency, int millis);

    int appWidth();

    int appHeight();
}
