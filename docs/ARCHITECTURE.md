# Архитектура LS Engine

```
┌──────────────────────── модуль app (LS Studio) ────────────────────────┐
│  ProjectsActivity → EditorActivity → PreviewActivity → SettingsActivity │
│  LsCodeEditor (подсветка, номера строк)   LsSurfaceView (кадры)        │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ использует
┌──────────────────────── модуль engine (чистая Java) ───────────────────┐
│  lang/   Lexer → Parser → Compiler → Chunk → BytecodeFile (LSB1)      │
│  vm/     Value, Table, Closure, Cell, Vm, Builtins                     │
│  runtime/LsRuntime  (игровой цикл + API: draw/input/time/ui/sound)     │
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
   │ RSA только здесь          │        └───────────────────────────────┘
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

## 2. Рантайм

`runtime/LsRuntime` — мост между VM и платформой:

* `Surface` (платформа-independent): `clear/rect/circle/line/text/width/height`.
* `Host`: `log(String)`, `beep(freq, millis)`.
* Игровой цикл: `frame(now)` → `update(dt)` → очистка экрана → `draw()`.
* Ввод: `onTouch(x, y, action)` + состояние `input.down/x/y`.
* Кнопки UI: `ui.button(x, y, w, h, label, callback)` — рисуются и обрабатываются
  вводом внутри движка, без Android-зависимостей.

Именно поэтому один и тот же байт-код одинаково работает в предпросмотре
(`app`) и в собранном приложении (`runtime`).

## 3. Контейнер `.c` и native-кодек

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

## 4. Офлайн-сборщик APK

```
sources ──▶ Compiler ──▶ Chunk ──▶ BytecodeFile ──▶ AppBundle(LSB1)
                                                    │
                                    Codec.seal ────┘ (LSC1: LZMA + AES-256)
                                                    │
   shell.apk (assets/runtime/shell.apk) ───────────┤
                                                    ▼
                             ApkBuilder: unzip → patch AndroidManifest.xml
                                          → assets/lsapp.c + assets/lsapp.meta
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

## 5. Сборка и CI

* AGP 8.13.0, Gradle 8.14.3, JDK 17, compile/target SDK 36, min SDK 26.
* Material 1.14.0 (Material 3), NDK 27.0.12077973, CMake 3.22.1, три ABI.
* `.github/workflows/build-apk.yml`: нативный тест кодека → модульные тесты →
  сборка → `apksigner verify` → проверка размера → `handoff/`.
