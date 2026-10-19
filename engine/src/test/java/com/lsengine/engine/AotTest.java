package com.lsengine.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

        // AArch64 Bionic is stricter than desktop glibc about ELF section
        // alignment, especially .dynsym and .dynamic.
        assertSectionAlignment(elf);
        assertProgramHeadersMapped(elf);
        assertDynamicSymbolEntrySize(elf);

        // Библиотека не должна содержать символьной таблицы и отладочных секций:
        // декомпилировать такой машинный код заметно сложнее.
        List<String> sections = sectionNames(elf);
        assertFalse("символьная таблица не должна попадать в библиотеку: " + sections,
                sections.contains(".symtab"));
        assertFalse("отладочных секций быть не должно: " + sections,
                sections.contains(".debug_info"));
        assertTrue("должна быть секция кода: " + sections + " | header=" + hex(elf, 64),
                sections.contains(".text"));
        assertTrue("должна быть динамическая символьная таблица: " + sections,
                sections.contains(".dynsym"));

        // ARM64: та же программа, но машинный код для AArch64.
        byte[] arm = AotModule.compileArm64(chunk);
        assertTrue("библиотека ARM64 не пустая", arm.length > 512);
        assertEquals(0x7F, arm[0] & 0xFF);
        assertEquals('E', arm[1] & 0xFF);
        assertEquals(3, arm[16] & 0xFF);  // ET_DYN
        assertEquals(183, arm[18] & 0xFF); // EM_AARCH64
        assertEquals(183, (arm[18] & 0xFF) | ((arm[19] & 0xFF) << 8));
        write(new File(dir, "liblsapp-arm64.so"), arm);
        assertSectionAlignment(arm);
        assertProgramHeadersMapped(arm);
        assertDynamicSymbolEntrySize(arm);
        assertFalse("в ARM64-библиотеке тоже нет символьной таблицы",
                sectionNames(arm).contains(".symtab"));
        StringBuilder expected = new StringBuilder();
        for (String line : printed) {
            expected.append(line).append('\n');
        }
        write(new File(dir, "expected.txt"), expected.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** DT_SYMENT must describe the 24-byte ELF64 symbol records. */
    private static void assertDynamicSymbolEntrySize(byte[] elf) {
        long phoff = readLong(elf, 32);
        int phentsize = (elf[54] & 0xFF) | ((elf[55] & 0xFF) << 8);
        int phnum = (elf[56] & 0xFF) | ((elf[57] & 0xFF) << 8);
        boolean found = false;
        for (int i = 0; i < phnum; i++) {
            int base = (int) phoff + i * phentsize;
            if (readInt(elf, base) != 2) { // PT_DYNAMIC
                continue;
            }
            long offset = readLong(elf, base + 8);
            long size = readLong(elf, base + 32);
            for (long pos = offset; pos + 16 <= offset + size; pos += 16) {
                long tag = readLong(elf, (int) pos);
                long value = readLong(elf, (int) pos + 8);
                if (tag == 11) { // DT_SYMENT
                    assertEquals("DT_SYMENT must be sizeof(Elf64_Sym)", 24L, value);
                    found = true;
                }
                if (tag == 0) { // DT_NULL
                    break;
                }
            }
        }
        assertTrue("ELF must declare DT_SYMENT", found);
    }

    /** Every ELF section's file offset and virtual address must honor sh_addralign. */
    private static void assertSectionAlignment(byte[] elf) {
        int shoff = (int) readLong(elf, 40);
        int shentsize = (elf[58] & 0xFF) | ((elf[59] & 0xFF) << 8);
        int shnum = (elf[60] & 0xFF) | ((elf[61] & 0xFF) << 8);
        for (int i = 1; i < shnum; i++) {
            int base = shoff + i * shentsize;
            long address = readLong(elf, base + 16);
            long offset = readLong(elf, base + 24);
            long alignment = readLong(elf, base + 48);
            assertTrue("section " + i + " has invalid sh_addralign " + alignment,
                    alignment == 0 || (address % alignment == 0 && offset % alignment == 0));
        }
    }

    /** Android's linker needs the ELF and program headers inside a PT_LOAD. */
    private static void assertProgramHeadersMapped(byte[] elf) {
        long phoff = readLong(elf, 32);
        int phentsize = (elf[54] & 0xFF) | ((elf[55] & 0xFF) << 8);
        int phnum = (elf[56] & 0xFF) | ((elf[57] & 0xFF) << 8);
        long tableEnd = phoff + (long) phentsize * phnum;
        boolean mapped = false;
        for (int i = 0; i < phnum; i++) {
            int base = (int) phoff + i * phentsize;
            if (readInt(elf, base) != 1) { // PT_LOAD
                continue;
            }
            long offset = readLong(elf, base + 8);
            long fileSize = readLong(elf, base + 32);
            if (phoff >= offset && tableEnd <= offset + fileSize) {
                mapped = true;
                break;
            }
        }
        assertTrue("ELF/program headers must be mapped by a PT_LOAD segment", mapped);
    }

    /** Имена секций ELF-библиотеки. */
    private static List<String> sectionNames(byte[] elf) {
        List<String> names = new ArrayList<>();
        int shoff = (int) readLong(elf, 40);
        int shentsize = (elf[58] & 0xFF) | ((elf[59] & 0xFF) << 8);
        int shnum = (elf[60] & 0xFF) | ((elf[61] & 0xFF) << 8);
        int shstrndx = (elf[62] & 0xFF) | ((elf[63] & 0xFF) << 8);
        if (shoff == 0 || shnum == 0 || shstrndx >= shnum) {
            return names;
        }
        int strOff = (int) readLong(elf, shoff + shstrndx * shentsize + 24);
        int strSize = (int) readLong(elf, shoff + shstrndx * shentsize + 32);
        for (int i = 0; i < shnum; i++) {
            int base = shoff + i * shentsize;
            int nameOff = readInt(elf, base);
            if (nameOff < 0 || nameOff >= strSize) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int p = strOff + nameOff;
            while (p < elf.length && elf[p] != 0) {
                sb.append((char) (elf[p] & 0xFF));
                p++;
            }
            names.add(sb.toString());
        }
        return names;
    }

    private static String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(length, data.length); i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
            if (i % 8 == 7) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (data[offset + i] & 0xFF)) << (8 * i);
        }
        return value;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data, 0, data.length);
        }
    }
}
