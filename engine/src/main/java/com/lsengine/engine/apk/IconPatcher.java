package com.lsengine.engine.apk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Replaces the adaptive launcher foreground resource with a user-selected PNG. */
public final class IconPatcher {
    private static final int TABLE_PACKAGE = 0x0200;
    private static final int TABLE_TYPE = 0x0201;
    private static final int STRING_POOL = 0x0001;
    private static final int VALUE_STRING = 0x03;
    private static final String FOREGROUND = "ic_launcher_foreground";

    public static final class Result {
        public final byte[] resourcesTable;
        public final String oldEntry;
        public final String newEntry;
        public final byte[] png;

        Result(byte[] resourcesTable, String oldEntry, String newEntry, byte[] png) {
            this.resourcesTable = resourcesTable;
            this.oldEntry = oldEntry;
            this.newEntry = newEntry;
            this.png = png;
        }
    }

    private static final class Pool {
        final int start;
        final int headerSize;
        final int count;
        final int flags;
        final int stringsStart;
        final int[] offsets;
        final List<String> strings;

        Pool(byte[] data, int start) throws IOException {
            checkRange(data, start, 28);
            if (u16(data, start) != STRING_POOL) throw new IOException("Некорректный string pool resources.arsc");
            this.start = start;
            this.headerSize = u16(data, start + 2);
            this.count = u32(data, start + 8);
            this.flags = u32(data, start + 16);
            this.stringsStart = u32(data, start + 20);
            if (count < 0 || count > 100000 || headerSize < 28) throw new IOException("Некорректный string pool");
            checkRange(data, start + headerSize, count * 4);
            offsets = new int[count];
            strings = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                offsets[i] = u32(data, start + headerSize + i * 4);
                strings.add(readString(data, start + stringsStart + offsets[i], (flags & 0x100) != 0));
            }
        }

