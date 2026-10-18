package com.lsengine.engine.vm;

import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Виртуальная машина LS — интерпретатор настоящего байт-кода.
 *
 * Модель вычислений: стек значений, кадры вызовов, замыкания с upvalue
 * (захваты переменных по ссылке). Никакой интерпретации исходного текста —
 * только исполнение скомпилированных инструкций.
 */
public final class Vm {

    /** Ячейка переменной — используется для захвата переменной замыканием. */
    public interface Printer {
        void print(String line);
    }

    private static final class Frame {
        final Closure closure;
        int pc;
        final int stackBase;
        final Value[] locals;
        final Cell[] cells;

        Frame(Closure closure, int stackBase, Value[] locals, Cell[] cells) {
            this.closure = closure;
            this.pc = 0;
            this.stackBase = stackBase;
            this.locals = locals;
            this.cells = cells;
        }
    }

    private final Table globals = new Table();
    private Value[] stack = new Value[256];
    private int sp;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private Chunk chunk;
    private Printer printer = line -> {
    };
    private long stepBudget = 500_000_000L;
    private long steps;

    // ---------------- публичный API ----------------

    public Table globals() {
        return globals;
    }

    public void setPrinter(Printer printer) {
        this.printer = printer == null ? line -> {
        } : printer;
    }

    /** Вывод строки в консоль (используется встроенной функцией print). */
    public void printLine(String line) {
        printer.print(line);
    }

    public void setStepBudget(long budget) {
        this.stepBudget = budget;
    }

    /** Загружает скомпилированную программу. */
    public void load(Chunk chunk) {
        this.chunk = chunk;
        this.steps = 0;
    }

    public Chunk chunk() {
        return chunk;
    }

    /** Запускает верхний уровень программы. */
    public void start() {
        if (chunk == null || chunk.entry < 0) {
            return;
        }
        resetStack();
        frames.clear();
        Closure entry = new Closure(chunk.functions.get(chunk.entry), new Cell[0]);
        callClosure(entry, new Value[0]);
        runFrames(1);
    }

    /** Вызывает значение-функцию из Java (используется встроенными функциями). */
    public Value call(Value fn, Value[] args) {
        if (fn == null || fn.isNil()) {
            throw new VmException("попытка вызвать значение nil");
        }
        if (fn.isNative()) {
            return fn.asNative().call(this, args);
        }
        if (fn.isFunction()) {
            int depth = frames.size();
            callClosure(fn.asFunction(), args);
            runFrames(depth + 1);
            Value result = sp > 0 ? stack[sp - 1] : Value.nil();
            sp = Math.max(0, sp - 1);
            return result;
        }
        throw new VmException("попытка вызвать значение типа " + fn.typeName());
    }

    /** Вызывает функцию и возвращает её результат (для native-обработчиков). */
    public Value callFunction(Value fn, Value[] args) {
        return call(fn, args == null ? new Value[0] : args);
    }

    public boolean isRunning() {
        return !frames.isEmpty();
    }

    public void stop() {
        frames.clear();
        sp = 0;
    }

    // ---------------- внутреннее ----------------

    private void resetStack() {
        sp = 0;
        clearStack();
    }

    private void clearStack() {
        for (int i = 0; i < stack.length; i++) {
            stack[i] = null;
        }
    }

    private void ensureStack(int need) {
        if (need + 16 <= stack.length) {
            return;
        }
        int newSize = stack.length;
        while (newSize < need + 16) {
            newSize *= 2;
        }
        Value[] bigger = new Value[newSize];
        System.arraycopy(stack, 0, bigger, 0, sp);
        stack = bigger;
    }

    private void push(Value v) {
        ensureStack(sp + 1);
        stack[sp++] = v == null ? Value.nil() : v;
    }

    private Value pop() {
        if (sp <= 0) {
            throw new VmException("переполнение стека (внутренняя ошибка VM)");
        }
        Value v = stack[--sp];
        stack[sp] = null;
        return v == null ? Value.nil() : v;
    }

    private Value peek(int back) {
        int i = sp - 1 - back;
        if (i < 0) {
            return Value.nil();
        }
        Value v = stack[i];
        return v == null ? Value.nil() : v;
    }

