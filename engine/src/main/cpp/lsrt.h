/*
 * lsrt.h — рантайм нативного кода LS (AOT).
 *
 * Сгенерированный AOT-код (машинный код x86-64 / ARM64) работает с этими
 * типами напрямую: значения — 64-битные NaN-боксы, вызовы — через таблицу
 * адресов, строки/таблицы/замыкания — объекты в куче с mark-and-sweep GC.
 */
#ifndef LSRT_H
#define LSRT_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* Значения: NaN-боксинг                                              */
/* ------------------------------------------------------------------ */

/*
 * Число — обычный IEEE-754 double. «Бокс» — специальный NaN:
 *   0x7FF8_0000_0000_0000 | tag<<48 | payload(48 бит)
 * Старшие 12 бит (0xFFF8…) отличают бокс от любого «тихого» NaN,
 * поэтому результаты арифметики никогда не превращаются в значение.
 */
typedef uint64_t ls_value;

#define LS_BOX_MASK   0xFFF8000000000000ULL
#define LS_BOX_BASE   0x7FF8000000000000ULL
#define LS_TAG_SHIFT  48
#define LS_PAYLOAD_MASK 0x0000FFFFFFFFFFFFULL

#define LS_TAG_NIL    0
#define LS_TAG_FALSE  1
#define LS_TAG_TRUE   2
#define LS_TAG_STR    3
#define LS_TAG_TABLE  4
#define LS_TAG_FUNC   5
#define LS_TAG_ITER   6
#define LS_TAG_NATIVE 7

#define LS_NIL   ((ls_value)(LS_BOX_BASE | ((ls_value)LS_TAG_NIL << LS_TAG_SHIFT)))
#define LS_FALSE ((ls_value)(LS_BOX_BASE | ((ls_value)LS_TAG_FALSE << LS_TAG_SHIFT)))
#define LS_TRUE  ((ls_value)(LS_BOX_BASE | ((ls_value)LS_TAG_TRUE << LS_TAG_SHIFT)))

static inline int ls_is_box(ls_value v) {
    return (v & LS_BOX_MASK) == LS_BOX_BASE;
}

static inline int ls_tag(ls_value v) {
    return (int)((v >> LS_TAG_SHIFT) & 0x7ULL);
}

static inline void *ls_payload(ls_value v) {
    return (void *)(uintptr_t)(v & LS_PAYLOAD_MASK);
}

static inline ls_value ls_box(int tag, const void *ptr) {
    return (ls_value)(LS_BOX_BASE | ((ls_value)tag << LS_TAG_SHIFT)
                      | ((ls_value)(uintptr_t)ptr & LS_PAYLOAD_MASK));
}

static inline ls_value ls_num(double d) {
    uint64_t bits;
    memcpy(&bits, &d, sizeof(bits));
    /* «тихий» NaN с редким паттерном приводим к каноническому, чтобы он
       никогда не был принят за бокс. */
    if ((bits & LS_BOX_MASK) == LS_BOX_BASE) {
        bits = 0x7FF8000000000000ULL;
    }
    return (ls_value)bits;
}

static inline double ls_as_num(ls_value v) {
    double d;
    uint64_t bits = (ls_value)v;
    memcpy(&d, &bits, sizeof(d));
    return d;
}

static inline int ls_is_num(ls_value v) {
    return !ls_is_box(v);
}

static inline int ls_is_nil(ls_value v) { return v == LS_NIL; }
static inline int ls_is_true(ls_value v) { return v == LS_TRUE; }
static inline int ls_is_false(ls_value v) { return v == LS_FALSE; }
static inline int ls_truthy(ls_value v) { return v != LS_NIL && v != LS_FALSE; }

/* ------------------------------------------------------------------ */
/* Объекты                                                             */
/* ------------------------------------------------------------------ */

typedef struct ls_string {
    uint32_t hash;
    uint32_t len;
    struct ls_string *next;   /* для цепочки одинаковых строк в хеше */
    char data[1];              /* NUL-терминатор */
} ls_string;

typedef struct ls_table {
    ls_value *array;           /* массив: 1..asize */
    uint32_t asize;
    uint32_t alen;             /* использованная длина массива */
    ls_value *hash_keys;
    ls_value *hash_values;
    uint32_t hsize;            /* степень двойки */
    uint32_t hcount;
} ls_table;

