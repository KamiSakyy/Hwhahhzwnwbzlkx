package com.lsengine.engine.aot;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Общая часть AOT-бэкендов: поток кода, исправления адресов, метки.
 */
public abstract class AotBackend implements AotIsa {

    protected final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    protected final List<AotElf.Fixup> fixups = new ArrayList<>();
    protected final List<int[]> jumps = new ArrayList<>();
    protected final Map<Integer, Integer> labels = new LinkedHashMap<>();

    protected void u8(int v) {
        out.write(v & 0xFF);
    }

    protected void u32(int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    protected void u64(long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >> (i * 8)) & 0xFF));
        }
    }

    /** Записывает rel32 и запоминает место для исправления. */
    protected void emitRel32(int labelId) {
        jumps.add(new int[]{out.size(), labelId});
        u32(0);
    }

    @Override
    public void label(int id) {
        labels.put(id, out.size());
    }

    @Override
    public int codeSize() {
        return out.size();
    }

    public byte[] code() {
        return out.toByteArray();
    }

    public List<AotElf.Fixup> fixups() {
        return fixups;
    }

    public List<int[]> jumps() {
        return jumps;
    }

    public Map<Integer, Integer> labels() {
        return labels;
    }
}
