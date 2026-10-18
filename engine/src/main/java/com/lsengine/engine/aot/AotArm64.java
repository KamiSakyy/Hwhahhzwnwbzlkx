package com.lsengine.engine.aot;

/**
 * AOT-бэкенд для AArch64 (ARM64, Android).
 *
 * Генерируется настоящий машинный код A64: прямые вызовы (BLR), адреса
 * через ADR, арифметика — инструкциями процессора. Отладочной информации
 * нет, библиотека не содержит ни одного импорта.
 *
 * Раскладка кадра (относительно x29 — указателя кадра):
 * <pre>
 *   [fp-8]   сохранённый x20 (замыкание)
 *   [fp-16]  сохранённый x19 (указатель стека значений)
 *   [fp-24]  сохранённый x21 (глобальные переменные)
 *   [fp-32]  выравнивание
 *   [fp-40-8*i]  локальная переменная i
 * </pre>
 */
public final class AotArm64 extends AotBackend {

    private static final int X19 = 19;   // указатель стека значений
    private static final int X20 = 20;   // замыкание
    private static final int X21 = 21;   // глобальные переменные
    private static final int X29 = 29;   // указатель кадра
    private static final int X30 = 30;   // адрес возврата
    private static final int XZR = 31;
    private static final int SP = 31;
    private static final int TMP = 16;   // x16 — временный регистр (IP0)

    /** Размер кадра текущей функции (нужен в эпилоге). */
    private int currentFrameSize;

    private static int reg(int index) {
        switch (index) {
            case R0: return 0;
            case R1: return 1;
            case R2: return 2;
            case R3: return 3;
            case R4: return 4;
            case R5: return 5;
            case R6: return 6;
            case R7: return 7;
            case SP: return X19;
            case FP: return X29;
            case CL: return X20;
            case GL: return X21;
            default: throw new IllegalArgumentException("неизвестный регистр " + index);
        }
    }

    private static int fp(int index) {
        if (index == D0) {
            return 0;
        }
        if (index == D1) {
            return 1;
        }
        throw new IllegalArgumentException("неизвестный FP-регистр " + index);
    }

    private void insn(int value) {
        u32(value);
    }

    // ---------------- пролог и эпилог ----------------

    @Override
    public void prologue(int frameSize) {
        currentFrameSize = frameSize;
        // stp x29, x30, [sp, #-16]! — запись кадра
        insn(0xA9800000 | ((-2 & 0x7F) << 15) | (X30 << 10) | (SP << 5) | X29);
        // mov x29, sp
        insn(0x910003FD);
        // stp x19, x20, [sp, #-16]!
        insn(0xA9800000 | ((-2 & 0x7F) << 15) | (X20 << 10) | (SP << 5) | X19);
        // stp x21, xzr, [sp, #-16]!
        insn(0xA9800000 | ((-2 & 0x7F) << 15) | (XZR << 10) | (SP << 5) | X21);
        // sub sp, sp, #frameSize
        if (frameSize <= 4095) {
            insn(0xD10003FF | (frameSize << 10));
        } else {
            movImm(TMP, frameSize);
            insn(0x8B0003E3 | (TMP << 16) | (SP << 5) | SP);
        }
    }

    @Override
    public void epilogue() {
        // Возвращаем указатель стека к сохранённым регистрам.
        if (currentFrameSize <= 4095) {
            insn(0x910003FF | (currentFrameSize << 10));   // add sp, sp, #frameSize
        } else {
            movImm(TMP, currentFrameSize);
            insn(0x8B0003E3 | (TMP << 16) | (SP << 5) | SP);
        }
        // ldp x21, xzr, [sp], #16
        insn(0xA8C10000 | ((2 & 0x7F) << 15) | (XZR << 10) | (SP << 5) | X21);
        // ldp x19, x20, [sp], #16
        insn(0xA8C10000 | ((2 & 0x7F) << 15) | (X20 << 10) | (SP << 5) | X19);
        // ldp x29, x30, [sp], #16
        insn(0xA8C10000 | ((2 & 0x7F) << 15) | (X30 << 10) | (SP << 5) | X29);
        // ret
        insn(0xD65F03C0);
    }

