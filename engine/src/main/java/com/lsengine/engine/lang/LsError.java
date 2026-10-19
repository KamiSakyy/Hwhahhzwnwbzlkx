package com.lsengine.engine.lang;

/**
 * Ошибка компиляции/выполнения языка LS с указанием позиции в исходнике.
 */
public class LsError extends Exception {

    private static final long serialVersionUID = 1L;

    public final int line;
    public final int col;

    public LsError(String message, int line, int col) {
        super(message);
        this.line = line;
        this.col = col;
    }

    public LsError(String message) {
        this(message, 0, 0);
    }

    /** Человекочитаемое представление с позицией. */
    public String pretty() {
        if (line <= 0) {
            return getMessage();
        }
        return getMessage() + " (строка " + line + ", столбец " + col + ")";
    }
}
