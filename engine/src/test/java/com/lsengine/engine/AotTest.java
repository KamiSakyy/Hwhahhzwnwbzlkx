package com.lsengine.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lsengine.engine.aot.AotModule;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.vm.Builtins;
import com.lsengine.engine.vm.Vm;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Тест AOT-компилятора: байт-код LS превращается в настоящий машинный код.
 *
 * Тест собирает shared library (ELF64) и сохраняет её вместе с ожидаемым
 * выводом виртуальной машины. CI затем запускает natively-хост, который
 * загружает библиотеку через dlopen и сверяет вывод.
 */
public class AotTest {

    /** Программа, покрывающая основные возможности языка. */
    static final String PROGRAM =
            "local function fib(n)\n"
                    + "  if n < 2 then\n"
                    + "    return n\n"
                    + "  end\n"
                    + "  return fib(n - 1) + fib(n - 2)\n"
                    + "end\n"
                    + "print('fib(12) =', fib(12))\n"
                    + "local t = {10, 20, 30}\n"
                    + "t.name = 'demo'\n"
                    + "print(t.name, #t)\n"
                    + "local counter = 0\n"
                    + "local function inc()\n"
                    + "  counter = counter + 1\n"
                    + "  return counter\n"
                    + "end\n"
                    + "inc()\n"
                    + "print('counter =', inc())\n"
                    + "local sum = 0\n"
                    + "for i = 1, 10 do\n"
                    + "  if i % 2 == 0 then\n"
                    + "    sum = sum + i\n"
                    + "  end\n"
                    + "end\n"
                    + "print('sum =', sum)\n"
                    + "local k = 0\n"
                    + "while k < 3 do\n"
                    + "  k = k + 1\n"
                    + "end\n"
                    + "print('while =', k)\n"
                    + "local r = 0\n"
                    + "repeat\n"
                    + "  r = r + 2\n"
                    + "until r >= 6\n"
                    + "print('repeat =', r)\n"
                    + "local obj = {greet = function(self, x) return 'hi ' .. x end}\n"
                    + "print(obj:greet('world'))\n"
                    + "print(string.upper('abc'), string.len('hello'))\n"
                    + "print(math.max(2, 7), math.floor(3.9))\n"
                    + "local acc = 0\n"
                    + "for key, value in pairs({a = 1, b = 2}) do\n"
                    + "  acc = acc + value\n"
                    + "end\n"
                    + "print('pairs =', acc)\n"
                    + "local list = {}\n"
                    + "for i = 1, 3 do\n"
                    + "  list[i] = i * i\n"
                    + "end\n"
                    + "print('list =', list[1], list[2], list[3])\n"
                    + "print('concat =', 'a' .. 'b' .. 'c')\n"
                    + "print('bool =', 1 < 2, 2 == 2, not false)\n";

    @Test
    public void compilesProgramIntoNativeLibrary() throws Exception {
        Compiler compiler = new Compiler();
        Chunk chunk = compiler.compile(PROGRAM);

        List<String> printed = new ArrayList<>();
        Vm vm = new Vm();
        Builtins.install(vm);
        vm.setPrinter(printed::add);
        vm.load(chunk);
        vm.start();
        assertTrue("программа должна что-то печатать", printed.size() > 5);

        byte[] elf = AotModule.compileX64(chunk);
        assertTrue("библиотека не пустая", elf.length > 512);
        assertEquals(0x7F, elf[0] & 0xFF);
        assertEquals('E', elf[1] & 0xFF);
        assertEquals('L', elf[2] & 0xFF);
        assertEquals('F', elf[3] & 0xFF);
        assertEquals(2, elf[4] & 0xFF);   // 64-битный ELF
        assertEquals(1, elf[5] & 0xFF);   // little endian
        assertEquals(3, elf[16] & 0xFF);  // ET_DYN

        File dir = new File(System.getProperty("ls.aot.dir", "build/aot"));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("не удалось создать каталог " + dir);
        }
        write(new File(dir, "liblsapp.so"), elf);
        StringBuilder expected = new StringBuilder();
        for (String line : printed) {
            expected.append(line).append('\n');
        }
        write(new File(dir, "expected.txt"), expected.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data, 0, data.length);
        }
    }
}
