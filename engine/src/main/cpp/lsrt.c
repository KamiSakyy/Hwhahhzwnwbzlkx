/*
 * lsrt.c — реализация рантайма нативного кода LS.
 *
 * Куча: строки, таблицы, замыкания, ячейки, итераторы.
 * Сборщик мусора: mark-and-sweep с консервативным просмотром стека.
 */
#include "lsrt.h"

#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* Куча и сборщик мусора                                              */
/* ------------------------------------------------------------------ */

#define LS_OBJ_STRING  1
#define LS_OBJ_TABLE   2
#define LS_OBJ_CLOSURE 3
#define LS_OBJ_CELL    4
#define LS_OBJ_ITER    5
#define LS_OBJ_NATIVE  6

#define LS_OBJ_MAGIC 0x4C534F42u   /* "LSOB" */

typedef struct ls_obj {
    struct ls_obj *next;
    uint32_t size;
    uint32_t magic;
    uint8_t kind;
    uint8_t mark;
} ls_obj;

typedef struct ls_string_obj {
    ls_obj header;
    uint32_t hash;
    uint32_t len;
    struct ls_string_obj *next;
    char data[1];
} ls_string_obj;

typedef struct ls_table_obj {
    ls_obj header;
    ls_value *array;
    uint32_t asize;
    uint32_t alen;
    ls_value *hash_keys;
    ls_value *hash_values;
    uint32_t hsize;
    uint32_t hcount;
} ls_table_obj;

typedef struct ls_closure_obj {
    ls_obj header;
    void *code;
    uint32_t nparams;
    uint32_t nupvals;
    ls_value *upvals;
    struct ls_closure_obj *next;
} ls_closure_obj;

typedef struct ls_cell_obj {
    ls_obj header;
    ls_value value;
} ls_cell_obj;

typedef struct ls_iter_obj {
    ls_obj header;
    ls_table_obj *table;
    ls_value *keys;
    uint32_t nkeys;
    uint32_t index;
} ls_iter_obj;

typedef struct ls_native_obj {
    ls_obj header;
    ls_native_fn fn;
    const char *name;
} ls_native_obj;

#define LS_BLOCK_SIZE (64u * 1024u)

typedef struct ls_block {
    struct ls_block *next;
    size_t used;
    size_t capacity;
    char data[1];
} ls_block;

static ls_obj *gc_objects = NULL;      /* все живые объекты */
static ls_block *heap_blocks = NULL;
static ls_block *heap_current = NULL;
static size_t gc_bytes = 0;
static size_t gc_threshold = 4u * 1024u * 1024u;
static void *stack_base = NULL;        /* граница стека для консервативного обхода */
static ls_table_obj *globals_table = NULL;
static int gc_disabled = 0;

/* Пул строковых констант сгенерированного кода: строки создаются лениво,
   но должны жить всё время работы программы, поэтому пул — корень GC. */
static ls_value *const_pool = NULL;
static int const_pool_count = 0;
static int const_pool_cap = 0;

/* forward */
static void mark_value(ls_value v);
static void mark_object(ls_obj *obj);
static void build_api_table(void);

/* Проверяет, что указатель принадлежит куче, и возвращает объект. */
static ls_obj *ls_object_at(void *p) {
    if (!p) {
        return NULL;
    }
    char *addr = (char *)p;
    for (ls_block *b = heap_blocks; b != NULL; b = b->next) {
        char *start = b->data;
        char *end = b->data + b->used;
        if (addr >= start && addr < end) {
            ls_obj *obj = (ls_obj *)addr;
            return (obj->magic == LS_OBJ_MAGIC) ? obj : NULL;
        }
    }
    return NULL;
}

static void *ls_alloc(size_t size, int kind) {
    if (gc_bytes > gc_threshold) {
        lsrt_gc();
    }
    size = (size + 15u) & ~(size_t)15u;
    if (heap_current == NULL || heap_current->used + size > heap_current->capacity) {
        size_t cap = LS_BLOCK_SIZE;
        if (size + 1024u > cap) {
            cap = size + 1024u;
        }
        ls_block *block = (ls_block *)malloc(sizeof(ls_block) + cap);
        if (!block) {
            fprintf(stderr, "LS: нехватка памяти\n");
            abort();
        }
        block->next = heap_blocks;
        block->used = 0;
        block->capacity = cap;
        heap_blocks = block;
        heap_current = block;
    }
    ls_obj *obj = (ls_obj *)(heap_current->data + heap_current->used);
    heap_current->used += size;
    memset(obj, 0, size);
    obj->kind = (uint8_t)kind;
    obj->size = (uint32_t)size;
    obj->magic = LS_OBJ_MAGIC;
    obj->mark = 0;
    obj->next = gc_objects;
    gc_objects = obj;
    gc_bytes += size;
    return obj;
}

/* --- обход стека (консервативно) --- */

static void mark_value(ls_value v) {
    if (!ls_is_box(v)) {
        return;
    }
    int tag = ls_tag(v);
    if (tag != LS_TAG_STR && tag != LS_TAG_TABLE && tag != LS_TAG_FUNC
            && tag != LS_TAG_ITER && tag != LS_TAG_NATIVE) {
        return;
    }
    ls_obj *obj = ls_object_at(ls_payload(v));
    if (obj) {
        mark_object(obj);
    }
}

static void mark_object(ls_obj *obj) {
    if (!obj || obj->mark) {
        return;
    }
    obj->mark = 1;
    switch (obj->kind) {
        case LS_OBJ_TABLE: {
            ls_table_obj *t = (ls_table_obj *)obj;
            for (uint32_t i = 0; i < t->alen; i++) {
                mark_value(t->array[i]);
            }
            for (uint32_t i = 0; i < t->hsize; i++) {
                if (!ls_is_nil(t->hash_keys[i])) {
                    mark_value(t->hash_keys[i]);
                    mark_value(t->hash_values[i]);
                }
            }
            break;
        }
        case LS_OBJ_CLOSURE: {
            ls_closure_obj *c = (ls_closure_obj *)obj;
            for (uint32_t i = 0; i < c->nupvals; i++) {
                ls_obj *cell = ls_object_at((void *)(uintptr_t)c->upvals[i]);
                if (cell) {
                    mark_object(cell);
                }
            }
            break;
        }
        case LS_OBJ_CELL: {
            ls_cell_obj *c = (ls_cell_obj *)obj;
            mark_value(c->value);
            break;
        }
        case LS_OBJ_ITER: {
            ls_iter_obj *it = (ls_iter_obj *)obj;
            if (it->table) {
                mark_object(&it->table->header);
            }
            for (uint32_t i = 0; i < it->nkeys; i++) {
                mark_value(it->keys[i]);
            }
            break;
        }
        default:
            break;
    }
}

static void mark_roots(void *sp) {
    if (globals_table) {
        mark_object(&globals_table->header);
    }
    for (int i = 0; i < const_pool_count; i++) {
        mark_value(const_pool[i]);
    }
    /* Консервативный просмотр стека: любое слово, похожее на ссылку,
       считается корнем. */
    uintptr_t lo = (uintptr_t)sp;
    uintptr_t hi = (uintptr_t)stack_base;
    if (lo > hi) {
        uintptr_t tmp = lo;
        lo = hi;
        hi = tmp;
    }
    /* выравнивание по 8 байт */
    lo = (lo + 7u) & ~(uintptr_t)7u;
    for (uintptr_t p = lo; p + 8 <= hi; p += 8) {
        ls_value v = *(ls_value *)p;
        if (ls_is_box(v)) {
            int tag = ls_tag(v);
            if (tag == LS_TAG_STR || tag == LS_TAG_TABLE || tag == LS_TAG_FUNC
                    || tag == LS_TAG_ITER || tag == LS_TAG_NATIVE) {
                mark_value(v);
            }
        } else {
            /* «сырой» указатель (ячейка, замыкание) — тоже корень */
            ls_obj *obj = ls_object_at((void *)(uintptr_t)v);
            if (obj) {
                mark_object(obj);
            }
        }
    }
}

