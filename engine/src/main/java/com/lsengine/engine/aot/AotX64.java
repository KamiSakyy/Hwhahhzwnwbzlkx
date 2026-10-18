package com.lsengine.engine.aot;

/**
 * AOT-бэкенд для x86-64 (System V, Linux/Android).
 *
 * Генерируется настоящий машинный код: прямые вызовы, константы в пуле,
 * арифметика — инструкциями SSE. Отладочной информации нет, имена символов
 * отсутствуют — декомпилировать такую библиотеку существенно сложнее.
 */
public final class AotX64 extends AotBackend {

    // Регистры x86-64.
    private static final int RAX = 0;
    private static final int RCX = 1;
    private static final int RDX = 2;
    private static final int RBX = 3;
    private static final int RSP = 4;
    private static final int RBP = 5;
    private static final int RSI = 6;
    private static final int RDI = 7;
    private static final int R8 = 8;
    private static final int R9 = 9;
    private static final int R10 = 10;
    private static final int R11 = 11;
    private static final int R12 = 12;
    private static final int R13 = 13;

    private static int x86(int reg) {
        switch (reg) {
            case R0: return RAX;
            case R1: return RCX;
            case R2: return RDX;
            case R3: return RSI;
            case R4: return RDI;
            case R5: return R8;
            case R6: return R9;
            case R7: return R10;
            case SP: return RBX;
            case FP: return RBP;
            case CL: return R13;
            case GL: return R12;
            default: throw new IllegalArgumentException("неизвестный регистр " + reg);
        }
    }

    private static int xmm(int reg) {
        if (reg == D0) {
            return 0;
        }
        if (reg == D1) {
            return 1;
        }
        throw new IllegalArgumentException("неизвестный FP-регистр " + reg);
    }

    private void rex(boolean w, int r, int b) {
        int v = 0x40 | (w ? 8 : 0) | ((r & 8) != 0 ? 4 : 0) | ((b & 8) != 0 ? 1 : 0);
        if (v != 0x40) {
            u8(v);
        }
    }

    /** ModRM + SIB + disp32 для [base + disp]. */
    private void modrmMem(int reg, int base, int disp) {
        u8(0x80 | ((reg & 7) << 3) | (base & 7));
        if ((base & 7) == 4) {
            u8(0x24);
        }
        u32(disp);
    }

    /** ModRM для [rip + disp32] — исправляемое смещение. */
    private int modrmRip(int reg) {
        u8(((reg & 7) << 3) | 5);
        int at = out.size();
        u32(0);
        return at;
    }

    private void modrmReg(int reg, int rm) {
        u8(0xC0 | ((reg & 7) << 3) | (rm & 7));
    }

    // ---------------- операции ----------------

    @Override
    public void prologue(int frameSize) {
        u8(0x55);                       // push rbp
        rex(true, RAX, RBP);
        u8(0x89);
        modrmReg(RAX, RBP);             // mov rbp, rsp
        u8(0x53);                       // push rbx
        u8(0x41);
        u8(0x54);                       // push r12
        rex(true, RAX, RAX);
        u8(0x81);
        u8(0xEC);                       // sub rsp, imm32
        u32(frameSize);
    }

    @Override
    public void epilogue() {
        rex(true, RAX, RBP);
        u8(0x89);
        modrmReg(RSP, RBP);             // mov rsp, rbp
        u8(0x41);
        u8(0x5C);                       // pop r12
        u8(0x5B);                       // pop rbx
        u8(0x5D);                       // pop rbp
        u8(0xC3);                       // ret
    }

    @Override
    public void jump(int id) {
        u8(0xE9);
        emitRel32(id);
    }

    private void jumpCond(int opcode, int id) {
        u8(0x0F);
        u8(opcode);
        emitRel32(id);
    }

    @Override
    public void jumpIfEq(int reg, long value, int id) {
        movImm(R10, value);
        rex(true, R10, x86(reg));
        u8(0x39);
        modrmReg(R10, x86(reg));
        jumpCond(0x84, id);
    }

    @Override
    public void jumpIfNe(int reg, long value, int id) {
        movImm(R10, value);
        rex(true, R10, x86(reg));
        u8(0x39);
        modrmReg(R10, x86(reg));
        jumpCond(0x85, id);
    }

