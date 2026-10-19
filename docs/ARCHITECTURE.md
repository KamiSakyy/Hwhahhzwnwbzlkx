# Архитектура LS Engine

```
┌──────────────────────── модуль app (LS Studio) ────────────────────────┐
│  ProjectsActivity → EditorActivity → PreviewActivity → SettingsActivity │
│  LsCodeEditor (подсветка, номера строк)   LsSurfaceView (кадры)        │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ использует
┌──────────────────────── модуль engine (чистая Java) ───────────────────┐
│  lang/   Lexer → Parser → Compiler → Chunk → BytecodeFile (LSB1)      │
│  vm/     Value, Table, Closure, Cell, Vm, Builtins  (fallback)          │
│  aot/    AotCompiler, AotX64, AotArm64, AotElf, AotIsa, AotValues      │
│  runtime/LsRuntime (VM) и AotHost (машинный код) — общий игровой цикл  │
│  bundle/AppBundle   (метаданные + байт-код + исходники)                │
│  nativecodec/Codec  (JNI: AES-256 + LZMA; ключи только в native)      │
│  apk/    ZipWriter, ApkSignerV2, AxmlPatcher, ApkBuilder              │
│  project/ProjectStore (файловое хранилище проектов)                    │
└───────────────┬──────────────────────────────────┬───────────────────┘
                │                                  │
   ┌────────────▼─────────────┐        ┌───────────▼──────────────────┐
   │ native liblscodec.so      │        │ модуль runtime (оболочка APK) │
   │ aes.c  codec.cpp  lzma/   │        │ RuntimeActivity               │
   │ lskeys.h — ключи AES и   │        │ RuntimeSurfaceView            │
   │ RSA только здесь          │        │  ↓ lib/<abi>/liblsapp.so      │
   │ native liblsrt.so         │        │  ↓ машинный код программы      │
   │ lsrt.c  lsrt_jni.cpp      │        └───────────────────────────────┘
   │ (рантайм AOT + JNI)       │
   └──────────────────────────┘
```

## 1. Язык и компиляция

| Файл | Ответственность |
|---|---|
| `lang/Token.java`, `lang/Lexer.java` | Лексика: имена, числа, строки, комментарии `--`, операторы |
| `lang/Ast.java` | Синтаксическое дерево (выражения, операторы) |
| `lang/Parser.java` | Рекурсивный спуск, приоритеты операций, `if/while/for/repeat` |
| `lang/Compiler.java` | Генерация байт-кода: локальные переменные, upvalues, jump-патчи |
| `lang/Opcodes.java` | 45 опкодов, инструкция 4 байта: `op:u8, a:u8, b:u16 LE` |
| `lang/Chunk.java` | Константы, прототипы функций, захваченные слоты |
| `lang/BytecodeFile.java` | Сериализация в формат `LSB1` и обратно |

### Формат `LSB1` (файл байт-кода)

```
magic "LSB1" | version | count
  константы: Double | Boolean | String
  функции:   name, paramCount, localCount, capturedSlots[], code[], upvalues[]
  entry
```

### Виртуальная машина

* Стек `Value[]`, кадры: `stackBase` + `Value[] locals` + `Cell[] cells`.
* Типы значений: `NIL, BOOL, NUM, STR, TABLE, FUNC, NATIVE, ITERATOR`.
* Замыкания: `Closure` хранит прототип + массив `Cell` (upvalues).
* `Cell` — общая ячейка: захваченный локал помечается `captured`, иначе
  `findUpvalue` поднимает его на уровень выше.
* Общий цикл `for k, v in pairs(t)` реализован опкодами `FOR_PREP/FOR_ITER`.
* Лимит шагов — 500 млн, чтобы бесконечный цикл не вешал приложение.

## 2. AOT: компиляция в машинный код

Основной путь исполнения — ** Ahead-of-Time компиляция в настоящий машинный код**.
Интерпретатора в рантайме нет.

| Файл | Ответственность |
|---|---|
| `aot/AotIsa.java` | Единый интерфейс бэкенда: регистры, конвенция вызова, раскладка кадра |
| `aot/AotBackend.java` | Общее поведение: метки, переходы, fixup-и адресов |
| `aot/AotX64.java` | Бэкенд x86-64 (System V AMD64) |
| `aot/AotArm64.java` | Бэкенд AArch64 (AAPCS64) |
| `aot/AotElf.java` | Сборка ELF64: PT_LOAD/PT_DYNAMIC, `.text`, `.rodata`, `.dynsym`, `.dynstr`, `.hash`, `.dynamic`, fixup-и переходов и адресов |
| `aot/AotCompiler.java` | Трансляция байт-кода LS в машинный код: стек, локалы, upvalues, вызовы, замыкания, переходы |
| `aot/AotValues.java` | Представление значений: NaN-boxing, строки, таблицы, замыкания |
| `aot/AotApi.java` | Номера слотов таблицы API (совпадают с `ls_api_table` в C) |
| `aot/AotModule.java` | Публичный вход: `AotModule.compile(chunk, arm64)` → байты ELF |
| `aot/AotRuntime.java` | Java-мост: загрузка библиотеки, передача таблицы API, вызов `update`/`draw` |
| `aot/LsPlatform.java` | Платформа для нативного кода: рисование, ввод, время, кнопки |

### Схема исполнения

