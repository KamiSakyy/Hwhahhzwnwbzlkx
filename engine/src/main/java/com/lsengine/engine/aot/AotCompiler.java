package com.lsengine.engine.aot;

import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Opcodes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Транслятор байт-кода LS в машинный код.
 *
 * Байт-код, который раньше исполняла виртуальная машина, превращается в
 * последовательность настоящих инструкций CPU: вызовы прямые, арифметика
 * выполняется процессором, интерпретатора в рантайме нет.
 */
public final class AotCompiler {

    private final Chunk chunk;
    private final AotBackend backend;

    /** Строковые константы: пул (ленивое создание) и .rodata (данные). */
    private final List<String> poolStrings = new ArrayList<>();
    private final Map<String, Integer> poolIndex = new HashMap<>();
    private final List<Integer> rodataOffsets = new ArrayList<>();
    private final List<Integer> rodataLengths = new ArrayList<>();
    private final ByteArrayOutputStream rodata = new ByteArrayOutputStream();

    private int[][] funcLabels;
    private int[] funcOffsets;
    private int nextLabel = 1;
    private int currentFunction;

    // Параметры текущей функции.
    private int localCount;
    private int frameSize;
    private int callBase;
    private int argsDisp;
    private boolean[] captured;

    public AotCompiler(Chunk chunk, AotBackend backend) {
        this.chunk = chunk;
        this.backend = backend;
    }

    public AotElf.Module compile() {
        int functionCount = chunk.functions.size();
        funcLabels = new int[functionCount][];
        for (int f = 0; f < functionCount; f++) {
            Chunk.FuncProto proto = chunk.functions.get(f);
            int count = proto.instructionCount();
            funcLabels[f] = new int[count];
            for (int i = 0; i < count; i++) {
                funcLabels[f][i] = nextLabel++;
            }
        }
        funcOffsets = new int[functionCount];
        for (int f = 0; f < functionCount; f++) {
            funcOffsets[f] = backend.codeSize();
            currentFunction = f;
            translateFunction(chunk.functions.get(f));
        }

        AotElf.Module module = new AotElf.Module();
        module.machine = backend.machineType();
        module.code = backend.code();
        module.rodata = rodata.toByteArray();
        module.functionOffsets = funcOffsets;
        module.fixups.addAll(backend.fixups());
        module.jumps.addAll(backend.jumps());
        int[] labelArray = new int[nextLabel];
        for (Map.Entry<Integer, Integer> e : backend.labels().entrySet()) {
            labelArray[e.getKey()] = e.getValue();
        }
        module.labelOffsets = labelArray;

        // .data: пул строковых констант, затем таблица API.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < poolStrings.size(); i++) {
            writeLong(data, AotValues.NIL);
        }
        module.poolOffset = 0;
        module.apiTableOffset = data.size();
        for (int i = 0; i < AotApi.COUNT; i++) {
            writeLong(data, 0);
        }
        module.data = data.toByteArray();

        module.rodataOffsets = new int[rodataOffsets.size()];
        for (int i = 0; i < rodataOffsets.size(); i++) {
            module.rodataOffsets[i] = rodataOffsets.get(i);
        }

