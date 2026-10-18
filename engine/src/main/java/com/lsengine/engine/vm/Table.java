package com.lsengine.engine.vm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Таблица LS — сочетает массив (ключи 1..N) и хеш-часть (остальные ключи).
 */
public final class Table {

    private final List<Value> array = new ArrayList<>();
    private final Map<Value, Value> hash = new LinkedHashMap<>();

    public Value get(Value key) {
        if (key.isNumber()) {
            double d = key.asNumber();
            int i = (int) d;
            if (i == d && i >= 1 && i <= array.size()) {
                return array.get(i - 1);
            }
        }
        Value v = hash.get(key);
        return v == null ? Value.nil() : v;
    }

    public Value get(String key) {
        return get(Value.of(key));
    }

    public void set(Value key, Value value) {
        if (key.isNumber()) {
            double d = key.asNumber();
            int i = (int) d;
            if (i == d && i >= 1) {
                if (i <= array.size()) {
                    array.set(i - 1, value);
                    return;
                }
                if (i == array.size() + 1) {
                    if (!value.isNil()) {
                        array.add(value);
                    }
                    return;
                }
            }
        }
        if (value.isNil()) {
            hash.remove(key);
        } else {
            hash.put(key, value);
        }
    }

    public void set(String key, Value value) {
        set(Value.of(key), value);
    }

    public void set(String key, String value) {
        set(Value.of(key), Value.of(value));
    }

    public void set(String key, double value) {
        set(Value.of(key), Value.of(value));
    }

    /** Число элементов (длина массивной части). */
    public int length() {
        return array.size();
    }

    public int size() {
        return array.size() + hash.size();
    }

    /** Все ключи: сначала числовые (1..N), затем остальные. */
    public List<Value> keys() {
        List<Value> keys = new ArrayList<>(array);
        keys.addAll(hash.keySet());
        return keys;
    }

    public List<Value> values() {
        List<Value> values = new ArrayList<>();
        for (Value k : keys()) {
            values.add(get(k));
        }
        return values;
    }

    public void remove(String key) {
        hash.remove(Value.of(key));
    }

    @Override
    public String toString() {
        return "table(" + size() + ")";
    }
}