    @Override
    public void jumpIfBelow(int reg, long value, int id) {
        movImm(R10, value);
        rex(true, R10, x86(reg));
        u8(0x3B);
        modrmReg(x86(reg), R10);
        jumpCond(0x82, id);
    }

    @Override
    public void jumpIfZero(int reg, int id) {
        rex(false, x86(reg), x86(reg));
        u8(0x85);
        modrmReg(x86(reg), x86(reg));
        jumpCond(0x84, id);
    }

    @Override
    public void jumpIfNotZero(int reg, int id) {
        rex(false, x86(reg), x86(reg));
        u8(0x85);
        modrmReg(x86(reg), x86(reg));
        jumpCond(0x85, id);
    }

    @Override
    public void movImm(int reg, long value) {
        rex(true, 0, x86(reg));
        u8(0xB8 + (x86(reg) & 7));
        u64(value);
    }

    @Override
    public void movReg(int dst, int src) {
        rex(true, x86(src), x86(dst));
        u8(0x89);
        modrmReg(x86(src), x86(dst));
    }

    @Override
    public void load(int reg, int base, int disp) {
        rex(true, x86(reg), x86(base));
        u8(0x8B);
        modrmMem(x86(reg), x86(base), disp);
    }

    @Override
    public void store(int base, int disp, int reg) {
        rex(true, x86(reg), x86(base));
        u8(0x89);
        modrmMem(x86(reg), x86(base), disp);
    }

    @Override
    public void lea(int reg, int base, int disp) {
        rex(true, x86(reg), x86(base));
        u8(0x8D);
        modrmMem(x86(reg), x86(base), disp);
    }

    @Override
    public void add(int dst, int src) {
        rex(true, x86(src), x86(dst));
        u8(0x01);
        modrmReg(x86(src), x86(dst));
    }

    @Override
    public void addImm(int reg, int imm) {
        rex(true, R10, x86(reg));
        u8(0x81);
        u8(0xC0 | (x86(reg) & 7));
        u32(imm);
    }

    @Override
    public void subImm(int reg, int imm) {
        rex(true, R10, x86(reg));
        u8(0x81);
        u8(0xE8 | (x86(reg) & 7));
        u32(imm);
    }

    @Override
    public void andImm(int reg, long mask) {
        if (mask >= Integer.MIN_VALUE && mask <= Integer.MAX_VALUE) {
            rex(true, R10, x86(reg));
            u8(0x81);
            u8(0xE0 | (x86(reg) & 7));
            u32((int) mask);
            return;
        }
        movImm(R11, mask);
        rex(true, R11, x86(reg));
        u8(0x21);
        modrmReg(R11, x86(reg));
    }

    @Override
    public void orImm(int reg, long mask) {
        if (mask >= Integer.MIN_VALUE && mask <= Integer.MAX_VALUE) {
            rex(true, R10, x86(reg));
            u8(0x81);
            u8(0xC8 | (x86(reg) & 7));
            u32((int) mask);
            return;
        }
        movImm(R11, mask);
        rex(true, R11, x86(reg));
        u8(0x09);
        modrmReg(R11, x86(reg));
    }

    @Override
    public void cmpImm(int reg, long imm) {
        movImm(R10, imm);
        rex(true, R10, x86(reg));
        u8(0x39);
        modrmReg(R10, x86(reg));
    }

    @Override
    public void shiftRightImm(int reg, int bits) {
        rex(true, R10, x86(reg));
        u8(0xC1);
        u8(0xE8 | (x86(reg) & 7));
        u8(bits);
    }

    @Override
    public void box(int reg, int tag) {
        orImm(reg, AotValues.BOX_BASE | ((long) tag << AotValues.TAG_SHIFT));
    }

    @Override
    public void payload(int reg) {
        andImm(reg, AotValues.PAYLOAD_MASK);
    }

    @Override
    public void loadDouble(int dreg, int base, int disp) {
        u8(0xF2);
        rex(true, xmm(dreg), x86(base));
        u8(0x10);
        modrmMem(xmm(dreg), x86(base), disp);
    }

    @Override
    public void storeDouble(int base, int disp, int dreg) {
        u8(0xF2);
        rex(true, xmm(dreg), x86(base));
        u8(0x11);
        modrmMem(xmm(dreg), x86(base), disp);
    }

    @Override
    public void doubleToInt(int reg, int dreg) {
        u8(0x66);
        rex(true, xmm(dreg), x86(reg));
        u8(0x7E);
        modrmReg(xmm(dreg), x86(reg));
    }

