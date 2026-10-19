package com.lsengine.engine.lang;

/**
 * Набор инструкций виртуальной машины LS.
 *
 * Формат инструкции — 4 байта:
 * <pre>
 *   [0] opcode (u8)
 *   [1] a      (u8)   — счётчик аргументов / количество значений
 *   [2..3] b   (u16, little endian) — индекс константы, слот, смещение перехода
 * </pre>
 */
public final class Opcodes {

    private Opcodes() {
    }

    public static final int NOP = 0;
    public static final int PUSH_CONST = 1;
    public static final int PUSH_NIL = 2;
    public static final int PUSH_TRUE = 3;
    public static final int PUSH_FALSE = 4;
    public static final int LOAD_LOCAL = 5;
    public static final int STORE_LOCAL = 6;
    public static final int LOAD_UPVAL = 7;
    public static final int STORE_UPVAL = 8;
    public static final int LOAD_GLOBAL = 9;
    public static final int STORE_GLOBAL = 10;
    public static final int GET_FIELD = 11;
    public static final int SET_FIELD = 12;
    public static final int GET_INDEX = 13;
    public static final int SET_INDEX = 14;
    public static final int NEW_TABLE = 15;
    public static final int ADD = 16;
    public static final int SUB = 17;
    public static final int MUL = 18;
    public static final int DIV = 19;
    public static final int MOD = 20;
    public static final int POW = 21;
    public static final int CONCAT = 22;
    public static final int LEN = 23;
    public static final int NEG = 24;
    public static final int NOT = 25;
    public static final int EQ = 26;
    public static final int NE = 27;
    public static final int LT = 28;
    public static final int LE = 29;
    public static final int GT = 30;
    public static final int GE = 31;
    public static final int JMP = 32;
    public static final int JZ = 33;
    public static final int JNZ = 34;
    public static final int CALL = 35;
    public static final int RETURN = 36;
    public static final int CLOSURE = 37;
    public static final int POP = 38;
    public static final int PRINT = 39;
    public static final int HALT = 40;
    public static final int DUP = 41;
    public static final int SWAP = 42;
    public static final int FOR_PREP = 43;
    public static final int FOR_ITER = 44;

    public static final int INSTRUCTION_SIZE = 4;

    /** Человекочитаемое имя опкода (для дизассемблера и отладки). */
    public static String name(int op) {
        switch (op) {
            case NOP: return "NOP";
            case PUSH_CONST: return "PUSH_CONST";
            case PUSH_NIL: return "PUSH_NIL";
            case PUSH_TRUE: return "PUSH_TRUE";
            case PUSH_FALSE: return "PUSH_FALSE";
            case LOAD_LOCAL: return "LOAD_LOCAL";
            case STORE_LOCAL: return "STORE_LOCAL";
            case LOAD_UPVAL: return "LOAD_UPVAL";
            case STORE_UPVAL: return "STORE_UPVAL";
            case LOAD_GLOBAL: return "LOAD_GLOBAL";
            case STORE_GLOBAL: return "STORE_GLOBAL";
            case GET_FIELD: return "GET_FIELD";
            case SET_FIELD: return "SET_FIELD";
            case GET_INDEX: return "GET_INDEX";
            case SET_INDEX: return "SET_INDEX";
            case NEW_TABLE: return "NEW_TABLE";
            case ADD: return "ADD";
            case SUB: return "SUB";
            case MUL: return "MUL";
            case DIV: return "DIV";
            case MOD: return "MOD";
            case POW: return "POW";
            case CONCAT: return "CONCAT";
            case LEN: return "LEN";
            case NEG: return "NEG";
            case NOT: return "NOT";
            case EQ: return "EQ";
            case NE: return "NE";
            case LT: return "LT";
            case LE: return "LE";
            case GT: return "GT";
            case GE: return "GE";
            case JMP: return "JMP";
            case JZ: return "JZ";
            case JNZ: return "JNZ";
            case CALL: return "CALL";
            case RETURN: return "RETURN";
            case CLOSURE: return "CLOSURE";
            case POP: return "POP";
            case PRINT: return "PRINT";
            case HALT: return "HALT";
            case DUP: return "DUP";
            case SWAP: return "SWAP";
            case FOR_PREP: return "FOR_PREP";
            case FOR_ITER: return "FOR_ITER";
            default: return "OP_" + op;
        }
    }
}
