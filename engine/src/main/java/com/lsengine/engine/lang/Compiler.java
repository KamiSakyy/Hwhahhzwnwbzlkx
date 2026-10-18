package com.lsengine.engine.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Компилятор языка LS: превращает исходный текст в настоящий байт-код
 * (пул констант + таблица функций с машинными инструкциями).
 */
public final class Compiler {

    private static final String FOR_LIMIT = "limit";
    private static final String FOR_STEP = "step";

    private static final class FuncState {
        final FuncState parent;
        final String name;
        final int paramCount;
        int localCount;
        /** Максимальное число локальных переменных за всё время компиляции функции. */
        int maxLocals;
        final List<String> localNames = new ArrayList<>();
        final Map<String, Integer> localSlots = new HashMap<>();
        final List<Chunk.Upval> upvalues = new ArrayList<>();
        final Map<String, Integer> upvalueSlots = new HashMap<>();
        final java.util.LinkedHashSet<Integer> capturedLocals = new java.util.LinkedHashSet<>();
        ByteArray code = new ByteArray();
        int scopeMark;

        FuncState(FuncState parent, String name, int paramCount) {
            this.parent = parent;
            this.name = name;
            this.paramCount = paramCount;
        }
    }

    private static final class Loop {
        final List<Integer> breaks = new ArrayList<>();
    }

    /** Простой растущий буфер байтов. */
    private static final class ByteArray {
        byte[] data = new byte[256];
        int size;

        void write(int b) {
            if (size == data.length) {
                byte[] n = new byte[data.length * 2];
                System.arraycopy(data, 0, n, 0, size);
                data = n;
            }
            data[size++] = (byte) b;
        }

        void write(byte[] src, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(src[off + i]);
            }
        }

