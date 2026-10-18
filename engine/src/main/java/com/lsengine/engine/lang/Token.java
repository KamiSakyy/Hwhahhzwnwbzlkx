package com.lsengine.engine.lang;

/**
 * Токен языка LS.
 */
public final class Token {

    public enum Type {
        NUMBER,
        STRING,
        NAME,
        KEYWORD,
        OP,
        EOF
    }

    public final Type type;
    /** Текст токена: имя, оператор или исходное числовое значение. */
    public final String text;
    /** Значение для числовых и строковых литералов. */
    public final Object value;
    public final int line;
    public final int col;

    private Token(Type type, String text, Object value, int line, int col) {
        this.type = type;
        this.text = text;
        this.value = value;
        this.line = line;
        this.col = col;
    }

    public static Token number(double v, int line, int col) {
        return new Token(Type.NUMBER, String.valueOf(v), v, line, col);
    }

    public static Token string(String v, int line, int col) {
        return new Token(Type.STRING, v, v, line, col);
    }

    public static Token name(String v, int line, int col) {
        return new Token(Type.NAME, v, v, line, col);
    }

    public static Token keyword(String v, int line, int col) {
        return new Token(Type.KEYWORD, v, v, line, col);
    }

    public static Token op(String v, int line, int col) {
        return new Token(Type.OP, v, v, line, col);
    }

    public static Token eof(int line, int col) {
        return new Token(Type.EOF, "<eof>", null, line, col);
    }

    public boolean is(Token.Type t) {
        return type == t;
    }

    public boolean isOp(String s) {
        return type == Type.OP && text.equals(s);
    }

    public boolean isKeyword(String s) {
        return type == Type.KEYWORD && text.equals(s);
    }

    public boolean isName(String s) {
        return type == Type.NAME && text.equals(s);
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + line + ":" + col;
    }
}
