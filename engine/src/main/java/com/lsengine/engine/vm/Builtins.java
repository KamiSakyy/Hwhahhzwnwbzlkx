package com.lsengine.engine.vm;

import java.util.Random;

/**
 * Стандартная библиотека языка LS: print, tostring, tonumber, type, pairs,
 * а также таблицы math, string и table.
 */
public final class Builtins {

    private static final Random RANDOM = new Random();

    private Builtins() {
    }

    public static void install(Vm vm) {
        Table g = vm.globals();

        g.set("print", Value.of((NativeFn) (v, args) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                sb.append(args[i].toString());
            }
            v.printLine(sb.toString());
            return Value.nil();
        }));

        g.set("tostring", Value.of((NativeFn) (v, args) ->
                Value.of(args.length > 0 ? args[0].toString() : "nil")));

        g.set("tonumber", Value.of((NativeFn) (v, args) -> {
            if (args.length == 0) {
                return Value.nil();
            }
            Value a = args[0];
            if (a.isNumber()) {
                return a;
            }
            if (a.isString()) {
                try {
                    return Value.of(Double.parseDouble(a.asString().trim()));
                } catch (NumberFormatException e) {
                    return Value.nil();
                }
            }
            return Value.nil();
        }));

        g.set("type", Value.of((NativeFn) (v, args) ->
                Value.of(args.length > 0 ? args[0].typeName() : "nil")));

        g.set("pairs", Value.of((NativeFn) (v, args) -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new VmException("pairs ожидает таблицу");
            }
            return Value.of(new IteratorState(args[0].asTable()));
        }));

        g.set("ipairs", Value.of((NativeFn) (v, args) -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new VmException("ipairs ожидает таблицу");
            }
            Table t = args[0].asTable();
            java.util.List<Value> keys = new java.util.ArrayList<>();
            for (int i = 1; i <= t.length(); i++) {
                keys.add(Value.of(i));
            }
            return Value.of(new IteratorState(t, keys));
        }));

        installMath(g);
        installString(g);
        installTable(g);
    }

    private static void installMath(Table g) {
        Table math = new Table();
        math.set("pi", Math.PI);
        math.set("huge", Double.MAX_VALUE);
        math.set("floor", Value.of((NativeFn) (v, args) ->
                Value.of(Math.floor(num(args, 0, "floor")))));
        math.set("ceil", Value.of((NativeFn) (v, args) ->
                Value.of(Math.ceil(num(args, 0, "ceil")))));
        math.set("abs", Value.of((NativeFn) (v, args) ->
                Value.of(Math.abs(num(args, 0, "abs")))));
        math.set("sqrt", Value.of((NativeFn) (v, args) ->
                Value.of(Math.sqrt(num(args, 0, "sqrt")))));
        math.set("sin", Value.of((NativeFn) (v, args) ->
                Value.of(Math.sin(num(args, 0, "sin")))));
        math.set("cos", Value.of((NativeFn) (v, args) ->
                Value.of(Math.cos(num(args, 0, "cos")))));
        math.set("tan", Value.of((NativeFn) (v, args) ->
                Value.of(Math.tan(num(args, 0, "tan")))));
        math.set("atan2", Value.of((NativeFn) (v, args) ->
                Value.of(Math.atan2(num(args, 0, "atan2"), num(args, 1, "atan2")))));
        math.set("min", Value.of((NativeFn) (v, args) -> {
            double r = num(args, 0, "min");
            for (int i = 1; i < args.length; i++) {
                r = Math.min(r, num(args, i, "min"));
            }
            return Value.of(r);
        }));
        math.set("max", Value.of((NativeFn) (v, args) -> {
            double r = num(args, 0, "max");
            for (int i = 1; i < args.length; i++) {
                r = Math.max(r, num(args, i, "max"));
            }
            return Value.of(r);
        }));
        math.set("random", Value.of((NativeFn) (v, args) -> {
            if (args.length == 0) {
                return Value.of(RANDOM.nextDouble());
            }
            if (args.length == 1) {
                int n = (int) num(args, 0, "random");
                if (n <= 0) {
                    return Value.of(0);
                }
                return Value.of(RANDOM.nextInt(n) + 1);
            }
            int lo = (int) num(args, 0, "random");
            int hi = (int) num(args, 1, "random");
            if (hi < lo) {
                int tmp = lo;
                lo = hi;
                hi = tmp;
            }
            return Value.of(lo + RANDOM.nextInt(hi - lo + 1));
        }));
        g.set("math", Value.of(math));
    }

    private static void installString(Table g) {
        Table str = new Table();
        str.set("len", Value.of((NativeFn) (v, args) ->
                Value.of(str(args, 0).length())));
        str.set("upper", Value.of((NativeFn) (v, args) ->
                Value.of(str(args, 0).toUpperCase())));
        str.set("lower", Value.of((NativeFn) (v, args) ->
                Value.of(str(args, 0).toLowerCase())));
        str.set("sub", Value.of((NativeFn) (v, args) -> {
            String s = str(args, 0);
            int from = (int) num(args, 1, "sub");
            int to = args.length > 2 ? (int) num(args, 2, "sub") : s.length();
            if (from < 0) {
                from = Math.max(0, s.length() + from + 1);
            }
            if (to < 0) {
                to = s.length() + to + 1;
            }
            if (to > s.length()) {
                to = s.length();
            }
            if (from > to) {
                return Value.of("");
            }
            return Value.of(s.substring(from, to));
        }));
        str.set("rep", Value.of((NativeFn) (v, args) -> {
            String s = str(args, 0);
            int n = (int) num(args, 1, "rep");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append(s);
            }
            return Value.of(sb.toString());
        }));
        str.set("format", Value.of((NativeFn) (v, args) -> {
            String fmt = str(args, 0);
            StringBuilder out = new StringBuilder();
            int argIndex = 1;
            for (int i = 0; i < fmt.length(); i++) {
                char c = fmt.charAt(i);
                if (c != '%' || i + 1 >= fmt.length()) {
                    out.append(c);
                    continue;
                }
                char spec = fmt.charAt(++i);
                switch (spec) {
                    case '%':
                        out.append('%');
                        break;
                    case 's':
                        out.append(argIndex < args.length ? args[argIndex].toString() : "");
                        argIndex++;
                        break;
                    case 'd': {
                        double d = argIndex < args.length ? args[argIndex].asNumber(0) : 0;
                        out.append((long) d);
                        argIndex++;
                        break;
                    }
                    case 'f': {
                        double d = argIndex < args.length ? args[argIndex].asNumber(0) : 0;
                        int prec = argIndex + 1 < args.length ? (int) args[argIndex + 1].asNumber(2) : 2;
                        out.append(String.format("%." + prec + "f", d));
                        argIndex++;
                        break;
                    }
                    default:
                        out.append('%').append(spec);
                        break;
                }
            }
            return Value.of(out.toString());
        }));
        g.set("string", Value.of(str));
    }

    private static void installTable(Table g) {
        Table tbl = new Table();
        tbl.set("insert", Value.of((NativeFn) (v, args) -> {
            if (args.length < 2 || !args[0].isTable()) {
                return Value.nil();
            }
            Table t = args[0].asTable();
            if (args.length >= 3) {
                t.set(Value.of(t.length() + 1), args[2]);
            } else {
                t.set(Value.of(t.length() + 1), args[1]);
            }
            return Value.nil();
        }));
        tbl.set("remove", Value.of((NativeFn) (v, args) -> {
            if (args.length < 1 || !args[0].isTable()) {
                return Value.nil();
            }
            Table t = args[0].asTable();
            int size = t.length();
            if (size == 0) {
                return Value.nil();
            }
            int index = args.length > 1 ? (int) args[1].asNumber(size) : size;
            if (index < 1 || index > size) {
                return Value.nil();
            }
            Value removed = t.get(Value.of(index));
            for (int i = index; i < size; i++) {
                t.set(Value.of(i), t.get(Value.of(i + 1)));
            }
            t.set(Value.of(size), Value.nil());
            return removed;
        }));
        tbl.set("concat", Value.of((NativeFn) (v, args) -> {
            if (args.length < 1 || !args[0].isTable()) {
                return Value.of("");
            }
            Table t = args[0].asTable();
            String sep = args.length > 1 ? args[1].toString() : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= t.length(); i++) {
                if (i > 1) {
                    sb.append(sep);
                }
                sb.append(t.get(Value.of(i)).toString());
            }
            return Value.of(sb.toString());
        }));
        g.set("table", Value.of(tbl));
    }

    private static double num(Value[] args, int index, String fn) {
        if (index >= args.length) {
            throw new VmException("не хватает аргумента у " + fn);
        }
        return args[index].asNumber();
    }

    private static String str(Value[] args, int index) {
        if (index >= args.length) {
            throw new VmException("не хватает аргумента");
        }
        return args[index].toString();
    }
}
