package com.lsengine.engine.aot;

/**
 * Абстракция машинного кода для AOT-компилятора LS.
 *
 * Реализации: {@link AotX64} (x86-64) и {@link AotArm64} (AArch64).
 * Раскладка кадра одинакова для обеих архитектур:
 *
 * <pre>
 *   [fp-8]                 сохранённый SP-регистр (rbx / x19)
 *   [fp-16]                сохранённый GL-регистр (r12 / x20)
 *   [fp-24-8*i]            локальная переменная i
 *   [fp-24-8*locals]       указатель на текущее замыкание (корень GC)
 *   [fp-32-8*locals]       вершина стека значений (регистр SP)
 *   ...                    стек значений (растёт вниз)
 *   [fp-16-frameSize]      исходящая область вызова: счётчик, аргументы
 * </pre>
 *
 * Соглашение вызова: адрес аргументов = [fp-16-frameSize+16], количество
 * аргументов хранится в [fp-16-frameSize+8], значение функции — в rax/x0.
 */
public interface AotIsa {

    int R0 = 0;
    int R1 = 1;
    int R2 = 2;
    int R3 = 3;
    int R4 = 4;
    int R5 = 5;
    int R6 = 6;
    int R7 = 7;
    /** Указатель стека значений. */
    int SP = 8;
    /** Указатель кадра. */
    int FP = 9;
    /** Текущее замыкание. */
    int CL = 10;
    /** Таблица глобальных переменных. */
    int GL = 11;
    /** Первый регистр с плавающей точкой. */
    int D0 = 12;
    /** Второй регистр с плавающей точкой. */
    int D1 = 13;

    int FIX_POOL = 1;
    int FIX_RODATA = 2;
    int FIX_API = 3;
    int FIX_FUNCTION = 4;

    /** Пролог: сохранить регистры и выделить кадр. */
    void prologue(int frameSize);

    /** Эпилог: восстановить регистры и вернуть значение из R0. */
    void epilogue();

    /** Метка перехода. */
    void label(int id);

    /** Безусловный переход. */
    void jump(int id);

    /** Переход, если значение в регистре равно константе. */
    void jumpIfEq(int reg, long value, int id);

    /** Переход, если значение в регистре не равно константе. */
    void jumpIfNe(int reg, long value, int id);

    /** Переход, если значение в регистре меньше константы (беззнаковое). */
    void jumpIfBelow(int reg, long value, int id);

    /** Переход, если значение в регистре равно нулю. */
    void jumpIfZero(int reg, int id);

    /** Переход, если значение в регистре не равно нулю. */
    void jumpIfNotZero(int reg, int id);

    /** Загрузить 64-битную константу. */
    void movImm(int reg, long value);

    /** Копировать регистр. */
    void movReg(int dst, int src);

    /** Загрузить из памяти [base + disp]. */
    void load(int reg, int base, int disp);

    /** Сохранить в память [base + disp]. */
    void store(int base, int disp, int reg);

    /** Вычислить адрес [base + disp]. */
    void lea(int reg, int base, int disp);

    /** dst += src. */
    void add(int dst, int src);

    /** reg += imm. */
    void addImm(int reg, int imm);

    /** reg -= imm. */
    void subImm(int reg, int imm);

    /** reg &= mask. */
    void andImm(int reg, long mask);

    /** reg |= mask. */
    void orImm(int reg, long mask);

    /** Сравнить регистр с константой (флаги). */
    void cmpImm(int reg, long imm);

    /** Логический сдвиг вправо. */
    void shiftRightImm(int reg, int bits);

    /** Пометить значение тегом (NaN-боксинг). */
    void box(int reg, int tag);

    /** Извлечь 48-битную полезную нагрузку. */
    void payload(int reg);

    /** Загрузить double из памяти. */
    void loadDouble(int dreg, int base, int disp);

    /** Сохранить double в память. */
    void storeDouble(int base, int disp, int dreg);

    /** Преобразовать double в целое (биты). */
    void doubleToInt(int reg, int dreg);

    /** Преобразовать целое (биты) в double. */
    void intToDouble(int dreg, int reg);

    void addDouble(int dst, int src);

    void subDouble(int dst, int src);

    void mulDouble(int dst, int src);

    void divDouble(int dst, int src);

    /** Сравнить два double (флаги). */
    void compareDouble(int a, int b);

    /**
     * Вызвать функцию нативного API.
     *
     * @param apiIndex индекс в таблице API
     * @param argsDisp смещение относительно FP, по которому лежит args[0]
     */
    void callApi(int apiIndex, int argsDisp);

    /** Вызов функции по адресу в регистре. */
    void callReg(int reg);

    /** Прямой вызов функции модуля по её индексу. */
    void callFunction(int functionIndex);

    /** Загрузить значение из пула констант. */
    void loadPool(int reg, int index);

    /** Адрес элемента пула констант. */
    void leaPool(int reg, int index);

    /** Адрес строковых данных. */
    void leaRodata(int reg, int index);

    /** Адрес элемента таблицы API. */
    void leaApi(int reg, int index);

    /** Адрес кода функции. */
    void leaFunction(int reg, int functionIndex);

    /** Текущий размер сгенерированного кода. */
    int codeSize();

    /**
     * Смещение низа кадра относительно FP: столько байт занимают
     * сохранённые регистры (включая обратный адрес).
     */
    int frameBase();

    /** Смещение локальной переменной относительно FP. */
    int localOffset(int slot);

    /** Смещение слота с указателем на замыкание. */
    int closureSlotOffset(int localCount);

    /** Смещение вспомогательного слота. */
    int scratchSlotOffset(int localCount);

    /** Начальное значение указателя стека значений. */
    int stackTopOffset(int localCount);

    /** Размер кадра в байтах. */
    int frameSize(int localCount, int maxStack, int maxArgs);
}