typedef struct ls_closure {
    void *code;                /* адрес машинного кода функции */
    uint32_t nparams;
    uint32_t nupvals;
    ls_value *upvals;          /* указатели на ячейки (ls_cell) */
    struct ls_closure *next;   /* для цепочки всех замыканий */
} ls_closure;

typedef struct ls_cell {
    ls_value value;
} ls_cell;

typedef struct ls_iter {
    ls_table *table;
    ls_value *keys;
    uint32_t nkeys;
    uint32_t index;
    struct ls_iter *next;
} ls_iter;

typedef ls_value (*ls_native_fn)(ls_value *args);

typedef struct ls_native {
    ls_native_fn fn;
    const char *name;
    struct ls_native *next;
} ls_native;

/* ------------------------------------------------------------------ */
/* Платформа (заполняется из Java через JNI)                          */
/* ------------------------------------------------------------------ */

typedef struct ls_platform {
    void (*clear)(int color);
    void (*rect)(float x, float y, float w, float h, int color);
    void (*circle)(float x, float y, float r, int color);
    void (*line)(float x1, float y1, float x2, float y2, int color);
    void (*text)(float x, float y, const char *text, float size, int color);
    float (*input_x)(void);
    float (*input_y)(void);
    int (*input_down)(void);
    int (*input_pressed)(void);
    int (*input_released)(void);
    double (*time_now)(void);
    long (*time_frame)(void);
    double (*time_dt)(void);
    int (*ui_button)(float x, float y, float w, float h, const char *label, ls_value callback);
    void (*beep)(int frequency, int millis);
    int (*app_width)(void);
    int (*app_height)(void);
} ls_platform;

void lsrt_set_platform(const ls_platform *platform);

/* ------------------------------------------------------------------ */
/* Инициализация и общий доступ                                      */
/* ------------------------------------------------------------------ */

void lsrt_init(void);
void lsrt_shutdown(void);
ls_table *lsrt_globals(void);
ls_table *lsrt_new_table(void);

/* Таблица кнопок интерфейса: Java рисует их и спрашивает о нажатиях. */
int lsrt_buttons_count(void);
void lsrt_button_rect(int index, float *out);       /* x, y, w, h */
const char *lsrt_button_label(int index);
int lsrt_button_clicked(int index);
void lsrt_buttons_reset(void);

/* Ввод от платформы. */
void lsrt_touch(float x, float y, int action);


/* ------------------------------------------------------------------ */
/* Таблица нативного API для сгенерированного кода                    */
/* ------------------------------------------------------------------ */

/*
 * Сгенерированный .so не импортирует ни одного символа: адреса функций
 * рантайма копируются в его собственную таблицу при загрузке
 * (функция ls_register). Это делает библиотеку полностью автономной.
 */
typedef ls_value (*ls_api_fn)(ls_value *args);

enum {
    LS_API_PRINT = 0,
    LS_API_TOSTRING,
    LS_API_TONUMBER,
    LS_API_TYPE,
    LS_API_PAIRS,
    LS_API_IPAIRS,
    LS_API_MATH_FLOOR,
    LS_API_MATH_CEIL,
    LS_API_MATH_ABS,
    LS_API_MATH_SQRT,
    LS_API_MATH_SIN,
    LS_API_MATH_COS,
    LS_API_MATH_TAN,
    LS_API_MATH_ATAN2,
    LS_API_MATH_MIN,
    LS_API_MATH_MAX,
    LS_API_MATH_RANDOM,
    LS_API_STRING_LEN,
    LS_API_STRING_UPPER,
    LS_API_STRING_LOWER,
    LS_API_STRING_SUB,
    LS_API_STRING_REP,
    LS_API_STRING_FORMAT,
    LS_API_TABLE_INSERT,
    LS_API_TABLE_REMOVE,
    LS_API_TABLE_CONCAT,
    LS_API_GLOBALS,
    LS_API_NEW_TABLE,
    LS_API_TABLE_GET,
    LS_API_TABLE_SET,
    LS_API_INDEX_GET,
    LS_API_INDEX_SET,
    LS_API_ADD,
    LS_API_SUB,
    LS_API_MUL,
    LS_API_DIV,
    LS_API_MOD,
    LS_API_POW,
    LS_API_CONCAT,
    LS_API_LEN,
    LS_API_NEG,
    LS_API_NOT,
    LS_API_EQ,
    LS_API_LT,
    LS_API_LE,
    LS_API_TRUTHY,
    LS_API_FOR_PREP,
    LS_API_FOR_ITER,
    LS_API_NEW_CELL,
    LS_API_NEW_CLOSURE,
    LS_API_CELL_VALUE,
    LS_API_NE,
    LS_API_CONST_STRING,
    LS_API_CLOSURE_SET_UPVAL,
    LS_API_ERROR,
    LS_API_COUNT
};