    private void callClosure(Closure closure, Value[] args) {
        Chunk.FuncProto proto = closure.proto;
        int argc = args == null ? 0 : args.length;
        Value[] locals = new Value[Math.max(1, proto.localCount)];
        for (int i = 0; i < locals.length; i++) {
            locals[i] = Value.nil();
        }
        for (int i = 0; i < argc && i < locals.length; i++) {
            locals[i] = args[i];
        }
        Cell[] cells = new Cell[locals.length];
        for (int slot : proto.capturedSlots) {
            if (slot >= 0 && slot < cells.length) {
                cells[slot] = new Cell(locals[slot]);
            }
        }
        int stackBase = sp;
        frames.add(new Frame(closure, stackBase, locals, cells));
    }

    private void returnFromFrame(Value result) {
        if (frames.isEmpty()) {
            return;
        }
        Frame f = frames.remove(frames.size() - 1);
        sp = f.stackBase;
        push(result);
    }

    private static int signed16(int v) {
        return (short) v;
    }

    private Value constant(int index) {
        Object c = chunk.constant(index);
        if (c instanceof Double) {
            return Value.of((Double) c);
        }
        if (c instanceof Boolean) {
            return Value.of((Boolean) c);
        }
        if (c instanceof String) {
            return Value.of((String) c);
        }
        return Value.nil();
    }

    private String constantString(int index) {
        Object c = chunk.constant(index);
        return c instanceof String ? (String) c : null;
    }

    // ---------------- главный цикл ----------------

    private void runFrames(int stopDepth) {
        while (frames.size() >= stopDepth) {
            Frame f = frames.get(frames.size() - 1);
            Chunk.FuncProto proto = f.closure.proto;
            byte[] code = proto.code;
            if (f.pc >= code.length) {
                returnFromFrame(Value.nil());
                continue;
            }
            if (++steps > stepBudget) {
                throw new VmException("превышен лимит выполнения (возможен бесконечный цикл)");
            }
            int ip = f.pc;
            f.pc = ip + Opcodes.INSTRUCTION_SIZE;
            int op = code[ip] & 0xFF;
            int a = code[ip + 1] & 0xFF;
            int b = (code[ip + 2] & 0xFF) | ((code[ip + 3] & 0xFF) << 8);
            execute(f, proto, op, a, b);
        }
    }

