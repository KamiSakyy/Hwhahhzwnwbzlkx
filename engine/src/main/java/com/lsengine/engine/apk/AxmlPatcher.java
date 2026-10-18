package com.lsengine.engine.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Патчер бинарного AndroidManifest.xml (AXML).
 *
 * Позволяет офлайн-сборщику задать собственное имя пакета, название приложения
 * и принудительно включить extractNativeLibs (чтобы нативные библиотеки
 * распаковывались при установке и не требовали выравнивания страниц).
 */
public final class AxmlPatcher {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int START_ELEMENT_TYPE = 0x0102;
    private static final int UTF8_FLAG = 0x00000100;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_BOOLEAN = 0x12;

    private AxmlPatcher() {
    }

    /** Результат разбора строкового пула. */
    private static final class StringPool {
        int chunkStart;
        int chunkHeaderSize;
        int chunkSize;
        int stringCount;
        int flags;
        int stringsStart;
        int[] offsets;
        List<String> strings = new ArrayList<>();
        List<byte[]> rawStrings = new ArrayList<>();
    }

    public static byte[] patch(byte[] manifest, String packageName, String appLabel) throws IOException {
        if (manifest.length < 12) {
            throw new IOException("повреждённый AndroidManifest.xml");
        }
        int fileType = u16(manifest, 0);
        if (fileType != RES_XML_TYPE) {
            throw new IOException("AndroidManifest.xml: неожиданный тип файла");
        }
        int headerSize = u16(manifest, 2);
        int fileSize = u32(manifest, 4);

        StringPool pool = parseStringPool(manifest, headerSize);
        Map<Integer, String> replacements = new HashMap<>();

        // Ищем элемент manifest и его атрибут package, а также application/label.
        List<int[]> patchPoints = new ArrayList<>();   // {offset, stringIndex, kind}
        int chunkStart = pool.chunkStart + pool.chunkSize;
        while (chunkStart + 8 <= fileSize) {
            int type = u16(manifest, chunkStart);
            int chunkHeaderSize = u16(manifest, chunkStart + 2);
            int chunkSize = u32(manifest, chunkStart + 4);
            if (chunkSize <= 0) {
                break;
            }
            if (type == START_ELEMENT_TYPE) {
                int elementNameIndex = u32(manifest, chunkStart + 20);
                int attributeStart = u16(manifest, chunkStart + 24);
                int attributeSize = u16(manifest, chunkStart + 26);
                int attributeCount = u16(manifest, chunkStart + 28);
                String elementName = indexOf(pool, elementNameIndex);
                int attributesOffset = chunkStart + chunkHeaderSize + attributeStart;
                for (int i = 0; i < attributeCount; i++) {
                    int attrOffset = attributesOffset + i * attributeSize;
                    if (attrOffset + 20 > manifest.length) {
                        break;
                    }
                    int attrNameIndex = u32(manifest, attrOffset + 4);
                    int rawValueIndex = u32(manifest, attrOffset + 8);
                    int dataType = manifest[attrOffset + 15] & 0xFF;
                    int data = u32(manifest, attrOffset + 16);
                    String attrName = indexOf(pool, attrNameIndex);

                    if ("manifest".equals(elementName) && "package".equals(attrName) && dataType == TYPE_STRING) {
                        replacements.put(data, packageName);
                        if (rawValueIndex >= 0) {
                            replacements.put(rawValueIndex, packageName);
                        }
                        patchPoints.add(new int[]{attrOffset, data, 0});
                    } else if ("application".equals(elementName) && "label".equals(attrName) && dataType == TYPE_STRING) {
                        replacements.put(data, appLabel);
                        if (rawValueIndex >= 0) {
                            replacements.put(rawValueIndex, appLabel);
                        }
                        patchPoints.add(new int[]{attrOffset, data, 0});
                    } else if ("application".equals(elementName)
                            && "extractNativeLibs".equals(attrName)
                            && dataType == TYPE_INT_BOOLEAN) {
                        patchPoints.add(new int[]{attrOffset, -1, 1});
                    }
                }
            }
            chunkStart += chunkSize;
        }

        // Пересобираем строковый пул с новыми строками.
        byte[] newPool = buildStringPool(manifest, pool, replacements);

        // Копируем «хвост» файла и патчим значения атрибутов.
        int tailStart = pool.chunkStart + pool.chunkSize;
        byte[] tail = new byte[manifest.length - tailStart];
        System.arraycopy(manifest, tailStart, tail, 0, tail.length);
        for (int[] point : patchPoints) {
            int offsetInFile = point[0] - tailStart;
            if (point[1] >= 0) {
                // Значение — строка; её индекс не меняется, так как порядок строк сохранён.
                continue;
            }
            // Булево значение: true
            int valueOffset = offsetInFile + 16;
            if (valueOffset + 4 <= tail.length) {
                tail[valueOffset] = (byte) 0xFF;
                tail[valueOffset + 1] = (byte) 0xFF;
                tail[valueOffset + 2] = (byte) 0xFF;
                tail[valueOffset + 3] = (byte) 0xFF;
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(manifest.length + 64);
        out.write(manifest, 0, headerSize);
        out.write(newPool);
        out.write(tail);
        byte[] result = out.toByteArray();
        writeIntLE(result, 4, result.length);
        return result;
    }

    private static StringPool parseStringPool(byte[] data, int headerSize) throws IOException {
        StringPool pool = new StringPool();
        pool.chunkStart = headerSize;
        if (u16(data, pool.chunkStart) != RES_STRING_POOL_TYPE) {
            throw new IOException("AndroidManifest.xml: нет строкового пула");
        }
        pool.chunkHeaderSize = u16(data, pool.chunkStart + 2);
        pool.chunkSize = u32(data, pool.chunkStart + 4);
        pool.stringCount = u32(data, pool.chunkStart + 8);
        pool.flags = u32(data, pool.chunkStart + 16);
        pool.stringsStart = u32(data, pool.chunkStart + 20);

        int offsetsStart = pool.chunkStart + pool.chunkHeaderSize;
        pool.offsets = new int[pool.stringCount];
        for (int i = 0; i < pool.stringCount; i++) {
            pool.offsets[i] = u32(data, offsetsStart + i * 4);
        }
        boolean utf8 = (pool.flags & UTF8_FLAG) != 0;
        for (int i = 0; i < pool.stringCount; i++) {
            int p = pool.chunkStart + pool.stringsStart + pool.offsets[i];
            int start = p;
            String value;
            if (utf8) {
                int b0 = data[p++] & 0xFF;
                if ((b0 & 0x80) != 0) {
                    b0 = ((b0 & 0x7F) << 8) | (data[p++] & 0xFF);
                }
                int b1 = data[p++] & 0xFF;
                if ((b1 & 0x80) != 0) {
                    b1 = ((b1 & 0x7F) << 8) | (data[p++] & 0xFF);
                }
                value = new String(data, p, b1, StandardCharsets.UTF_8);
                p += b1;
            } else {
                int charLen = u16(data, p);
                p += 2;
                value = new String(data, p, charLen * 2, StandardCharsets.UTF_16LE);
                p += charLen * 2;
            }
            // после строки идёт нулевой терминатор (1 или 2 байта)
            int rawEnd = p + (utf8 ? 1 : 2);
            pool.strings.add(value);
            pool.rawStrings.add(java.util.Arrays.copyOfRange(data, start, Math.min(rawEnd, data.length)));
        }
        return pool;
    }

    private static byte[] buildStringPool(byte[] data, StringPool pool, Map<Integer, String> replacements)
            throws IOException {
        boolean utf8 = (pool.flags & UTF8_FLAG) != 0;
        List<byte[]> encoded = new ArrayList<>();
        for (int i = 0; i < pool.stringCount; i++) {
            String replacement = replacements.get(i);
            if (replacement != null) {
                encoded.add(encodeString(replacement, utf8));
            } else {
                encoded.add(pool.rawStrings.get(i));
            }
        }

        int offsetsStart = pool.chunkStart + pool.chunkHeaderSize;
        int dataStart = offsetsStart + pool.stringCount * 4;
        int[] newOffsets = new int[pool.stringCount];
        int cursor = 0;
        for (int i = 0; i < pool.stringCount; i++) {
            newOffsets[i] = cursor;
            cursor += encoded.get(i).length;
        }
        int newStringsStart = dataStart - pool.chunkStart;
        int newChunkSize = dataStart - pool.chunkStart + cursor;

        ByteArrayOutputStream out = new ByteArrayOutputStream(newChunkSize);
        out.write(data, pool.chunkStart, pool.chunkHeaderSize);
        // строковые офсеты
        for (int offset : newOffsets) {
            writeIntLE(out, offset);
        }
        // данные строк
        for (byte[] raw : encoded) {
            out.write(raw);
        }
        byte[] result = out.toByteArray();
        if (result.length != newChunkSize) {
            throw new IOException("AndroidManifest.xml: ошибка сборки строкового пула");
        }
        writeIntLE(result, 4, newChunkSize);
        writeIntLE(result, 20, newStringsStart);
        return result;
    }

    private static byte[] encodeString(String value, boolean utf8) {
        if (utf8) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 8);
            writeLengthUtf8(out, value.length());
            writeLengthUtf8(out, bytes.length);
            out.write(bytes, 0, bytes.length);
            out.write(0);
            return out.toByteArray();
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 8);
        writeShortLE(out, value.length());
        out.write(bytes, 0, bytes.length);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    private static void writeLengthUtf8(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(0x80 | ((length >> 8) & 0x7F));
            out.write(length & 0xFF);
        } else {
            out.write(length & 0xFF);
        }
    }

    private static String indexOf(StringPool pool, int index) {
        if (index < 0 || index >= pool.strings.size()) {
            return null;
        }
        return pool.strings.get(index);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
