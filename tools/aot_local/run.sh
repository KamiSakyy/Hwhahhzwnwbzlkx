#!/bin/bash
# Локальный цикл проверки AOT: компиляция Java → ELF → нативный запуск.
# Не требует Android SDK, Gradle и сети — только JRE (jdk4py) и ecj.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [ -z "${JAVA:-}" ]; then
    if command -v java >/dev/null 2>&1; then
        JAVA="$(command -v java)"
    else
        JAVA="$(python3 -c "import jdk4py,os;print(os.path.join(str(jdk4py.JAVA_HOME),'bin','java'))")"
    fi
fi
ECJ="${ECJ_JAR:-/tmp/jdk/ecj.jar}"
OUT="${OUT_DIR:-/tmp/aot}"
PROG="${1:-$ROOT/tools/aot_local/prog.ls}"

if [ ! -f "$ECJ" ]; then
    echo "ECJ compiler jar not found: $ECJ (set ECJ_JAR)" >&2
    exit 2
fi
mkdir -p "$OUT" /tmp/build/classes

echo "== компиляция движка =="
"$JAVA" -jar "$ECJ" -nowarn -source 11 -target 11 -d /tmp/build/classes \
    $(find "$ROOT/engine/src/main/java" -name "*.java") "$ROOT/tools/aot_local/LocalAot.java"

echo "== компиляция программы в машинный код =="
"$JAVA" -cp /tmp/build/classes com.lsengine.engine.aot.LocalAot "$PROG" "$OUT/liblsapp.so"

echo "== сборка нативного хоста =="
gcc -std=c11 -O0 -g -I "$ROOT/engine/src/main/cpp" \
    "$ROOT/engine/src/main/cpp/lsrt.c" "$ROOT/tools/aot_host_test.c" \
    -o "$OUT/aot_host" -ldl -lm

echo "== запуск машинного кода =="
set +e
"$OUT/aot_host" "$OUT/liblsapp.so" > "$OUT/actual.txt" 2> "$OUT/actual.err"
rc=$?
set -e
if [ $rc -ne 0 ]; then
    echo "ХОСТ УПАЛ (код $rc)"
    head -20 "$OUT/actual.err"
fi

echo "--- вывод машинного кода ---"
cat "$OUT/actual.txt"
echo "--- сверка с виртуальной машиной ---"
if diff -u "$OUT/liblsapp.so.expected" "$OUT/actual.txt"; then
    echo "СОВПАДАЕТ"
else
    echo "РАСХОЖДЕНИЕ"
    exit 1
fi