    @Override
    public int frameBase() {
        return -40;   // запись кадра (16) + три сохранённых регистра (24)
    }

    @Override
    public int localOffset(int slot) {
        return -(40 + 8 * slot);
    }

    @Override
    public int closureSlotOffset(int localCount) {
        return -(40 + 8 * localCount);
    }

    @Override
    public int scratchSlotOffset(int localCount) {
        return -(48 + 8 * localCount);
    }

    @Override
    public int stackTopOffset(int localCount) {
        return -(56 + 8 * localCount);
    }

    @Override
    public int frameSize(int localCount, int maxStack, int maxArgs) {
        int callArea = AotFrame.align16(16 + 8 * maxArgs);
        return AotFrame.align16(40 + 8 * localCount + 8 * maxStack + callArea);
    }

    // ---------------- переходы ----------------

    @Override
    public void jump(int id) {
        int at = out.size();
        insn(0x14000000);
        jumps.add(new AotElf.Fixup(at, 0, id, AotElf.MODE_BRANCH));
    }

    private void branchCond(int cond, int id) {
        int at = out.size();
        insn(0x54000000 | cond);
        jumps.add(new AotElf.Fixup(at, 0, id, AotElf.MODE_BCOND));
    }

    @Override
    public void jumpIfEq(int reg, long value, int id) {
        cmpImm(reg, value);
        branchCond(0x0, id);   // EQ
    }

    @Override
    public void jumpIfNe(int reg, long value, int id) {
        cmpImm(reg, value);
        branchCond(0x1, id);   // NE
    }

    @Override
    public void jumpIfBelow(int reg, long value, int id) {
        cmpImm(reg, value);
        branchCond(0x3, id);   // LO (беззнаковое «меньше»)
    }

    @Override
    public void jumpIfZero(int reg, int id) {
        cmpImm(reg, 0);
        branchCond(0x0, id);
    }

    @Override
    public void jumpIfNotZero(int reg, int id) {
        cmpImm(reg, 0);
        branchCond(0x1, id);
    }

    // ---------------- перемещения и память ----------------

    @Override
    public void movImm(int reg, long value) {
        int r = reg(reg);
        u32(0xD2800000 | (((int) value & 0xFFFF) << 5) | r);
        u32(0xF2800000 | ((((int) (value >>> 16)) & 0xFFFF) << 5) | r);
        u32(0xF2800000 | ((((int) (value >>> 32)) & 0xFFFF) << 5) | r);
        u32(0xF2800000 | ((((int) (value >>> 48)) & 0xFFFF) << 5) | r);
    }

    @Override
    public void movReg(int dst, int src) {
        // orr xd, xzr, xm
        insn(0xAA0003E0 | (reg(src) << 16) | (XZR << 5) | reg(dst));
    }

    /** Вычисляет адрес [base + disp] в регистре dst. */
    private void address(int dst, int base, int disp) {
        int d = reg(dst);
        int b = reg(base);
        if (disp == 0) {
            movReg(dst, base);
            return;
        }
        if (disp > 0 && disp <= 4095) {
            insn(0x91000000 | (disp << 10) | (b << 5) | d);
            return;
        }
        if (disp < 0 && -disp <= 4095) {
            insn(0xD1000000 | ((-disp) << 10) | (b << 5) | d);
            return;
        }
        movImm(TMP, disp);
        insn(0x8B000000 | (TMP << 16) | (b << 5) | d);
    }

    @Override
    public void load(int reg, int base, int disp) {
        address(TMP, base, disp);
        // ldr xt, [x16]
        insn(0xF9400000 | (TMP << 5) | reg(reg));
    }

