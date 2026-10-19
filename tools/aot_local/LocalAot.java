/*
 * LocalAot.java — локальная проверка AOT-компилятора без Android и CI.
 *
 * Компилирует тестовую программу в машинный код (ELF64) и сохраняет
 * результат в файл, который затем запускается нативным хостом.
 *
 * Использование: java -cp <classes> LocalAot <программа.ls> <out.so> [arm64]
 */
package com.lsengine.engine.aot;

import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.vm.Builtins;
import com.lsengine.engine.vm.Vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class LocalAot {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LocalAot <program.ls> <out.so> [arm64]");
            System.exit(2);
        }
        String source = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        boolean arm64 = args.length > 2 && "arm64".equals(args[2]);

        Compiler compiler = new Compiler();
        Chunk chunk = compiler.compile(source);

        // Ожидаемый вывод виртуальной машины.
        List<String> printed = new ArrayList<>();
        Vm vm = new Vm();
        Builtins.install(vm);
        vm.setPrinter(printed::add);
        vm.load(chunk);
        vm.start();
        StringBuilder expected = new StringBuilder();
        for (String line : printed) {
            expected.append(line).append('\n');
        }

        byte[] elf = arm64 ? AotModule.compileArm64(chunk) : AotModule.compileX64(chunk);
        Files.write(Paths.get(args[1]), elf);
        Files.write(Paths.get(args[1] + ".expected"), expected.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("ELF: " + elf.length + " байт, строк в VM: " + printed.size());
    }
}
