/*
 * lsrt_jni.cpp — мост между Java и нативным рантаймом AOT.
 *
 * Здесь выполняется загрузка сгенерированной библиотеки (ELF) прямо из
 * памяти, передача адресов функций рантайма в её таблицу API и вызов
 * точек входа. Платформа (рисование, ввод) передаётся из Java.
 */
#include <jni.h>

#include <android/dlext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "lsrt.h"

static JavaVM *g_vm = NULL;
static jobject g_platform = NULL;

/* Кэш классов и методов платформы (ускоряет обратные вызовы). */
static jclass g_platformClass = NULL;
static jmethodID g_mClear = NULL;
static jmethodID g_mRect = NULL;
static jmethodID g_mCircle = NULL;
static jmethodID g_mLine = NULL;
static jmethodID g_mText = NULL;
static jmethodID g_mInputX = NULL;
static jmethodID g_mInputY = NULL;
static jmethodID g_mInputDown = NULL;
static jmethodID g_mInputPressed = NULL;
static jmethodID g_mInputReleased = NULL;
static jmethodID g_mTimeNow = NULL;
static jmethodID g_mTimeFrame = NULL;
static jmethodID g_mTimeDt = NULL;
static jmethodID g_mUiButton = NULL;
static jmethodID g_mBeep = NULL;
static jmethodID g_mAppWidth = NULL;
static jmethodID g_mAppHeight = NULL;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv *env_for_thread() {
    JNIEnv *env = NULL;
    if (!g_vm) {
        return NULL;
    }
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            return NULL;
        }
    }
    return env;
}

/* ------------------------------------------------------------------ */
/* Обратные вызовы платформы                                           */
/* ------------------------------------------------------------------ */

static void platform_clear(int color) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mClear) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mClear, color);
}

static void platform_rect(float x, float y, float w, float h, int color) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mRect) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mRect, x, y, w, h, color);
}

static void platform_circle(float x, float y, float r, int color) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mCircle) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mCircle, x, y, r, color);
}

static void platform_line(float x1, float y1, float x2, float y2, int color) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mLine) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mLine, x1, y1, x2, y2, color);
}

static void platform_text(float x, float y, const char *text, float size, int color) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mText) {
        return;
    }
    jstring s = env->NewStringUTF(text ? text : "");
    if (!s) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mText, x, y, s, size, color);
    env->DeleteLocalRef(s);
}

static float platform_input_x(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mInputX) {
        return 0.0f;
    }
    return env->CallFloatMethod(g_platform, g_mInputX);
}

static float platform_input_y(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mInputY) {
        return 0.0f;
    }
    return env->CallFloatMethod(g_platform, g_mInputY);
}

static int platform_input_down(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mInputDown) {
        return 0;
    }
    return env->CallBooleanMethod(g_platform, g_mInputDown) ? 1 : 0;
}

static int platform_input_pressed(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mInputPressed) {
        return 0;
    }
    return env->CallBooleanMethod(g_platform, g_mInputPressed) ? 1 : 0;
}

static int platform_input_released(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mInputReleased) {
        return 0;
    }
    return env->CallBooleanMethod(g_platform, g_mInputReleased) ? 1 : 0;
}

static double platform_time_now(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mTimeNow) {
        return 0.0;
    }
    return env->CallDoubleMethod(g_platform, g_mTimeNow);
}

static long platform_time_frame(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mTimeFrame) {
        return 0;
    }
    return (long) env->CallLongMethod(g_platform, g_mTimeFrame);
}

static double platform_time_dt(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mTimeDt) {
        return 0.0;
    }
    return env->CallDoubleMethod(g_platform, g_mTimeDt);
}

static int platform_ui_button(float x, float y, float w, float h, const char *label,
                              ls_value callback) {
    (void) callback;
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mUiButton) {
        return 0;
    }
    jstring s = env->NewStringUTF(label ? label : "");
    if (!s) {
        return 0;
    }
    jboolean pressed = env->CallBooleanMethod(g_platform, g_mUiButton, x, y, w, h, s);
    env->DeleteLocalRef(s);
    return pressed ? 1 : 0;
}

