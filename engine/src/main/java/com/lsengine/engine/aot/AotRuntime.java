package com.lsengine.engine.aot;

/**
 * Загрузка и запуск скомпилированной в машинный код библиотеки.
 *
 * Библиотека не содержит ни одного импорта: адреса функций рантайма
 * передаются ей через таблицу API сразу после загрузки.
 */
public final class AotRuntime {

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("lsrt");
            ok = true;
        } catch (Throwable error) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private long handle;

    private AotRuntime(long handle) {
        this.handle = handle;
    }

    /** Доступен ли нативный рантайм в этом процессе. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Загружает ELF-библиотеку с машинным кодом.
     *
     * @return экземпляр рантайма либо null, если загрузка не удалась
     */
    public static AotRuntime load(byte[] library) {
        if (!AVAILABLE || library == null || library.length == 0) {
            return null;
        }
        long handle = nativeLoad(library);
        return handle == 0 ? null : new AotRuntime(handle);
    }

    /**
     * Загружает библиотеку, уже установленную системой (lib/&lt;abi&gt;/liblsapp.so).
     *
     * @return экземпляр рантайма либо null, если загрузка не удалась
     */
    public static AotRuntime loadLibrary(String libraryName) {
        if (!AVAILABLE || libraryName == null || libraryName.isEmpty()) {
            return null;
        }
        long handle = nativeLoadLibrary(libraryName);
        return handle == 0 ? null : new AotRuntime(handle);
    }

    /** Выполняет точку входа программы (верхний уровень скрипта). */
    public void run() {
        if (handle != 0) {
            nativeRun(handle);
        }
    }

    /** Вызывает глобальную функцию программы: update, draw, onTouch. */
    public void call(String name) {
        call(name, new double[0]);
    }

    /** Вызывает глобальную функцию программы с числовыми аргументами. */
    public void call(String name, double... args) {
        if (handle != 0 && name != null) {
            nativeCall(handle, name, args == null ? new double[0] : args);
        }
    }

    /** Передаёт платформу (рисование, ввод) нативному рантайму. */
    public static void setPlatform(LsPlatform platform) {
        if (AVAILABLE) {
            nativeSetPlatform(platform);
        }
    }

    /** Освобождает загруженную библиотеку. */
    public void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeLoad(byte[] library);

    private static native long nativeLoadLibrary(String libraryName);

    private static native void nativeRun(long handle);

    private static native boolean nativeCall(long handle, String name, double[] args);

    private static native void nativeFree(long handle);

    private static native void nativeSetPlatform(LsPlatform platform);
}
