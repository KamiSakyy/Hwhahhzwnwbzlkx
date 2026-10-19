package com.lsengine.engine.aot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Загружает сгенерированный код AArch64 через штатный загрузчик Android. */
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

    private static volatile String javaLoadError;
    private long handle;

    private AotRuntime(long handle) {
        this.handle = handle;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Writes a temporary read-only ELF in the app-private code cache and loads it using bionic. */
    public static AotRuntime load(byte[] library, File codeCacheDir) {
        if (!AVAILABLE || library == null || library.length == 0 || codeCacheDir == null) {
            return null;
        }
        File elf = null;
        javaLoadError = null;
        try {
            if (!codeCacheDir.isDirectory() && !codeCacheDir.mkdirs()) {
                return null;
            }
            elf = File.createTempFile("ls-aot-", ".so", codeCacheDir);
            try (FileOutputStream output = new FileOutputStream(elf)) {
                // Android 14+ requires dynamically loaded code to be read-only.
                if (!elf.setReadOnly()) {
                    throw new IOException("Android не разрешил пометить временный ELF как read-only");
                }
                output.write(library);
                output.getFD().sync();
            }
            long loaded = nativeLoadFile(elf.getAbsolutePath());
            return loaded == 0 ? null : new AotRuntime(loaded);
        } catch (Throwable error) {
            javaLoadError = error.getMessage() == null ? error.toString() : error.getMessage();
            return null;
        } finally {
            if (elf != null && elf.exists()) {
                // dlopen keeps the mapped image alive after unlinking its read-only backing file.
                // If a platform prevents unlinking a mapped file, it is cleaned on next process start.
                //noinspection ResultOfMethodCallIgnored
                elf.delete();
            }
        }
    }

    public static String lastError() {
        if (javaLoadError != null && !javaLoadError.isEmpty()) return javaLoadError;
        if (!AVAILABLE) return "нативный рантайм недоступен";
        String error = nativeLastError();
        return error == null || error.isEmpty() ? "загрузчик Android не сообщил причину" : error;
    }

    public void run() {
        if (handle != 0) nativeRun(handle);
    }

    public void call(String name) {
        call(name, new double[0]);
    }

    public void call(String name, double... args) {
        if (handle != 0 && name != null) nativeCall(handle, name, args == null ? new double[0] : args);
    }

    public static void setPlatform(LsPlatform platform) {
        if (AVAILABLE) nativeSetPlatform(platform);
    }

    public void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeLoadFile(String path);
    private static native String nativeLastError();
    private static native void nativeRun(long handle);
    private static native boolean nativeCall(long handle, String name, double[] args);
    private static native void nativeFree(long handle);
    private static native void nativeSetPlatform(LsPlatform platform);
}
