package com.lsengine.engine.vm;

/**
 * Ячейка переменной: используется, когда переменная захватывается замыканием
 * (тогда все обращения идут через одну и ту же ячейку — как в Lua).
 */
public final class Cell {

    public Value value;

    public Cell(Value value) {
        this.value = value == null ? Value.nil() : value;
    }
}
