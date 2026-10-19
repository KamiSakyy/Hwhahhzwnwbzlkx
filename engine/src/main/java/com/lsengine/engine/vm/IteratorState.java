package com.lsengine.engine.vm;

import java.util.List;

/**
 * Состояние итератора по таблице (pairs/ipairs).
 */
public final class IteratorState {

    public final List<Value> keys;
    public final Table table;
    public int index;

    public IteratorState(Table table) {
        this.table = table;
        this.keys = table.keys();
    }

    public IteratorState(Table table, List<Value> keys) {
        this.table = table;
        this.keys = keys;
    }
}
