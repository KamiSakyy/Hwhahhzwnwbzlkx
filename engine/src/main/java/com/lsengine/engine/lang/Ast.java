package com.lsengine.engine.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * AST языка LS.
 */
public final class Ast {

    private Ast() {
    }

    public abstract static class Node {
        public int line;
        public int col;
    }

    public abstract static class Expr extends Node {
    }

    public abstract static class Stat extends Node {
    }

    public static final class Block extends Node {
        public final List<Stat> stats = new ArrayList<>();
    }

    // ---------------- выражения ----------------

    public static final class NumLit extends Expr {
        public final double value;

        public NumLit(double value) {
            this.value = value;
        }
    }

    public static final class StrLit extends Expr {
        public final String value;

        public StrLit(String value) {
            this.value = value;
        }
    }

    public static final class BoolLit extends Expr {
        public final boolean value;

        public BoolLit(boolean value) {
            this.value = value;
        }
    }

    public static final class NilLit extends Expr {
    }

    public static final class Name extends Expr {
        public final String name;

        public Name(String name) {
            this.name = name;
        }
    }

    /** obj[key] или obj.field */
    public static final class Index extends Expr {
        public final Expr obj;
        public final Expr key;
        /** true, если ключ задан полем (obj.field). */
        public final boolean isField;

        public Index(Expr obj, Expr key, boolean isField) {
            this.obj = obj;
            this.key = key;
            this.isField = isField;
        }
    }

    public static final class Call extends Expr {
        public final Expr callee;
        public final List<Expr> args;
        /** true для вызова через двоеточие obj:method(...). */
        public final boolean method;

        public Call(Expr callee, List<Expr> args, boolean method) {
            this.callee = callee;
            this.args = args;
            this.method = method;
        }
    }

    public static final class Func extends Expr {
        public final List<String> params;
        public final Block body;

        public Func(List<String> params, Block body) {
            this.params = params;
            this.body = body;
        }
    }

    public static final class TableLit extends Expr {
        public final List<Expr> arrayItems = new ArrayList<>();
        public final List<String> hashKeys = new ArrayList<>();
        public final List<Expr> hashValues = new ArrayList<>();
        /** Ключи-числа (например [1] = 10). */
        public final List<Double> numKeys = new ArrayList<>();
        public final List<Expr> numValues = new ArrayList<>();

        public TableLit() {
        }
    }

    public static final class Bin extends Expr {
        public final String op;
        public final Expr left;
        public final Expr right;

        public Bin(String op, Expr left, Expr right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }
    }

    public static final class Un extends Expr {
        public final String op;
        public final Expr operand;

        public Un(String op, Expr operand) {
            this.op = op;
            this.operand = operand;
        }
    }

    // ---------------- операторы ----------------

    public static final class VarDecl extends Stat {
        public final String name;
        public final Expr value;

        public VarDecl(String name, Expr value) {
            this.name = name;
            this.value = value;
        }
    }

    public static final class Assign extends Stat {
        public final Expr target;
        public final Expr value;

        public Assign(Expr target, Expr value) {
            this.target = target;
            this.value = value;
        }
    }

    public static final class If extends Stat {
        public final Expr cond;
        public final Block thenBlock;
        public final Block elseBlock;

        public If(Expr cond, Block thenBlock, Block elseBlock) {
            this.cond = cond;
            this.thenBlock = thenBlock;
            this.elseBlock = elseBlock;
        }
    }

    public static final class While extends Stat {
        public final Expr cond;
        public final Block body;

        public While(Expr cond, Block body) {
            this.cond = cond;
            this.body = body;
        }
    }

    public static final class NumFor extends Stat {
        public final String var;
        public final Expr start;
        public final Expr limit;
        public final Expr step;
        public final Block body;

        public NumFor(String var, Expr start, Expr limit, Expr step, Block body) {
            this.var = var;
            this.start = start;
            this.limit = limit;
            this.step = step;
            this.body = body;
        }
    }

    public static final class GenFor extends Stat {
        public final List<String> vars;
        public final Expr iterable;
        public final Block body;

        public GenFor(List<String> vars, Expr iterable, Block body) {
            this.vars = vars;
            this.iterable = iterable;
            this.body = body;
        }
    }

    public static final class FuncDecl extends Stat {
        public final String name;
        public final List<String> params;
        public final Block body;
        public final boolean local;

        public FuncDecl(String name, List<String> params, Block body, boolean local) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.local = local;
        }
    }

    public static final class Return extends Stat {
        public final Expr value;

        public Return(Expr value) {
            this.value = value;
        }
    }

    public static final class Break extends Stat {
    }

    /** Блок do ... end */
    public static final class DoStat extends Stat {
        public final Block body;

        public DoStat(Block body) {
            this.body = body;
        }
    }

    public static final class ExprStat extends Stat {
        public final Expr expr;

        public ExprStat(Expr expr) {
            this.expr = expr;
        }
    }
}