void lsrt_gc(void) {
    if (gc_disabled) {
        return;
    }
    void *sp = &sp;
    mark_roots(sp);
    ls_obj **link = &gc_objects;
    while (*link) {
        ls_obj *obj = *link;
        if (obj->mark) {
            obj->mark = 0;
            link = &obj->next;
            continue;
        }
        *link = obj->next;
        gc_bytes -= obj->size;
        switch (obj->kind) {
            case LS_OBJ_TABLE: {
                ls_table_obj *t = (ls_table_obj *)obj;
                free(t->array);
                free(t->hash_keys);
                free(t->hash_values);
                break;
            }
            case LS_OBJ_CLOSURE: {
                ls_closure_obj *c = (ls_closure_obj *)obj;
                free(c->upvals);
                break;
            }
            case LS_OBJ_ITER: {
                ls_iter_obj *it = (ls_iter_obj *)obj;
                free(it->keys);
                break;
            }
            default:
                break;
        }
        free(obj);
    }
}

void lsrt_gc_maybe(void) {
    if (gc_bytes > gc_threshold) {
        lsrt_gc();
    }
}

/* ------------------------------------------------------------------ */
/* Строки                                                              */
/* ------------------------------------------------------------------ */

static uint32_t hash_bytes(const char *data, size_t len) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < len; i++) {
        h ^= (unsigned char)data[i];
        h *= 16777619u;
    }
    return h ? h : 1u;
}

ls_string *lsrt_new_string(const char *data, size_t len) {
    ls_string_obj *s = (ls_string_obj *)ls_alloc(sizeof(ls_string_obj) + len, LS_OBJ_STRING);
    s->len = (uint32_t)len;
    s->hash = hash_bytes(data, len);
    if (len) {
        memcpy(s->data, data, len);
    }
    s->data[len] = '\0';
    return (ls_string *)s;
}

ls_string *lsrt_new_string_z(const char *data) {
    return lsrt_new_string(data, data ? strlen(data) : 0);
}

const char *lsrt_string_cstr(ls_value v) {
    if (!ls_is_box(v) || ls_tag(v) != LS_TAG_STR) {
        return "";
    }
    return ((ls_string *)ls_payload(v))->data;
}

size_t ls_str_len(ls_value v) {
    if (!ls_is_box(v) || ls_tag(v) != LS_TAG_STR) {
        return 0;
    }
    return ((ls_string *)ls_payload(v))->len;
}

ls_value lsrt_string_concat(ls_value a, ls_value b) {
    const char *sa = lsrt_string_cstr(a);
    const char *sb = lsrt_string_cstr(b);
    size_t la = ls_str_len(a);
    size_t lb = ls_str_len(b);
    char *buf = (char *)malloc(la + lb + 1);
    if (!buf) {
        return LS_NIL;
    }
    memcpy(buf, sa, la);
    memcpy(buf + la, sb, lb);
    buf[la + lb] = '\0';
    ls_string *s = lsrt_new_string(buf, la + lb);
    free(buf);
    return ls_box(LS_TAG_STR, s);
}

/* ------------------------------------------------------------------ */
/* Таблицы                                                             */
/* ------------------------------------------------------------------ */

static uint32_t value_hash(ls_value key) {
    if (ls_is_num(key)) {
        double d = ls_as_num(key);
        uint64_t bits;
        memcpy(&bits, &d, sizeof(bits));
        return hash_bytes((const char *)&bits, sizeof(bits));
    }
    if (ls_is_box(key) && ls_tag(key) == LS_TAG_STR) {
        ls_string *s = (ls_string *)ls_payload(key);
        return s->hash;
    }
    return hash_bytes((const char *)&key, sizeof(key));
}

static int value_equal(ls_value a, ls_value b) {
    if (a == b) {
        return 1;
    }
    if (ls_is_num(a) && ls_is_num(b)) {
        return ls_as_num(a) == ls_as_num(b);
    }
    if (ls_is_box(a) && ls_is_box(b) && ls_tag(a) == LS_TAG_STR && ls_tag(b) == LS_TAG_STR) {
        ls_string *x = (ls_string *)ls_payload(a);
        ls_string *y = (ls_string *)ls_payload(b);
        return x->hash == y->hash && x->len == y->len && memcmp(x->data, y->data, x->len) == 0;
    }
    return 0;
}

static void table_rehash(ls_table_obj *t, uint32_t new_size) {
    ls_value *old_keys = t->hash_keys;
    ls_value *old_values = t->hash_values;
    uint32_t old_size = t->hsize;
    t->hash_keys = (ls_value *)calloc(new_size, sizeof(ls_value));
    t->hash_values = (ls_value *)calloc(new_size, sizeof(ls_value));
    t->hsize = new_size;
    t->hcount = 0;
    for (uint32_t i = 0; i < old_size; i++) {
        if (!ls_is_nil(old_keys[i])) {
            lsrt_table_set((ls_table *)t, old_keys[i], old_values[i]);
        }
    }
    free(old_keys);
    free(old_values);
}

static void table_grow_array(ls_table_obj *t) {
    uint32_t new_size = t->asize ? t->asize * 2 : 8;
    ls_value *arr = (ls_value *)calloc(new_size, sizeof(ls_value));
    if (t->array) {
        memcpy(arr, t->array, t->alen * sizeof(ls_value));
        free(t->array);
    }
    t->array = arr;
    t->asize = new_size;
}

ls_table *lsrt_new_table(void) {
    ls_table_obj *t = (ls_table_obj *)ls_alloc(sizeof(ls_table_obj), LS_OBJ_TABLE);
    t->hsize = 8;
    t->hash_keys = (ls_value *)calloc(t->hsize, sizeof(ls_value));
    t->hash_values = (ls_value *)calloc(t->hsize, sizeof(ls_value));
    return (ls_table *)t;
}

static int table_set_hash(ls_table_obj *t, ls_value key, ls_value value) {
    if (t->hcount * 2 >= t->hsize) {
        table_rehash(t, t->hsize * 2);
    }
    uint32_t mask = t->hsize - 1;
    uint32_t pos = value_hash(key) & mask;
    for (uint32_t probe = 0; probe < t->hsize; probe++) {
        ls_value k = t->hash_keys[pos];
        if (ls_is_nil(k)) {
            t->hash_keys[pos] = key;
            t->hash_values[pos] = value;
            t->hcount++;
            return 1;
        }
        if (value_equal(k, key)) {
            t->hash_values[pos] = value;
            return 0;
        }
        pos = (pos + 1) & mask;
    }
    return 0;
}

static int table_get_hash(ls_table_obj *t, ls_value key, ls_value *out) {
    if (t->hsize == 0) {
        return 0;
    }
    uint32_t mask = t->hsize - 1;
    uint32_t pos = value_hash(key) & mask;
    for (uint32_t probe = 0; probe < t->hsize; probe++) {
        ls_value k = t->hash_keys[pos];
        if (ls_is_nil(k)) {
            return 0;
        }
        if (value_equal(k, key)) {
            *out = t->hash_values[pos];
            return 1;
        }
        pos = (pos + 1) & mask;
    }
    return 0;
}

void lsrt_table_set(ls_table *table, ls_value key, ls_value value) {
    ls_table_obj *t = (ls_table_obj *)table;
    if (!t) {
        return;
    }
    if (ls_is_num(key)) {
        double d = ls_as_num(key);
        if (d >= 1 && d == (double)(long long)d) {
            long long idx = (long long)d;
            if (idx >= 1 && idx <= (long long)t->alen) {
                t->array[idx - 1] = value;
                return;
            }
            if (idx == (long long)t->alen + 1) {
                if (t->alen == t->asize) {
                    table_grow_array(t);
                }
                t->array[t->alen++] = value;
                return;
            }
        }
    }
    if (ls_is_nil(key)) {
        return;
    }
    table_set_hash(t, key, value);
}

