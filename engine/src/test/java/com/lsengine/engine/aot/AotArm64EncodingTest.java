package com.lsengine.engine.aot;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class AotArm64EncodingTest {

    @Test
    public void encodesCommonBitMasksWithCorrectRotation() {
        assertArrayEquals(new int[]{1, 0, 47},
                AotArm64.encodeBitMask(0x0000FFFFFFFFFFFFL));
        assertArrayEquals(new int[]{0, 0, 60},
                AotArm64.encodeBitMask(0x5555555555555555L));
        assertArrayEquals(new int[]{0, 0, 39},
                AotArm64.encodeBitMask(0x00FF00FF00FF00FFL));
        assertArrayEquals(new int[]{1, 8, 15},
                AotArm64.encodeBitMask(0xFF000000000000FFL));
    }

    @Test
    public void movImmUsesAllFourHalfwordPositions() {
        AotArm64 backend = new AotArm64();
        backend.movImm(AotIsa.R0, 0x1122334455667788L);
        byte[] code = backend.code();
        assertEquals(16, code.length);
        assertEquals(0xD28EF100, word(code, 0));  // movz x0, #0x7788
        assertEquals(0xF2AAACC0, word(code, 4));  // movk x0, #0x5566, lsl #16
        assertEquals(0xF2C66880, word(code, 8));  // movk x0, #0x3344, lsl #32
        assertEquals(0xF2E22440, word(code, 12)); // movk x0, #0x1122, lsl #48
    }

    private static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
