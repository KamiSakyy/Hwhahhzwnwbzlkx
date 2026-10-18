package com.lsengine.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.lang.LsError;
import com.lsengine.engine.vm.Builtins;
import com.lsengine.engine.vm.Value;
import com.lsengine.engine.vm.Vm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Тесты языка LS: синтаксис, компиляция и исполнение на виртуальной машине.
 */
public class LanguageTest {

    private static final List<String> PRINTED = new ArrayList<>();

    private Vm run(String source) throws LsError {
        Compiler compiler = new Compiler();
        Chunk chunk = compiler.compile(source);
        Vm vm = new Vm();
        Builtins.install(vm);
        vm.setPrinter(PRINTED::add);
        vm.load(chunk);
        vm.start();
        return vm;
    }

    @Test
    public void arithmeticAndGlobals() throws Exception {
        Vm vm = run("x = 2 + 3 * 4\ny = (2 + 3) * 4\nz = 7 % 3");
        assertEquals(14.0, vm.globals().get("x").asNumber(), 1e-9);
        assertEquals(20.0, vm.globals().get("y").asNumber(), 1e-9);
        assertEquals(1.0, vm.globals().get("z").asNumber(), 1e-9);
    }

    @Test
    public void stringsAndConcat() throws Exception {
        Vm vm = run("s = 'при' .. 'вет'\nt = string.upper(s)\nl = string.len(s)");
        assertEquals("привет", vm.globals().get("s").asString());
        assertEquals("ПРИВЕТ", vm.globals().get("t").asString());
        assertEquals(6.0, vm.globals().get("l").asNumber(), 1e-9);
    }

    @Test
    public void booleansAndNil() throws Exception {
        Vm vm = run("a = 1 < 2\nb = 2 <= 2\nc = 'x' == 'x'\nd = nil\ne = not a");
        assertTrue(vm.globals().get("a").asNumber() != 0);
        assertTrue(vm.globals().get("b").asNumber() != 0);
        assertTrue(vm.globals().get("c").asNumber() != 0);
        assertTrue(vm.globals().get("d").isNil());
        // not даёт логическое значение (а не число)
        assertTrue(vm.globals().get("e").type() == Value.BOOL);
    }

    @Test
    public void localScopeAndClosures() throws Exception {
        Vm vm = run("local function counter()\n"
                + "  local n = 0\n"
                + "  return function()\n"
                + "    n = n + 1\n"
                + "    return n\n"
                + "  end\n"
                + "end\n"
                + "c = counter()\n"
                + "c()\n"
                + "c()\n"
                + "r = c()");
        assertEquals(3.0, vm.globals().get("r").asNumber(), 1e-9);
    }

    @Test
    public void tablesAndLength() throws Exception {
        Vm vm = run("t = {1, 2, 3}\n"
                + "t.name = 'demo'\n"
                + "n = #t\n"
                + "table.insert(t, 4)\n"
                + "m = #t\n"
                + "v = t[2]\n"
                + "w = t.name");
        assertEquals(3.0, vm.globals().get("n").asNumber(), 1e-9);
        assertEquals(4.0, vm.globals().get("m").asNumber(), 1e-9);
        assertEquals(2.0, vm.globals().get("v").asNumber(), 1e-9);
        assertEquals("demo", vm.globals().get("w").asString());
    }

    @Test
    public void loopsAndConditionals() throws Exception {
        Vm vm = run("sum = 0\n"
                + "for i = 1, 10 do\n"
                + "  if i % 2 == 0 then\n"
                + "    sum = sum + i\n"
                + "  end\n"
                + "end\n"
                + "k = 0\n"
                + "while k < 5 do k = k + 1 end\n"
                + "r = 0\n"
                + "repeat r = r + 1 until r >= 3");
        assertEquals(30.0, vm.globals().get("sum").asNumber(), 1e-9);
        assertEquals(5.0, vm.globals().get("k").asNumber(), 1e-9);
        assertEquals(3.0, vm.globals().get("r").asNumber(), 1e-9);
    }

    @Test
    public void compoundAssignmentAndTernary() throws Exception {
        Vm vm = run("x = 10\n"
                + "x += 5\n"
                + "x -= 3\n"
                + "x *= 2\n"
                + "x /= 4\n"
                + "x %= 5\n"
                + "t = {n = 4}\n"
                + "t.n += 6\n"
                + "y = x > 3 ? 'много' : 'мало'\n"
                + "z = 0\n"
                + "z += 1\n");
        // 10 + 5 = 15, -3 = 12, *2 = 24, /4 = 6, %5 = 1
        assertEquals(1.0, vm.globals().get("x").asNumber(), 1e-9);
        assertEquals(10.0, vm.globals().get("t").asTable().get("n").asNumber(), 1e-9);
        assertEquals("много", vm.globals().get("y").asString());
        assertEquals(1.0, vm.globals().get("z").asNumber(), 1e-9);
    }

    @Test
    public void genericForOverTable() throws Exception {
        Vm vm = run("t = {a = 1, b = 2}\n"
                + "sum = 0\n"
                + "for k, v in pairs(t) do sum = sum + v end\n"
                + "list = {10, 20, 30}\n"
                + "acc = 0\n"
                + "for i, v in ipairs(list) do acc = acc + v end");
        assertEquals(3.0, vm.globals().get("sum").asNumber(), 1e-9);
        assertEquals(60.0, vm.globals().get("acc").asNumber(), 1e-9);
    }

    @Test
    public void methodCallSyntax() throws Exception {
        Vm vm = run("s = 'abc'\nu = s:upper()\nlen = s:len()");
        assertEquals("ABC", vm.globals().get("u").asString());
        assertEquals(3.0, vm.globals().get("len").asNumber(), 1e-9);
    }

    @Test
    public void printGoesToPrinter() throws Exception {
        PRINTED.clear();
        run("print('hello', 42)");
        assertEquals(1, PRINTED.size());
        assertTrue(PRINTED.get(0).contains("hello"));
    }

    @Test
    public void recursionWorks() throws Exception {
        Vm vm = run("local function fact(n)\n"
                + "  if n <= 1 then return 1 end\n"
                + "  return n * fact(n - 1)\n"
                + "end\n"
                + "f = fact(6)");
        assertEquals(720.0, vm.globals().get("f").asNumber(), 1e-9);
    }

    @Test
    public void syntaxErrorIsReported() {
        try {
            new Compiler().compile("x = = 1");
            fail("ожидалась ошибка разбора");
        } catch (LsError e) {
            assertNotNull(e.getMessage());
            assertTrue(e.line >= 1);
        }
    }
}