ls_value lsrt_table_get(ls_table *table, ls_value key) {
    ls_table_obj *t = (ls_table_obj *)table;
    if (!t) {
        return LS_NIL;
    }
    if (ls_is_num(key)) {
        double d = ls_as_num(key);
        if (d >= 1 && d == (double)(long long)d) {
            long long idx = (long long)d;
            if (idx >= 1 && idx <= (long long)t->alen) {
                return t->array[idx - 1];
            }
            return LS_NIL;
        }
    }
    ls_value out = LS_NIL;
    if (table_get_hash(t, key, &out)) {
        return out;
    }
    return LS_NIL;
}

ls_value lsrt_table_get_str(ls_table *table, const char *key) {
    ls_string *s = lsrt_new_string_z(key);
    return lsrt_table_get(table, ls_box(LS_TAG_STR, s));
}

void lsrt_table_set_str(ls_table *table, const char *key, ls_value value) {
    ls_string *s = lsrt_new_string_z(key);
    lsrt_table_set(table, ls_box(LS_TAG_STR, s), value);
}

int lsrt_table_length(ls_table *table) {
    ls_table_obj *t = (ls_table_obj *)table;
    return t ? (int)t->alen : 0;
}

/* ------------------------------------------------------------------ */
/* Замыкания, ячейки, итераторы                                       */
/* ------------------------------------------------------------------ */

ls_closure *lsrt_new_closure(void *code, uint32_t nparams, uint32_t nupvals) {
    ls_closure_obj *c = (ls_closure_obj *)ls_alloc(sizeof(ls_closure_obj), LS_OBJ_CLOSURE);
    c->code = code;
    c->nparams = nparams;
    c->nupvals = nupvals;
    if (nupvals) {
        c->upvals = (ls_value *)calloc(nupvals, sizeof(ls_value));
    }
    return (ls_closure *)c;
}

ls_cell *lsrt_new_cell(ls_value value) {
    ls_cell_obj *c = (ls_cell_obj *)ls_alloc(sizeof(ls_cell_obj), LS_OBJ_CELL);
    c->value = value;
    return (ls_cell *)c;
}

ls_iter *lsrt_iter_table(ls_table *table) {
    ls_table_obj *t = (ls_table_obj *)table;
    ls_iter_obj *it = (ls_iter_obj *)ls_alloc(sizeof(ls_iter_obj), LS_OBJ_ITER);
    it->table = t;
    uint32_t count = 0;
    if (t) {
        count = t->alen + t->hcount;
    }
    it->keys = (ls_value *)calloc(count ? count : 1, sizeof(ls_value));
    it->nkeys = 0;
    if (t) {
        for (uint32_t i = 0; i < t->alen; i++) {
            it->keys[it->nkeys++] = ls_num((double)(i + 1));
        }
        for (uint32_t i = 0; i < t->hsize; i++) {
            if (!ls_is_nil(t->hash_keys[i])) {
                it->keys[it->nkeys++] = t->hash_keys[i];
            }
        }
    }
    it->index = 0;
    return (ls_iter *)it;
}

ls_iter *lsrt_iter_array(ls_table *table) {
    ls_iter_obj *it = (ls_iter_obj *)ls_alloc(sizeof(ls_iter_obj), LS_OBJ_ITER);
    it->table = (ls_table_obj *)table;
    uint32_t len = table ? lsrt_table_length(table) : 0;
    it->keys = (ls_value *)calloc(len ? len : 1, sizeof(ls_value));
    for (uint32_t i = 0; i < len; i++) {
        it->keys[i] = ls_num((double)(i + 1));
    }
    it->nkeys = len;
    it->index = 0;
    return (ls_iter *)it;
}

int lsrt_iter_next(ls_iter *iter, ls_value *key, ls_value *value) {
    ls_iter_obj *it = (ls_iter_obj *)iter;
    if (!it || it->index >= it->nkeys) {
        return 0;
    }
    ls_value k = it->keys[it->index++];
    *key = k;
    *value = it->table ? lsrt_table_get((ls_table *)it->table, k) : LS_NIL;
    return 1;
}

/* ------------------------------------------------------------------ */
/* Платформа и состояние ввода                                        */
/* ------------------------------------------------------------------ */

static ls_platform platform;
static float input_x = 0, input_y = 0;
static int input_down = 0, input_pressed = 0, input_released = 0;

#define LS_MAX_BUTTONS 64

typedef struct {
    float x, y, w, h;
    const char *label;
    ls_value callback;
    int clicked;
} ls_button;

static ls_button buttons[LS_MAX_BUTTONS];
static int button_count = 0;

void lsrt_set_platform(const ls_platform *p) {
    if (p) {
        platform = *p;
    }
}

void lsrt_buttons_reset(void) {
    for (int i = 0; i < button_count; i++) {
        buttons[i].clicked = 0;
    }
    button_count = 0;
}

int lsrt_buttons_count(void) {
    return button_count;
}

void lsrt_button_rect(int index, float *out) {
    if (index < 0 || index >= button_count) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    out[0] = buttons[index].x;
    out[1] = buttons[index].y;
    out[2] = buttons[index].w;
    out[3] = buttons[index].h;
}

const char *lsrt_button_label(int index) {
    if (index < 0 || index >= button_count) {
        return "";
    }
    return buttons[index].label ? buttons[index].label : "";
}

int lsrt_button_clicked(int index) {
    if (index < 0 || index >= button_count) {
        return 0;
    }
    return buttons[index].clicked;
}

void lsrt_touch(float x, float y, int action) {
    input_x = x;
    input_y = y;
    if (action == 0) {         /* DOWN */
        input_down = 1;
        input_pressed = 1;
        for (int i = 0; i < button_count; i++) {
            ls_button *b = &buttons[i];
            if (x >= b->x && x <= b->x + b->w && y >= b->y && y <= b->y + b->h) {
                b->clicked = 1;
            }
        }
    } else if (action == 1) {  /* UP */
        input_down = 0;
        input_released = 1;
    }
}

/* ------------------------------------------------------------------ */
/* Операции над значениями                                             */
/* ------------------------------------------------------------------ */

int lsrt_type_of(ls_value v) {
    if (!ls_is_box(v)) {
        return 2;   /* number */
    }
    switch (ls_tag(v)) {
        case LS_TAG_NIL: return 0;
        case LS_TAG_FALSE:
        case LS_TAG_TRUE: return 1;
        case LS_TAG_STR: return 3;
        case LS_TAG_TABLE: return 4;
        case LS_TAG_FUNC:
        case LS_TAG_NATIVE: return 5;
        case LS_TAG_ITER: return 6;
        default: return 0;
    }
}

const char *lsrt_type_name(ls_value v) {
    switch (lsrt_type_of(v)) {
        case 0: return "nil";
        case 1: return "boolean";
        case 2: return "number";
        case 3: return "string";
        case 4: return "table";
        case 5: return "function";
        default: return "iterator";
    }
}

ls_value lsrt_to_string(ls_value v) {
    switch (lsrt_type_of(v)) {
        case 0: return ls_box(LS_TAG_STR, lsrt_new_string_z("nil"));
        case 1: return ls_box(LS_TAG_STR, lsrt_new_string_z(ls_truthy(v) ? "true" : "false"));
        case 2: {
            double d = ls_as_num(v);
            char buf[64];
            if (d == (double)(long long)d && d < 1e15 && d > -1e15) {
                snprintf(buf, sizeof(buf), "%lld", (long long)d);
            } else {
                snprintf(buf, sizeof(buf), "%.14g", d);
            }
            return ls_box(LS_TAG_STR, lsrt_new_string_z(buf));
        }
        case 3: return v;
        case 4: return ls_box(LS_TAG_STR, lsrt_new_string_z("table"));
        case 5: return ls_box(LS_TAG_STR, lsrt_new_string_z("function"));
        default: return ls_box(LS_TAG_STR, lsrt_new_string_z("iterator"));
    }
}

ls_value lsrt_to_number(ls_value v) {
    if (ls_is_num(v)) {
        return v;
    }
    if (ls_is_box(v) && ls_tag(v) == LS_TAG_STR) {
        const char *s = lsrt_string_cstr(v);
        char *end = NULL;
        double d = strtod(s, &end);
        if (end != s) {
            return ls_num(d);
        }
    }
    return LS_NIL;
}

