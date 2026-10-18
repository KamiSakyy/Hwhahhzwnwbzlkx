package com.lsengine.engine.aot;

import com.lsengine.engine.lang.Chunk;

/**
 * Публичный интерфейс AOT-компилятора: байт-код LS превращается в
 * готовую к загрузке shared library с машинным кодом.
 */
public final class AotModule {

    private AotModule() {
    }

    /** Компилирует программу под нужную архитектуру. */
    public static byte[] compile(Chunk chunk, boolean arm64) {
        AotBackend backend = arm64 ? new AotArm64() : new AotX64();
        AotElf.Module module = new AotCompiler(chunk, backend).compile();
        return AotElf.build(module);
    }

    /** Компилирует программу под x86-64. */
    public static byte[] compileX64(Chunk chunk) {
        return compile(chunk, false);
    }

    /** Компилирует программу под AArch64. */
    public static byte[] compileArm64(Chunk chunk) {
        return compile(chunk, true);
    }
}
