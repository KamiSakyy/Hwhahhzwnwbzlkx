package com.lsengine.engine.aot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Сборка ELF64 shared library из сгенерированного машинного кода.
 *
 * Библиотека не импортирует ни одного символа: адреса функций рантайма
 * передаются через таблицу API при загрузке. Поэтому .so самодостаточна:
 * её можно положить в lib/ APK и загрузить штатным способом.
 */
public final class AotElf {

    /** Исправление адреса в машинном коде. */
    public static final class Fixup {
        public final int offset;
        public final int kind;
        public final int index;

        public Fixup(int offset, int kind, int index) {
            this.offset = offset;
            this.kind = kind;
            this.index = index;
        }
    }

    /** Экспортируемый символ. */
    public static final class Export {
        public String name = "";
        /** Смещение внутри .text или .data. */
        public long offset;
        /** true — символ в .data, false — в .text. */
        public boolean inData;
    }

    /** Описание собираемой библиотеки. */
    public static final class Module {
        public byte[] code = new byte[0];
        public byte[] rodata = new byte[0];
        public byte[] data = new byte[0];
        /** Смещение кода каждой функции внутри .text. */
        public int[] functionOffsets = new int[0];
        /** Смещение таблицы API внутри .data. */
        public int apiTableOffset;
        /** Смещение пула констант внутри .data. */
        public int poolOffset;
        /** Смещения строковых данных внутри .rodata. */
        public int[] rodataOffsets = new int[0];
        public final List<Fixup> fixups = new ArrayList<>();
        /** Переходы: {смещение disp, метка}. */
        public final List<int[]> jumps = new ArrayList<>();
        /** Смещения меток в .text. */
        public int[] labelOffsets = new int[0];
        public final List<Export> exported = new ArrayList<>();
        public String soname = "liblsapp.so";
    }

    private static final int PAGE = 0x1000;

    private AotElf() {
    }

    private static void u8(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void u32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }

