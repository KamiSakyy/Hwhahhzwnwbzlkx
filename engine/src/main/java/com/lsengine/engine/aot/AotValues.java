package com.lsengine.engine.aot;

/**
 * Представление значений LS в AOT-коде (NaN-боксинг, как в lsrt.h).
 */
public final class AotValues {

    private AotValues() {
    }

    public static final long BOX_MASK = 0xFFF8000000000000L;
    public static final long BOX_BASE = 0x7FF8000000000000L;
    public static final int TAG_SHIFT = 48;
    public static final long PAYLOAD_MASK = 0x0000FFFFFFFFFFFFL;

    public static final int TAG_NIL = 0;
    public static final int TAG_FALSE = 1;
    public static final int TAG_TRUE = 2;
    public static final int TAG_STR = 3;

    /*
     * Смещения полей объектов в куче рантайма. В начале каждого объекта
     * лежит заголовок сборщика мусора (24 байта), поэтому поля сдвинуты.
     * Значения совпадают с макросами LS_*_OFF в engine/src/main/cpp/lsrt.h.
     */
    public static final int OBJ_CELL_VALUE = 24;
    public static final int OBJ_CLOSURE_CODE = 24;
    public static final int OBJ_CLOSURE_UPVALS = 40;
    public static final int OBJ_NATIVE_FN = 24;
    public static final int TAG_TABLE = 4;
    public static final int TAG_FUNC = 5;
    public static final int TAG_ITER = 6;
    public static final int TAG_NATIVE = 7;

    public static final long NIL = BOX_BASE | ((long) TAG_NIL << TAG_SHIFT);
    public static final long TRUE = BOX_BASE | ((long) TAG_TRUE << TAG_SHIFT);
    public static final long FALSE = BOX_BASE | ((long) TAG_FALSE << TAG_SHIFT);

    /** Битовое представление double. */
    public static long num(double d) {
        return Double.doubleToRawLongBits(d);
    }

    /** Значение как double. */
    public static double asNum(long v) {
        return Double.longBitsToDouble(v);
    }
}
