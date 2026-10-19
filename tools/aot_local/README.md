# Локальный AOT smoke-test

`run.sh` компилирует Java-движок, переводит LS-программу в ELF64 x86-64, запускает её через `tools/aot_host_test.c` и сравнивает stdout с виртуальной машиной. Это быстрый вспомогательный цикл; официальные проверки Android/APK и ARM64 выполняются GitHub Actions.

Требования: JDK/JRE 11+, Eclipse Compiler for Java (`ecj.jar`) и GCC. Пример:

```sh
ECJ_JAR=/path/to/ecj.jar OUT_DIR=/tmp/aot tools/aot_local/run.sh tools/aot_local/prog.ls
```

Если `java` не установлен как команда, скрипт использует `jdk4py` из Python. Если `ECJ_JAR` не задан, используется `/tmp/jdk/ecj.jar`.
