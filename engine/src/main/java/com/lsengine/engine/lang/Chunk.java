package com.lsengine.engine.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Скомпилированная программа LS — настоящий байт-код.
 *
 * Содержит пул констант (числа, строки, логические значения) и таблицу функций
 * с машинным кодом. Именно эта структура попадает в собранное приложение
 * (в зашифрованный контейнер .c), а не исходный текст.
 */
public final class Chunk {

    /** Описание upvalue (захвата переменной из внешней функции). */
    public static final class Upval {
        /** true — берётся локальный слот родительской функции, false — её upvalue. */
        public final boolean fromParentLocal;
        public final int index;
        public final String name;

        public Upval(boolean fromParentLocal, int index, String name) {
            this.fromParentLocal = fromParentLocal;
            this.index = index;
            this.name = name;
        }
    }

    /** Прототип функции: машинный код + метаданные. */
    public static final class FuncProto {
        public String name = "function";
        public int paramCount;
        public int localCount;
        public byte[] code = new byte[0];
        public final List<Upval> upvalues = new ArrayList<>();
        /** Слоты локальных переменных, захватываемых вложенными функциями. */
        public int[] capturedSlots = new int[0];

        public int instructionCount() {
            return code.length / Opcodes.INSTRUCTION_SIZE;
        }
    }

    /** Пул констант: Double, Boolean или String. */
    public final List<Object> constants = new ArrayList<>();
    /** Таблица функций. */
    public final List<FuncProto> functions = new ArrayList<>();
    /** Индекс функции-точки входа (верхний уровень скрипта). */
    public int entry = -1;
    /** Имя программы. */
    public String name = "main";

    public int addConstant(Object value) {
        for (int i = 0; i < constants.size(); i++) {
            Object c = constants.get(i);
            if (c instanceof Double && value instanceof Double) {
                if (((Double) c).doubleValue() == ((Double) value).doubleValue()) {
                    return i;
                }
            } else if (c instanceof Boolean && value instanceof Boolean) {
                if (((Boolean) c).booleanValue() == ((Boolean) value).booleanValue()) {
                    return i;
                }
            } else if (c instanceof String && value instanceof String) {
                if (c.equals(value)) {
                    return i;
                }
            }
        }
        constants.add(value);
        return constants.size() - 1;
    }

    public Object constant(int index) {
        return constants.get(index);
    }

    public String constantString(int index) {
        Object o = constants.get(index);
        return o instanceof String ? (String) o : null;
    }

    /** Дизассемблер — используется в отладке и в консоли редактора. */
    public String disassemble() {
        StringBuilder sb = new StringBuilder();
        sb.append("; LS bytecode ").append(name).append('\n');
        for (int i = 0; i < functions.size(); i++) {
            FuncProto f = functions.get(i);
            sb.append("\nfunction #").append(i).append(' ').append(f.name)
                    .append(" (params=").append(f.paramCount)
                    .append(", locals=").append(f.localCount)
                    .append(", upvals=").append(f.upvalues.size()).append(")\n");
            int count = f.instructionCount();
            for (int ip = 0; ip < count; ip++) {
                int off = ip * Opcodes.INSTRUCTION_SIZE;
                int op = f.code[off] & 0xFF;
                int a = f.code[off + 1] & 0xFF;
                int b = (f.code[off + 2] & 0xFF) | ((f.code[off + 3] & 0xFF) << 8);
                sb.append(String.format("  %4d  %-12s a=%d b=%d%n", ip, Opcodes.name(op), a, b));
            }
        }
        return sb.toString();
    }
}