    @Override
    public void store(int base, int disp, int reg) {
        address(TMP, base, disp);
        // str xt, [x16]
        insn(0xF9000000 | (TMP << 5) | reg(reg));
    }

    @Override
    public void lea(int reg, int base, int disp) {
        address(reg, base, disp);
    }

    // ---------------- арифметика ----------------

    @Override
    public void add(int dst, int src) {
        insn(0x8B000000 | (reg(src) << 16) | (reg(dst) << 5) | reg(dst));
    }

    @Override
    public void addImm(int reg, int imm) {
        int r = reg(reg);
        if (imm >= 0 && imm <= 4095) {
            insn(0x91000000 | (imm << 10) | (r << 5) | r);
            return;
        }
        movImm(TMP, imm);
        insn(0x8B000000 | (TMP << 16) | (r << 5) | r);
    }

    @Override
    public void subImm(int reg, int imm) {
        int r = reg(reg);
        if (imm >= 0 && imm <= 4095) {
            insn(0xD1000000 | (imm << 10) | (r << 5) | r);
            return;
        }
        movImm(TMP, -imm);
        insn(0xCB000000 | (TMP << 16) | (r << 5) | r);
    }

    @Override
    public void andImm(int reg, long mask) {
        logical(0x92000000, reg, mask);
    }

    @Override
    public void orImm(int reg, long mask) {
        logical(0xB2000000, reg, mask);
    }

    /**
     * Логическая операция с непосредственным операндом (bitmask immediate).
     * Если константа не представима в таком виде, используется регистр.
     */
    private void logical(int opcode, int reg, long mask) {
        int[] enc = encodeBitMask(mask);
        if (enc != null) {
            insn(opcode | (enc[0] << 22) | (enc[1] << 16) | (enc[2] << 10)
                    | (reg(reg) << 5) | reg(reg));
            return;
        }
        movImm(TMP, mask);
        if (opcode == 0x92000000) {
            // and xd, xn, x16
            insn(0x8A000000 | (TMP << 16) | (reg(reg) << 5) | reg(reg));
        } else {
            // orr xd, xn, x16
            insn(0xAA000000 | (TMP << 16) | (reg(reg) << 5) | reg(reg));
        }
    }

    /**
     * Кодирование 64-битной константы как «bitmask immediate» (N:immr:imms).
     * Возвращает {N, immr, imms} либо null, если константа не подходит.
     */
    static int[] encodeBitMask(long value) {
        int ones = Long.bitCount(value);
        if (ones == 0) {
            return null;
        }
        if (ones == 64) {
            return new int[]{1, 0, 63};   // все единицы
        }
        int lowest = Long.numberOfTrailingZeros(value);
        int highest = 63 - Long.numberOfLeadingZeros(value);
        int start;
        if (highest - lowest + 1 == ones) {
            start = lowest;
        } else {
            // Единицы «переходят» через границу 64 бита.
            start = (highest - ones + 1) & 63;
        }
        int immr = (64 - ones - start) & 63;
        return new int[]{1, immr, ones - 1};
    }

    @Override
    public void cmpImm(int reg, long imm) {
        int r = reg(reg);
        if (imm >= 0 && imm <= 4095) {
            // subs xzr, xN, #imm
            insn(0xF100001F | ((int) imm << 10) | (r << 5));
            return;
        }
        movImm(TMP, imm);
        // subs xzr, xN, x16
        insn(0xEB00001F | (TMP << 16) | (r << 5));
    }

    @Override
    public void shiftRightImm(int reg, int bits) {
        // lsr xd, xn, #bits
        insn(0xD3400000 | (bits << 16) | (reg(reg) << 5) | reg(reg));
    }

    @Override
    public void box(int reg, int tag) {
        orImm(reg, AotValues.BOX_BASE | ((long) tag << AotValues.TAG_SHIFT));
    }

    @Override
    public void payload(int reg) {
        andImm(reg, AotValues.PAYLOAD_MASK);
    }

