package com.lsengine.engine.vm;

import com.lsengine.engine.lang.Chunk;

/**
 * Замыкание LS: прототип функции + захваченные переменные (upvalue).
 */
public final class Closure {

    public final Chunk.FuncProto proto;
    public final Cell[] upvalues;

    public Closure(Chunk.FuncProto proto, Cell[] upvalues) {
        this.proto = proto;
        this.upvalues = upvalues == null ? new Cell[0] : upvalues;
    }

    @Override
    public String toString() {
        return "function " + proto.name;
    }
}