    private void execute(Frame f, Chunk.FuncProto proto, int op, int a, int b) {
        switch (op) {
            case Opcodes.NOP:
                break;
            case Opcodes.PUSH_CONST:
                push(constant(b));
                break;
            case Opcodes.PUSH_NIL:
                push(Value.nil());
                break;
            case Opcodes.PUSH_TRUE:
                push(Value.of(true));
                break;
            case Opcodes.PUSH_FALSE:
                push(Value.of(false));
                break;
            case Opcodes.LOAD_LOCAL: {
                Cell cell = f.cells[b];
                push(cell != null ? cell.value : f.locals[b]);
                break;
            }
            case Opcodes.STORE_LOCAL: {
                Value v = pop();
                Cell cell = f.cells[b];
                if (cell != null) {
                    cell.value = v;
                } else {
                    f.locals[b] = v;
                }
                break;
            }
            case Opcodes.LOAD_UPVAL:
                push(f.closure.upvalues[b].value);
                break;
            case Opcodes.STORE_UPVAL:
                f.closure.upvalues[b].value = pop();
                break;
            case Opcodes.LOAD_GLOBAL:
                push(globals.get(constantString(b)));
                break;
            case Opcodes.STORE_GLOBAL:
                globals.set(constantString(b), pop());
                break;
            case Opcodes.GET_FIELD: {
                Value t = pop();
                push(indexOf(t, Value.of(constantString(b))));
                break;
            }
            case Opcodes.SET_FIELD: {
                Value v = pop();
                Value t = pop();
                tableOf(t).set(constantString(b), v);
                break;
            }
            case Opcodes.GET_INDEX: {
                Value key = pop();
                Value t = pop();
                push(indexOf(t, key));
                break;
            }
            case Opcodes.SET_INDEX: {
                Value v = pop();
                Value key = pop();
                Value t = pop();
                tableOf(t).set(key, v);
                break;
            }
            case Opcodes.NEW_TABLE:
                push(Value.of(new Table()));
                break;
            case Opcodes.ADD: {
                Value right = pop();
                Value left = pop();
                if (left.isNumber() && right.isNumber()) {
                    push(Value.of(left.asNumber() + right.asNumber()));
                } else if (left.isString() || right.isString()) {
                    push(Value.of(left.toString() + right.toString()));
                } else {
                    throw new VmException("нельзя сложить " + left.typeName() + " и " + right.typeName());
                }
                break;
            }
            case Opcodes.SUB: {
                Value right = pop();
                Value left = pop();
                push(Value.of(numberOf(left, "-") - numberOf(right, "-")));
                break;
            }
            case Opcodes.MUL: {
                Value right = pop();
                Value left = pop();
                push(Value.of(numberOf(left, "*") * numberOf(right, "*")));
                break;
            }
            case Opcodes.DIV: {
                Value right = pop();
                Value left = pop();
                push(Value.of(numberOf(left, "/") / numberOf(right, "/")));
                break;
            }
            case Opcodes.MOD: {
                Value right = pop();
                Value left = pop();
                double x = numberOf(left, "%");
                double y = numberOf(right, "%");
                double r = x - Math.floor(x / y) * y;
                push(Value.of(r));
                break;
            }
            case Opcodes.POW: {
                Value right = pop();
                Value left = pop();
                push(Value.of(Math.pow(numberOf(left, "^"), numberOf(right, "^"))));
                break;
            }
            case Opcodes.CONCAT: {
                Value right = pop();
                Value left = pop();
                push(Value.of(left.toString() + right.toString()));
                break;
            }
            case Opcodes.LEN: {
                Value v = pop();
                if (v.isString()) {
                    push(Value.of(v.asString().length()));
                } else if (v.isTable()) {
                    push(Value.of(v.asTable().length()));
                } else {
                    throw new VmException("длина применима только к строке или таблице");
                }
                break;
            }
            case Opcodes.NEG:
                push(Value.of(-numberOf(pop(), "-")));
                break;
            case Opcodes.NOT:
                push(Value.of(!pop().isTruthy()));
                break;
            case Opcodes.EQ: {
                Value right = pop();
                Value left = pop();
                push(Value.of(left.equals(right)));
                break;
            }
            case Opcodes.NE: {
                Value right = pop();
                Value left = pop();
                push(Value.of(!left.equals(right)));
                break;
            }
            case Opcodes.LT:
            case Opcodes.LE:
            case Opcodes.GT:
            case Opcodes.GE: {
                Value right = pop();
                Value left = pop();
                push(Value.of(compare(op, left, right)));
                break;
            }
            case Opcodes.JMP:
                f.pc += signed16(b) * Opcodes.INSTRUCTION_SIZE;
                break;
            case Opcodes.JZ: {
                boolean truthy = pop().isTruthy();
                if (!truthy) {
                    f.pc += signed16(b) * Opcodes.INSTRUCTION_SIZE;
                }
                break;
            }
            case Opcodes.JNZ: {
                boolean truthy = pop().isTruthy();
                if (truthy) {
                    f.pc += signed16(b) * Opcodes.INSTRUCTION_SIZE;
                }
                break;
            }
            case Opcodes.CALL: {
                int argc = a;
                int calleeIdx = sp - argc - 1;
                if (calleeIdx < 0) {
                    throw new VmException("некорректный вызов функции");
                }
                Value callee = stack[calleeIdx];
                if (callee.isNative()) {
                    Value[] args = new Value[argc];
                    for (int i = 0; i < argc; i++) {
                        args[i] = stack[calleeIdx + 1 + i];
                    }
                    sp = calleeIdx;
                    Value r = callee.asNative().call(this, args);
                    push(r);
                } else if (callee.isFunction()) {
                    Value[] args = new Value[argc];
                    for (int i = 0; i < argc; i++) {
                        args[i] = stack[calleeIdx + 1 + i];
                    }
                    sp = calleeIdx;
                    callClosure(callee.asFunction(), args);
                } else {
                    throw new VmException("попытка вызвать значение типа " + callee.typeName());
                }
                break;
            }
            case Opcodes.RETURN: {
                Value result = sp > f.stackBase ? pop() : Value.nil();
                returnFromFrame(result);
                break;
            }
            case Opcodes.CLOSURE: {
                Chunk.FuncProto target = chunk.functions.get(b);
                Cell[] ups = new Cell[target.upvalues.size()];
                for (int i = 0; i < ups.length; i++) {
                    Chunk.Upval u = target.upvalues.get(i);
                    if (u.fromParentLocal) {
                        Cell cell = f.cells[u.index];
                        if (cell == null) {
                            cell = new Cell(f.locals[u.index]);
                            f.cells[u.index] = cell;
                        }
                        ups[i] = cell;
                    } else {
                        ups[i] = f.closure.upvalues[u.index];
                    }
                }
                push(Value.of(new Closure(target, ups)));
                break;
            }
            case Opcodes.POP:
                pop();
                break;
            case Opcodes.PRINT: {
                int count = a;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        sb.append('\t');
                    }
                    sb.append(pop().toString());
                }
                printer.print(sb.toString());
                break;
            }
            case Opcodes.HALT:
                frames.clear();
                break;
            case Opcodes.DUP:
                push(peek(0));
                break;
            case Opcodes.SWAP: {
                Value top = pop();
                Value second = pop();
                push(top);
                push(second);
                break;
            }
            case Opcodes.FOR_PREP: {
                Value it = pop();
                Value iterValue;
                Value s = Value.nil();
                Value ctrl = Value.nil();
                if (it.isTable()) {
                    iterValue = Value.of(new IteratorState(it.asTable()));
                } else if (it.isIterator()) {
                    iterValue = it;
                } else if (it.isFunction() || it.isNative()) {
                    iterValue = it;
                } else {
                    throw new VmException("некорректный итератор: " + it.typeName());
                }
                push(iterValue);
                push(s);
                push(ctrl);
                break;
            }
            case Opcodes.FOR_ITER: {
                int varCount = a;
                int base = sp - 3;
                if (base < 0) {
                    throw new VmException("повреждённый цикл for");
                }
                Value iterFn = stack[base];
                Value state = stack[base + 1];
                Value control = stack[base + 2];
                Value key = null;
                Value val = Value.nil();
                if (iterFn.isIterator()) {
                    IteratorState st = iterFn.asIterator();
                    if (st.index < st.keys.size()) {
                        key = st.keys.get(st.index++);
                        val = st.table.get(key);
                    }
                } else {
                    Value res = call(iterFn, new Value[]{state, control});
                    if (res != null && !res.isNil()) {
                        key = res;
                        val = Value.nil();
                    }
                }
                if (key == null) {
                    f.pc += signed16(b) * Opcodes.INSTRUCTION_SIZE;
                } else {
                    stack[base + 2] = key;
                    push(key);
                    push(val);
                }
                break;
            }
            default:
                throw new VmException("неизвестная инструкция " + op);
        }
    }

    private double numberOf(Value v, String op) {
        if (v.isNumber()) {
            return v.asNumber();
        }
        throw new VmException("ожидалось число в операции '" + op + "', получено " + v.typeName());
    }

    private boolean compare(int op, Value left, Value right) {
        if (left.isNumber() && right.isNumber()) {
            double a = left.asNumber();
            double b = right.asNumber();
            switch (op) {
                case Opcodes.LT: return a < b;
                case Opcodes.LE: return a <= b;
                case Opcodes.GT: return a > b;
                default: return a >= b;
            }
        }
        if (left.isString() && right.isString()) {
            int c = left.asString().compareTo(right.asString());
            switch (op) {
                case Opcodes.LT: return c < 0;
                case Opcodes.LE: return c <= 0;
                case Opcodes.GT: return c > 0;
                default: return c >= 0;
            }
        }
        throw new VmException("нельзя сравнить " + left.typeName() + " и " + right.typeName());
    }

    private Value indexOf(Value target, Value key) {
        if (target.isTable()) {
            return target.asTable().get(key);
        }
        if (target.isString()) {
            String s = target.asString();
            if (key.isNumber()) {
                int i = (int) key.asNumber();
                if (i >= 1 && i <= s.length()) {
                    return Value.of(String.valueOf(s.charAt(i - 1)));
                }
                return Value.nil();
            }
        }
        throw new VmException("попытка обратиться к элементу типа " + target.typeName());
    }

    private Table tableOf(Value v) {
        if (v.isTable()) {
            return v.asTable();
        }
        throw new VmException("ожидалась таблица, получено " + v.typeName());
    }

    // ---------------- вызов из Java ----------------

    /** Вызывает функцию LS из Java и возвращает результат. */
    public Value callLua(Value fn, List<Value> args) {
        Value[] arr = args == null ? new Value[0] : args.toArray(new Value[0]);
        return call(fn, arr);
    }
}