/* Таблица адресов, используемая сгенерированным кодом. */
extern ls_api_fn ls_api_table[LS_API_COUNT];

/* Копирует таблицу API в сгенерированную библиотеку. */
void ls_register_api(ls_api_fn *table);

/* ------------------------------------------------------------------ */
/* Операции со значениями                                             */
/* ------------------------------------------------------------------ */

int lsrt_type_of(ls_value v);            /* 0 nil, 1 bool, 2 number, 3 string, 4 table, 5 function */
const char *lsrt_type_name(ls_value v);
ls_value lsrt_to_string(ls_value v);
ls_value lsrt_to_number(ls_value v);
int lsrt_truthy(ls_value v);
int lsrt_equals(ls_value a, ls_value b);
int lsrt_compare(ls_value a, ls_value b);   /* <0, 0, >0 */

ls_string *lsrt_new_string(const char *data, size_t len);
ls_string *lsrt_new_string_z(const char *data);
const char *lsrt_string_cstr(ls_value v);
size_t ls_str_len(ls_value v);
ls_value lsrt_string_concat(ls_value a, ls_value b);

ls_value lsrt_table_get(ls_table *t, ls_value key);
void lsrt_table_set(ls_table *t, ls_value key, ls_value value);
ls_value lsrt_table_get_str(ls_table *t, const char *key);
void lsrt_table_set_str(ls_table *t, const char *key, ls_value value);
int lsrt_table_length(ls_table *t);
ls_value lsrt_table_insert(ls_table *t, ls_value value);
ls_value lsrt_table_remove(ls_table *t, ls_value index);
ls_value lsrt_table_concat(ls_table *t, ls_value sep);

/* Итераторы. */
ls_iter *lsrt_iter_table(ls_table *t);
ls_iter *lsrt_iter_array(ls_table *t);
int lsrt_iter_next(ls_iter *it, ls_value *key, ls_value *value);

/* Замыкания и ячейки. */
ls_closure *lsrt_new_closure(void *code, uint32_t nparams, uint32_t nupvals);
ls_cell *lsrt_new_cell(ls_value value);

/* Стандартная библиотека (вызывается сгенерированным кодом напрямую). */
ls_value lsrt_print(ls_value *args);
ls_value lsrt_tostring(ls_value *args);
ls_value lsrt_tonumber(ls_value *args);
ls_value lsrt_type(ls_value *args);
ls_value lsrt_pairs(ls_value *args);
ls_value lsrt_ipairs(ls_value *args);
ls_value lsrt_math_floor(ls_value *args);
ls_value lsrt_math_ceil(ls_value *args);
ls_value lsrt_math_abs(ls_value *args);
ls_value lsrt_math_sqrt(ls_value *args);
ls_value lsrt_math_sin(ls_value *args);
ls_value lsrt_math_cos(ls_value *args);
ls_value lsrt_math_tan(ls_value *args);
ls_value lsrt_math_atan2(ls_value *args);
ls_value lsrt_math_min(ls_value *args);
ls_value lsrt_math_max(ls_value *args);
ls_value lsrt_math_random(ls_value *args);
ls_value lsrt_string_len(ls_value *args);
ls_value lsrt_string_upper(ls_value *args);
ls_value lsrt_string_lower(ls_value *args);
ls_value lsrt_string_sub(ls_value *args);
ls_value lsrt_string_rep(ls_value *args);
ls_value lsrt_string_format(ls_value *args);
ls_value lsrt_table_insert_fn(ls_value *args);
ls_value lsrt_table_remove_fn(ls_value *args);
ls_value lsrt_table_concat_fn(ls_value *args);

/* Приведение к числу с проверкой (используется в арифметике). */
double lsrt_check_number(ls_value v, const char *op);

/* Вызывает значение-функцию (замыкание или встроенную функцию). */
ls_value lsrt_call_value(ls_value fn, ls_value *args, int argc);

/* Вызывает глобальную функцию программы по имени. */
ls_value lsrt_call_global(const char *name, ls_value *args, int argc);

/* Сборка мусора. */
void lsrt_gc(void);
void lsrt_gc_maybe(void);

#ifdef __cplusplus
}
#endif

#endif /* LSRT_H */
