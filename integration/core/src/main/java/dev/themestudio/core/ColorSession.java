package dev.themestudio.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One document, one thread. Temporary slider changes are never committed implicitly. */
public final class ColorSession {
    private final TreeMap<String, Integer> colors = new TreeMap<>();
    private final ArrayDeque<TreeMap<String, Integer>> undo = new ArrayDeque<>();
    private final ArrayDeque<TreeMap<String, Integer>> redo = new ArrayDeque<>();
    private final int historyLimit;
    private final Thread owner = Thread.currentThread();
    private TreeMap<String, Integer> before;
    private String editingKey;
    private Runnable listener = () -> {};

    public ColorSession(Map<String, Integer> initial, int historyLimit) {
        if (historyLimit < 1) throw new IllegalArgumentException("historyLimit must be positive");
        this.historyLimit = historyLimit;
        initial.forEach((key, value) -> { validateKey(key); colors.put(key, Objects.requireNonNull(value)); });
    }

    public void setListener(Runnable listener) { checkThread(); this.listener = Objects.requireNonNull(listener); }
    public Map<String, Integer> snapshot() { checkThread(); return Collections.unmodifiableMap(new TreeMap<>(colors)); }
    /** Safe for persistence/rotation: excludes any unconfirmed slider preview. */
    public Map<String, Integer> committedSnapshot() {
        checkThread();
        return Collections.unmodifiableMap(new TreeMap<>(before == null ? colors : before));
    }
    public Integer getOverride(String key) { checkThread(); validateKey(key); return colors.get(key); }
    public boolean isEditing() { checkThread(); return before != null; }

    public void begin(String key) {
        checkThread(); validateKey(key);
        if (before != null) throw new IllegalStateException("Finish the current color transaction first");
        before = new TreeMap<>(colors);
        editingKey = key;
    }

    public void preview(int argb) {
        requireEdit(); colors.put(editingKey, argb); listener.run();
    }

    public void previewDefault() {
        requireEdit(); colors.remove(editingKey); listener.run();
    }

    public void commit() {
        requireEdit();
        if (!before.equals(colors)) {
            push(undo, before);
            redo.clear();
        }
        before = null; editingKey = null; listener.run();
    }

    public void cancel() {
        requireEdit(); colors.clear(); colors.putAll(before);
        before = null; editingKey = null; listener.run();
    }

    public boolean undo() { return restore(undo, redo); }
    public boolean redo() { return restore(redo, undo); }

    private boolean restore(ArrayDeque<TreeMap<String, Integer>> from, ArrayDeque<TreeMap<String, Integer>> to) {
        checkThread();
        if (before != null) throw new IllegalStateException("Cannot change history during color editing");
        if (from.isEmpty()) return false;
        push(to, new TreeMap<>(colors)); colors.clear(); colors.putAll(from.pop()); listener.run(); return true;
    }

    private void push(ArrayDeque<TreeMap<String, Integer>> stack, TreeMap<String, Integer> value) {
        stack.push(value);
        while (stack.size() > historyLimit) stack.removeLast();
    }

    private void requireEdit() { checkThread(); if (before == null) throw new IllegalStateException("No color transaction"); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("ColorSession accessed from another thread"); }
    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid theme key");
    }

    /** AARRGGBB only; no implicit alpha and no ambiguous shorthand. */
    public static int parseArgb(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Expected #AARRGGBB");
        return (int) Long.parseLong(value.substring(1), 16);
    }

    public static String formatArgb(int color) { return String.format(java.util.Locale.ROOT, "#%08X", color); }
}
