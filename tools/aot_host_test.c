/*
 * aot_host_test.c — проверка AOT-библиотеки на хосте.
 *
 * Загружает сгенерированную .so через dlopen, передаёт адреса функций
 * рантайма (у библиотеки нет ни одного импорта) и запускает точку входа.
 * Вывод должен совпасть с выводом виртуальной машины.
 */
#define _GNU_SOURCE
#include "lsrt.h"

#include <dlfcn.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <ucontext.h>

static void report_fatal_signal(int sig, siginfo_t *info, void *context) {
    ucontext_t *uc = (ucontext_t *)context;
#if defined(__aarch64__)
    fprintf(stderr,
            "AOT host signal=%d address=%p pc=%016llx sp=%016llx "
            "x0=%016llx x1=%016llx x2=%016llx x3=%016llx "
            "x16=%016llx x19=%016llx x29=%016llx\n",
            sig, info->si_addr, (unsigned long long)uc->uc_mcontext.pc,
            (unsigned long long)uc->uc_mcontext.sp,
            (unsigned long long)uc->uc_mcontext.regs[0],
            (unsigned long long)uc->uc_mcontext.regs[1],
            (unsigned long long)uc->uc_mcontext.regs[2],
            (unsigned long long)uc->uc_mcontext.regs[3],
            (unsigned long long)uc->uc_mcontext.regs[16],
            (unsigned long long)uc->uc_mcontext.regs[19],
            (unsigned long long)uc->uc_mcontext.regs[29]);
#elif defined(__x86_64__)
    (void)uc;
    fprintf(stderr, "AOT host signal=%d address=%p\n", sig, info->si_addr);
#else
    fprintf(stderr, "AOT host signal=%d address=%p\n", sig, info->si_addr);
#endif
    fflush(stderr);
    _Exit(128 + sig);
}

static void install_signal_diagnostics(void) {
    struct sigaction action;
    action.sa_sigaction = report_fatal_signal;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &action, NULL);
    sigaction(SIGBUS, &action, NULL);
    sigaction(SIGILL, &action, NULL);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: aot_host <library.so>\n");
        return 2;
    }
    install_signal_diagnostics();
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