    // ---------------- числа с плавающей точкой ----------------

    @Override
    public void loadDouble(int dreg, int base, int disp) {
        address(TMP, base, disp);
        // ldr dt, [x16]
        insn(0xFD400000 | (TMP << 5) | fp(dreg));
    }

    @Override
    public void storeDouble(int base, int disp, int dreg) {
        address(TMP, base, disp);
        // str dt, [x16]
        insn(0xFD000000 | (TMP << 5) | fp(dreg));
    }

    @Override
    public void doubleToInt(int reg, int dreg) {
        // fmov xd, dn
        insn(0x9E660000 | (fp(dreg) << 5) | reg(reg));
    }

    @Override
    public void intToDouble(int dreg, int reg) {
        // fmov dd, xn
        insn(0x9E660000 | (reg(reg) << 5) | fp(dreg));
    }

    @Override
    public void addDouble(int dst, int src) {
        // fadd dd, dn, dm
        insn(0x1E602800 | (fp(src) << 16) | (fp(dst) << 5) | fp(dst));
    }

    @Override
    public void subDouble(int dst, int src) {
        // fsub dd, dn, dm
        insn(0x1E601800 | (fp(src) << 16) | (fp(dst) << 5) | fp(dst));
    }

    @Override
    public void mulDouble(int dst, int src) {
        // fmul dd, dn, dm
        insn(0x1E603800 | (fp(src) << 16) | (fp(dst) << 5) | fp(dst));
    }

    @Override
    public void divDouble(int dst, int src) {
        // fdiv dd, dn, dm
        insn(0x1E604800 | (fp(src) << 16) | (fp(dst) << 5) | fp(dst));
    }

    @Override
    public void compareDouble(int a, int b) {
        // fcmp dn, dm (сравнение с нулём при Rn = 31)
        insn(0x1E602000 | (fp(b) << 16) | (XZR << 5));
    }

    // ---------------- вызовы ----------------

    @Override
    public void callApi(int apiIndex, int argsDisp) {
        // x0 = адрес аргументов
        address(0, FP, argsDisp);
        // x16 = адрес функции в таблице API
        adr(TMP, AotIsa.FIX_API, apiIndex);
        // ldr x16, [x16]
        insn(0xF9400000 | (TMP << 5) | TMP);
        // blr x16
        insn(0xD63F0200 | (TMP << 5));
    }

    @Override
    public void callReg(int reg) {
        // blr xN
        insn(0xD63F0000 | (reg(reg) << 5));
    }

    @Override
    public void callFunction(int functionIndex) {
        int at = out.size();
        // bl target
        insn(0x94000000);
        fixups.add(new AotElf.Fixup(at, AotIsa.FIX_FUNCTION, functionIndex, AotElf.MODE_BRANCH));
    }

    // ---------------- адреса данных ----------------

    /** ADR xd, адрес (смещение считается от начала инструкции). */
    private void adr(int dst, int kind, int index) {
        int at = out.size();
        insn(0x10000000 | (dst & 0x1F));
        fixups.add(new AotElf.Fixup(at, kind, index, AotElf.MODE_ADR));
    }

    @Override
    public void loadPool(int reg, int index) {
        adr(TMP, AotIsa.FIX_POOL, index);
        // ldr xt, [x16]
        insn(0xF9400000 | (TMP << 5) | reg(reg));
    }

    @Override
    public void leaPool(int reg, int index) {
        adr(reg, AotIsa.FIX_POOL, index);
    }

    @Override
    public void leaRodata(int reg, int index) {
        adr(reg, AotIsa.FIX_RODATA, index);
    }

    @Override
    public void leaApi(int reg, int index) {
        adr(reg, AotIsa.FIX_API, index);
    }

    @Override
    public void leaFunction(int reg, int functionIndex) {
        adr(reg, AotIsa.FIX_FUNCTION, functionIndex);
    }
}
