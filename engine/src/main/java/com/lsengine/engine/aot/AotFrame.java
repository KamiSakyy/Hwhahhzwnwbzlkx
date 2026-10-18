package com.lsengine.engine.aot;

/**
 * Общая раскладка кадра для всех AOT-бэкендов.
 */
public final class AotFrame {

    private AotFrame() {
    }

    public static int align16(int value) {
        return (value + 15) / 16 * 16;
    }

    /** Смещение локальной переменной относительно FP. */
    public static int localOffset(int slot) {
        return -(24 + 8 * slot);
    }

    /** Смещение слота с указателем на замыкание (корень GC). */
    public static int closureOffset(int localCount) {
        return -(24 + 8 * localCount);
    }

    /** Смещение вспомогательного слота (значение итератора). */
    public static int scratchOffset(int localCount) {
        return -(32 + 8 * localCount);
    }

    /** Начальное значение указателя стека значений. */
    public static int stackTop(int localCount) {
        return -(40 + 8 * localCount);
    }

    /** Размер кадра. */
    public static int frameSize(int localCount, int maxStack, int maxArgs) {
        int callArea = align16(16 + 8 * maxArgs);
        return align16(24 + 8 * localCount + 8 * maxStack + callArea);
    }

    /** Смещение исходящей области вызова относительно FP. */
    public static int callAreaBase(int frameSize) {
        return -(16 + frameSize);
    }
}
