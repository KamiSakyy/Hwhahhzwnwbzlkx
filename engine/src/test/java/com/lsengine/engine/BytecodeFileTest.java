package com.lsengine.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.lsengine.engine.bundle.AppBundle;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;

import org.junit.Test;

import java.util.Map;

/**
 * Тесты сериализации байт-кода и пакета приложения.
 */
public class BytecodeFileTest {

    @Test
    public void bytecodeRoundTrip() throws Exception {
        String source = "local t = {a = 1, b = 'два'}\n"
                + "function f(x)\n"
                + "  local y = x * 2\n"
                + "  return y + 1\n"
                + "end\n"
                + "print(f(21))";
        Chunk chunk = new Compiler().compile(source);
        byte[] data = BytecodeFile.write(chunk);
        assertTrue("байт-код должен быть непустым", data.length > 16);

        Chunk restored = BytecodeFile.read(data);
        assertEquals(chunk.functions.size(), restored.functions.size());
        assertEquals(chunk.constants.size(), restored.constants.size());
        assertEquals(chunk.entry, restored.entry);
        for (int i = 0; i < chunk.functions.size(); i++) {
            Chunk.FuncProto a = chunk.functions.get(i);
            Chunk.FuncProto b = restored.functions.get(i);
            assertEquals(a.name, b.name);
            assertEquals(a.paramCount, b.paramCount);
            assertEquals(a.localCount, b.localCount);
            assertEquals(a.instructionCount(), b.instructionCount());
        }
    }

    @Test
    public void constantsArePreserved() throws Exception {
        Chunk chunk = new Compiler().compile("a = 'текст'\nb = 12.5\nc = true");
        Chunk restored = BytecodeFile.read(BytecodeFile.write(chunk));
        boolean sawString = false;
        boolean sawDouble = false;
        boolean sawBoolean = false;
        for (Object constant : restored.constants) {
            if (constant instanceof String && "текст".equals(constant)) {
                sawString = true;
            }
            if (constant instanceof Double && Math.abs((Double) constant - 12.5) < 1e-9) {
                sawDouble = true;
            }
            if (constant instanceof Boolean && (Boolean) constant) {
                sawBoolean = true;
            }
        }
        assertTrue(sawString);
        assertTrue(sawDouble);
        // логические константы компилируются в отдельные опкоды, а не в константы
        assertTrue(!sawBoolean);
    }

    @Test
    public void appBundleRoundTrip() throws Exception {
        AppBundle bundle = new AppBundle();
        bundle.appName = "Тест";
        bundle.packageName = "com.test.app";
        bundle.versionName = "2.3.4";
        bundle.versionCode = 7;
        bundle.orientation = "landscape";
        bundle.description = "описание";
        bundle.entryFile = "main.ls";
        bundle.bytecode = new byte[]{1, 2, 3, 4, 5};
        bundle.files.put("main.ls", "print('hi')".getBytes("UTF-8"));

        byte[] data = bundle.toBytes();
        AppBundle restored = AppBundle.fromBytes(data);
        assertEquals("Тест", restored.appName);
        assertEquals("com.test.app", restored.packageName);
        assertEquals("2.3.4", restored.versionName);
        assertEquals(7, restored.versionCode);
        assertEquals("landscape", restored.orientation);
        assertEquals("main.ls", restored.entryFile);
        assertEquals(5, restored.bytecode.length);
        for (Map.Entry<String, byte[]> entry : restored.files.entrySet()) {
            assertNotNull(entry.getValue());
            assertEquals("print('hi')", new String(entry.getValue(), "UTF-8"));
        }
    }
}
