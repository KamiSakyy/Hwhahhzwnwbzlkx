package com.tuman.free.ui;

import android.content.Context;

import java.util.Locale;

/** Small formatting / dimension helpers shared by the screens. */
public final class UiUtil {

    private UiUtil() {
    }

    public static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static String traffic(long bytes) {
        if (bytes < 1024L) return bytes + " \u0411";
        double v = bytes;
        String[] units = {"\u041a\u0411", "\u041c\u0411", "\u0413\u0411", "\u0422\u0411"};
        int i = -1;
        while (v >= 1024.0 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        if (i < 0) return String.format(Locale.US, "%.0f \u0411", v);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    public static String uptime(long sinceMs) {
        if (sinceMs <= 0L) return "\u2014";
        long s = (System.currentTimeMillis() - sinceMs) / 1000L;
        if (s < 0) s = 0;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0) return String.format(Locale.US, "%d\u0447 %02d\u043c", h, m);
        if (m > 0) return String.format(Locale.US, "%d\u043c %02d\u0441", m, sec);
        return String.format(Locale.US, "%d\u0441", sec);
    }

    public static String speed(long bytes, long windowMs) {
        if (windowMs <= 0L) return "0 \u0411/\u0441";
        return traffic((long) (bytes * 1000.0 / windowMs)) + "/\u0441";
    }

    public static String host(String url) {
        try {
            int i = url.indexOf("://");
            String rest = i >= 0 ? url.substring(i + 3) : url;
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(0, slash) : rest;
        } catch (Throwable t) {
            return url;
        }
    }
}
