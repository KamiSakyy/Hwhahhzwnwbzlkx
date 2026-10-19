package com.lsengine.engine.nativecodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Кодек контейнера .c — упаковка байт-кода в зашифрованный архив.
 *
 * Основной (боевой) путь — нативная библиотека {@code liblscodec.so}:
 * ключ AES-256 и ключ подписи APK хранятся ТОЛЬКО в машинном коде.
 * Чисто-Java реализация используется лишь в модульных тестах на JVM,
 * где .so недоступна, и работает с отдельным тестовым ключом.
 */
public final class Codec {

    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean available;
        try {
            System.loadLibrary("lscodec");
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        NATIVE_AVAILABLE = available;
    }

    /** Заголовок контейнера .c (совпадает с нативной реализацией). */
    private static final byte[] MAGIC = {'L', 'S', 'C', '1'};
    private static final int HEADER_SIZE = 28;
    /** Ключ исключительно для JVM-тестов — в production-коде его нет. */
    private static final byte[] TEST_KEY = new byte[]{
            'L', 'S', '-', 'T', 'E', 'S', 'T', '-', 'K', 'E', 'Y', '-', '0', '1', '2', '3',
            'L', 'S', '-', 'T', 'E', 'S', 'T', '-', 'K', 'E', 'Y', '-', '4', '5', '6', '7'};

    private Codec() {
    }

    public static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    /** Упаковывает данные в контейнер .c (LZMA + AES-256-CBC). */
    public static byte[] seal(byte[] plain) {
        if (NATIVE_AVAILABLE) {
            return nativeSeal(plain);
        }
        return sealFallback(plain);
    }

    /** Распаковывает контейнер .c. */
    public static byte[] open(byte[] blob) {
        if (NATIVE_AVAILABLE) {
            return nativeOpen(blob);
        }
        return openFallback(blob);
    }

    /** DER-ключ RSA (PKCS#8) для подписи собранных APK. */
    public static byte[] privateKeyDer() {
        if (NATIVE_AVAILABLE) {
            return nativePrivateKey();
        }
        throw new CodecException("нативная библиотека недоступна — ключ подписи в нативном коде");
    }

    /** DER-сертификат X.509. */
    public static byte[] certificateDer() {
        if (NATIVE_AVAILABLE) {
            return nativeCertificate();
        }
        throw new CodecException("нативная библиотека недоступна — сертификат в нативном коде");
    }

    /** DER SubjectPublicKeyInfo. */
    public static byte[] publicKeyDer() {
        if (NATIVE_AVAILABLE) {
            return nativePublicKey();
        }
        throw new CodecException("нативная библиотека недоступна — ключ в нативном коде");
    }

    private static native byte[] nativeSeal(byte[] plain);

    private static native byte[] nativeOpen(byte[] blob);

    private static native byte[] nativePrivateKey();

    private static native byte[] nativeCertificate();

    private static native byte[] nativePublicKey();

    // ---------------- запасная реализация для JVM-тестов ----------------

    private static byte[] sealFallback(byte[] plain) {
        try {
            byte[] compressed = deflate(plain);
            byte[] iv = new byte[16];
            for (int i = 0; i < iv.length; i++) {
                iv[i] = (byte) (System.nanoTime() >> (i * 3));
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(TEST_KEY, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(compressed);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC, 0, MAGIC.length);
            out.write(1);
            out.write(1);
            out.write(0);
            out.write(0);
            int size = plain.length;
            out.write(size & 0xFF);
            out.write((size >> 8) & 0xFF);
            out.write((size >> 16) & 0xFF);
            out.write((size >> 24) & 0xFF);
            out.write(iv, 0, iv.length);
            out.write(encrypted, 0, encrypted.length);
            return out.toByteArray();
        } catch (Exception e) {
            throw new CodecException("не удалось упаковать контейнер", e);
        }
    }

    private static byte[] openFallback(byte[] blob) {
        try {
            for (int i = 0; i < 4; i++) {
                if (blob[i] != MAGIC[i]) {
                    throw new IOException("неверная магия контейнера");
                }
            }
            int size = (blob[8] & 0xFF) | ((blob[9] & 0xFF) << 8)
                    | ((blob[10] & 0xFF) << 16) | ((blob[11] & 0xFF) << 24);
            byte[] iv = new byte[16];
            System.arraycopy(blob, 12, iv, 0, 16);
            byte[] encrypted = new byte[blob.length - HEADER_SIZE];
            System.arraycopy(blob, HEADER_SIZE, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TEST_KEY, "AES"), new IvParameterSpec(iv));
            byte[] compressed = cipher.doFinal(encrypted);
            byte[] plain = inflate(compressed, size);
            return plain;
        } catch (Exception e) {
            throw new CodecException("не удалось распаковать контейнер", e);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data, int expectedSize) throws java.io.IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] result = new byte[expectedSize];
        int offset = 0;
        try {
            while (offset < result.length) {
                int n = inflater.inflate(result, offset, result.length - offset);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        break;
                    }
                }
                offset += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new java.io.IOException("повреждённые сжатые данные", e);
        } finally {
            inflater.end();
        }
        if (offset != result.length) {
            throw new java.io.IOException("неверный размер распакованных данных");
        }
        return result;
    }
}
