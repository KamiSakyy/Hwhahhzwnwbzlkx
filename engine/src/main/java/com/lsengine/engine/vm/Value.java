package com.lsengine.engine.vm;

/**
 * Значение виртуальной машины LS.
 */
public final class Value {

    public static final int NIL = 0;
    public static final int BOOL = 1;
    public static final int NUM = 2;
    public static final int STR = 3;
    public static final int TABLE = 4;
    public static final int FUNC = 5;
    public static final int NATIVE = 6;
    public static final int ITER = 7;

    private static final Value NIL_VALUE = new Value(NIL, 0, false, null, null);
    private static final Value TRUE_VALUE = new Value(BOOL, 0, true, null, null);
    private static final Value FALSE_VALUE = new Value(BOOL, 0, false, null, null);

    private final int type;
    private final double num;
    private final boolean bool;
    private final String str;
    private final Object obj;

    private Value(int type, double num, boolean bool, String str, Object obj) {
        this.type = type;
        this.num = num;
        this.bool = bool;
        this.str = str;
        this.obj = obj;
    }

    public static Value nil() {
        return NIL_VALUE;
    }

    public static Value of(boolean b) {
        return b ? TRUE_VALUE : FALSE_VALUE;
    }

    public static Value of(double d) {
        return new Value(NUM, d, false, null, null);
    }

    public static Value of(int i) {
        return new Value(NUM, i, false, null, null);
    }

    public static Value of(String s) {
        if (s == null) {
            return NIL_VALUE;
        }
        return new Value(STR, 0, false, s, null);
    }

    public static Value of(Table t) {
        return new Value(TABLE, 0, false, null, t);
    }

    public static Value of(Closure c) {
        return new Value(FUNC, 0, false, null, c);
    }

    public static Value of(NativeFn f) {
        return new Value(NATIVE, 0, false, null, f);
    }

    public static Value of(IteratorState it) {
        return new Value(ITER, 0, false, null, it);
    }

    public int type() {
        return type;
    }

    public boolean isNil() {
        return type == NIL;
    }

    public boolean isNumber() {
        return type == NUM;
    }

    public boolean isString() {
        return type == STR;
    }

    public boolean isTable() {
        return type == TABLE;
    }

    public boolean isFunction() {
        return type == FUNC;
    }

    public boolean isNative() {
        return type == NATIVE;
    }

    public boolean isIterator() {
        return type == ITER;
    }

    public boolean isTruthy() {
        return type != NIL && !(type == BOOL && !bool);
    }

    public double asNumber() {
        if (type == NUM) {
            return num;
        }
        if (type == BOOL) {
            return bool ? 1 : 0;
        }
        throw new VmException("Ожидалось число");
    }

    public double asNumber(double fallback) {
        return type == NUM ? num : fallback;
    }

    public String asString() {
        if (type == STR) {
            return str;
        }
        throw new VmException("Ожидалась строка");
    }

    public boolean asBoolean() {
        return isTruthy();
    }

    public Table asTable() {
        if (type != TABLE) {
            throw new VmException("Ожидалась таблица");
        }
        return (Table) obj;
    }

    public Closure asFunction() {
        if (type != FUNC) {
            throw new VmException("Ожидалась функция");
        }
        return (Closure) obj;
    }

    public NativeFn asNative() {
        if (type != NATIVE) {
            throw new VmException("Ожидалась встроенная функция");
        }
        return (NativeFn) obj;
    }

    public IteratorState asIterator() {
        if (type != ITER) {
            throw new VmException("Ожидался итератор");
        }
        return (IteratorState) obj;
    }

    public String typeName() {
        switch (type) {
            case NIL: return "nil";
            case BOOL: return "boolean";
            case NUM: return "number";
            case STR: return "string";
            case TABLE: return "table";
            case FUNC: return "function";
            case NATIVE: return "function";
            case ITER: return "iterator";
            default: return "unknown";
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case NIL: return "nil";
            case BOOL: return bool ? "true" : "false";
            case NUM: return formatNumber(num);
            case STR: return str;
            case TABLE: return "table";
            case FUNC: return "function";
            case NATIVE: return "function";
            case ITER: return "iterator";
            default: return "?";
        }
    }

    /** Аккуратный вывод числа без «хвостов» вида 3.0000000000000004. */
    public static String formatNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            long l = (long) d;
            return String.valueOf(l);
        }
        String s = String.valueOf(d);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Value)) {
            return false;
        }
        Value other = (Value) o;
        if (type != other.type) {
            // Числа и логические значения не смешиваем.
            return false;
        }
        switch (type) {
            case NIL:
                return true;
            case BOOL:
                return bool == other.bool;
            case NUM:
                return num == other.num;
            case STR:
                return str.equals(other.str);
            case TABLE:
            case FUNC:
            case NATIVE:
            case ITER:
                return obj == other.obj;
            default:
                return false;
        }
    }

    @Override
    public int hashCode() {
        switch (type) {
            case NIL: return 0;
            case BOOL: return bool ? 1 : 2;
            case NUM: return Double.hashCode(num);
            case STR: return str.hashCode();
            default: return System.identityHashCode(obj);
        }
    }
}
