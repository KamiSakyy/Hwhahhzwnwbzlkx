/*
 * aot_host_test.c — проверка AOT-библиотеки на хосте.
 *
 * Загружает сгенерированную .so через dlopen, передаёт адреса функций
 * рантайма (у библиотеки нет ни одного импорта) и запускает точку входа.
 * Вывод должен совпасть с выводом виртуальной машины.
 */
#include "lsrt.h"

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: aot_host <library.so>\n");
        return 2;
    }
    lsrt_init();

    /* Контракт базового рантайма: глобальная таблица должна содержать
       нативные функции. Защищает от неверной инициализации LS_NIL-слотов. */
    ls_value print_fn = lsrt_table_get_str(lsrt_globals(), "print");
    if (!ls_is_box(print_fn) || ls_tag(print_fn) != LS_TAG_NATIVE) {
        fprintf(stderr, "runtime init failed: global 'print' is not native\n");
        return 6;
    }

    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 3;
    }
    ls_api_fn *table = (ls_api_fn *)dlsym(handle, "ls_app_api_table");
    if (!table) {
        fprintf(stderr, "symbol ls_app_api_table not found\n");
        return 4;
    }
    ls_register_api(table);

    ls_value (*entry)(ls_value *) = (ls_value (*)(ls_value *))dlsym(handle, "ls_app_entry");
    if (!entry) {
        fprintf(stderr, "symbol ls_app_entry not found\n");
        return 5;
    }

    /* Раскладка вызова: [0] — замыкание, [1] — число аргументов,
       далее идут сами аргументы. */
    ls_value frame[4];
    frame[0] = 0;
    frame[1] = 0;
    ls_value result = entry(frame + 2);
    (void) result;

    fflush(stdout);
    lsrt_shutdown();
    return 0;
}