    @Override
    public void intToDouble(int dreg, int reg) {
        u8(0x66);
        rex(true, xmm(dreg), x86(reg));
        u8(0x6E);
        modrmReg(xmm(dreg), x86(reg));
    }

    @Override
    public void addDouble(int dst, int src) {
        u8(0xF2);
        rex(true, xmm(src), xmm(dst));
        u8(0x58);
        modrmReg(xmm(src), xmm(dst));
    }

    @Override
    public void subDouble(int dst, int src) {
        u8(0xF2);
        rex(true, xmm(src), xmm(dst));
        u8(0x5C);
        modrmReg(xmm(src), xmm(dst));
    }

    @Override
    public void mulDouble(int dst, int src) {
        u8(0xF2);
        rex(true, xmm(src), xmm(dst));
        u8(0x59);
        modrmReg(xmm(src), xmm(dst));
    }

    @Override
    public void divDouble(int dst, int src) {
        u8(0xF2);
        rex(true, xmm(src), xmm(dst));
        u8(0x5E);
        modrmReg(xmm(src), xmm(dst));
    }

    @Override
    public void compareDouble(int a, int b) {
        u8(0x66);
        rex(true, xmm(b), xmm(a));
        u8(0x2E);
        modrmReg(xmm(b), xmm(a));
    }

    @Override
    public void callApi(int apiIndex, int argsDisp) {
        // rdi = адрес аргументов, r10 = адрес функции
        rex(true, RDI, FP);
        u8(0x8D);
        modrmMem(RDI, RBP, argsDisp);
        rex(true, R10, 0);
        u8(0x8B);
        int at = modrmRip(R10);
        fixups.add(new AotElf.Fixup(at, FIX_API, apiIndex));
        rex(false, R10, R10);
        u8(0xFF);
        modrmReg(R10, R10);
    }

    @Override
    public void callReg(int reg) {
        rex(false, x86(reg), x86(reg));
        u8(0xFF);
        modrmReg(2, x86(reg));   // FF /2 — вызов по адресу в регистре
    }

    @Override
    public void callFunction(int functionIndex) {
        u8(0xE8);
        int at = out.size();
        u32(0);
        fixups.add(new AotElf.Fixup(at, FIX_FUNCTION, functionIndex));
    }

    @Override
    public void loadPool(int reg, int index) {
        rex(true, x86(reg), 0);
        u8(0x8B);
        int at = modrmRip(x86(reg));
        fixups.add(new AotElf.Fixup(at, FIX_POOL, index));
    }

    @Override
    public void leaPool(int reg, int index) {
        rex(true, x86(reg), 0);
        u8(0x8D);
        int at = modrmRip(x86(reg));
        fixups.add(new AotElf.Fixup(at, FIX_POOL, index));
    }

    @Override
    public void leaRodata(int reg, int index) {
        rex(true, x86(reg), 0);
        u8(0x8D);
        int at = modrmRip(x86(reg));
        fixups.add(new AotElf.Fixup(at, FIX_RODATA, index));
    }

    @Override
    public void leaApi(int reg, int index) {
        rex(true, x86(reg), 0);
        u8(0x8D);
        int at = modrmRip(x86(reg));
        fixups.add(new AotElf.Fixup(at, FIX_API, index));
    }

    @Override
    public int frameBase() {
        return -16;   // push rbx, push r12
    }

    @Override
    public int localOffset(int slot) {
        return AotFrame.localOffset(slot);
    }

    @Override
    public int closureSlotOffset(int localCount) {
        return AotFrame.closureOffset(localCount);
    }

    @Override
    public int scratchSlotOffset(int localCount) {
        return AotFrame.scratchOffset(localCount);
    }

    @Override
    public int stackTopOffset(int localCount) {
        return AotFrame.stackTop(localCount);
    }

    @Override
    public int frameSize(int localCount, int maxStack, int maxArgs) {
        return AotFrame.frameSize(localCount, maxStack, maxArgs);
    }

    @Override
    public void leaFunction(int reg, int functionIndex) {
        rex(true, x86(reg), 0);
        u8(0x8D);
        int at = modrmRip(x86(reg));
        fixups.add(new AotElf.Fixup(at, FIX_FUNCTION, functionIndex));
    }
}
