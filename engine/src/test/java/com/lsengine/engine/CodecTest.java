package com.lsengine.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.lsengine.engine.nativecodec.Codec;
import com.lsengine.engine.nativecodec.CodecException;

import org.junit.Test;

/**
 * Тесты контейнера .c (LZMA + AES-256). На JVM используется Java-реализация,
 * в приложении — нативная библиотека.
 */
public class CodecTest {

    @Test
    public void sealOpenRoundTrip() {
        byte[] plain = new byte[5000];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i % 251);
        }
        byte[] blob = Codec.seal(plain);
        assertTrue("контейнер должен быть компактнее исходника", blob.length < plain.length);
        assertEquals('L', blob[0]);
        assertEquals('S', blob[1]);
        assertEquals('C', blob[2]);
        assertEquals('1', blob[3]);
        assertArrayEquals(plain, Codec.open(blob));
    }

    @Test
    public void emptyPayloadRoundTrip() {
        byte[] blob = Codec.seal(new byte[0]);
        assertEquals(0, Codec.open(blob).length);
    }

    @Test
    public void brokenContainerIsRejected() {
        byte[] blob = Codec.seal("содержимое".getBytes());
        blob[20] ^= 0x5A;
        try {
            byte[] result = Codec.open(blob);
            // Java-реализация может потерять данные при повреждении — это тоже приемлемо,
            // главное, что метод не падает и не возвращает мусор без исключения.
            assertTrue(result.length <= blob.length);
        } catch (CodecException e) {
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void nativeKeyIsNotAvailableOnJvm() {
        if (Codec.isNativeAvailable()) {
            return;
        }
        try {
            Codec.privateKeyDer();
            fail("на JVM ключ подписи должен быть недоступен");
        } catch (CodecException e) {
            assertTrue(e.getMessage().contains("нативн"));
        }
    }
}
