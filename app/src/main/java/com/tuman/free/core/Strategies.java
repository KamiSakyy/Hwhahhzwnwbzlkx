package com.tuman.free.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * tpws launch profiles.
 *
 * <p>Every option used here was verified against the bundled tpws build
 * (zapret, {@code --socks} mode). The default profile is the upstream zapret
 * preset {@code TPWS_SOCKS_OPT}:
 * <pre>
 *   --filter-tcp=80 --methodeol --new
 *   --filter-tcp=443 --split-pos=1,midsld --disorder
 * </pre>
 */
public final class Strategies {

    public static final String DEFAULT_ID = "auto";
    public static final String CUSTOM_ID = "custom";

    /** Replaced at launch time with the absolute path of the extracted host list. */
    public static final String HOSTLIST_TOKEN = "%HOSTLIST%";

    private Strategies() {
    }

    public static List<Strategy> all() {
        List<Strategy> l = new ArrayList<>();
        l.add(new Strategy(DEFAULT_ID, "Авто \u2014 пресет zapret",
                "Официальная связка zapret для socks-режима: HTTP и TLS. Заходит у большинства провайдеров.",
                "--filter-tcp=80", "--methodeol",
                "--new",
                "--filter-tcp=443", "--split-pos=1,midsld", "--disorder"));

        l.add(new Strategy("split_disorder", "Сплит + disorder",
                "Режет TLS ClientHello на 1-м байте и в середине домена, второй сегмент уходит первым.",
                "--filter-tcp=443", "--split-pos=1,midsld", "--disorder"));

        l.add(new Strategy("sniext", "Сплит по SNI",
                "Разрез точно по началу поля SNI-расширения в ClientHello.",
                "--filter-tcp=443", "--split-pos=sniext"));

        l.add(new Strategy("sniext_disorder", "SNI + disorder",
                "Разрез по SNI и обратный порядок сегментов.",
                "--filter-tcp=443", "--split-pos=sniext", "--disorder"));

        l.add(new Strategy("disorder", "Только disorder",
                "Минимальное вмешательство: только перестановка сегментов.",
                "--filter-tcp=443", "--disorder"));

        l.add(new Strategy("split2", "Сплит по 2-му байту",
                "Классический вариант для DPI, смотрящих в первые байты пакета.",
                "--filter-tcp=443", "--split-pos=2"));

        l.add(new Strategy("split3", "Сплит по 3-му байту",
                "То же, но со смещением на байт дальше.",
                "--filter-tcp=443", "--split-pos=3"));

        l.add(new Strategy("oob", "OOB-байт",
                "Добавляет out-of-band байт в конец первой части сплита.",
                "--filter-tcp=443", "--split-pos=sniext", "--oob"));

        l.add(new Strategy("tlsrec", "Дробление TLS record",
                "ClientHello разбивается на две TLS-записи.",
                "--filter-tcp=443", "--tlsrec=sniext"));

        l.add(new Strategy("tlsrec_split", "TLS record + сплит",
                "Комбинация дробления записи и сегментации с disorder.",
                "--filter-tcp=443", "--tlsrec=sniext", "--split-pos=1,midsld", "--disorder"));

        l.add(new Strategy("http", "Только HTTP (порт 80)",
                "Для провайдеров, которые режут только незашифрованный трафик.",
                "--filter-tcp=80", "--methodeol", "--hostdot"));

        l.add(new Strategy("full", "Максимум: 80 + 443",
                "Все методы сразу: HTTP-модификации и агрессивная десинхронизация TLS.",
                "--filter-tcp=80", "--methodeol", "--hostdot",
                "--new",
                "--filter-tcp=443", "--split-pos=1,midsld", "--disorder", "--oob"));

        l.add(new Strategy("any", "Все TCP-порты",
                "Дурит любой TCP-трафик, а не только 80/443. Медленнее, зато шире охват.",
                "--filter-tcp=*", "--split-pos=1", "--disorder", "--split-any-protocol"));

        l.add(new Strategy("eco", "Эконом: только заблокированные",
                "Методы применяются лишь к доменам из встроенного списка (~350 записей).",
                "--filter-tcp=443", "--split-pos=1,midsld", "--disorder",
                "--hostlist=" + HOSTLIST_TOKEN));

        l.add(new Strategy(CUSTOM_ID, "Свои аргументы",
                "Произвольная строка параметров tpws из вкладки «Стратегии».",
                "--filter-tcp=443", "--split-pos=1,midsld", "--disorder"));
        return l;
    }

    public static Strategy byId(String id) {
        for (Strategy s : all()) if (s.id.equals(id)) return s;
        return all().get(0);
    }

    /** Strategies worth iterating over during auto-detection. */
    public static List<Strategy> detectable() {
        List<Strategy> out = new ArrayList<>();
        for (String id : new String[]{
                DEFAULT_ID, "split_disorder", "sniext", "sniext_disorder", "disorder",
                "split2", "split3", "oob", "tlsrec", "tlsrec_split", "full", "any"}) {
            out.add(byId(id));
        }
        return out;
    }

    /**
     * Resolves a strategy for launch: substitutes tokens and pulls custom args
     * out of preferences.
     */
    public static Strategy resolve(Context ctx, String id) {
        Prefs p = new Prefs(ctx);
        Strategy base = byId(id);
        String[] args = base.args;
        if (CUSTOM_ID.equals(id)) {
            String custom = p.customArgs().trim();
            if (custom.length() > 0) args = splitArgs(custom);
        }
        String hostlist = hostlistFile(ctx).getAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            if (args[i].contains(HOSTLIST_TOKEN)) {
                args[i] = args[i].replace(HOSTLIST_TOKEN, hostlist);
            }
        }
        return new Strategy(base.id, base.name, base.desc, args);
    }

    public static String[] splitArgs(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** Extracts assets/hostlist/blocked.txt into the private files dir. */
    public static File hostlistFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "lists");
        File out = new File(dir, "blocked.txt");
        if (out.exists() && out.length() > 0) return out;
        InputStream is = null;
        OutputStream os = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) return out;
            is = ctx.getAssets().open("hostlist/blocked.txt");
            os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } catch (Exception e) {
            Engine.log("! hostlist: " + e.getMessage());
        } finally {
            closeQuietly(is);
            closeQuietly(os);
        }
        return out;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    /** Human readable dump used by the logs tab and the auto-detector. */
    public static String describe(String[] args) {
        return Arrays.toString(args).replace("[", "").replace("]", "").replace(",", "");
    }
}
