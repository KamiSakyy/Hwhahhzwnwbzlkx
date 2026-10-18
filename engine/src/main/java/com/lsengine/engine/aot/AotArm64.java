package com.lsengine.engine.aot;

/**
 * AOT-бэкенд для AArch64 (ARM64, Android).
 *
 * Реализация будет дополнена после того, как x86-64 backend пройдёт
 * сверку вывода с виртуальной машиной.
 */
public final class AotArm64 extends AotBackend {

    @Override
    public void prologue(int frameSize) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void epilogue() {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jump(int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jumpIfEq(int reg, long value, int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jumpIfNe(int reg, long value, int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jumpIfBelow(int reg, long value, int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jumpIfZero(int reg, int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void jumpIfNotZero(int reg, int id) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void movImm(int reg, long value) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void movReg(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void load(int reg, int base, int disp) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void store(int base, int disp, int reg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void lea(int reg, int base, int disp) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void add(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void addImm(int reg, int imm) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void subImm(int reg, int imm) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void andImm(int reg, long mask) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void orImm(int reg, long mask) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void cmpImm(int reg, long imm) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void shiftRightImm(int reg, int bits) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void box(int reg, int tag) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void payload(int reg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void loadDouble(int dreg, int base, int disp) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void storeDouble(int base, int disp, int dreg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void doubleToInt(int reg, int dreg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void intToDouble(int dreg, int reg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void addDouble(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void subDouble(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void mulDouble(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void divDouble(int dst, int src) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void compareDouble(int a, int b) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void callApi(int apiIndex, int argsDisp) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void callReg(int reg) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void callFunction(int functionIndex) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void loadPool(int reg, int index) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void leaPool(int reg, int index) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void leaRodata(int reg, int index) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void leaApi(int reg, int index) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }

    @Override
    public void leaFunction(int reg, int functionIndex) {
        throw new UnsupportedOperationException("AArch64 backend ещё не реализован");
    }
}