int lsrt_truthy(ls_value v) {
    return v != LS_NIL && v != LS_FALSE;
}

int lsrt_equals(ls_value a, ls_value b) {
    return value_equal(a, b);
}

int lsrt_compare(ls_value a, ls_value b) {
    if (ls_is_num(a) && ls_is_num(b)) {
        double x = ls_as_num(a);
        double y = ls_as_num(b);
        return x < y ? -1 : (x > y ? 1 : 0);
    }
    if (ls_is_box(a) && ls_is_box(b) && ls_tag(a) == LS_TAG_STR && ls_tag(b) == LS_TAG_STR) {
        ls_string *x = (ls_string *)ls_payload(a);
        ls_string *y = (ls_string *)ls_payload(b);
        size_t n = x->len < y->len ? x->len : y->len;
        int c = memcmp(x->data, y->data, n);
        if (c != 0) {
            return c < 0 ? -1 : 1;
        }
        return x->len == y->len ? 0 : (x->len < y->len ? -1 : 1);
    }
    return 0;
}

double lsrt_check_number(ls_value v, const char *op) {
    if (ls_is_num(v)) {
        return ls_as_num(v);
    }
    fprintf(stderr, "LS: %s ожидал число, получено %s\n", op, lsrt_type_name(v));
    return 0.0;
}

/* ------------------------------------------------------------------ */
/* Стандартная библиотека                                             */
/* ------------------------------------------------------------------ */

/*
 * Соглашение о вызовах: значение функции получает указатель на буфер
 * аргументов, где args[-1] хранит их количество. Это позволяет вызывать
 * как LS-функции, так и встроенные без обёрток.
 */
#define LS_ARGC(args) ((int)(args)[-1])

ls_value lsrt_print(ls_value *args) {
    int argc = LS_ARGC(args);
    fputs("", stdout);
    for (int i = 0; i < argc; i++) {
        ls_value v = args[i];
        if (ls_is_num(v)) {
            double d = ls_as_num(v);
            char buf[64];
            if (d == (double)(long long)d && d < 1e15 && d > -1e15) {
                snprintf(buf, sizeof(buf), "%lld", (long long)d);
            } else {
                snprintf(buf, sizeof(buf), "%.14g", d);
            }
            fputs(buf, stdout);
        } else if (ls_is_box(v) && ls_tag(v) == LS_TAG_STR) {
            fputs(lsrt_string_cstr(v), stdout);
        } else if (ls_is_box(v) && ls_tag(v) == LS_TAG_NIL) {
            fputs("nil", stdout);
        } else if (ls_is_box(v) && ls_tag(v) == LS_TAG_TRUE) {
            fputs("true", stdout);
        } else if (ls_is_box(v) && ls_tag(v) == LS_TAG_FALSE) {
            fputs("false", stdout);
        } else {
            fputs(lsrt_type_name(v), stdout);
        }
        if (i + 1 < argc) {
            fputc('\t', stdout);
        }
    }
    fputc('\n', stdout);
    fflush(stdout);
    return LS_NIL;
}

ls_value lsrt_tostring(ls_value *args) {
    return lsrt_to_string(args[0]);
}

ls_value lsrt_tonumber(ls_value *args) {
    return lsrt_to_number(args[0]);
}

ls_value lsrt_type(ls_value *args) {
    return ls_box(LS_TAG_STR, lsrt_new_string_z(lsrt_type_name(args[0])));
}

ls_value lsrt_pairs(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return LS_NIL;
    }
    return ls_box(LS_TAG_ITER, lsrt_iter_table((ls_table *)ls_payload(args[0])));
}

ls_value lsrt_ipairs(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return LS_NIL;
    }
    return ls_box(LS_TAG_ITER, lsrt_iter_array((ls_table *)ls_payload(args[0])));
}

#define MATH1(name, expr) \
    ls_value name(ls_value *args) { \
        double x = lsrt_check_number(args[0], #name); \
        (void) x; \
        return ls_num(expr); \
    }

MATH1(lsrt_math_floor, floor(x))
MATH1(lsrt_math_ceil, ceil(x))
MATH1(lsrt_math_abs, fabs(x))
MATH1(lsrt_math_sqrt, sqrt(x))
MATH1(lsrt_math_sin, sin(x))
MATH1(lsrt_math_cos, cos(x))
MATH1(lsrt_math_tan, tan(x))

ls_value lsrt_math_atan2(ls_value *args) {
    double y = lsrt_check_number(args[0], "atan2");
    double x = lsrt_check_number(args[1], "atan2");
    return ls_num(atan2(y, x));
}

ls_value lsrt_math_min(ls_value *args) {
    int argc = LS_ARGC(args);
    double m = lsrt_check_number(args[0], "min");
    for (int i = 1; i < argc; i++) {
        double v = lsrt_check_number(args[i], "min");
        if (v < m) {
            m = v;
        }
    }
    return ls_num(m);
}

ls_value lsrt_math_max(ls_value *args) {
    int argc = LS_ARGC(args);
    double m = lsrt_check_number(args[0], "max");
    for (int i = 1; i < argc; i++) {
        double v = lsrt_check_number(args[i], "max");
        if (v > m) {
            m = v;
        }
    }
    return ls_num(m);
}

static unsigned int rng_state = 2463534242u;

ls_value lsrt_math_random(ls_value *args) {
    rng_state = rng_state * 1664525u + 1013904223u;
    double r = (double)(rng_state >> 8) / (double)(1u << 24);
    if (LS_ARGC(args) >= 2) {
        double lo = lsrt_check_number(args[0], "random");
        double hi = lsrt_check_number(args[1], "random");
        if (hi < lo) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        return ls_num(lo + r * (hi - lo + 1));
    }
    return ls_num(r);
}

ls_value lsrt_string_len(ls_value *args) {
    return ls_num((double)ls_str_len(args[0]));
}

ls_value lsrt_string_upper(ls_value *args) {
    const char *s = lsrt_string_cstr(args[0]);
    size_t n = ls_str_len(args[0]);
    char *buf = (char *)malloc(n + 1);
    for (size_t i = 0; i < n; i++) {
        buf[i] = (char)toupper((unsigned char)s[i]);
    }
    buf[n] = 0;
    ls_value out = ls_box(LS_TAG_STR, lsrt_new_string(buf, n));
    free(buf);
    return out;
}

ls_value lsrt_string_lower(ls_value *args) {
    const char *s = lsrt_string_cstr(args[0]);
    size_t n = ls_str_len(args[0]);
    char *buf = (char *)malloc(n + 1);
    for (size_t i = 0; i < n; i++) {
        buf[i] = (char)tolower((unsigned char)s[i]);
    }
    buf[n] = 0;
    ls_value out = ls_box(LS_TAG_STR, lsrt_new_string(buf, n));
    free(buf);
    return out;
}

ls_value lsrt_string_sub(ls_value *args) {
    int argc = LS_ARGC(args);
    const char *s = lsrt_string_cstr(args[0]);
    size_t n = ls_str_len(args[0]);
    long from = (long)lsrt_check_number(args[1], "sub");
    long to = argc > 2 ? (long)lsrt_check_number(args[2], "sub") : (long)n;
    if (from < 0) {
        from = (long)n + from + 1;
    }
    if (to < 0) {
        to = (long)n + to + 1;
    }
    if (from < 1) {
        from = 1;
    }
    if (to > (long)n) {
        to = (long)n;
    }
    if (from > to) {
        return ls_box(LS_TAG_STR, lsrt_new_string("", 0));
    }
    return ls_box(LS_TAG_STR, lsrt_new_string(s + from - 1, (size_t)(to - from + 1)));
}

ls_value lsrt_string_rep(ls_value *args) {
    const char *s = lsrt_string_cstr(args[0]);
    size_t n = ls_str_len(args[0]);
    long times = (long)lsrt_check_number(args[1], "rep");
    if (times < 0) {
        times = 0;
    }
    char *buf = (char *)malloc(n * (size_t)times + 1);
    size_t pos = 0;
    for (long i = 0; i < times; i++) {
        memcpy(buf + pos, s, n);
        pos += n;
    }
    buf[pos] = 0;
    ls_value out = ls_box(LS_TAG_STR, lsrt_new_string(buf, pos));
    free(buf);
    return out;
}

ls_value lsrt_string_format(ls_value *args) {
    const char *fmt = lsrt_string_cstr(args[0]);
    size_t len = ls_str_len(args[0]);
    char *buf = (char *)malloc(len * 4 + 256);
    size_t out = 0;
    int argi = 1;
    for (size_t i = 0; i < len; i++) {
        if (fmt[i] != '%' || i + 1 >= len) {
            buf[out++] = fmt[i];
            continue;
        }
        char spec = fmt[++i];
        char tmp[64];
        switch (spec) {
            case '%':
                buf[out++] = '%';
                break;
            case 's': {
                ls_value v = argi < LS_ARGC(args) ? args[argi++] : LS_NIL;
                ls_value s = lsrt_to_string(v);
                const char *cs = lsrt_string_cstr(s);
                size_t sl = ls_str_len(s);
                memcpy(buf + out, cs, sl);
                out += sl;
                break;
            }
            case 'd': {
                double d = argi < LS_ARGC(args) ? lsrt_check_number(args[argi++], "format") : 0;
                snprintf(tmp, sizeof(tmp), "%lld", (long long)d);
                size_t tl = strlen(tmp);
                memcpy(buf + out, tmp, tl);
                out += tl;
                break;
            }
            case 'f': {
                double d = argi < LS_ARGC(args) ? lsrt_check_number(args[argi++], "format") : 0;
                snprintf(tmp, sizeof(tmp), "%.2f", d);
                size_t tl = strlen(tmp);
                memcpy(buf + out, tmp, tl);
                out += tl;
                break;
            }
            default:
                buf[out++] = '%';
                buf[out++] = spec;
                break;
        }
    }
    ls_value outv = ls_box(LS_TAG_STR, lsrt_new_string(buf, out));
    free(buf);
    return outv;
}

ls_value lsrt_table_insert_fn(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return LS_NIL;
    }
    ls_table *t = (ls_table *)ls_payload(args[0]);
    lsrt_table_set(t, ls_num((double)(lsrt_table_length(t) + 1)), args[1]);
    return LS_NIL;
}

