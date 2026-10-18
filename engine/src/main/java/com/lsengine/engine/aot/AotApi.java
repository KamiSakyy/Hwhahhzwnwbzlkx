package com.lsengine.engine.aot;

/**
 * Индексы функций нативного API (совпадают с перечислением в lsrt.h).
 */
public final class AotApi {

    private AotApi() {
    }

    public static final int PRINT = 0;
    public static final int TOSTRING = 1;
    public static final int TONUMBER = 2;
    public static final int TYPE = 3;
    public static final int PAIRS = 4;
    public static final int IPAIRS = 5;
    public static final int MATH_FLOOR = 6;
    public static final int MATH_CEIL = 7;
    public static final int MATH_ABS = 8;
    public static final int MATH_SQRT = 9;
    public static final int MATH_SIN = 10;
    public static final int MATH_COS = 11;
    public static final int MATH_TAN = 12;
    public static final int MATH_ATAN2 = 13;
    public static final int MATH_MIN = 14;
    public static final int MATH_MAX = 15;
    public static final int MATH_RANDOM = 16;
    public static final int STRING_LEN = 17;
    public static final int STRING_UPPER = 18;
    public static final int STRING_LOWER = 19;
    public static final int STRING_SUB = 20;
    public static final int STRING_REP = 21;
    public static final int STRING_FORMAT = 22;
    public static final int TABLE_INSERT = 23;
    public static final int TABLE_REMOVE = 24;
    public static final int TABLE_CONCAT = 25;
    public static final int GLOBALS = 26;
    public static final int NEW_TABLE = 27;
    public static final int TABLE_GET = 28;
    public static final int TABLE_SET = 29;
    public static final int INDEX_GET = 30;
    public static final int INDEX_SET = 31;
    public static final int ADD = 32;
    public static final int SUB = 33;
    public static final int MUL = 34;
    public static final int DIV = 35;
    public static final int MOD = 36;
    public static final int POW = 37;
    public static final int CONCAT = 38;
    public static final int LEN = 39;
    public static final int NEG = 40;
    public static final int NOT = 41;
    public static final int EQ = 42;
    public static final int LT = 43;
    public static final int LE = 44;
    public static final int TRUTHY = 45;
    public static final int FOR_PREP = 46;
    public static final int FOR_ITER = 47;
    public static final int NEW_CELL = 48;
    public static final int NEW_CLOSURE = 49;
    public static final int CELL_VALUE = 50;
    public static final int NE = 51;
    public static final int CONST_STRING = 52;
    public static final int CLOSURE_SET_UPVAL = 53;
    public static final int ERROR = 54;
    public static final int COUNT = 55;
}