static void platform_beep(int frequency, int millis) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mBeep) {
        return;
    }
    env->CallVoidMethod(g_platform, g_mBeep, frequency, millis);
}

static int platform_app_width(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mAppWidth) {
        return 0;
    }
    return (int) env->CallIntMethod(g_platform, g_mAppWidth);
}

static int platform_app_height(void) {
    JNIEnv *env = env_for_thread();
    if (!env || !g_platform || !g_mAppHeight) {
        return 0;
    }
    return (int) env->CallIntMethod(g_platform, g_mAppHeight);
}

/* ------------------------------------------------------------------ */
/* Загрузка модуля                                                     */
/* ------------------------------------------------------------------ */

struct ls_module {
    void *handle;
    ls_value (*entry)(ls_value *);
};

static int write_all(int fd, const unsigned char *data, size_t size) {
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, data + done, size - done);
        if (n <= 0) {
            return -1;
        }
        done += (size_t) n;
    }
    return 0;
}

typedef void *(*ls_get_namespace_fn)(const char *);
typedef void *(*ls_dlopen_ext_fn)(const char *, int, void *);

static char g_last_load_error[1024] = "";

static jlong finish_module_load(void *handle) {
    if (!handle) {
        const char *error = dlerror();
        snprintf(g_last_load_error, sizeof(g_last_load_error), "%s",
                 error ? error : "dlopen завершился без указания причины");
        return 0;
    }
    lsrt_init();
    ls_api_fn *table = (ls_api_fn *) dlsym(handle, "ls_app_api_table");
    if (table) ls_register_api(table);
    ls_value (*entry)(ls_value *) = (ls_value (*)(ls_value *)) dlsym(handle, "ls_app_entry");
    if (!entry) {
        const char *error = dlerror();
        snprintf(g_last_load_error, sizeof(g_last_load_error), "нет ls_app_entry: %s",
                 error ? error : "экспортированный символ не найден");
        dlclose(handle);
        return 0;
    }
    ls_module *module = (ls_module *) calloc(1, sizeof(ls_module));
    if (!module) {
        snprintf(g_last_load_error, sizeof(g_last_load_error), "недостаточно памяти для AOT-модуля");
        dlclose(handle);
        return 0;
    }
    module->handle = handle;
    module->entry = entry;
    g_last_load_error[0] = '\0';
    return (jlong) (intptr_t) module;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeLoadFile(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (path == NULL) {
        snprintf(g_last_load_error, sizeof(g_last_load_error), "путь к ELF не задан");
        return 0;
    }
    const char *utf = env->GetStringUTFChars(path, NULL);
    if (!utf) {
        snprintf(g_last_load_error, sizeof(g_last_load_error), "не удалось прочитать путь к ELF");
        return 0;
    }
    dlerror();
    void *handle = dlopen(utf, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(path, utf);
    return finish_module_load(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeLastError(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF(g_last_load_error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeRun(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ls_module *module = (ls_module *) (intptr_t) handle;
    if (!module || !module->entry) {
        return;
    }
    ls_value frame[4];
    frame[0] = 0;    /* замыкание */
    frame[1] = 0;    /* количество аргументов */
    module->entry(frame + 2);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeCall(JNIEnv *env, jclass clazz, jlong handle,
                                                    jstring name, jdoubleArray args) {
    (void) clazz;
    (void) handle;
    if (name == NULL) {
        return JNI_FALSE;
    }
    const char *utf = env->GetStringUTFChars(name, NULL);
    if (!utf) {
        return JNI_FALSE;
    }
    jsize count = args == NULL ? 0 : env->GetArrayLength(args);
    jdouble *values = NULL;
    if (count > 0) {
        values = env->GetDoubleArrayElements(args, NULL);
    }
    ls_value *frame = (ls_value *) calloc((size_t) count + 2, sizeof(ls_value));
    if (frame != NULL) {
        frame[0] = 0;
        frame[1] = (ls_value) count;
        for (jsize i = 0; i < count; i++) {
            frame[2 + i] = ls_num(values ? values[i] : 0.0);
        }
        lsrt_call_global(utf, frame + 2, (int) count);
        free(frame);
    }
    if (values != NULL) {
        env->ReleaseDoubleArrayElements(args, values, JNI_ABORT);
    }
    env->ReleaseStringUTFChars(name, utf);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ls_module *module = (ls_module *) (intptr_t) handle;
    if (!module) {
        return;
    }
    if (module->handle) {
        dlclose(module->handle);
    }
    free(module);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lsengine_engine_aot_AotRuntime_nativeSetPlatform(JNIEnv *env, jclass clazz,
                                                           jobject platform) {
    (void) clazz;
    if (g_platform) {
        env->DeleteGlobalRef(g_platform);
        g_platform = NULL;
    }
    g_mClear = g_mRect = g_mCircle = g_mLine = g_mText = NULL;
    g_mInputX = g_mInputY = g_mInputDown = g_mInputPressed = g_mInputReleased = NULL;
    g_mTimeNow = g_mTimeFrame = g_mTimeDt = NULL;
    g_mUiButton = g_mBeep = g_mAppWidth = g_mAppHeight = NULL;

    if (platform == NULL) {
        ls_platform empty;
        memset(&empty, 0, sizeof(empty));
        lsrt_set_platform(&empty);
        return;
    }
    g_platform = env->NewGlobalRef(platform);
    g_platformClass = (jclass) env->GetObjectClass(platform);
    if (g_platformClass) {
        g_platformClass = (jclass) env->NewGlobalRef(g_platformClass);
    }
    g_mClear = env->GetMethodID(g_platformClass, "clear", "(I)V");
    g_mRect = env->GetMethodID(g_platformClass, "rect", "(FFFFI)V");
    g_mCircle = env->GetMethodID(g_platformClass, "circle", "(FFFI)V");
    g_mLine = env->GetMethodID(g_platformClass, "line", "(FFFFI)V");
    g_mText = env->GetMethodID(g_platformClass, "text", "(FFSFI)V");
    g_mInputX = env->GetMethodID(g_platformClass, "inputX", "()F");
    g_mInputY = env->GetMethodID(g_platformClass, "inputY", "()F");
    g_mInputDown = env->GetMethodID(g_platformClass, "inputDown", "()Z");
    g_mInputPressed = env->GetMethodID(g_platformClass, "inputPressed", "()Z");
    g_mInputReleased = env->GetMethodID(g_platformClass, "inputReleased", "()Z");
    g_mTimeNow = env->GetMethodID(g_platformClass, "timeNow", "()D");
    g_mTimeFrame = env->GetMethodID(g_platformClass, "timeFrame", "()J");
    g_mTimeDt = env->GetMethodID(g_platformClass, "timeDt", "()D");
    g_mUiButton = env->GetMethodID(g_platformClass, "uiButton", "(FFFFS)Z");
    g_mBeep = env->GetMethodID(g_platformClass, "beep", "(II)V");
    g_mAppWidth = env->GetMethodID(g_platformClass, "appWidth", "()I");
    g_mAppHeight = env->GetMethodID(g_platformClass, "appHeight", "()I");

    ls_platform p;
    memset(&p, 0, sizeof(p));
    p.clear = platform_clear;
    p.rect = platform_rect;
    p.circle = platform_circle;
    p.line = platform_line;
    p.text = platform_text;
    p.input_x = platform_input_x;
    p.input_y = platform_input_y;
    p.input_down = platform_input_down;
    p.input_pressed = platform_input_pressed;
    p.input_released = platform_input_released;
    p.time_now = platform_time_now;
    p.time_frame = platform_time_frame;
    p.time_dt = platform_time_dt;
    p.ui_button = platform_ui_button;
    p.beep = platform_beep;
    p.app_width = platform_app_width;
    p.app_height = platform_app_height;
    lsrt_set_platform(&p);
}