ls_value lsrt_table_remove_fn(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return LS_NIL;
    }
    ls_table *t = (ls_table *)ls_payload(args[0]);
    int len = lsrt_table_length(t);
    int index = len;
    if (LS_ARGC(args) > 1) {
        index = (int)lsrt_check_number(args[1], "remove");
    }
    if (index < 1 || index > len) {
        return LS_NIL;
    }
    ls_value removed = lsrt_table_get(t, ls_num((double)index));
    for (int i = index; i < len; i++) {
        lsrt_table_set(t, ls_num((double)i), lsrt_table_get(t, ls_num((double)(i + 1))));
    }
    lsrt_table_set(t, ls_num((double)len), LS_NIL);
    /* убираем хвост массива */
    ls_table_obj *to = (ls_table_obj *)t;
    if (to->alen == (uint32_t)len) {
        to->alen--;
    }
    return removed;
}

ls_value lsrt_table_concat_fn(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return ls_box(LS_TAG_STR, lsrt_new_string("", 0));
    }
    ls_table *t = (ls_table *)ls_payload(args[0]);
    int argc = LS_ARGC(args);
    const char *sep = argc > 1 ? lsrt_string_cstr(args[1]) : "";
    size_t seplen = argc > 1 ? ls_str_len(args[1]) : 0;
    int len = lsrt_table_length(t);
    size_t total = 0;
    for (int i = 1; i <= len; i++) {
        total += ls_str_len(lsrt_table_get(t, ls_num((double)i)));
        if (i < len) {
            total += seplen;
        }
    }
    char *buf = (char *)malloc(total + 1);
    size_t pos = 0;
    for (int i = 1; i <= len; i++) {
        ls_value v = lsrt_table_get(t, ls_num((double)i));
        ls_value s = lsrt_to_string(v);
        size_t sl = ls_str_len(s);
        memcpy(buf + pos, lsrt_string_cstr(s), sl);
        pos += sl;
        if (i < len) {
            memcpy(buf + pos, sep, seplen);
            pos += seplen;
        }
    }
    buf[pos] = 0;
    ls_value out = ls_box(LS_TAG_STR, lsrt_new_string(buf, pos));
    free(buf);
    return out;
}

/* ------------------------------------------------------------------ */
/* Глобальная таблица и инициализация                                 */
/* ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ */
/* Платформенные встроенные функции: рисование, ввод, время, звук      */
/* ------------------------------------------------------------------ */

static float arg_float(ls_value *args, int i) {
    return (float)ls_as_num(args[i]);
}

static ls_value native_clear(ls_value *args) {
    if (platform.clear) {
        platform.clear((int)ls_as_num(args[0]));
    }
    return LS_NIL;
}

static ls_value native_rect(ls_value *args) {
    if (platform.rect) {
        platform.rect(arg_float(args, 0), arg_float(args, 1), arg_float(args, 2),
                      arg_float(args, 3), (int)ls_as_num(args[4]));
    }
    return LS_NIL;
}

static ls_value native_circle(ls_value *args) {
    if (platform.circle) {
        platform.circle(arg_float(args, 0), arg_float(args, 1), arg_float(args, 2),
                        (int)ls_as_num(args[3]));
    }
    return LS_NIL;
}

static ls_value native_line(ls_value *args) {
    if (platform.line) {
        platform.line(arg_float(args, 0), arg_float(args, 1), arg_float(args, 2),
                      arg_float(args, 3), (int)ls_as_num(args[4]));
    }
    return LS_NIL;
}

static ls_value native_text(ls_value *args) {
    if (platform.text) {
        platform.text(arg_float(args, 0), arg_float(args, 1), lsrt_string_cstr(args[2]),
                      arg_float(args, 3), (int)ls_as_num(args[4]));
    }
    return LS_NIL;
}

static ls_value native_input_x(ls_value *args) {
    (void) args;
    return ls_num(platform.input_x ? (double)platform.input_x() : 0.0);
}

static ls_value native_input_y(ls_value *args) {
    (void) args;
    return ls_num(platform.input_y ? (double)platform.input_y() : 0.0);
}

static ls_value native_input_down(ls_value *args) {
    (void) args;
    return (platform.input_down && platform.input_down()) ? LS_TRUE : LS_FALSE;
}

static ls_value native_input_pressed(ls_value *args) {
    (void) args;
    return (platform.input_pressed && platform.input_pressed()) ? LS_TRUE : LS_FALSE;
}

static ls_value native_input_released(ls_value *args) {
    (void) args;
    return (platform.input_released && platform.input_released()) ? LS_TRUE : LS_FALSE;
}

static ls_value native_time_now(ls_value *args) {
    (void) args;
    return ls_num(platform.time_now ? platform.time_now() : 0.0);
}

static ls_value native_time_frame(ls_value *args) {
    (void) args;
    return ls_num(platform.time_frame ? (double)platform.time_frame() : 0.0);
}

static ls_value native_time_dt(ls_value *args) {
    (void) args;
    return ls_num(platform.time_dt ? platform.time_dt() : 0.0);
}

static ls_value native_ui_button(ls_value *args) {
    if (!platform.ui_button) {
        return ls_num(0);
    }
    int pressed = platform.ui_button(arg_float(args, 0), arg_float(args, 1), arg_float(args, 2),
                                     arg_float(args, 3), lsrt_string_cstr(args[4]), args[5]);
    return ls_num((double)pressed);
}

static ls_value native_beep(ls_value *args) {
    if (platform.beep) {
        platform.beep((int)ls_as_num(args[0]), (int)ls_as_num(args[1]));
    }
    return LS_NIL;
}