        int size() {
            return size;
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    private final Chunk chunk = new Chunk();
    private final List<Loop> loops = new ArrayList<>();
    private FuncState current;

    public Chunk compile(String source) throws LsError {
        Ast.Block block = Parser.parse(source);
        chunk.name = "main";
        current = new FuncState(null, "main", 0);
        compileBlock(block);
        emit(Opcodes.RETURN, 0, 0);
        chunk.functions.add(toProto(current));
        chunk.entry = chunk.functions.size() - 1;
        return chunk;
    }

    // ---------------- вспомогательное ----------------

    private void emit(int op) {
        emit(op, 0, 0);
    }

    private void emit(int op, int a, int b) {
        current.code.write(op);
        current.code.write(a);
        current.code.write(b & 0xFF);
        current.code.write((b >> 8) & 0xFF);
    }

    private int emitJump(int op) {
        int pos = current.code.size();
        emit(op, 0, 0);
        return pos;
    }

    private void patchJump(int pos) {
        patchJumpTo(pos, current.code.size());
    }

    /** Записывает переход на указанный адрес (в байтах кода). */
    private void patchJumpTo(int pos, int target) {
        // Смещение отсчитывается от инструкции ПОСЛЕ перехода, поэтому -1.
        int delta = (target - pos) / Opcodes.INSTRUCTION_SIZE - 1;
        byte[] code = current.code.data;
        code[pos + 2] = (byte) (delta & 0xFF);
        code[pos + 3] = (byte) ((delta >> 8) & 0xFF);
    }

    private void openScope() {
        current.scopeMark = current.localCount;
    }

    private void closeScope() {
        for (int i = current.localCount - 1; i >= current.scopeMark; i--) {
            String n = current.localNames.get(i);
            current.localSlots.remove(n);
        }
        current.localCount = current.scopeMark;
    }

    private int declareLocal(String name) {
        int slot = current.localCount++;
        if (current.localCount > current.maxLocals) {
            current.maxLocals = current.localCount;
        }
        while (current.localNames.size() <= slot) {
            current.localNames.add(null);
        }
        current.localNames.set(slot, name);
        current.localSlots.put(name, slot);
        return slot;
    }

    private int findLocal(FuncState f, String name) {
        Integer s = f.localSlots.get(name);
        return s == null ? -1 : s;
    }

    private int findUpvalue(FuncState f, String name) {
        Integer idx = f.upvalueSlots.get(name);
        if (idx != null) {
            return idx;
        }
        if (f.parent == null) {
            return -1;
        }
        Integer parentLocal = f.parent.localSlots.get(name);
        if (parentLocal != null) {
            f.parent.capturedLocals.add(parentLocal);
            f.upvalues.add(new Chunk.Upval(true, parentLocal, name));
            f.upvalueSlots.put(name, f.upvalues.size() - 1);
            return f.upvalues.size() - 1;
        }
        int parentUp = findUpvalue(f.parent, name);
        if (parentUp >= 0) {
            f.upvalues.add(new Chunk.Upval(false, parentUp, name));
            f.upvalueSlots.put(name, f.upvalues.size() - 1);
            return f.upvalues.size() - 1;
        }
        return -1;
    }

    private Chunk.FuncProto toProto(FuncState f) {
        Chunk.FuncProto proto = new Chunk.FuncProto();
        proto.name = f.name;
        proto.paramCount = f.paramCount;
        // Локальные переменные циклов освобождаются раньше конца функции,
        // но слоты в машинном коде остаются — нужен максимум, а не текущее число.
        proto.localCount = Math.max(f.localCount, f.maxLocals);
        proto.code = f.code.toByteArray();
        proto.upvalues.addAll(f.upvalues);
        int[] captured = new int[f.capturedLocals.size()];
        int i = 0;
        for (int slot : f.capturedLocals) {
            captured[i++] = slot;
        }
        proto.capturedSlots = captured;
        return proto;
    }

    private int compileFunction(String name, List<String> params, Ast.Block body) throws LsError {
        FuncState parent = current;
        FuncState fs = new FuncState(parent, name, params.size());
        current = fs;
        for (String p : params) {
            declareLocal(p);
        }
        compileBlock(body);
        emit(Opcodes.RETURN, 0, 0);
        current = parent;
        int index = chunk.functions.size();
        chunk.functions.add(toProto(fs));
        return index;
    }

    // ---------------- операторы ----------------

    private void compileBlock(Ast.Block block) throws LsError {
        openScope();
        for (Ast.Stat stat : block.stats) {
            compileStat(stat);
        }
        closeScope();
    }

    private void compileStat(Ast.Stat stat) throws LsError {
        if (stat instanceof Ast.VarDecl) {
            Ast.VarDecl decl = (Ast.VarDecl) stat;
            if (decl.value != null) {
                compileExpr(decl.value);
            } else {
                emit(Opcodes.PUSH_NIL, 0, 0);
            }
            int slot = declareLocal(decl.name);
            emit(Opcodes.STORE_LOCAL, 0, slot);
        } else if (stat instanceof Ast.Assign) {
            Ast.Assign assign = (Ast.Assign) stat;
            compileAssign(assign);
        } else if (stat instanceof Ast.If) {
            Ast.If node = (Ast.If) stat;
            compileExpr(node.cond);
            int elseJump = emitJump(Opcodes.JZ);
            compileBlock(node.thenBlock);
            int endJump = emitJump(Opcodes.JMP);
            patchJump(elseJump);
            if (node.elseBlock != null) {
                compileBlock(node.elseBlock);
            }
            patchJump(endJump);
        } else if (stat instanceof Ast.While) {
            Ast.While node = (Ast.While) stat;
            int start = current.code.size();
            compileExpr(node.cond);
            int exitJump = emitJump(Opcodes.JZ);
            loops.add(new Loop());
            compileBlock(node.body);
            loops.remove(loops.size() - 1);
            int backJump = emitJump(Opcodes.JMP);
            patchJumpTo(backJump, start);
            patchJump(exitJump);
            patchBreaks(start);
        } else if (stat instanceof Ast.DoStat) {
            compileBlock(((Ast.DoStat) stat).body);
        } else if (stat instanceof Ast.NumFor) {
            compileNumFor((Ast.NumFor) stat);
        } else if (stat instanceof Ast.GenFor) {
            compileGenFor((Ast.GenFor) stat);
        } else if (stat instanceof Ast.FuncDecl) {
            Ast.FuncDecl decl = (Ast.FuncDecl) stat;
            int slot = -1;
            if (decl.local) {
                slot = declareLocal(decl.name);
            }
            int protoIndex = compileFunction(decl.name, decl.params, decl.body);
            emit(Opcodes.CLOSURE, 0, protoIndex);
            if (decl.local) {
                emit(Opcodes.STORE_LOCAL, 0, slot);
            } else {
                emit(Opcodes.STORE_GLOBAL, 0, chunk.addConstant(decl.name));
            }
        } else if (stat instanceof Ast.Return) {
            Ast.Return ret = (Ast.Return) stat;
            if (ret.value != null) {
                compileExpr(ret.value);
            } else {
                emit(Opcodes.PUSH_NIL, 0, 0);
            }
            emit(Opcodes.RETURN, 0, 0);
        } else if (stat instanceof Ast.Break) {
            if (loops.isEmpty()) {
                throw new LsError("'break' вне цикла", stat.line, stat.col);
            }
            int pos = emitJump(Opcodes.JMP);
            loops.get(loops.size() - 1).breaks.add(pos);
        } else if (stat instanceof Ast.ExprStat) {
            compileExpr(((Ast.ExprStat) stat).expr);
            emit(Opcodes.POP, 0, 0);
        } else {
            throw new LsError("Неизвестный оператор", stat.line, stat.col);
        }
    }

    private void patchBreaks(int target) {
        if (loops.isEmpty()) {
            return;
        }
        Loop loop = loops.get(loops.size() - 1);
        for (int pos : loop.breaks) {
            patchJump(pos);
        }
        loop.breaks.clear();
    }

    /** Прыгает все break-и текущего цикла на указанную позицию в коде. */
    private void patchBreaksTo(int targetPos) {
        if (loops.isEmpty()) {
            return;
        }
        Loop loop = loops.get(loops.size() - 1);
        for (int pos : loop.breaks) {
            int delta = (targetPos - pos) / Opcodes.INSTRUCTION_SIZE - 1;
            current.code.data[pos + 2] = (byte) (delta & 0xFF);
            current.code.data[pos + 3] = (byte) ((delta >> 8) & 0xFF);
        }
        loop.breaks.clear();
    }

    private void compileAssign(Ast.Assign assign) throws LsError {
        Ast.Expr target = assign.target;
        if (assign.op != null) {
            compileCompoundAssign(assign);
            return;
        }
        if (target instanceof Ast.Name) {
            String name = ((Ast.Name) target).name;
            compileExpr(assign.value);
            int local = findLocal(current, name);
            if (local >= 0) {
                emit(Opcodes.STORE_LOCAL, 0, local);
                return;
            }
            int up = findUpvalue(current, name);
            if (up >= 0) {
                emit(Opcodes.STORE_UPVAL, 0, up);
                return;
            }
            emit(Opcodes.STORE_GLOBAL, 0, chunk.addConstant(name));
            return;
        }
        if (target instanceof Ast.Index) {
            Ast.Index index = (Ast.Index) target;
            compileExpr(index.obj);
            if (index.isField && index.key instanceof Ast.StrLit) {
                compileExpr(assign.value);
                emit(Opcodes.SET_FIELD, 0, chunk.addConstant(((Ast.StrLit) index.key).value));
            } else {
                compileExpr(index.key);
                compileExpr(assign.value);
                emit(Opcodes.SET_INDEX, 0, 0);
            }
            return;
        }
        throw new LsError("Некорректная цель присваивания", assign.line, assign.col);
    }

    /** Составное присваивание: x += v превращается в x = x + v. */
    private void compileCompoundAssign(Ast.Assign assign) throws LsError {
        Ast.Expr target = assign.target;
        int op = binaryOpcode(assign.op);
        if (target instanceof Ast.Name) {
            String name = ((Ast.Name) target).name;
            compileExpr(target);
            compileExpr(assign.value);
            emit(op, 0, 0);
            int local = findLocal(current, name);
            if (local >= 0) {
                emit(Opcodes.STORE_LOCAL, 0, local);
                return;
            }
            int up = findUpvalue(current, name);
            if (up >= 0) {
                emit(Opcodes.STORE_UPVAL, 0, up);
                return;
            }
            emit(Opcodes.STORE_GLOBAL, 0, chunk.addConstant(name));
            return;
        }
        Ast.Index index = (Ast.Index) target;
        compileExpr(index.obj);
        // Таблица остаётся на стеке: SET_FIELD/SET_INDEX ожидают её под значением.
        emit(Opcodes.DUP, 0, 0);
        if (index.isField && index.key instanceof Ast.StrLit) {
            String key = ((Ast.StrLit) index.key).value;
            emit(Opcodes.GET_FIELD, 0, chunk.addConstant(key));
            compileExpr(assign.value);
            emit(op, 0, 0);
            emit(Opcodes.SET_FIELD, 0, chunk.addConstant(key));
            return;
        }
        compileExpr(index.key);
        compileExpr(assign.value);
        emit(op, 0, 0);
        emit(Opcodes.SET_INDEX, 0, 0);
    }

    private void compileNumFor(Ast.NumFor node) throws LsError {
        openScope();
        int varSlot = declareLocal(node.var);
        int limitSlot = declareLocal(FOR_LIMIT);
        int stepSlot = declareLocal(FOR_STEP);

        // limit
        compileExpr(node.limit);
        emit(Opcodes.STORE_LOCAL, 0, limitSlot);
        // step
        if (node.step != null) {
            compileExpr(node.step);
        } else {
            emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(1.0d));
        }
        emit(Opcodes.STORE_LOCAL, 0, stepSlot);
        // counter
        compileExpr(node.start);
        emit(Opcodes.STORE_LOCAL, 0, varSlot);

        int start = current.code.size();
        // условие: (step >= 0 and i <= limit) or (step < 0 and i >= limit)
        // (step >= 0 and i <= limit)
        emit(Opcodes.LOAD_LOCAL, 0, stepSlot);
        emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(0.0d));
        emit(Opcodes.GE, 0, 0);
        emit(Opcodes.DUP, 0, 0);
        int skipAnd = emitJump(Opcodes.JZ);
        emit(Opcodes.POP, 0, 0);
        emit(Opcodes.LOAD_LOCAL, 0, varSlot);
        emit(Opcodes.LOAD_LOCAL, 0, limitSlot);
        emit(Opcodes.LE, 0, 0);
        int toOr = emitJump(Opcodes.JMP);
        patchJump(skipAnd);
        emit(Opcodes.POP, 0, 0);
        // (step < 0 and i >= limit)
        emit(Opcodes.LOAD_LOCAL, 0, stepSlot);
        emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(0.0d));
        emit(Opcodes.LT, 0, 0);
        emit(Opcodes.DUP, 0, 0);
        int skipAnd2 = emitJump(Opcodes.JZ);
        emit(Opcodes.POP, 0, 0);
        emit(Opcodes.LOAD_LOCAL, 0, varSlot);
        emit(Opcodes.LOAD_LOCAL, 0, limitSlot);
        emit(Opcodes.GE, 0, 0);
        int toEnd = emitJump(Opcodes.JMP);
        patchJump(skipAnd2);
        emit(Opcodes.POP, 0, 0);
        emit(Opcodes.PUSH_FALSE, 0, 0);
        patchJump(toOr);
        patchJump(toEnd);
        int exitJump = emitJump(Opcodes.JZ);

        loops.add(new Loop());
        compileBlock(node.body);

        // i = i + step
        emit(Opcodes.LOAD_LOCAL, 0, varSlot);
        emit(Opcodes.LOAD_LOCAL, 0, stepSlot);
        emit(Opcodes.ADD, 0, 0);
        emit(Opcodes.STORE_LOCAL, 0, varSlot);

        int backJump = emitJump(Opcodes.JMP);
        patchJumpTo(backJump, start);
        patchJump(exitJump);
        patchBreaks(start);
        loops.remove(loops.size() - 1);
        closeScope();
    }

    private void compileGenFor(Ast.GenFor node) throws LsError {
        openScope();
        // Итератор: f, s, ctrl
        compileExpr(node.iterable);
        emit(Opcodes.FOR_PREP, 0, 0);

        int varCount = node.vars.size();
        int[] slots = new int[varCount];
        for (int i = 0; i < varCount; i++) {
            slots[i] = declareLocal(node.vars.get(i));
        }

        int start = current.code.size();
        int iterJump = emitJump(Opcodes.FOR_ITER);
        // FOR_ITER: a = varCount, b = смещение к выходу
        current.code.data[iterJump + 1] = (byte) varCount;
        // сохраняем k и v в локальные переменные
        if (varCount >= 2) {
            emit(Opcodes.STORE_LOCAL, 0, slots[1]);
        }
        emit(Opcodes.STORE_LOCAL, 0, slots[0]);

        loops.add(new Loop());
        compileBlock(node.body);

        int backJump = emitJump(Opcodes.JMP);
        patchJumpTo(backJump, start);
        // Метка выхода: сюда попадают и FOR_ITER, и break — здесь чистим f, s, ctrl.
        int exitPos = current.code.size();
        patchJump(iterJump);
        patchBreaksTo(exitPos);
        loops.remove(loops.size() - 1);
        emit(Opcodes.POP, 0, 0);
        emit(Opcodes.POP, 0, 0);
        emit(Opcodes.POP, 0, 0);
        closeScope();
    }

    // ---------------- выражения ----------------

    private void compileExpr(Ast.Expr expr) throws LsError {
        if (expr instanceof Ast.NumLit) {
            emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(((Ast.NumLit) expr).value));
        } else if (expr instanceof Ast.StrLit) {
            emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(((Ast.StrLit) expr).value));
        } else if (expr instanceof Ast.BoolLit) {
            emit(((Ast.BoolLit) expr).value ? Opcodes.PUSH_TRUE : Opcodes.PUSH_FALSE, 0, 0);
        } else if (expr instanceof Ast.NilLit) {
            emit(Opcodes.PUSH_NIL, 0, 0);
        } else if (expr instanceof Ast.Name) {
            String name = ((Ast.Name) expr).name;
            int local = findLocal(current, name);
            if (local >= 0) {
                emit(Opcodes.LOAD_LOCAL, 0, local);
                return;
            }
            int up = findUpvalue(current, name);
            if (up >= 0) {
                emit(Opcodes.LOAD_UPVAL, 0, up);
                return;
            }
            emit(Opcodes.LOAD_GLOBAL, 0, chunk.addConstant(name));
        } else if (expr instanceof Ast.Index) {
            Ast.Index index = (Ast.Index) expr;
            compileExpr(index.obj);
            if (index.isField && index.key instanceof Ast.StrLit) {
                emit(Opcodes.GET_FIELD, 0, chunk.addConstant(((Ast.StrLit) index.key).value));
            } else {
                compileExpr(index.key);
                emit(Opcodes.GET_INDEX, 0, 0);
            }
        } else if (expr instanceof Ast.Call) {
            compileCall((Ast.Call) expr);
        } else if (expr instanceof Ast.Func) {
            Ast.Func func = (Ast.Func) expr;
            int protoIndex = compileFunction("anonymous", func.params, func.body);
            emit(Opcodes.CLOSURE, 0, protoIndex);
        } else if (expr instanceof Ast.TableLit) {
            compileTable((Ast.TableLit) expr);
        } else if (expr instanceof Ast.Bin) {
            compileBinary((Ast.Bin) expr);
        } else if (expr instanceof Ast.Un) {
            compileUnary((Ast.Un) expr);
        } else if (expr instanceof Ast.Ternary) {
            Ast.Ternary ternary = (Ast.Ternary) expr;
            compileExpr(ternary.cond);
            int elseJump = emitJump(Opcodes.JZ);
            compileExpr(ternary.yes);
            int endJump = emitJump(Opcodes.JMP);
            patchJump(elseJump);
            compileExpr(ternary.no);
            patchJump(endJump);
        } else {
            throw new LsError("Неизвестное выражение", expr.line, expr.col);
        }
    }

    private void compileCall(Ast.Call call) throws LsError {
        if (call.method) {
            Ast.Index index = (Ast.Index) call.callee;
            compileExpr(index.obj);
            emit(Opcodes.GET_FIELD, 0, chunk.addConstant(((Ast.StrLit) index.key).value));
            compileExpr(index.obj); // self
            for (Ast.Expr arg : call.args) {
                compileExpr(arg);
            }
            emit(Opcodes.CALL, call.args.size() + 1, 0);
            return;
        }
        compileExpr(call.callee);
        for (Ast.Expr arg : call.args) {
            compileExpr(arg);
        }
        emit(Opcodes.CALL, call.args.size(), 0);
    }

    private void compileTable(Ast.TableLit table) throws LsError {
        emit(Opcodes.NEW_TABLE, 0, 0);
        for (int i = 0; i < table.arrayItems.size(); i++) {
            emit(Opcodes.DUP, 0, 0);
            emit(Opcodes.PUSH_CONST, 0, chunk.addConstant((double) (i + 1)));
            compileExpr(table.arrayItems.get(i));
            emit(Opcodes.SET_INDEX, 0, 0);
        }
        for (int i = 0; i < table.hashKeys.size(); i++) {
            emit(Opcodes.DUP, 0, 0);
            compileExpr(table.hashValues.get(i));
            // Ключ передаётся в самой инструкции SET_FIELD, в стек он не кладётся.
            emit(Opcodes.SET_FIELD, 0, chunk.addConstant(table.hashKeys.get(i)));
        }
        for (int i = 0; i < table.numKeys.size(); i++) {
            emit(Opcodes.DUP, 0, 0);
            emit(Opcodes.PUSH_CONST, 0, chunk.addConstant(table.numKeys.get(i)));
            compileExpr(table.numValues.get(i));
            emit(Opcodes.SET_INDEX, 0, 0);
        }
    }

    /** Код бинарной операции по её записи. */
    private int binaryOpcode(String op) throws LsError {
        switch (op) {
            case "+": return Opcodes.ADD;
            case "-": return Opcodes.SUB;
            case "*": return Opcodes.MUL;
            case "/": return Opcodes.DIV;
            case "%": return Opcodes.MOD;
            case "^": return Opcodes.POW;
            case "..": return Opcodes.CONCAT;
            case "==": return Opcodes.EQ;
            case "~=": return Opcodes.NE;
            case "<": return Opcodes.LT;
            case "<=": return Opcodes.LE;
            case ">": return Opcodes.GT;
            case ">=": return Opcodes.GE;
            default:
                throw new LsError("Неизвестный оператор '" + op + "'", 0, 0);
        }
    }

    private void compileBinary(Ast.Bin bin) throws LsError {
        if (bin.op.equals("and")) {
            compileExpr(bin.left);
            emit(Opcodes.DUP, 0, 0);
            int falseJump = emitJump(Opcodes.JZ);
            emit(Opcodes.POP, 0, 0);
            compileExpr(bin.right);
            emit(Opcodes.SWAP, 0, 0);
            emit(Opcodes.POP, 0, 0);
            int endJump = emitJump(Opcodes.JMP);
            patchJump(falseJump);
            emit(Opcodes.POP, 0, 0);
            patchJump(endJump);
            return;
        }
        if (bin.op.equals("or")) {
            compileExpr(bin.left);
            emit(Opcodes.DUP, 0, 0);
            int trueJump = emitJump(Opcodes.JNZ);
            emit(Opcodes.POP, 0, 0);
            compileExpr(bin.right);
            emit(Opcodes.SWAP, 0, 0);
            emit(Opcodes.POP, 0, 0);
            int endJump = emitJump(Opcodes.JMP);
            patchJump(trueJump);
            emit(Opcodes.POP, 0, 0);
            patchJump(endJump);
            return;
        }
        compileExpr(bin.left);
        compileExpr(bin.right);
        int op = binaryOpcode(bin.op);
        emit(op, 0, 0);
    }

    private void compileUnary(Ast.Un un) throws LsError {
        compileExpr(un.operand);
        if (un.op.equals("not")) {
            emit(Opcodes.NOT, 0, 0);
        } else if (un.op.equals("-")) {
            emit(Opcodes.NEG, 0, 0);
        } else if (un.op.equals("#")) {
            emit(Opcodes.LEN, 0, 0);
        } else {
            throw new LsError("Неизвестный унарный оператор '" + un.op + "'", un.line, un.col);
        }
    }
}
