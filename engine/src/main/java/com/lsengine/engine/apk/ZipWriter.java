package com.lsengine.engine.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Сборка ZIP-архива (APK) «с нуля» — полный контроль над байтами,
 * что необходимо для вычисления подписи APK Signature Scheme v2.
 *
 * Никаких data descriptor'ов и extra-полей — расположение байтов полностью детерминировано.
 */
public final class ZipWriter {

    /** Результат сборки: сами байты и смещения секций. */
    public static final class Result {
        public final byte[] data;
        public final int centralDirectoryOffset;
        public final int centralDirectorySize;
        public final int eocdOffset;

        Result(byte[] data, int cdOffset, int cdSize, int eocdOffset) {
            this.data = data;
            this.centralDirectoryOffset = cdOffset;
            this.centralDirectorySize = cdSize;
            this.eocdOffset = eocdOffset;
        }
    }

    /** Одна запись архива. */
    public static final class Entry {
        public final String name;
        public final byte[] data;
        /** true — записать без сжатия (для .so, если нужно). */
        public final boolean stored;

        public Entry(String name, byte[] data) {
            this(name, data, false);
        }

        public Entry(String name, byte[] data, boolean stored) {
            this.name = name;
            this.data = data == null ? new byte[0] : data;
            this.stored = stored;
        }
    }

    private ZipWriter() {
    }

    public static Result build(List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        List<int[]> offsets = new ArrayList<>();   // offset локального заголовка
        List<byte[]> methods = new ArrayList<>();
        List<byte[]> compressed = new ArrayList<>();
        List<byte[]> crcs = new ArrayList<>();

        // Фиксированная метка времени — детерминированная сборка.
        int dosTime = 0x6000;   // 12:00:00
        int dosDate = 0x5821;   // 2024-01-01

        for (Entry entry : entries) {
            byte[] raw = entry.data;
            byte[] payload;
            int method;
            if (entry.stored) {
                payload = raw;
                method = 0;
            } else {
                payload = deflate(raw);
                if (payload.length >= raw.length && raw.length > 0) {
                    payload = raw;
                    method = 0;
                } else {
                    method = 8;
                }
            }
            CRC32 crc = new CRC32();
            crc.update(raw);

            int localOffset = out.size();
            offsets.add(new int[]{localOffset});
            methods.add(new byte[]{(byte) method});
            compressed.add(payload);
            crcs.add(new byte[]{
                    (byte) (crc.getValue() & 0xFF),
                    (byte) ((crc.getValue() >> 8) & 0xFF),
                    (byte) ((crc.getValue() >> 16) & 0xFF),
                    (byte) ((crc.getValue() >> 24) & 0xFF)});

            byte[] nameBytes = entry.name.getBytes("UTF-8");
            writeIntLE(out, 0x04034b50);
            writeShortLE(out, 20);            // version needed
            writeShortLE(out, 0);             // flags
            writeShortLE(out, method);
            writeShortLE(out, dosTime);
            writeShortLE(out, dosDate);
            out.write(crcs.get(crcs.size() - 1));
            writeIntLE(out, payload.length);
            writeIntLE(out, raw.length);
            writeShortLE(out, nameBytes.length);
            writeShortLE(out, 0);             // extra length
            out.write(nameBytes);
            out.write(payload);
        }

        int cdOffset = out.size();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            byte[] nameBytes = entry.name.getBytes("UTF-8");
            byte[] payload = compressed.get(i);
            int method = methods.get(i)[0] & 0xFF;
            int localOffset = offsets.get(i)[0];

            writeIntLE(out, 0x02014b50);
            writeShortLE(out, 20);            // version made by
            writeShortLE(out, 20);            // version needed
            writeShortLE(out, 0);             // flags
            writeShortLE(out, method);
            writeShortLE(out, dosTime);
            writeShortLE(out, dosDate);
            out.write(crcs.get(i));
            writeIntLE(out, payload.length);
            writeIntLE(out, entry.data.length);
            writeShortLE(out, nameBytes.length);
            writeShortLE(out, 0);             // extra
            writeShortLE(out, 0);             // comment
            writeShortLE(out, 0);             // disk number
            writeShortLE(out, 0);             // internal attributes
            writeIntLE(out, 0);               // external attributes
            writeIntLE(out, localOffset);
            out.write(nameBytes);
        }
        int cdSize = out.size() - cdOffset;
        int eocdOffset = out.size();
        writeIntLE(out, 0x06054b50);
        writeShortLE(out, 0);
        writeShortLE(out, 0);
        writeShortLE(out, entries.size());
        writeShortLE(out, entries.size());
        writeIntLE(out, cdSize);
        writeIntLE(out, cdOffset);
        writeShortLE(out, 0);                 // comment length

        return new Result(out.toByteArray(), cdOffset, cdSize, eocdOffset);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length / 2));
            byte[] buffer = new byte[16384];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