static ls_value native_app_width(ls_value *args) {
    (void) args;
    return ls_num(platform.app_width ? (double)platform.app_width() : 0.0);
}

static ls_value native_app_height(ls_value *args) {
    (void) args;
    return ls_num(platform.app_height ? (double)platform.app_height() : 0.0);
}

static void register_native(ls_table *globals, const char *name, ls_native_fn fn) {
    ls_native_obj *n = (ls_native_obj *)ls_alloc(sizeof(ls_native_obj), LS_OBJ_NATIVE);
    n->fn = fn;
    n->name = name;
    lsrt_table_set_str(globals, name, ls_box(LS_TAG_NATIVE, n));
}

ls_table *lsrt_globals(void) {
    return (ls_table *)globals_table;
}

void lsrt_init(void) {
    char probe;
    stack_base = &probe;
    build_api_table();
    gc_disabled = 1;
    globals_table = (ls_table_obj *)lsrt_new_table();
    gc_disabled = 0;

    ls_table *g = (ls_table *)globals_table;
    register_native(g, "print", lsrt_print);
    register_native(g, "tostring", lsrt_tostring);
    register_native(g, "tonumber", lsrt_tonumber);
    register_native(g, "type", lsrt_type);
    register_native(g, "pairs", lsrt_pairs);
    register_native(g, "ipairs", lsrt_ipairs);

    /* math */
    ls_table_obj *math = (ls_table_obj *)lsrt_new_table();
    lsrt_table_set_str((ls_table *)math, "pi", ls_num(3.14159265358979323846));
    lsrt_table_set_str((ls_table *)math, "huge", ls_num(1.7976931348623157e308));
    register_native((ls_table *)math, "floor", lsrt_math_floor);
    register_native((ls_table *)math, "ceil", lsrt_math_ceil);
    register_native((ls_table *)math, "abs", lsrt_math_abs);
    register_native((ls_table *)math, "sqrt", lsrt_math_sqrt);
    register_native((ls_table *)math, "sin", lsrt_math_sin);
    register_native((ls_table *)math, "cos", lsrt_math_cos);
    register_native((ls_table *)math, "tan", lsrt_math_tan);
    register_native((ls_table *)math, "atan2", lsrt_math_atan2);
    register_native((ls_table *)math, "min", lsrt_math_min);
    register_native((ls_table *)math, "max", lsrt_math_max);
    register_native((ls_table *)math, "random", lsrt_math_random);
    lsrt_table_set_str(g, "math", ls_box(LS_TAG_TABLE, math));

    /* string */
    ls_table_obj *str = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)str, "len", lsrt_string_len);
    register_native((ls_table *)str, "upper", lsrt_string_upper);
    register_native((ls_table *)str, "lower", lsrt_string_lower);
    register_native((ls_table *)str, "sub", lsrt_string_sub);
    register_native((ls_table *)str, "rep", lsrt_string_rep);
    register_native((ls_table *)str, "format", lsrt_string_format);
    lsrt_table_set_str(g, "string", ls_box(LS_TAG_TABLE, str));

    /* table */
    ls_table_obj *tbl = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)tbl, "insert", lsrt_table_insert_fn);
    register_native((ls_table *)tbl, "remove", lsrt_table_remove_fn);
    register_native((ls_table *)tbl, "concat", lsrt_table_concat_fn);
    lsrt_table_set_str(g, "table", ls_box(LS_TAG_TABLE, tbl));

    /* Платформа: рисование */
    ls_table_obj *draw = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)draw, "clear", native_clear);
    register_native((ls_table *)draw, "rect", native_rect);
    register_native((ls_table *)draw, "circle", native_circle);
    register_native((ls_table *)draw, "line", native_line);
    register_native((ls_table *)draw, "text", native_text);
    lsrt_table_set_str(g, "draw", ls_box(LS_TAG_TABLE, draw));

    /* Платформа: ввод */
    ls_table_obj *input = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)input, "x", native_input_x);
    register_native((ls_table *)input, "y", native_input_y);
    register_native((ls_table *)input, "down", native_input_down);
    register_native((ls_table *)input, "pressed", native_input_pressed);
    register_native((ls_table *)input, "released", native_input_released);
    lsrt_table_set_str(g, "input", ls_box(LS_TAG_TABLE, input));

    /* Платформа: время */
    ls_table_obj *time_tbl = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)time_tbl, "now", native_time_now);
    register_native((ls_table *)time_tbl, "dt", native_time_dt);
    register_native((ls_table *)time_tbl, "frame", native_time_frame);
    lsrt_table_set_str(g, "time", ls_box(LS_TAG_TABLE, time_tbl));

    /* Платформа: элементы интерфейса */
    ls_table_obj *ui = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)ui, "button", native_ui_button);
    lsrt_table_set_str(g, "ui", ls_box(LS_TAG_TABLE, ui));

    /* Платформа: звук */
    ls_table_obj *sound = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)sound, "beep", native_beep);
    lsrt_table_set_str(g, "sound", ls_box(LS_TAG_TABLE, sound));

    /* Платформа: размеры окна */
    ls_table_obj *app = (ls_table_obj *)lsrt_new_table();
    register_native((ls_table *)app, "width", native_app_width);
    register_native((ls_table *)app, "height", native_app_height);
    lsrt_table_set_str(g, "app", ls_box(LS_TAG_TABLE, app));
}

void lsrt_shutdown(void) {
    gc_disabled = 1;
    gc_objects = NULL;
    gc_bytes = 0;
    globals_table = NULL;
}

/* ------------------------------------------------------------------ */
/* Дополнительные операции, используемые сгенерированным кодом         */
/* ------------------------------------------------------------------ */

ls_value lsrt_index_get(ls_value target, ls_value key) {
    if (ls_is_box(target) && ls_tag(target) == LS_TAG_TABLE) {
        return lsrt_table_get((ls_table *)ls_payload(target), key);
    }
    if (ls_is_box(target) && ls_tag(target) == LS_TAG_STR) {
        if (ls_is_num(key)) {
            double d = ls_as_num(key);
            long idx = (long)d;
            const char *s = lsrt_string_cstr(target);
            if (d == (double)idx && idx >= 1 && idx <= (long)ls_str_len(target)) {
                char one[2] = {s[idx - 1], 0};
                return ls_box(LS_TAG_STR, lsrt_new_string(one, 1));
            }
        }
        return LS_NIL;
    }
    return LS_NIL;
}

ls_value lsrt_index_set(ls_value target, ls_value key, ls_value value) {
    if (ls_is_box(target) && ls_tag(target) == LS_TAG_TABLE) {
        lsrt_table_set((ls_table *)ls_payload(target), key, value);
    }
    return LS_NIL;
}

ls_value lsrt_add_values(ls_value a, ls_value b) {
    if (ls_is_num(a) && ls_is_num(b)) {
        return ls_num(ls_as_num(a) + ls_as_num(b));
    }
    if ((ls_is_box(a) && ls_tag(a) == LS_TAG_STR) || (ls_is_box(b) && ls_tag(b) == LS_TAG_STR)) {
        ls_value sa = lsrt_to_string(a);
        ls_value sb = lsrt_to_string(b);
        return lsrt_string_concat(sa, sb);
    }
    fprintf(stderr, "LS: нельзя сложить %s и %s\n", lsrt_type_name(a), lsrt_type_name(b));
    return LS_NIL;
}

ls_value lsrt_sub_values(ls_value a, ls_value b) {
    return ls_num(lsrt_check_number(a, "-") - lsrt_check_number(b, "-"));
}

ls_value lsrt_mul_values(ls_value a, ls_value b) {
    return ls_num(lsrt_check_number(a, "*") * lsrt_check_number(b, "*"));
}

ls_value lsrt_div_values(ls_value a, ls_value b) {
    double d = lsrt_check_number(b, "/");
    if (d == 0.0) {
        return ls_num(0.0 / 0.0);   /* NaN, как и в Lua */
    }
    return ls_num(lsrt_check_number(a, "/") / d);
}

