package com.lsengine.engine.runtime;

/**
 * Разбор цветов для API рисования: "red", "#ff8800", 0xFF8800, {r,g,b}.
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    public static int parse(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            long v = ((Number) value).longValue();
            if (v < 0) {
                v += 0x100000000L;
            }
            if (v <= 0xFFFFFF) {
                return (int) (0xFF000000L | v);
            }
            return (int) v;
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                sb.append(c).append(c);
            }
            s = sb.toString();
        }
        if (s.length() == 6 || s.length() == 8) {
            try {
                long v = Long.parseLong(s, 16);
                if (s.length() == 6) {
                    return (int) (0xFF000000L | v);
                }
                return (int) v;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        Integer named = NAMED.get(s.toLowerCase());
        return named == null ? fallback : named;
    }

    private static final java.util.Map<String, Integer> NAMED = new java.util.HashMap<>();

    static {
        NAMED.put("black", 0xFF000000);
        NAMED.put("white", 0xFFFFFFFF);
        NAMED.put("red", 0xFFFF0000);
        NAMED.put("green", 0xFF00FF00);
        NAMED.put("blue", 0xFF0000FF);
        NAMED.put("yellow", 0xFFFFFF00);
        NAMED.put("cyan", 0xFF00FFFF);
        NAMED.put("magenta", 0xFFFF00FF);
        NAMED.put("gray", 0xFF808080);
        NAMED.put("grey", 0xFF808080);
        NAMED.put("orange", 0xFFFFA500);
        NAMED.put("purple", 0xFF800080);
        NAMED.put("lime", 0xFF00FF00);
        NAMED.put("navy", 0xFF000080);
        NAMED.put("teal", 0xFF008080);
        NAMED.put("pink", 0xFFFFC0CB);
        NAMED.put("brown", 0xFFA52A2A);
        NAMED.put("transparent", 0x00000000);
    }
}