```
Chunk (LSB1)
   │  AotCompiler: разбор опкодов, стековые слоты, локалы, upvalues
   ▼
машинный код (.text)  +  .rodata (строки, числа)  +  таблица API
   │  AotElf: ELF64, без символов и отладочных секций
   ▼
liblsapp.so  ──dlopen──▶  lsrt_init() + ls_register_api(таблица)
   │
   └─▶ точка входа: верхний уровень программы, затем update(dt) / draw()
```

### Соглашения

* Значение — 64 бита: `0x7FF8000000000000 | tag<<48 | payload48`; число — double.
* Аргументы вызова: `args[-1]` — количество, `args[-2]` — замыкание.
* Таблица API: 55 записей `ls_value (*)(ls_value *args)`; адреса копируются в
  таблицу скомпилированной библиотеки сразу после загрузки — ни одного
  импорта, ни одной зависимости от символов рантайма.
* Раскладка кадра: x86-64 — `rbp` как база, локалы ниже, исходящая область
  аргументов выше; AArch64 — `x29` как база, `x19/x20/x21` — календарь/счётчик.

### Проверка в CI

Шаг «AOT: машинный код и сверка с виртуальной машиной» собирает библиотеку
из тестовой программы, запускает её нативным хостом (`dlopen` + таблица API)
и сравнивает вывод с выводом виртуальной машины (`diff`). Расхождение —
провал сборки. Дополнительно печатается дизассемблирование (`objdump -d`).

## 3. Рантайм

`runtime/LsRuntime` — мост между VM и платформой (fallback-путь):

* `Surface` (платформа-independent): `clear/rect/circle/line/text/width/height`.
* `Host`: `log(String)`, `beep(freq, millis)`.
* Игровой цикл: `frame(now)` → `update(dt)` → очистка экрана → `draw()`.
* Ввод: `onTouch(x, y, action)` + состояние `input.down/x/y`.
* Кнопки UI: `ui.button(x, y, w, h, label, callback)` — рисуются и обрабатываются
  вводом внутри движка, без Android-зависимостей.

`runtime/AotHost` — такой же мост, но для машинного кода: он реализует
`LsPlatform` и вызывает глобальные функции программы `update`/`draw` в
скомпилированной библиотеке.

Именно поэтому один и тот же исходник одинаково работает в предпросмотре
(`app`) и в собранном приложении (`runtime`) — в обоих случаях это машинный
код, а различается только способ его загрузки (из памяти или из APK).

## 4. Контейнер `.c` и native-кодек

| Файл | Ответственность |
|---|---|
| `cpp/aes.h`, `cpp/aes.c` | AES-256 (S-box генерируется в рантайме), CBC без padding |
| `cpp/codec.h`, `cpp/codec.cpp` | Формат `LSC1`, LZMA (уровень 9), маскирование ключа, экспорт RSA-блобов |
| `cpp/lskeys.h` | Ключ AES-256 и DER-блобы RSA, собираются в рантайме по частям |
| `cpp/lscodec.cpp` | JNI: `nativeSeal`, `nativeOpen`, `nativePrivateKey`, `nativeCertificate`, `nativePublicKey` |
| `cpp/lzma/*` | LZMA SDK 18.05 (public domain), `Threads.c` переведён на POSIX-pthreads |

Заголовок контейнера (28 байт):

```
"LSC1" | version=1 | flags=1 | reserved(2) | plainSize(u32 LE) | IV(16) | payload
```

`payload = AES-256-CBC( PKCS#7( LZMA1(данные) || 5 байт props ) )`

Java-часть `Codec` — тонкий мост: в приложении работает только native-путь,
Java-fallback существует исключительно для JVM-тестов (и работает с отдельным
тестовым ключом, настоящих ключей в Java нет).

## 5. Офлайн-сборщик APK

```
sources ──▶ Compiler ──▶ Chunk ──▶ BytecodeFile ──▶ AppBundle(LSB1)
                                                    │
                                    Codec.seal ────┘ (LSC1: LZMA + AES-256)
                                                    │
                    AotModule.compile ──▶ lib/<abi>/liblsapp.so
                                                    │
   shell.apk (assets/runtime/shell.apk) ───────────┤
                                                    ▼
                             ApkBuilder: unzip → patch AndroidManifest.xml
                                          → assets/lsapp.c + assets/lsapp.meta
                                          → lib/<abi>/liblsapp.so (машинный код)
                                          → ZipWriter (deflate, CRC32)
                                          → ApkSignerV2 (RSA-2048, схема v2)
                                                    ▼
                                    app-release.apk → «Загрузки/LS Studio»
```

* `ZipWriter` — свой ZIP-писатель: локальные заголовки, central directory,
  EOCD, CRC32, DOS-время.
* `ApkSignerV2` — схема подписи v2 целиком на Java: блок подписи `0x7109871a`,
  чанковый SHA-256 (чанк 1 МБ), `signed data` без префикса длины,
  RSA PKCS#1 v1.5 SHA-256, вставка блока перед central directory и обновление
  смещения CD.
* `AxmlPatcher` — разбор бинарного `AndroidManifest.xml`: строковый пул
  (UTF-8/UTF-16), замена `package` и `label`, `extractNativeLibs = true`.

## 6. Сборка и CI

* AGP 8.13.0, Gradle 8.14.3, JDK 17, compile/target SDK 36, min SDK 26.
* Material 1.14.0 (Material 3), NDK 27.0.12077973, CMake 3.22.1, три ABI.
* `.github/workflows/build-apk.yml`: нативный тест кодека → модульные тесты →
  сверка AOT с виртуальной машиной → сборка → `apksigner verify` → проверка
  размера → `handoff/`.