        if (chunk.entry >= 0 && chunk.entry < funcOffsets.length) {
            AotElf.Export entry = new AotElf.Export();
            entry.name = "ls_app_entry";
            entry.offset = funcOffsets[chunk.entry];
            entry.inData = false;
            module.exported.add(entry);
        }
        AotElf.Export table = new AotElf.Export();
        table.name = "ls_app_api_table";
        table.offset = module.apiTableOffset;
        table.inData = true;
        module.exported.add(table);
        return module;
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }

    // ---------------- строковые константы ----------------

    private int poolSlot(String text) {
        Integer existing = poolIndex.get(text);
        if (existing != null) {
            return existing;
        }
        int slot = poolStrings.size();
        poolStrings.add(text);
        poolIndex.put(text, slot);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        rodataOffsets.add(rodata.size());
        rodataLengths.add(bytes.length);
        rodata.write(bytes, 0, bytes.length);
        rodata.write(0);
        return slot;
    }

    /** Кладёт значение строковой константы в dest (с ленивым созданием). */
    private void emitStringConstant(String text, int dest) {
        int slot = poolSlot(text);
        int dataIndex = rodataOffsets.size() - 1;
        int lblCreate = nextLabel++;
        int lblDone = nextLabel++;
        backend.leaPool(AotIsa.R1, slot);
        backend.load(AotIsa.R0, AotIsa.R1, 0);
        backend.jumpIfNe(AotIsa.R0, AotValues.NIL, lblCreate);
        backend.label(lblCreate);
        backend.movImm(AotIsa.R0, AotValues.num(slot));
        backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
        backend.leaRodata(AotIsa.R0, dataIndex);
        backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
        backend.movImm(AotIsa.R0, AotValues.num(rodataLengths.get(dataIndex)));
        backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R0);
        backend.movImm(AotIsa.R0, 3);
        backend.store(AotIsa.FP, callBase + 8, AotIsa.R0);
        backend.callApi(AotApi.CONST_STRING, argsDisp);
        backend.leaPool(AotIsa.R1, slot);
        backend.store(AotIsa.R1, 0, AotIsa.R0);
        backend.label(lblDone);
        backend.leaPool(AotIsa.R1, slot);
        backend.load(dest, AotIsa.R1, 0);
    }

    // ---------------- стек значений ----------------

    private void push(int reg) {
        backend.subImm(AotIsa.SP, 8);
        backend.store(AotIsa.SP, 0, reg);
    }

    private void pop(int reg) {
        backend.load(reg, AotIsa.SP, -8);
        backend.addImm(AotIsa.SP, 8);
    }

    private void peek(int reg) {
        backend.load(reg, AotIsa.SP, -8);
    }

    private void callApi(int index, int count) {
        backend.movImm(AotIsa.R0, count);
        backend.store(AotIsa.FP, callBase + 8, AotIsa.R0);
        backend.callApi(index, argsDisp);
    }

    // ---------------- функция ----------------

    private void translateFunction(Chunk.FuncProto proto) {
        localCount = proto.localCount;
        captured = new boolean[Math.max(1, localCount)];
        for (int slot : proto.capturedSlots) {
            if (slot >= 0 && slot < captured.length) {
                captured[slot] = true;
            }
        }
        int maxStack = analyzeStack(proto) + 8;
        int maxArgs = 0;
        int count = proto.instructionCount();
        for (int ip = 0; ip < count; ip++) {
            int off = ip * Opcodes.INSTRUCTION_SIZE;
            int op = proto.code[off] & 0xFF;
            int a = proto.code[off + 1] & 0xFF;
            if (op == Opcodes.CALL || op == Opcodes.PRINT) {
                maxArgs = Math.max(maxArgs, a);
            }
        }
        maxArgs = Math.max(maxArgs, 4);
        frameSize = backend.frameSize(localCount, maxStack, maxArgs);
        callBase = backend.frameBase() - frameSize;
        argsDisp = callBase + 16;

        backend.prologue(frameSize);
        backend.lea(AotIsa.SP, AotIsa.FP, backend.stackTopOffset(localCount));

        // Указатель на входящие аргументы сохраняем в кадре: вызовы API
        // портят регистры, а параметры читаются позже.
        backend.store(AotIsa.FP, backend.argsPtrOffset(localCount), AotIsa.ARGP);

        // Указатель на замыкание: в кадре (корень GC) и в регистре.
        // По соглашению вызова замыкание лежит на 16 байт ниже args[0].
        backend.load(AotIsa.R0, AotIsa.ARGP, -16);
        backend.store(AotIsa.FP, backend.closureSlotOffset(localCount), AotIsa.R0);
        backend.movReg(AotIsa.CL, AotIsa.R0);

        // Таблица глобальных переменных.
        callApi(AotApi.GLOBALS, 0);
        backend.movReg(AotIsa.GL, AotIsa.R0);

        // Ячейки для захваченных переменных.
        for (int slot : proto.capturedSlots) {
            backend.movImm(AotIsa.R0, AotValues.NIL);
            backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
            backend.movImm(AotIsa.R0, 1);
            backend.store(AotIsa.FP, callBase + 8, AotIsa.R0);
            backend.callApi(AotApi.NEW_CELL, argsDisp);
            backend.store(AotIsa.FP, backend.localOffset(slot), AotIsa.R0);
        }

        // Параметры: недостающие — nil.
        for (int i = 0; i < proto.paramCount; i++) {
            int lblNil = nextLabel++;
            int lblStore = nextLabel++;
            backend.load(AotIsa.R1, AotIsa.FP, backend.argsPtrOffset(localCount));
            backend.load(AotIsa.R1, AotIsa.R1, -8);   // количество аргументов
            backend.jumpIfBelow(AotIsa.R1, i + 1, lblNil);
            backend.load(AotIsa.R1, AotIsa.FP, backend.argsPtrOffset(localCount));
            backend.load(AotIsa.R0, AotIsa.R1, 8 * i); // args[i]
            backend.jump(lblStore);
            backend.label(lblNil);
            backend.movImm(AotIsa.R0, AotValues.NIL);
            backend.label(lblStore);
            if (i < captured.length && captured[i]) {
                backend.load(AotIsa.R1, AotIsa.FP, backend.localOffset(i));
                backend.store(AotIsa.R1, 0, AotIsa.R0);
            } else {
                backend.store(AotIsa.FP, backend.localOffset(i), AotIsa.R0);
            }
        }

        for (int ip = 0; ip < count; ip++) {
            int off = ip * Opcodes.INSTRUCTION_SIZE;
            int op = proto.code[off] & 0xFF;
            int a = proto.code[off + 1] & 0xFF;
            int b = (proto.code[off + 2] & 0xFF) | ((proto.code[off + 3] & 0xFF) << 8);
            translateInstruction(proto, ip, op, a, b);
        }
    }

    private int jumpTarget(int ip, int b) {
        int delta = (short) b;
        return ip + delta + 1;
    }

    private void translateInstruction(Chunk.FuncProto proto, int ip, int op, int a, int b) {
        switch (op) {
            case Opcodes.NOP:
                break;
            case Opcodes.PUSH_CONST: {
                Object c = chunk.constant(b);
                if (c instanceof Double) {
                    backend.movImm(AotIsa.R0, AotValues.num((Double) c));
                } else if (c instanceof Boolean) {
                    backend.movImm(AotIsa.R0, ((Boolean) c) ? AotValues.TRUE : AotValues.FALSE);
                } else if (c instanceof String) {
                    emitStringConstant((String) c, AotIsa.R0);
                    push(AotIsa.R0);
                    return;
                } else {
                    backend.movImm(AotIsa.R0, AotValues.NIL);
                }
                push(AotIsa.R0);
                break;
            }
            case Opcodes.PUSH_NIL:
                backend.movImm(AotIsa.R0, AotValues.NIL);
                push(AotIsa.R0);
                break;
            case Opcodes.PUSH_TRUE:
                backend.movImm(AotIsa.R0, AotValues.TRUE);
                push(AotIsa.R0);
                break;
            case Opcodes.PUSH_FALSE:
                backend.movImm(AotIsa.R0, AotValues.FALSE);
                push(AotIsa.R0);
                break;
            case Opcodes.LOAD_LOCAL: {
                backend.load(AotIsa.R0, AotIsa.FP, backend.localOffset(b));
                if (b < captured.length && captured[b]) {
                    backend.load(AotIsa.R0, AotIsa.R0, 0);
                }
                push(AotIsa.R0);
                break;
            }
            case Opcodes.STORE_LOCAL: {
                pop(AotIsa.R0);
                if (b < captured.length && captured[b]) {
                    backend.load(AotIsa.R1, AotIsa.FP, backend.localOffset(b));
                    backend.store(AotIsa.R1, 0, AotIsa.R0);
                } else {
                    backend.store(AotIsa.FP, backend.localOffset(b), AotIsa.R0);
                }
                break;
            }
            case Opcodes.LOAD_UPVAL: {
                backend.load(AotIsa.R0, AotIsa.CL, 16);
                backend.addImm(AotIsa.R0, 8 * b);
                backend.load(AotIsa.R0, AotIsa.R0, 0);
                backend.load(AotIsa.R0, AotIsa.R0, 0);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.STORE_UPVAL: {
                pop(AotIsa.R0);
                backend.load(AotIsa.R1, AotIsa.CL, 16);
                backend.addImm(AotIsa.R1, 8 * b);
                backend.load(AotIsa.R1, AotIsa.R1, 0);
                backend.store(AotIsa.R1, 0, AotIsa.R0);
                break;
            }
            case Opcodes.LOAD_GLOBAL: {
                emitStringConstant(chunk.constantString(b), AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp, AotIsa.GL);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
                callApi(AotApi.TABLE_GET, 2);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.STORE_GLOBAL: {
                pop(AotIsa.R2);
                emitStringConstant(chunk.constantString(b), AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp, AotIsa.GL);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R2);
                callApi(AotApi.TABLE_SET, 3);
                break;
            }
            case Opcodes.GET_FIELD: {
                emitStringConstant(chunk.constantString(b), AotIsa.R0);
                pop(AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
                callApi(AotApi.INDEX_GET, 2);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.SET_FIELD: {
                pop(AotIsa.R2);
                pop(AotIsa.R1);
                emitStringConstant(chunk.constantString(b), AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R2);
                callApi(AotApi.INDEX_SET, 3);
                break;
            }
            case Opcodes.GET_INDEX: {
                pop(AotIsa.R1);
                pop(AotIsa.R2);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R2);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R1);
                callApi(AotApi.INDEX_GET, 2);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.SET_INDEX: {
                pop(AotIsa.R2);
                pop(AotIsa.R1);
                pop(AotIsa.R3);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R3);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R2);
                callApi(AotApi.INDEX_SET, 3);
                break;
            }
            case Opcodes.NEW_TABLE:
                callApi(AotApi.NEW_TABLE, 0);
                push(AotIsa.R0);
                break;
            case Opcodes.ADD:
            case Opcodes.SUB:
            case Opcodes.MUL:
            case Opcodes.DIV:
            case Opcodes.MOD:
            case Opcodes.POW:
            case Opcodes.CONCAT:
            case Opcodes.EQ:
            case Opcodes.NE:
            case Opcodes.LT:
            case Opcodes.LE: {
                pop(AotIsa.R1);
                pop(AotIsa.R2);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R2);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R1);
                callApi(arithApi(op, false), 2);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.GT:
            case Opcodes.GE: {
                pop(AotIsa.R1);
                pop(AotIsa.R2);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R2);
                callApi(arithApi(op, true), 2);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.LEN:
            case Opcodes.NEG:
            case Opcodes.NOT: {
                pop(AotIsa.R1);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R1);
                callApi(op == Opcodes.LEN ? AotApi.LEN
                        : (op == Opcodes.NEG ? AotApi.NEG : AotApi.NOT), 1);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.JMP:
                backend.jump(funcLabels[currentFunction][jumpTarget(ip, b)]);
                break;
            case Opcodes.JZ: {
                int target = funcLabels[currentFunction][jumpTarget(ip, b)];
                peek(AotIsa.R0);
                backend.jumpIfEq(AotIsa.R0, AotValues.NIL, target);
                backend.jumpIfEq(AotIsa.R0, AotValues.FALSE, target);
                pop(AotIsa.R0);
                break;
            }
            case Opcodes.JNZ: {
                int target = funcLabels[currentFunction][jumpTarget(ip, b)];
                int skip = nextLabel++;
                peek(AotIsa.R0);
                backend.jumpIfNe(AotIsa.R0, AotValues.NIL, skip);
                backend.jumpIfNe(AotIsa.R0, AotValues.FALSE, skip);
                backend.jump(target);
                backend.label(skip);
                pop(AotIsa.R0);
                break;
            }
            case Opcodes.CALL:
                translateCall(a);
                break;
            case Opcodes.RETURN:
                backend.load(AotIsa.R0, AotIsa.SP, -8);
                backend.epilogue();
                break;
            case Opcodes.CLOSURE:
                translateClosure(b);
                break;
            case Opcodes.POP:
                pop(AotIsa.R0);
                break;
            case Opcodes.PRINT: {
                for (int i = 0; i < a; i++) {
                    backend.load(AotIsa.R0, AotIsa.SP, -8 * (a - i));
                    backend.store(AotIsa.FP, argsDisp + 8 * i, AotIsa.R0);
                }
                callApi(AotApi.PRINT, a);
                backend.addImm(AotIsa.SP, 8 * a);
                break;
            }
            case Opcodes.HALT:
                backend.movImm(AotIsa.R0, AotValues.NIL);
                backend.epilogue();
                break;
            case Opcodes.DUP:
                peek(AotIsa.R0);
                push(AotIsa.R0);
                break;
            case Opcodes.SWAP: {
                backend.load(AotIsa.R0, AotIsa.SP, -8);
                backend.load(AotIsa.R1, AotIsa.SP, -16);
                backend.store(AotIsa.SP, -8, AotIsa.R1);
                backend.store(AotIsa.SP, -16, AotIsa.R0);
                break;
            }
            case Opcodes.FOR_PREP: {
                pop(AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
                callApi(AotApi.FOR_PREP, 1);
                push(AotIsa.R0);
                backend.movImm(AotIsa.R0, AotValues.NIL);
                push(AotIsa.R0);
                push(AotIsa.R0);
                break;
            }
            case Opcodes.FOR_ITER: {
                int target = funcLabels[currentFunction][jumpTarget(ip, b)];
                int done = nextLabel++;
                backend.load(AotIsa.R0, AotIsa.SP, -24);
                backend.load(AotIsa.R1, AotIsa.SP, -8);
                backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
                backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R1);
                backend.lea(AotIsa.R0, AotIsa.FP, backend.scratchSlotOffset(localCount));
                backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R0);
                backend.movImm(AotIsa.R0, 3);
                backend.store(AotIsa.FP, callBase + 8, AotIsa.R0);
                backend.callApi(AotApi.FOR_ITER, argsDisp);
                backend.jumpIfEq(AotIsa.R0, AotValues.NIL, target);
                backend.store(AotIsa.SP, -8, AotIsa.R0);
                push(AotIsa.R0);
                backend.load(AotIsa.R1, AotIsa.FP, backend.scratchSlotOffset(localCount));
                push(AotIsa.R1);
                backend.label(done);
                break;
            }
            default:
                throw new IllegalStateException("неизвестная инструкция " + op);
        }
    }

    private static int arithApi(int op, boolean swapped) {
        switch (op) {
            case Opcodes.ADD: return AotApi.ADD;
            case Opcodes.SUB: return AotApi.SUB;
            case Opcodes.MUL: return AotApi.MUL;
            case Opcodes.DIV: return AotApi.DIV;
            case Opcodes.MOD: return AotApi.MOD;
            case Opcodes.POW: return AotApi.POW;
            case Opcodes.CONCAT: return AotApi.CONCAT;
            case Opcodes.EQ: return AotApi.EQ;
            case Opcodes.NE: return AotApi.NE;
            case Opcodes.LT: return AotApi.LT;
            case Opcodes.LE: return AotApi.LE;
            case Opcodes.GT: return AotApi.LT;
            default: return AotApi.LE;
        }
    }

    private void translateCall(int argc) {
        int lblError = nextLabel++;
        int lblAfter = nextLabel++;
        backend.load(AotIsa.R0, AotIsa.SP, -8 * (argc + 1));
        backend.payload(AotIsa.R0);
        backend.load(AotIsa.R1, AotIsa.R0, 0);
        backend.store(AotIsa.FP, callBase, AotIsa.R0);
        backend.movImm(AotIsa.R0, argc);
        backend.store(AotIsa.FP, callBase + 8, AotIsa.R0);
        for (int i = 0; i < argc; i++) {
            backend.load(AotIsa.R0, AotIsa.SP, -8 * (argc - i));
            backend.store(AotIsa.FP, callBase + 16 + 8 * i, AotIsa.R0);
        }
        backend.jumpIfZero(AotIsa.R1, lblError);
        // Указатель на аргументы передаём по соглашению вызова.
        backend.lea(AotIsa.ARGP, AotIsa.FP, argsDisp);
        backend.callReg(AotIsa.R1);
        // Убираем callee и аргументы из стека значений.
        backend.addImm(AotIsa.SP, 8 * (argc + 1));
        backend.jump(lblAfter);
        backend.label(lblError);
        backend.movImm(AotIsa.R0, AotValues.num(1));
        backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
        callApi(AotApi.ERROR, 1);
        backend.movImm(AotIsa.R0, AotValues.NIL);
        backend.label(lblAfter);
        push(AotIsa.R0);
    }

    private void translateClosure(int targetIndex) {
        Chunk.FuncProto target = chunk.functions.get(targetIndex);
        backend.leaFunction(AotIsa.R0, targetIndex);
        backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
        backend.movImm(AotIsa.R0, AotValues.num(target.paramCount));
        backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
        backend.movImm(AotIsa.R0, AotValues.num(target.upvalues.size()));
        backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R0);
        callApi(AotApi.NEW_CLOSURE, 3);
        backend.movReg(AotIsa.R2, AotIsa.R0);
        for (int i = 0; i < target.upvalues.size(); i++) {
            Chunk.Upval u = target.upvalues.get(i);
            backend.movReg(AotIsa.R0, AotIsa.R2);
            backend.store(AotIsa.FP, argsDisp, AotIsa.R0);
            backend.movImm(AotIsa.R0, AotValues.num(i));
            backend.store(AotIsa.FP, argsDisp + 8, AotIsa.R0);
            if (u.fromParentLocal) {
                backend.load(AotIsa.R1, AotIsa.FP, backend.localOffset(u.index));
            } else {
                backend.load(AotIsa.R1, AotIsa.CL, 16);
                backend.addImm(AotIsa.R1, 8 * u.index);
                backend.load(AotIsa.R1, AotIsa.R1, 0);
            }
            backend.store(AotIsa.FP, argsDisp + 16, AotIsa.R1);
            callApi(AotApi.CLOSURE_SET_UPVAL, 3);
        }
        backend.movReg(AotIsa.R0, AotIsa.R2);
        backend.box(AotIsa.R0, AotValues.TAG_FUNC);
        push(AotIsa.R0);
    }

    // ---------------- анализ глубины стека ----------------

    private int analyzeStack(Chunk.FuncProto proto) {
        int count = proto.instructionCount();
        if (count == 0) {
            return 0;
        }
        int[] depth = new int[count];
        Arrays.fill(depth, -1);
        int[] queue = new int[count * 4];
        int head = 0;
        int tail = 0;
        depth[0] = 0;
        queue[tail++] = 0;
        int max = 0;
        while (head < tail) {
            int ip = queue[head++];
            int d = depth[ip];
            int off = ip * Opcodes.INSTRUCTION_SIZE;
            int op = proto.code[off] & 0xFF;
            int a = proto.code[off + 1] & 0xFF;
            int b = (proto.code[off + 2] & 0xFF) | ((proto.code[off + 3] & 0xFF) << 8);
            if (op == Opcodes.RETURN || op == Opcodes.HALT) {
                continue;
            }
            int nextDepth = d + stackDelta(op, a);
            if (nextDepth < 0) {
                nextDepth = 0;
            }
            if (nextDepth > max) {
                max = nextDepth;
            }
            int next = ip + 1;
            if (next < count && depth[next] < nextDepth) {
                depth[next] = nextDepth;
                queue[tail++] = next;
            }
            if (op == Opcodes.JMP || op == Opcodes.JZ || op == Opcodes.JNZ
                    || op == Opcodes.FOR_ITER) {
                int target = jumpTarget(ip, b);
                if (target >= 0 && target < count && depth[target] < d) {
                    depth[target] = d;
                    queue[tail++] = target;
                }
            }
            if (tail >= queue.length) {
                break;
            }
        }
        return max;
    }

    private static int stackDelta(int op, int a) {
        switch (op) {
            case Opcodes.PUSH_CONST:
            case Opcodes.PUSH_NIL:
            case Opcodes.PUSH_TRUE:
            case Opcodes.PUSH_FALSE:
            case Opcodes.LOAD_LOCAL:
            case Opcodes.LOAD_UPVAL:
            case Opcodes.LOAD_GLOBAL:
            case Opcodes.NEW_TABLE:
            case Opcodes.CLOSURE:
            case Opcodes.DUP:
                return 1;
            case Opcodes.STORE_LOCAL:
            case Opcodes.STORE_UPVAL:
            case Opcodes.STORE_GLOBAL:
            case Opcodes.POP:
                return -1;
            case Opcodes.GET_FIELD:
            case Opcodes.GET_INDEX:
            case Opcodes.LEN:
            case Opcodes.NEG:
            case Opcodes.NOT:
            case Opcodes.SWAP:
                return 0;
            case Opcodes.SET_FIELD:
            case Opcodes.SET_INDEX:
                return -2;
            case Opcodes.ADD:
            case Opcodes.SUB:
            case Opcodes.MUL:
            case Opcodes.DIV:
            case Opcodes.MOD:
            case Opcodes.POW:
            case Opcodes.CONCAT:
            case Opcodes.EQ:
            case Opcodes.NE:
            case Opcodes.LT:
            case Opcodes.LE:
            case Opcodes.GT:
            case Opcodes.GE:
                return -1;
            case Opcodes.CALL:
                return -a;
            case Opcodes.PRINT:
                return -a;
            case Opcodes.FOR_PREP:
            case Opcodes.FOR_ITER:
                return 2;
            case Opcodes.JZ:
            case Opcodes.JNZ:
                // Условие снимается со стека.
                return -1;
            default:
                return 0;
        }
    }
}