    private static void u64(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >> (i * 8)) & 0xFF));
        }
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** SysV hash строки. */
    private static int elfHash(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) {
            h = (h << 4) + name.charAt(i);
            int g = h & 0xf0000000;
            if (g != 0) {
                h ^= g >>> 24;
            }
            h &= ~g;
        }
        return h & 0x7FFFFFFF;
    }

    public static byte[] build(Module module) {
        int textSize = module.code.length;
        int rodataSize = module.rodata.length;
        int dataSize = module.data.length;

        int textOffset = PAGE;
        int rodataOffset = align(textOffset + textSize, 16);
        int dataOffset = align(rodataOffset + rodataSize, 16);

        // Динамические секции размещаются в записываемом сегменте.
        List<String> symbolNames = new ArrayList<>();
        List<int[]> symbolSections = new ArrayList<>();
        List<Long> symbolValues = new ArrayList<>();
        symbolNames.add("");
        symbolSections.add(new int[]{0});
        symbolValues.add(0L);
        for (Export export : module.exported) {
            symbolNames.add(export.name);
            symbolSections.add(new int[]{export.inData ? 3 : 1});
            long value = export.inData ? dataOffset + export.offset : textOffset + export.offset;
            symbolValues.add(value);
        }
        // SONAME — первая строка в таблице строк.
        StringBuilder dynstr = new StringBuilder();
        dynstr.append('\0');
        int sonameOffset = dynstr.length();
        dynstr.append(module.soname).append('\0');
        int[] nameOffsets = new int[symbolNames.size()];
        for (int i = 1; i < symbolNames.size(); i++) {
            nameOffsets[i] = dynstr.length();
            dynstr.append(symbolNames.get(i)).append('\0');
        }
        byte[] dynstrBytes = dynstr.toString().getBytes(StandardCharsets.US_ASCII);
        int dynstrOffsetInSeg = align(dataSize, 8);
        int dynsymOffsetInSeg = dynstrOffsetInSeg + dynstrBytes.length;
        int dynsymSize = 24 * symbolNames.size();
        int hashOffsetInSeg = align(dynsymOffsetInSeg + dynsymSize, 8);

        int nbucket = 8;
        int nchain = symbolNames.size();
        int hashSize = 8 + nbucket * 4 + nchain * 4;
        int dynamicOffsetInSeg = hashOffsetInSeg + hashSize;
        int dynamicSize = 8 * 16;

        int dataSegmentSize = align(dynamicOffsetInSeg + dynamicSize, 16);

        long textAddr = textOffset;
        long rodataAddr = rodataOffset;
        long dataAddr = dataOffset;
        long dynstrAddr = dataAddr + dynstrOffsetInSeg;
        long dynsymAddr = dataAddr + dynsymOffsetInSeg;
        long hashAddr = dataAddr + hashOffsetInSeg;
        long dynamicAddr = dataAddr + dynamicOffsetInSeg;
        long apiTableAddr = dataAddr + module.apiTableOffset;

        // --- патчи машинного кода ---
        byte[] code = module.code.clone();
        for (Fixup fixup : module.fixups) {
            long target;
            switch (fixup.kind) {
                case AotIsa.FIX_POOL:
                    target = dataAddr + module.poolOffset + 8L * fixup.index;
                    break;
                case AotIsa.FIX_RODATA:
                    target = rodataAddr + module.rodataOffsets[fixup.index];
                    break;
                case AotIsa.FIX_API:
                    target = apiTableAddr + 8L * fixup.index;
                    break;
                case AotIsa.FIX_FUNCTION:
                    target = textAddr + module.functionOffsets[fixup.index];
                    break;
                default:
                    target = 0;
                    break;
            }
            long disp = target - (textAddr + fixup.offset + 4);
            putInt(code, fixup.offset, (int) disp);
        }
        for (int[] jump : module.jumps) {
            long target = textAddr + module.labelOffsets[jump[1]];
            long disp = target - (textAddr + jump[0] + 4);
            putInt(code, jump[0], (int) disp);
        }

        // --- итоговый файл ---
        int headerSize = 64;
        int phoff = headerSize;
        int phnum = 4;
        int phentsize = 56;
        int shoff = align(dataOffset + dataSegmentSize, 8);
        int shnum = 9;
        int shentsize = 64;

        ByteArrayOutputStream out = new ByteArrayOutputStream(code.length + dataSegmentSize + 4096);
        byte[] ident = new byte[16];
        ident[0] = 0x7F;
        ident[1] = 'E';
        ident[2] = 'L';
        ident[3] = 'F';
        ident[4] = 2;   // 64 бита
        ident[5] = 1;   // little endian
        ident[6] = 1;   // версия
        ident[7] = 0;   // System V
        out.write(ident, 0, 16);
        u16(out, 3);          // ET_DYN
        u16(out, 0x3E);       // EM_X86_64
        u32(out, 1);          // версия
        u64(out, 0);          // точка входа
        u64(out, shoff);
        u32(out, 0);
        u16(out, headerSize);
        u16(out, phentsize);
        u16(out, phnum);
        u16(out, shentsize);
        u16(out, shnum);
        u16(out, 8);          // индекс .shstrtab

        // Заголовки программ
        u32(out, 1);
        u32(out, 5);          // PF_R | PF_X
        u64(out, textOffset);
        u64(out, textAddr);
        u64(out, textSize);
        u64(out, textSize);
        u64(out, PAGE);
        u32(out, 1);
        u32(out, 4);          // PF_R
        u64(out, rodataOffset);
        u64(out, rodataAddr);
        u64(out, rodataSize);
        u64(out, rodataSize);
        u64(out, PAGE);
        u32(out, 1);
        u32(out, 6);          // PF_R | PF_W
        u64(out, dataOffset);
        u64(out, dataAddr);
        u64(out, dataSegmentSize);
        u64(out, dataSegmentSize);
        u64(out, PAGE);
        u32(out, 2);          // PT_DYNAMIC
        u32(out, 6);
        u64(out, dynamicOffsetInSeg + dataOffset);
        u64(out, dynamicAddr);
        u64(out, dynamicSize);
        u64(out, dynamicSize);
        u64(out, 8);

        while (out.size() < textOffset) {
            out.write(0);
        }
        out.write(code, 0, code.length);
        while (out.size() < rodataOffset) {
            out.write(0);
        }
        if (rodataSize > 0) {
            out.write(module.rodata, 0, rodataSize);
        }
        while (out.size() < dataOffset) {
            out.write(0);
        }
        if (dataSize > 0) {
            out.write(module.data, 0, dataSize);
        }
        while (out.size() < dataOffset + dynstrOffsetInSeg) {
            out.write(0);
        }
        out.write(dynstrBytes, 0, dynstrBytes.length);
        while (out.size() < dataOffset + dynsymOffsetInSeg) {
            out.write(0);
        }
        for (int i = 0; i < symbolNames.size(); i++) {
            u32(out, nameOffsets[i]);
            u8(out, i == 0 ? 0 : 0x12);   // GLOBAL | DEFINED
            u8(out, 0);
            u16(out, symbolSections.get(i)[0]);
            u64(out, symbolValues.get(i));
            u64(out, 0);
        }
        while (out.size() < dataOffset + hashOffsetInSeg) {
            out.write(0);
        }
        int[] buckets = new int[nbucket];
        int[] chains = new int[nchain];
        for (int i = 1; i < symbolNames.size(); i++) {
            int h = elfHash(symbolNames.get(i));
            int b = h % nbucket;
            if (buckets[b] == 0) {
                buckets[b] = i;
            } else {
                int idx = buckets[b];
                while (chains[idx] != 0) {
                    idx = chains[idx];
                }
                chains[idx] = i;
            }
        }
        u32(out, nbucket);
        u32(out, nchain);
        for (int b : buckets) {
            u32(out, b);
        }
        for (int c : chains) {
            u32(out, c);
        }
        while (out.size() < dataOffset + dynamicOffsetInSeg) {
            out.write(0);
        }
        u64(out, 4);            // DT_HASH
        u64(out, hashAddr);
        u64(out, 5);            // DT_STRTAB
        u64(out, dynstrAddr);
        u64(out, 6);            // DT_SYMTAB
        u64(out, dynsymAddr);
        u64(out, 10);           // DT_STRSZ
        u64(out, dynstrBytes.length);
        u64(out, 11);           // DT_SYMENT
        u64(out, 24);
        u64(out, 14);           // DT_SONAME
        u64(out, dynstrAddr + sonameOffset);
        u64(out, 0);            // DT_NULL
        u64(out, 0);

        // Таблица имён секций
        while (out.size() < shoff) {
            out.write(0);
        }
        String[] sectionNames = {
                "", ".text", ".rodata", ".data", ".dynsym", ".dynstr", ".hash", ".dynamic",
                ".shstrtab"};
        ByteArrayOutputStream shstr = new ByteArrayOutputStream();
        shstr.write(0);
        int[] nameIndex = new int[sectionNames.length];
        for (int i = 1; i < sectionNames.length; i++) {
            nameIndex[i] = shstr.size();
            shstr.write(sectionNames[i].getBytes(StandardCharsets.US_ASCII), 0,
                    sectionNames[i].length());
            shstr.write(0);
        }
        byte[] shstrBytes = shstr.toByteArray();
        long shstrAddr = shoff + (long) shnum * 64;

        long[] shAddr = {
                0, textAddr, rodataAddr, dataAddr, dynsymAddr, dynstrAddr, hashAddr, dynamicAddr,
                shstrAddr};
        long[] shSize = {
                0, textSize, rodataSize, dataSize, dynsymSize, dynstrBytes.length, hashSize,
                dynamicSize, shstrBytes.length};
        int[] shType = {0, 1, 1, 1, 11, 3, 5, 6, 3};
        int[] shFlags = {0, 6, 2, 3, 2, 2, 2, 2, 2};
        long[] shOffset = {
                0, textOffset, rodataOffset, dataOffset, dataOffset + dynsymOffsetInSeg,
                dataOffset + dynstrOffsetInSeg, dataOffset + hashOffsetInSeg,
                dataOffset + dynamicOffsetInSeg, shoff + (long) shnum * 64};
        for (int i = 0; i < sectionNames.length; i++) {
            u32(out, nameIndex[i]);
            u32(out, shType[i]);
            u64(out, shFlags[i]);
            u64(out, shAddr[i]);
            u64(out, shOffset[i]);
            u64(out, shSize[i]);
            u32(out, 0);
            u32(out, 0);
            u64(out, 1);
            u64(out, 0);
            u32(out, 0);
            u32(out, 0);
            u32(out, 16);   // sh_addralign
            u64(out, 0);
        }
        out.write(shstrBytes, 0, shstrBytes.length);
        return out.toByteArray();
    }

    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