ls_value lsrt_mod_values(ls_value a, ls_value b) {
    double x = lsrt_check_number(a, "%");
    double y = lsrt_check_number(b, "%");
    if (y == 0.0) {
        return ls_num(0.0 / 0.0);
    }
    double r = fmod(x, y);
    if (r != 0 && ((r < 0) != (y < 0))) {
        r += y;
    }
    return ls_num(r);
}

ls_value lsrt_pow_values(ls_value a, ls_value b) {
    return ls_num(pow(lsrt_check_number(a, "^"), lsrt_check_number(b, "^")));
}

ls_value lsrt_concat_values(ls_value a, ls_value b) {
    ls_value sa = lsrt_to_string(a);
    ls_value sb = lsrt_to_string(b);
    return lsrt_string_concat(sa, sb);
}

ls_value lsrt_len_value(ls_value v) {
    if (ls_is_box(v) && ls_tag(v) == LS_TAG_STR) {
        return ls_num((double)ls_str_len(v));
    }
    if (ls_is_box(v) && ls_tag(v) == LS_TAG_TABLE) {
        return ls_num((double)lsrt_table_length((ls_table *)ls_payload(v)));
    }
    return ls_num(0.0);
}

ls_value lsrt_neg_value(ls_value v) {
    return ls_num(-lsrt_check_number(v, "унарный минус"));
}

ls_value lsrt_not_value(ls_value v) {
    return lsrt_truthy(v) ? LS_FALSE : LS_TRUE;
}

ls_value lsrt_eq_value(ls_value a, ls_value b) {
    return lsrt_equals(a, b) ? LS_TRUE : LS_FALSE;
}

ls_value lsrt_lt_value(ls_value a, ls_value b) {
    return lsrt_compare(a, b) < 0 ? LS_TRUE : LS_FALSE;
}

ls_value lsrt_le_value(ls_value a, ls_value b) {
    return lsrt_compare(a, b) <= 0 ? LS_TRUE : LS_FALSE;
}

/* Подготовка итератора: таблица -> итератор, готовый итератор -> он сам. */
ls_value lsrt_for_prep_value(ls_value v) {
    if (ls_is_box(v) && ls_tag(v) == LS_TAG_TABLE) {
        return ls_box(LS_TAG_ITER, lsrt_iter_table((ls_table *)ls_payload(v)));
    }
    if (ls_is_box(v) && ls_tag(v) == LS_TAG_ITER) {
        return v;
    }
    if (ls_is_box(v) && (ls_tag(v) == LS_TAG_FUNC || ls_tag(v) == LS_TAG_NATIVE)) {
        return v;
    }
    return LS_NIL;
}

/*
 * Шаг итерации: возвращает ключ (nil — конец) и записывает значение
 * по адресу out (args[2] — сырой указатель).
 */
ls_value lsrt_for_iter_value(ls_value iter, ls_value ctrl, ls_value *out) {
    if (ls_is_box(iter) && ls_tag(iter) == LS_TAG_ITER) {
        ls_value key = LS_NIL;
        ls_value value = LS_NIL;
        if (lsrt_iter_next((ls_iter *)ls_payload(iter), &key, &value)) {
            if (out) {
                *out = value;
            }
            return key;
        }
        return LS_NIL;
    }
    /* Пользовательская функция-итератор: возвращает только ключ. */
    if (ls_is_box(iter) && ls_tag(iter) == LS_TAG_NATIVE) {
        ls_native *n = (ls_native *)ls_payload(iter);
        ls_value frame[3];
        frame[0] = ls_num(2);
        frame[1] = ctrl;
        frame[2] = LS_NIL;
        ls_value r = n->fn(frame + 1);
        if (out) {
            *out = LS_NIL;
        }
        return r;
    }
    if (ls_is_box(iter) && ls_tag(iter) == LS_TAG_FUNC) {
        ls_value call_args[2];
        call_args[0] = ctrl;
        call_args[1] = LS_NIL;
        ls_value r = lsrt_call_value(iter, call_args, 2);
        if (out) {
            *out = LS_NIL;
        }
        return r;
    }
    return LS_NIL;
}

/* ------------------------------------------------------------------ */
/* Вызов значения-функции                                             */
/* ------------------------------------------------------------------ */

/*
 * Вызывает замыкание сгенерированного кода или встроенную функцию.
 * Раскладка аргументов та же, что и у API: args[-1] — количество.
 */
ls_value lsrt_call_value(ls_value fn, ls_value *args, int argc) {
    if (!ls_is_box(fn)) {
        return LS_NIL;
    }
    int tag = ls_tag(fn);
    if (tag == LS_TAG_NATIVE) {
        ls_native *native = (ls_native *)ls_payload(fn);
        ls_value frame[64];
        int count = argc > 62 ? 62 : argc;
        frame[0] = ls_num((double)count);
        for (int i = 0; i < count; i++) {
            frame[i + 1] = args[i];
        }
        return native->fn(frame + 1);
    }
    if (tag == LS_TAG_FUNC) {
        ls_closure *c = (ls_closure *)ls_payload(fn);
        if (!c || !c->code) {
            return LS_NIL;
        }
        ls_value frame[64];
        int count = argc > 62 ? 62 : argc;
        frame[0] = (ls_value)(uintptr_t)fn;   /* замыкание */
        frame[1] = ls_num((double)count);      /* количество аргументов */
        for (int i = 0; i < count; i++) {
            frame[i + 2] = args[i];
        }
        ls_value (*code)(ls_value *) = (ls_value (*)(ls_value *))c->code;
        return code(frame + 2);
    }
    return LS_NIL;
}

/* ------------------------------------------------------------------ */
/* Обёртки API для сгенерированного кода                               */
/* ------------------------------------------------------------------ */

ls_api_fn ls_api_table[LS_API_COUNT];

static ls_value api_globals(ls_value *args) {
    (void) args;
    return ls_box(LS_TAG_TABLE, globals_table);
}

static ls_value api_new_table(ls_value *args) {
    (void) args;
    return ls_box(LS_TAG_TABLE, lsrt_new_table());
}

static ls_value api_table_get(ls_value *args) {
    if (!args[0] || !ls_is_box(args[0]) || ls_tag(args[0]) != LS_TAG_TABLE) {
        return LS_NIL;
    }
    return lsrt_table_get((ls_table *)ls_payload(args[0]), args[1]);
}

static ls_value api_table_set(ls_value *args) {
    if (args[0] && ls_is_box(args[0]) && ls_tag(args[0]) == LS_TAG_TABLE) {
        lsrt_table_set((ls_table *)ls_payload(args[0]), args[1], args[2]);
    }
    return LS_NIL;
}

static ls_value api_index_get(ls_value *args) {
    return lsrt_index_get(args[0], args[1]);
}

static ls_value api_index_set(ls_value *args) {
    return lsrt_index_set(args[0], args[1], args[2]);
}

static ls_value api_add(ls_value *args) { return lsrt_add_values(args[0], args[1]); }
static ls_value api_sub(ls_value *args) { return lsrt_sub_values(args[0], args[1]); }
static ls_value api_mul(ls_value *args) { return lsrt_mul_values(args[0], args[1]); }
static ls_value api_div(ls_value *args) { return lsrt_div_values(args[0], args[1]); }
static ls_value api_mod(ls_value *args) { return lsrt_mod_values(args[0], args[1]); }
static ls_value api_pow(ls_value *args) { return lsrt_pow_values(args[0], args[1]); }
static ls_value api_concat(ls_value *args) { return lsrt_concat_values(args[0], args[1]); }
static ls_value api_len(ls_value *args) { return lsrt_len_value(args[0]); }
static ls_value api_neg(ls_value *args) { return lsrt_neg_value(args[0]); }
static ls_value api_not(ls_value *args) { return lsrt_not_value(args[0]); }
static ls_value api_eq(ls_value *args) { return lsrt_eq_value(args[0], args[1]); }
static ls_value api_lt(ls_value *args) { return lsrt_lt_value(args[0], args[1]); }
static ls_value api_le(ls_value *args) { return lsrt_le_value(args[0], args[1]); }
static ls_value api_truthy(ls_value *args) { return lsrt_truthy(args[0]) ? LS_TRUE : LS_FALSE; }

