package com.lsengine.engine.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Лексер языка LS.
 *
 * Поддерживается: числа (в т.ч. экспоненциальная запись), строки в кавычках и
 * длинные строки [[...]], комментарии -- ... и --[[ ... ]], имена, ключевые слова
 * и операторы.
 */
public final class Lexer {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "local", "function", "end", "if", "then", "else", "elseif", "while", "do",
            "for", "in", "return", "break", "and", "or", "not", "true", "false", "nil",
            "repeat", "until", "global"));

    /** Операторы от длинных к коротким, чтобы корректно разбирать двухсимвольные. */
    private static final String[] OPERATORS = {
            "...", "..", "==", "~=", "<=", ">=", "::",
            "+=", "-=", "*=", "/=", "%=", "**=",
            "+", "-", "*", "/", "%", "^", "#", "<", ">", "=",
            "(", ")", "{", "}", "[", "]", ",", ".", ";", ":", "?"
    };

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    public Lexer(String src) {
        this.src = src == null ? "" : src;
    }

    public List<Token> tokenize() throws LsError {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                out.add(Token.eof(line, col));
                return out;
            }
            int startLine = line;
            int startCol = col;
            char c = src.charAt(pos);

            if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                out.add(Token.number(readNumber(), startLine, startCol));
            } else if (c == '"' || c == '\'') {
                out.add(Token.string(readQuoted(c), startLine, startCol));
            } else if (c == '[' && pos + 1 < src.length() && src.charAt(pos + 1) == '[') {
                out.add(Token.string(readLongString(), startLine, startCol));
            } else if (Character.isLetter(c) || c == '_') {
                String name = readName();
                if (KEYWORDS.contains(name)) {
                    out.add(Token.keyword(name, startLine, startCol));
                } else {
                    out.add(Token.name(name, startLine, startCol));
                }
            } else {
                String op = readOperator();
                if (op == null) {
                    throw new LsError("Неизвестный символ: '" + c + "'", startLine, startCol);
                }
                out.add(Token.op(op, startLine, startCol));
            }
        }
    }

    private void skipWhitespaceAndComments() throws LsError {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                advance();
            } else if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                int startLine = line;
                int startCol = col;
                advance();
                advance();
                if (pos + 1 < src.length() && src.charAt(pos) == '[' && src.charAt(pos + 1) == '[') {
                    advance();
                    advance();
                    skipLongBracketBody();
                } else {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        advance();
                    }
                }
                if (line == startLine && col == startCol) {
                    throw new LsError("Ошибка в комментарии", startLine, startCol);
                }
            } else {
                return;
            }
        }
    }

    private void skipLongBracketBody() throws LsError {
        while (true) {
            if (pos >= src.length()) {
                throw new LsError("Незакрытый блок комментария", line, col);
            }
            if (src.charAt(pos) == ']' && pos + 1 < src.length() && src.charAt(pos + 1) == ']') {
                advance();
                advance();
                return;
            }
            advance();
        }
    }

    private double readNumber() throws LsError {
        int start = pos;
        boolean dot = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                advance();
            } else if (c == '.' && !dot) {
                dot = true;
                advance();
            } else if ((c == 'e' || c == 'E') && pos + 1 < src.length()
                    && (Character.isDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '-' || src.charAt(pos + 1) == '+')) {
                advance();
                advance();
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new LsError("Неверное число: " + text, line, col);
        }
    }

    private String readQuoted(char quote) throws LsError {
        int startLine = line;
        int startCol = col;
        advance(); // открывающая кавычка
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new LsError("Незакрытая строка", startLine, startCol);
            }
            char c = src.charAt(pos);
            if (c == '\n') {
                throw new LsError("Незакрытая строка", startLine, startCol);
            }
            if (c == quote) {
                advance();
                return sb.toString();
            }
            if (c == '\\') {
                advance();
                if (pos >= src.length()) {
                    throw new LsError("Незакрытая строка", startLine, startCol);
                }
                char e = src.charAt(pos);
                switch (e) {
                    case 'n': sb.append('\n'); advance(); break;
                    case 't': sb.append('\t'); advance(); break;
                    case 'r': sb.append('\r'); advance(); break;
                    case '0': sb.append('\0'); advance(); break;
                    case '\\': sb.append('\\'); advance(); break;
                    case '"': sb.append('"'); advance(); break;
                    case '\'': sb.append('\''); advance(); break;
                    case 'x': {
                        advance();
                        int hex = 0;
                        for (int i = 0; i < 2 && pos < src.length(); i++) {
                            int d = Character.digit(src.charAt(pos), 16);
                            if (d < 0) {
                                break;
                            }
                            hex = hex * 16 + d;
                            advance();
                        }
                        sb.append((char) hex);
                        break;
                    }
                    default:
                        sb.append(e);
                        advance();
                        break;
                }
            } else {
                sb.append(c);
                advance();
            }
        }
    }

    private String readLongString() throws LsError {
        int startLine = line;
        int startCol = col;
        advance();
        advance();
        int start = pos;
        while (true) {
            if (pos >= src.length()) {
                throw new LsError("Незакрытая длинная строка", startLine, startCol);
            }
            if (src.charAt(pos) == ']' && pos + 1 < src.length() && src.charAt(pos + 1) == ']') {
                String text = src.substring(start, pos);
                advance();
                advance();
                // Первая новая строка сразу после [[ игнорируется (как в Lua).
                if (text.startsWith("\n")) {
                    text = text.substring(1);
                } else if (text.startsWith("\r\n")) {
                    text = text.substring(2);
                }
                return text;
            }
            advance();
        }
    }

    private String readName() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                advance();
            } else {
                break;
            }
        }
        return src.substring(start, pos);
    }

    private String readOperator() {
        for (String op : OPERATORS) {
            if (src.startsWith(op, pos)) {
                for (int i = 0; i < op.length(); i++) {
                    advance();
                }
                return op;
            }
        }
        return null;
    }

    private void advance() {
        if (pos < src.length() && src.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }
}