        int indexOf(String value) { return strings.indexOf(value); }
    }

    private IconPatcher() { }

    /** Changes the UTF-16 package name stored in the app resource-table package header. */
    public static byte[] rewritePackageName(byte[] resourcesTable, String packageName) throws IOException {
        if (packageName == null || packageName.length() > 127) throw new IOException("Недопустимое имя пакета ресурсов");
        int size = u32(resourcesTable, 4);
        if (u16(resourcesTable, 0) != 0x0002 || size > resourcesTable.length) throw new IOException("Некорректный resources.arsc");
        byte[] result = resourcesTable.clone();
        int cursor = u16(result, 2);
        while (cursor + 288 <= size) {
            int type = u16(result, cursor);
            int chunkSize = u32(result, cursor + 4);
            if (chunkSize < 8 || cursor + chunkSize > size) throw new IOException("Повреждённый resources.arsc");
            if (type == TABLE_PACKAGE) {
                byte[] utf16 = packageName.getBytes(StandardCharsets.UTF_16LE);
                java.util.Arrays.fill(result, cursor + 12, cursor + 12 + 256, (byte) 0);
                System.arraycopy(utf16, 0, result, cursor + 12, utf16.length);
                return result;
            }
            cursor += chunkSize;
        }
        throw new IOException("В resources.arsc не найден пакет приложения");
    }

    /** Finds the named adaptive-icon foreground resource and changes its path from XML to PNG. */
    public static Result patch(byte[] resourcesTable, byte[] userPng) throws IOException {
        if (resourcesTable == null || resourcesTable.length < 12 || userPng == null || userPng.length < 8
                || userPng[0] != (byte) 0x89 || userPng[1] != 'P' || userPng[2] != 'N' || userPng[3] != 'G') {
            throw new IOException("Иконка должна быть корректным PNG");
        }
        int tableSize = u32(resourcesTable, 4);
        if (u16(resourcesTable, 0) != 0x0002 || tableSize > resourcesTable.length || tableSize < 12) {
            throw new IOException("Некорректная таблица ресурсов Android");
        }
        Pool global = new Pool(resourcesTable, u16(resourcesTable, 2));
        int cursor = u16(resourcesTable, 2);
        while (cursor + 8 <= tableSize) {
            int type = u16(resourcesTable, cursor);
            int header = u16(resourcesTable, cursor + 2);
            int size = u32(resourcesTable, cursor + 4);
            if (size < header || cursor + size > tableSize) throw new IOException("Повреждённый resources.arsc");
            if (type == TABLE_PACKAGE) {
                Result result = patchPackage(resourcesTable, cursor, size, global, userPng);
                if (result != null) return result;
            }
            cursor += size;
        }
        throw new IOException("В APK не найден ресурс ic_launcher_foreground для замены");
    }

    private static Result patchPackage(byte[] source, int packageStart, int packageSize,
                                       Pool global, byte[] png) throws IOException {
        checkRange(source, packageStart, 288);
        int typePoolOffset = u32(source, packageStart + 268);
        int keyPoolOffset = u32(source, packageStart + 276);
        Pool types = new Pool(source, packageStart + typePoolOffset);
        Pool keys = new Pool(source, packageStart + keyPoolOffset);
        int cursor = packageStart + u16(source, packageStart + 2);
        int end = packageStart + packageSize;
        while (cursor + 8 <= end) {
            int chunkType = u16(source, cursor);
            int headerSize = u16(source, cursor + 2);
            int chunkSize = u32(source, cursor + 4);
            if (chunkSize < headerSize || cursor + chunkSize > end) throw new IOException("Повреждённый type chunk");
            if (chunkType == TABLE_TYPE && headerSize >= 24) {
                int typeId = source[cursor + 8] & 0xff;
                int typeFlags = source[cursor + 9] & 0xff;
                int entryCount = u32(source, cursor + 12);
                int entriesStart = u32(source, cursor + 16);
                int configSize = u32(source, cursor + 20);
                boolean defaultConfig = configSize >= 4 && configSize <= headerSize - 20;
                for (int i = 4; defaultConfig && i < configSize; i++) {
                    if (source[cursor + 20 + i] != 0) defaultConfig = false;
                }
                if (defaultConfig && typeId > 0 && typeId <= types.count && entryCount >= 0 && entryCount < 100000) {
                    int offsetsBase = cursor + headerSize;
                    for (int i = 0; i < entryCount; i++) {
                        int entryOffset;
                        if ((typeFlags & 0x01) != 0) { // sparse entry-offset table
                            int pair = offsetsBase + i * 4;
                            if (pair + 4 > cursor + entriesStart) break;
                            int entryIndex = u16(source, pair);
                            if (entryIndex != i) continue;
                            entryOffset = u16(source, pair + 2) * 4;
                        } else {
                            int offsetAt = offsetsBase + i * 4;
                            if (offsetAt + 4 > cursor + entriesStart) break;
                            entryOffset = u32(source, offsetAt);
                            if (entryOffset == -1) continue;
                        }
                        int entry = cursor + entriesStart + entryOffset;
                        if (entry < cursor || entry + 8 > cursor + chunkSize) continue;
                        int entrySize = u16(source, entry);
                        int flags = u16(source, entry + 2);
                        int keyIndex = u32(source, entry + 4);
                        if ((flags & 0x01) != 0 || keyIndex < 0 || keyIndex >= keys.count
                                || !FOREGROUND.equals(keys.strings.get(keyIndex))) continue;
                        int value = entry + entrySize;
                        if (value + 8 > cursor + chunkSize || (source[value + 3] & 0xff) != VALUE_STRING) continue;
                        int pathIndex = u32(source, value + 4);
                        if (pathIndex < 0 || pathIndex >= global.count) continue;
                        String oldPath = global.strings.get(pathIndex);
                        if (!oldPath.startsWith("res/") || !oldPath.endsWith(".xml")) {
                            throw new IOException("Неожиданный формат launcher foreground: " + oldPath);
                        }
                        String newPath = oldPath.substring(0, oldPath.length() - 4) + ".png";
                        byte[] patched = source.clone();
                        replaceStringInPlace(patched, global, pathIndex, newPath);
                        return new Result(patched, oldPath, newPath, png.clone());
                    }
                }
            }
            cursor += chunkSize;
        }
        return null;
    }

    private static void replaceStringInPlace(byte[] data, Pool pool, int index, String replacement) throws IOException {
        int offset = pool.start + pool.stringsStart + pool.offsets[index];
        boolean utf8 = (pool.flags & 0x100) != 0;
        byte[] oldEncoded = encodedString(data, offset, utf8);
        byte[] newEncoded = encodeString(replacement, utf8);
        if (oldEncoded.length != newEncoded.length) throw new IOException("Длина пути иконки неожиданно изменилась");
        System.arraycopy(newEncoded, 0, data, offset, newEncoded.length);
    }

    private static String readString(byte[] data, int offset, boolean utf8) throws IOException {
        checkRange(data, offset, 2);
        if (utf8) {
            int[] a = readLength8(data, offset);
            int[] b = readLength8(data, a[1]);
            checkRange(data, b[1], b[0]);
            return new String(data, b[1], b[0], StandardCharsets.UTF_8);
        }
        int length = u16(data, offset);
        checkRange(data, offset + 2, length * 2);
        return new String(data, offset + 2, length * 2, StandardCharsets.UTF_16LE);
    }

    private static byte[] encodedString(byte[] data, int offset, boolean utf8) throws IOException {
        if (utf8) {
            int[] a = readLength8(data, offset);
            int[] b = readLength8(data, a[1]);
            int length = b[1] - offset + b[0] + 1;
            checkRange(data, offset, length);
            byte[] result = new byte[length];
            System.arraycopy(data, offset, result, 0, length);
            return result;
        }
        int chars = u16(data, offset);
        int length = 2 + chars * 2 + 2;
        checkRange(data, offset, length);
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

    private static byte[] encodeString(String value, boolean utf8) {
        byte[] raw = value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
        if (utf8) {
            byte[] chars = value.getBytes(StandardCharsets.UTF_8);
            int charCount = value.length();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            writeLength8(out, charCount);
            writeLength8(out, chars.length);
            out.write(chars, 0, chars.length);
            out.write(0);
            return out.toByteArray();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(value.length() & 0xff);
        out.write((value.length() >>> 8) & 0xff);
        out.write(raw, 0, raw.length);
        out.write(0); out.write(0);
        return out.toByteArray();
    }

    private static int[] readLength8(byte[] data, int at) throws IOException {
        checkRange(data, at, 1);
        int first = data[at++] & 0xff;
        if ((first & 0x80) == 0) return new int[]{first, at};
        checkRange(data, at, 1);
        return new int[]{((first & 0x7f) << 8) | (data[at] & 0xff), at + 1};
    }

    private static void writeLength8(java.io.ByteArrayOutputStream out, int length) {
        if (length > 0x7f) {
            out.write(0x80 | ((length >>> 8) & 0x7f));
            out.write(length & 0xff);
        } else out.write(length);
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | (b[o + 3] << 24);
    }
    private static void checkRange(byte[] b, int o, int n) throws IOException {
        if (o < 0 || n < 0 || o > b.length - n) throw new IOException("Повреждённая таблица ресурсов Android");
    }
}
