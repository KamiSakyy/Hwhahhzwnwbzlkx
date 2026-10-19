package com.lsengine.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.lsengine.engine.bundle.AppBundle;

import org.junit.Test;

public class AppBundleTest {
    @Test
    public void nativeArm64PayloadRoundTripsInsideEncryptedBundlePlaintext() throws Exception {
        AppBundle source = new AppBundle();
        source.appName = "ARM64 app";
        source.bytecode = new byte[]{1, 2, 3};
        source.nativeCode = new byte[]{0x7f, 'E', 'L', 'F', 4, 5};
        AppBundle restored = AppBundle.fromBytes(source.toBytes());
        assertEquals(source.appName, restored.appName);
        assertArrayEquals(source.bytecode, restored.bytecode);
        assertArrayEquals(source.nativeCode, restored.nativeCode);
    }
}
