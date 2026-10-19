package com.lsengine.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.lsengine.engine.apk.AxmlPatcher;
import com.lsengine.engine.apk.ApkSignerV2;
import com.lsengine.engine.apk.IconPatcher;
import com.lsengine.engine.apk.ZipWriter;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Тесты сборочной линии: ZIP-писатель, подпись APK v2 и патчер манифеста.
 */
public class ApkPipelineTest {

    // ---------------- ZIP ----------------

    @Test
    public void zipCanBeReadBack() throws Exception {
        byte[] payload = new byte[4096];
        Arrays.fill(payload, (byte) 'x');
        List<ZipWriter.Entry> entries = new ArrayList<>();
        entries.add(new ZipWriter.Entry("AndroidManifest.xml", "manifest".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ZipWriter.Entry("assets/lsapp.c", payload));
        entries.add(new ZipWriter.Entry("classes.dex", new byte[]{1, 2, 3}));
        entries.add(new ZipWriter.Entry("resources.arsc", new byte[73], true));
        entries.add(new ZipWriter.Entry("lib/arm64-v8a/libsample.so", new byte[100], true));

        ZipWriter.Result result = ZipWriter.build(entries);
        assertTrue(result.data.length > 0);
        assertEquals(0x06054b50, u32(result.data, result.eocdOffset));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                byte[] buffer = new byte[8192];
                while (zip.read(buffer) > 0) {
                    // читаем содержимое
                }
            }
        }
        assertTrue(names.contains("AndroidManifest.xml"));
        assertTrue(names.contains("assets/lsapp.c"));
        assertTrue(names.contains("classes.dex"));
        assertTrue(names.contains("resources.arsc"));
        assertTrue(names.contains("lib/arm64-v8a/libsample.so"));
        assertEquals(0, storedEntryDataOffset(result.data, "resources.arsc") % 4);
        assertEquals(0, storedEntryDataOffset(result.data, "lib/arm64-v8a/libsample.so") % 16384);
    }

    // ---------------- подпись v2 ----------------

    @Test
    public void signatureBlockIsInserted() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        List<ZipWriter.Entry> entries = new ArrayList<>();
        entries.add(new ZipWriter.Entry("AndroidManifest.xml", "manifest".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ZipWriter.Entry("assets/lsapp.c", new byte[2048]));
        ZipWriter.Result zip = ZipWriter.build(entries);

        byte[] signed = ApkSignerV2.sign(zip, pair.getPrivate(),
                pair.getPublic().getEncoded(), pair.getPublic().getEncoded());
        assertTrue(signed.length > zip.data.length);

        byte[] magic = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
        assertTrue("блок подписи должен присутствовать", indexOf(signed, magic) >= 0);

        // APK v2 length-prefixes both the signers sequence and each signer, then
        // the digest/signature sequences and their individual records.
        int valueOffset = zip.centralDirectoryOffset + 20; // block sizes + v2 block ID
        int signersSize = u32(signed, valueOffset);
        int signersOffset = valueOffset + 4;
        assertTrue(signersSize > 4 && signersOffset + signersSize <= signed.length);
        int signerSize = u32(signed, signersOffset);
        int signerOffset = signersOffset + 4;
        assertEquals(signersSize - 4, signerSize);
        int signedDataSize = u32(signed, signerOffset);
        int signedDataOffset = signerOffset + 4;
        int digestsSize = u32(signed, signedDataOffset);
        int digestsOffset = signedDataOffset + 4;
        assertEquals(digestsSize - 4, u32(signed, digestsOffset));
        assertEquals(0x0103, u32(signed, digestsOffset + 4));
        assertEquals(32, u32(signed, digestsOffset + 8)); // length-prefixed SHA-256 digest
        int certificatesSizeOffset = signedDataOffset + 4 + digestsSize;
        int certificatesSize = u32(signed, certificatesSizeOffset);
        int attributesSizeOffset = certificatesSizeOffset + 4 + certificatesSize;
        assertEquals(0, u32(signed, attributesSizeOffset));
        assertEquals(signedDataSize, attributesSizeOffset + 4 + u32(signed, attributesSizeOffset) - signedDataOffset);
        int signaturesSizeOffset = signerOffset + 4 + signedDataSize;
        int signaturesSize = u32(signed, signaturesSizeOffset);
        int signaturesOffset = signaturesSizeOffset + 4;
        assertEquals(signaturesSize - 4, u32(signed, signaturesOffset));
        assertEquals(0x0103, u32(signed, signaturesOffset + 4));
        assertEquals(256, u32(signed, signaturesOffset + 8)); // length-prefixed RSA-2048 signature

        // после подписи архив остаётся читаемым
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(signed))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                count++;
                byte[] buffer = new byte[4096];
                while (in.read(buffer) > 0) {
                    // читаем
                }
            }
        }
        assertEquals(2, count);
    }

    // ---------------- патчер манифеста ----------------

    @Test
    public void manifestIsPatched() throws Exception {
        byte[] manifest = FakeAxml.build();
        byte[] patched = AxmlPatcher.patch(manifest, "com.example.demo", "Демо");

        List<String> strings = FakeAxml.readStrings(patched);
        assertTrue(strings.contains("com.example.demo"));
        assertTrue(strings.contains("Демо"));
        assertTrue(!strings.contains("com.lsengine.runtime"));

        // extractNativeLibs должен стать true (0xFFFFFFFF)
        assertEquals(0xFFFFFFFF, FakeAxml.readBooleanAttribute(patched, "extractNativeLibs"));
        // package/label должны ссылаться на новые строки
        assertEquals("com.example.demo", FakeAxml.readStringAttribute(patched, "manifest", "package"));
        assertEquals("Демо", FakeAxml.readStringAttribute(patched, "application", "label"));
    }

    @Test
    public void manifestStructureStaysValid() throws Exception {
        byte[] manifest = FakeAxml.build();
        byte[] patched = AxmlPatcher.patch(manifest, "com.example.demo", "Демо");
        assertEquals(patched.length, u32(patched, 4));

        int headerSize = u16(patched, 2);
        assertEquals(0x0003, u16(patched, 0));
        assertEquals(0x0001, u16(patched, headerSize));
        int poolSize = u32(patched, headerSize + 4);
        // сразу после строкового пула должен идти следующий чанк AXML
        int next = headerSize + poolSize;
        assertTrue("ожидался корректный чанк после строкового пула",
                u16(patched, next) == 0x0100 || u16(patched, next) == 0x0102);
    }

    @Test
    public void realRuntimeManifestRemainsAlignedAfterPatch() throws Exception {
        byte[] manifest;
        try (InputStream in = getClass().getResourceAsStream("/runtime-manifest.axml")) {
            assertNotNull(in);
            manifest = readAll(in);
        }
        byte[] patched = AxmlPatcher.patch(manifest, "com.example.arm64test", "Очень длинное приложение 🌙");
        assertEquals(patched.length, u32(patched, 4));
        int cursor = u16(patched, 2);
        while (cursor < patched.length) {
            assertEquals("AXML chunk must remain 4-byte aligned", 0, cursor % 4);
            int size = u32(patched, cursor + 4);
            assertTrue("invalid AXML chunk size", size >= u16(patched, cursor + 2));
            cursor += size;
        }
        assertEquals(patched.length, cursor);
        assertTrue(indexOf(patched, "com.lsengine.runtime.RuntimeActivity".getBytes(StandardCharsets.UTF_16LE)) >= 0);
        assertTrue(indexOf(patched, "com.example.arm64test".getBytes(StandardCharsets.UTF_16LE)) >= 0);
    }

    @Test
    public void iconPatcherReplacesAndroidAdaptiveForegroundResource() throws Exception {
        byte[] table;
        try (InputStream in = getClass().getResourceAsStream("/runtime-resources.arsc")) {
            assertNotNull(in);
            table = readAll(in);
        }
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=");
        IconPatcher.Result result = IconPatcher.patch(table, png);
        assertTrue(result.oldEntry.startsWith("res/"));
        assertTrue(result.oldEntry.endsWith(".xml"));
        assertEquals(result.oldEntry.substring(0, result.oldEntry.length() - 4) + ".png", result.newEntry);
        assertTrue(indexOf(result.resourcesTable, result.newEntry.getBytes(StandardCharsets.UTF_8)) >= 0);
        assertTrue(Arrays.equals(png, result.png));
        byte[] renamed = IconPatcher.rewritePackageName(result.resourcesTable, "com.example.generated");
        byte[] packageName = "com.example.generated".getBytes(StandardCharsets.UTF_16LE);
        assertTrue(indexOf(renamed, packageName) >= 0);
    }

    private static int storedEntryDataOffset(byte[] zip, String wanted) {
        int cursor = 0;
        while (cursor + 30 <= zip.length && u32(zip, cursor) == 0x04034b50) {
            int compressed = u32(zip, cursor + 18);
            int nameLength = u16(zip, cursor + 26);
            int extraLength = u16(zip, cursor + 28);
            String name = new String(zip, cursor + 30, nameLength, StandardCharsets.UTF_8);
            int data = cursor + 30 + nameLength + extraLength;
            if (wanted.equals(name)) return data;
            cursor = data + compressed;
        }
        throw new AssertionError("ZIP entry not found: " + wanted);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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

    /**
     * Генератор минимального бинарного AndroidManifest.xml для тестов патчера.
     */
    static final class FakeAxml {

        private static final String[] STRINGS = {
                "android",
                "http://schemas.android.com/apk/res/android",
                "manifest",
                "package",
                "com.lsengine.runtime",
                "application",
                "label",
                "LS Runtime",
                "extractNativeLibs",
                "versionCode"};

        static byte[] build() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // заголовок XML
            writeShort(out, 0x0003);
            writeShort(out, 8);
            writeInt(out, 0);   // размер перепишем в конце
            byte[] pool = buildStringPool();
            out.write(pool, 0, pool.length);
            byte[] ns = buildStartNamespace();
            out.write(ns, 0, ns.length);
            byte[] manifest = buildManifestElement();
            out.write(manifest, 0, manifest.length);
            byte[] application = buildApplicationElement();
            out.write(application, 0, application.length);
            byte[] result = out.toByteArray();
            writeInt(result, 4, result.length);
            return result;
        }

        private static byte[] buildStringPool() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int count = STRINGS.length;
            writeShort(out, 0x0001);
            writeShort(out, 28);
            writeInt(out, 0);
            writeInt(out, count);
            writeInt(out, 0);
            writeInt(out, 0x00000100);              // UTF-8
            writeInt(out, 28 + count * 4);          // stringsStart
            writeInt(out, 0);                       // stylesStart
            int cursor = 0;
            for (String s : STRINGS) {
                writeInt(out, cursor);
                cursor += encodedLength(s);
            }
            for (String s : STRINGS) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.write(s.length());
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
                out.write(0);
            }
            byte[] result = out.toByteArray();
            writeInt(result, 4, result.length);
            return result;
        }

        private static int encodedLength(String s) {
            return 2 + s.getBytes(StandardCharsets.UTF_8).length + 1;
        }

        private static byte[] buildStartNamespace() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeShort(out, 0x0100);
            writeShort(out, 16);
            writeInt(out, 24);
            writeInt(out, 1);   // line
            writeInt(out, 0xFFFFFFFF);  // comment
            writeInt(out, 0);   // prefix -> "android"
            writeInt(out, 1);   // uri
            return out.toByteArray();
        }

        private static byte[] buildManifestElement() {
            // <manifest package="com.lsengine.runtime" android:versionCode="1">
            return buildElement(0, 2, new int[][]{
                    {0xFFFFFFFF, 3, 4, 0x03, 4},
                    {0xFFFFFFFF, 9, 0xFFFFFFFF, 0x10, 1}});
        }

        private static byte[] buildApplicationElement() {
            // <application android:label="LS Runtime" android:extractNativeLibs="false">
            return buildElement(0, 5, new int[][]{
                    {0xFFFFFFFF, 6, 7, 0x03, 7},
                    {0xFFFFFFFF, 8, 0xFFFFFFFF, 0x12, 0}});
        }

        /**
         * @param nameIndex индекс имени элемента в пуле
         * @param nsIndex   индекс пространства имён
         * @param attrs     {ns, name, rawValue, dataType, data}
         */
        private static byte[] buildElement(int nsIndex, int nameIndex, int[][] attrs) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int size = 16 + 20 + 20 * attrs.length;
            writeShort(out, 0x0102);
            writeShort(out, 16);
            writeInt(out, size);
            writeInt(out, 1);              // line
            writeInt(out, 0xFFFFFFFF);     // comment
            writeInt(out, nsIndex);
            writeInt(out, nameIndex);
            writeShort(out, 20);           // attributeStart
            writeShort(out, 20);           // attributeSize
            writeShort(out, attrs.length);
            writeShort(out, 0);            // idIndex
            writeShort(out, 0);            // classIndex
            writeShort(out, 0);            // styleIndex
            for (int[] attr : attrs) {
                writeInt(out, attr[0]);    // ns
                writeInt(out, attr[1]);    // name
                writeInt(out, attr[2]);    // rawValue
                writeShort(out, 8);        // typedValue.size
                out.write(0);              // res0
                out.write(attr[3]);        // dataType
                writeInt(out, attr[4]);    // data
            }
            return out.toByteArray();
        }

        /** Читает строки из строкового пула. */
        static List<String> readStrings(byte[] data) {
            List<String> result = new ArrayList<>();
            int headerSize = u16(data, 2);
            int poolStart = headerSize;
            int count = u32(data, poolStart + 8);
            int flags = u32(data, poolStart + 16);
            int stringsStart = u32(data, poolStart + 20);
            boolean utf8 = (flags & 0x00000100) != 0;
            int offsetsStart = poolStart + u16(data, poolStart + 2);
            for (int i = 0; i < count; i++) {
                int offset = u32(data, offsetsStart + i * 4);
                int p = poolStart + stringsStart + offset;
                if (utf8) {
                    int b0 = data[p++] & 0xFF;
                    if ((b0 & 0x80) != 0) {
                        b0 = ((b0 & 0x7F) << 8) | (data[p++] & 0xFF);
                    }
                    int b1 = data[p++] & 0xFF;
                    if ((b1 & 0x80) != 0) {
                        b1 = ((b1 & 0x7F) << 8) | (data[p++] & 0xFF);
                    }
                    result.add(new String(data, p, b1, StandardCharsets.UTF_8));
                } else {
                    int len = u16(data, p);
                    p += 2;
                    result.add(new String(data, p, len * 2, StandardCharsets.UTF_16LE));
                }
            }
            return result;
        }

        /** Возвращает значение булева атрибута (или -1). */
        static int readBooleanAttribute(byte[] data, String attributeName) {
            int[] found = findAttribute(data, attributeName);
            return found == null ? -1 : found[1];
        }

        /** Возвращает строковое значение атрибута. */
        static String readStringAttribute(byte[] data, String elementName, String attributeName) {
            int[] found = findAttribute(data, attributeName);
            if (found == null) {
                return null;
            }
            List<String> strings = readStrings(data);
            int index = found[1];
            if (index < 0 || index >= strings.size()) {
                return null;
            }
            return strings.get(index);
        }

        /** {data, dataType} для атрибута. */
        private static int[] findAttribute(byte[] data, String attributeName) {
            List<String> strings = readStrings(data);
            int headerSize = u16(data, 2);
            int fileSize = u32(data, 4);
            int chunkStart = headerSize + u32(data, headerSize + 4);
            while (chunkStart + 8 <= fileSize) {
                int type = u16(data, chunkStart);
                int chunkHeaderSize = u16(data, chunkStart + 2);
                int chunkSize = u32(data, chunkStart + 4);
                if (chunkSize <= 0) {
                    break;
                }
                if (type == 0x0102) {
                    int attributeStart = u16(data, chunkStart + 24);
                    int attributeSize = u16(data, chunkStart + 26);
                    int attributeCount = u16(data, chunkStart + 28);
                    int attributesOffset = chunkStart + chunkHeaderSize + attributeStart;
                    for (int i = 0; i < attributeCount; i++) {
                        int attrOffset = attributesOffset + i * attributeSize;
                        int nameIndex = u32(data, attrOffset + 4);
                        int dataType = data[attrOffset + 15] & 0xFF;
                        int value = u32(data, attrOffset + 16);
                        if (nameIndex >= 0 && nameIndex < strings.size()
                                && attributeName.equals(strings.get(nameIndex))) {
                            return new int[]{value, dataType == 0x12 ? value : value};
                        }
                    }
                }
                chunkStart += chunkSize;
            }
            return null;
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 24) & 0xFF);
        }

        private static void writeInt(byte[] data, int offset, int value) {
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            data[offset + 2] = (byte) ((value >> 16) & 0xFF);
            data[offset + 3] = (byte) ((value >> 24) & 0xFF);
        }
    }
}
