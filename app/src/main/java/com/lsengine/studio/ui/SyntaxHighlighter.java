package com.lsengine.studio.ui;

import android.graphics.Color;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Подсветка синтаксиса языка LS: ключевые слова, строки, числа, комментарии,
 * вызовы функций и API движка.
 */
public final class SyntaxHighlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "local", "function", "end", "if", "then", "else", "elseif", "while", "do",
            "for", "in", "return", "break", "and", "or", "not", "true", "false", "nil",
            "repeat", "until", "global"));

    private static final Set<String> API = new HashSet<>(Arrays.asList(
            "draw", "input", "time", "math", "string", "table", "ui", "sound", "app", "print"));

    private SyntaxHighlighter() {
    }

    public static void highlight(TextView view) {
        CharSequence textValue = view.getText();
        if (!(textValue instanceof Spannable)) {
            return;
        }
        Spannable spannable = (Spannable) textValue;
        // Убираем прежнюю подсветку.
        ForegroundColorSpan[] old = spannable.getSpans(0, spannable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : old) {
            spannable.removeSpan(span);
        }
        String text = spannable.toString();
        int length = text.length();
        int colorKeyword = 0xFFC792EA;
        int colorString = 0xFFC3E88D;
        int colorNumber = 0xFFF78C6C;
        int colorComment = 0xFF697098;
        int colorFunction = 0xFF82AAFF;
        int colorApi = 0xFF7DD3FC;

        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < length && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                if (end < 0) {
                    end = length;
                }
                apply(spannable, i, end, colorComment);
                i = end;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < length) {
                    char ch = text.charAt(end);
                    if (ch == '\\') {
                        end += 2;
                        continue;
                    }
                    if (ch == c) {
                        end++;
                        break;
                    }
                    end++;
                }
                apply(spannable, i, Math.min(end, length), colorString);
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i;
                while (end < length && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                apply(spannable, i, end, colorNumber);
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < length && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(i, end);
                String lower = word.toLowerCase(Locale.ROOT);
                if (KEYWORDS.contains(lower)) {
                    apply(spannable, i, end, colorKeyword);
                } else if (API.contains(lower)) {
                    apply(spannable, i, end, colorApi);
                } else if (end < length && text.charAt(end) == '(') {
                    apply(spannable, i, end, colorFunction);
                }
                i = end;
                continue;
            }
            i++;
        }
    }

    private static void apply(Spannable spannable, int start, int end, int color) {
        if (end <= start) {
            return;
        }
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Цвет строки по номеру (для подсветки текущей строки). */
    public static int lineColor(int line) {
        return line % 2 == 0 ? Color.TRANSPARENT : 0x0AFFFFFF;
    }
}