static ls_value api_for_prep(ls_value *args) { return lsrt_for_prep_value(args[0]); }

static ls_value api_for_iter(ls_value *args) {
    ls_value *out = (ls_value *)(uintptr_t)args[2];
    return lsrt_for_iter_value(args[0], args[1], out);
}

static ls_value api_new_cell(ls_value *args) {
    ls_cell *cell = lsrt_new_cell(args[0]);
    return (ls_value)(uintptr_t)cell;
}

static ls_value api_new_closure(ls_value *args) {
    void *code = (void *)(uintptr_t)args[0];
    ls_closure *c = lsrt_new_closure(code, (uint32_t)args[1], (uint32_t)args[2]);
    return (ls_value)(uintptr_t)c;
}

static ls_value api_cell_value(ls_value *args) {
    ls_cell *cell = (ls_cell *)(uintptr_t)args[0];
    return cell ? cell->value : LS_NIL;
}

static ls_value api_ne(ls_value *args) {
    return lsrt_eq_value(args[0], args[1]) ? LS_FALSE : LS_TRUE;
}

/*
 * Строковая константа: args[0] — индекс в пуле, args[1] — указатель на
 * данные в .rodata, args[2] — длина. Строка создаётся один раз.
 */
static ls_value api_const_string(ls_value *args) {
    int index = (int)ls_as_num(args[0]);
    const char *data = (const char *)(uintptr_t)args[1];
    int len = (int)ls_as_num(args[2]);
    if (index < 0) {
        return LS_NIL;
    }
    if (index >= const_pool_cap) {
        int cap = const_pool_cap ? const_pool_cap : 64;
        while (cap <= index) {
            cap *= 2;
        }
        ls_value *bigger = (ls_value *)realloc(const_pool, (size_t)cap * sizeof(ls_value));
        if (!bigger) {
            fprintf(stderr, "LS: нехватка памяти для пула констант\n");
            abort();
        }
        for (int i = const_pool_cap; i < cap; i++) {
            bigger[i] = LS_NIL;
        }
        const_pool = bigger;
        const_pool_cap = cap;
    }
    if (ls_is_nil(const_pool[index])) {
        const_pool[index] = ls_box(LS_TAG_STR, lsrt_new_string(data ? data : "", (size_t)(len > 0 ? len : 0)));
    }
    if (index >= const_pool_count) {
        const_pool_count = index + 1;
    }
    return const_pool[index];
}

/* Привязывает ячейку к переменной замыкания. */
static ls_value api_closure_set_upval(ls_value *args) {
    ls_closure *c = (ls_closure *)(uintptr_t)args[0];
    int index = (int)ls_as_num(args[1]);
    ls_cell *cell = (ls_cell *)(uintptr_t)args[2];
    if (c && index >= 0 && (uint32_t)index < c->nupvals) {
        c->upvals[index] = (ls_value)(uintptr_t)cell;
    }
    return LS_NIL;
}

/* Критическая ошибка: сообщение в stderr и аварийный останов. */
static ls_value api_error(ls_value *args) {
    int code = args[0] ? (int)ls_as_num(args[0]) : 0;
    const char *msg = "неизвестная ошибка";
    if (code == 1) {
        msg = "попытка вызвать значение, не являющееся функцией";
    }
    fprintf(stderr, "LS: %s\n", msg);
    fflush(stderr);
    abort();
    return LS_NIL;
}

/*
 * Копирует адреса функций рантайма в таблицу сгенерированной библиотеки:
 * у .so нет ни одного импорта, поэтому адреса передаются только так.
 */
void ls_register_api(ls_api_fn *table) {
    if (table) {
        memcpy(table, ls_api_table, sizeof(ls_api_table));
    }
}

static void build_api_table(void) {
    ls_api_table[LS_API_PRINT] = lsrt_print;
    ls_api_table[LS_API_TOSTRING] = lsrt_tostring;
    ls_api_table[LS_API_TONUMBER] = lsrt_tonumber;
    ls_api_table[LS_API_TYPE] = lsrt_type;
    ls_api_table[LS_API_PAIRS] = lsrt_pairs;
    ls_api_table[LS_API_IPAIRS] = lsrt_ipairs;
    ls_api_table[LS_API_MATH_FLOOR] = lsrt_math_floor;
    ls_api_table[LS_API_MATH_CEIL] = lsrt_math_ceil;
    ls_api_table[LS_API_MATH_ABS] = lsrt_math_abs;
    ls_api_table[LS_API_MATH_SQRT] = lsrt_math_sqrt;
    ls_api_table[LS_API_MATH_SIN] = lsrt_math_sin;
    ls_api_table[LS_API_MATH_COS] = lsrt_math_cos;
    ls_api_table[LS_API_MATH_TAN] = lsrt_math_tan;
    ls_api_table[LS_API_MATH_ATAN2] = lsrt_math_atan2;
    ls_api_table[LS_API_MATH_MIN] = lsrt_math_min;
    ls_api_table[LS_API_MATH_MAX] = lsrt_math_max;
    ls_api_table[LS_API_MATH_RANDOM] = lsrt_math_random;
    ls_api_table[LS_API_STRING_LEN] = lsrt_string_len;
    ls_api_table[LS_API_STRING_UPPER] = lsrt_string_upper;
    ls_api_table[LS_API_STRING_LOWER] = lsrt_string_lower;
    ls_api_table[LS_API_STRING_SUB] = lsrt_string_sub;
    ls_api_table[LS_API_STRING_REP] = lsrt_string_rep;
    ls_api_table[LS_API_STRING_FORMAT] = lsrt_string_format;
    ls_api_table[LS_API_TABLE_INSERT] = lsrt_table_insert_fn;
    ls_api_table[LS_API_TABLE_REMOVE] = lsrt_table_remove_fn;
    ls_api_table[LS_API_TABLE_CONCAT] = lsrt_table_concat_fn;
    ls_api_table[LS_API_GLOBALS] = api_globals;
    ls_api_table[LS_API_NEW_TABLE] = api_new_table;
    ls_api_table[LS_API_TABLE_GET] = api_table_get;
    ls_api_table[LS_API_TABLE_SET] = api_table_set;
    ls_api_table[LS_API_INDEX_GET] = api_index_get;
    ls_api_table[LS_API_INDEX_SET] = api_index_set;
    ls_api_table[LS_API_ADD] = api_add;
    ls_api_table[LS_API_SUB] = api_sub;
    ls_api_table[LS_API_MUL] = api_mul;
    ls_api_table[LS_API_DIV] = api_div;
    ls_api_table[LS_API_MOD] = api_mod;
    ls_api_table[LS_API_POW] = api_pow;
    ls_api_table[LS_API_CONCAT] = api_concat;
    ls_api_table[LS_API_LEN] = api_len;
    ls_api_table[LS_API_NEG] = api_neg;
    ls_api_table[LS_API_NOT] = api_not;
    ls_api_table[LS_API_EQ] = api_eq;
    ls_api_table[LS_API_LT] = api_lt;
    ls_api_table[LS_API_LE] = api_le;
    ls_api_table[LS_API_TRUTHY] = api_truthy;
    ls_api_table[LS_API_FOR_PREP] = api_for_prep;
    ls_api_table[LS_API_FOR_ITER] = api_for_iter;
    ls_api_table[LS_API_NEW_CELL] = api_new_cell;
    ls_api_table[LS_API_NEW_CLOSURE] = api_new_closure;
    ls_api_table[LS_API_CELL_VALUE] = api_cell_value;
    ls_api_table[LS_API_NE] = api_ne;
    ls_api_table[LS_API_CONST_STRING] = api_const_string;
    ls_api_table[LS_API_CLOSURE_SET_UPVAL] = api_closure_set_upval;
    ls_api_table[LS_API_ERROR] = api_error;
}
