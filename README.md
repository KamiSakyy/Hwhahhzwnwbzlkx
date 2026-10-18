# LS Engine — движок создания приложений и игр

**LS Studio** — это полностью офлайновая среда разработки на Android: пишете код на
языке **LS**, нажимаете **LIVE** — и приложение запускается мгновенно, без APK;
нажимаете **APK** — и получаете готовый подписанный APK, собранный прямо на
устройстве.

Ни сети, ни серверов, ни внешних программ: компилятор, виртуальная машина и
сборщик APK работают внутри приложения.

---

## Что внутри

| Модуль | Назначение |
|---|---|
| `engine` | Язык LS, компилятор в байт-код, виртуальная машина, нативный кодекс контейнера `.c`, ZIP-писатель, подпись APK v2, патчер манифеста, офлайн-сборщик |
| `runtime` | Минимальный рантайм: хост для собранных приложений (читает `assets/lsapp.c`, запускает байт-код) |
| `app` | LS Studio: редактор с подсветкой, живой предпросмотр, офлайн-сборка APK, Material 3 дизайн |

## Ключевые идеи

### 1. Настоящий байт-код, а не «интерпретация исходника»

Исходник LS **никогда** не попадает в dex и не выполняется как текст.
`Compiler` превращает его в байт-код формата `LSB1`
(константы, прототипы функций, 4-байтовые инструкции `op:a:b`),
который исполняет стековая виртуальная машина `Vm`:

```
main.ls  ──Lexer──▶ Token ──Parser──▶ AST ──Compiler──▶ Chunk (LSB1) ──▶ Vm
```

### 2. Код пользователя в зашифрованном контейнере `.c`

Внутри собранного APK лежит **один** файл `assets/lsapp.c` — не 7z и не zip,
а собственный контейнер:

```
LSC1 | version | flags | reserved | plainSize(4) | IV(16) | payload
payload = AES-256-CBC( PKCS#7( LZMA1( LSB1-пакет ) ) )
```

* **LZMA** с максимальным сжатием (уровень 9, словарь 1 МБ) — модифицированный
  LZMA SDK 18.05, собранный под POSIX-pthreads.
* **AES-256-CBC** — реализация в C, ключ **не выгружается в Java**: он лежит
  в native-библиотеке `liblscodec.so` (`engine/src/main/cpp/lskeys.h`) и
  собирается в рантайме по частям, как в Solar2D.
* Ключ подписи APK (RSA-2048, PKCS#8) и сертификат тоже хранятся только в
  native-коде — в Java их нет.

### 3. Собственная подпись APK (Signature Scheme v2)

Офлайн-сборщик не вызывает `apksigner`: реализация схемы v2 написана с нуля
(`engine/src/main/java/com/lsengine/engine/apk/ApkSignerV2.java`) — чанковый
SHA-256, блок `0x7109871a`, RSA PKCS#1 v1.5 SHA-256 (`0x0103`).
Идентификатор пакета и название приложения патчатся прямо в бинарном
`AndroidManifest.xml` (`AxmlPatcher`), `extractNativeLibs` принудительно
включается, чтобы нативные библиотеки распаковывались при установке.

### 4. Живой предпросмотр

Кнопка **LIVE** компилирует текущий файл и запускает байт-код в той же виртуальной
машине, на которой работает собранное приложение. Никакой пересборки APK —
перезапуск мгновенный.

---

## Сборка

Сборка выполняется **только** через GitHub Actions (официальный сборщик):

```bash
git push origin arena/e57c57a9-hwhahhzwnwbzlkx
gh run watch
gh run download
```

Workflow `.github/workflows/build-apk.yml` делает:

1. нативный тест кодека (AES-256 по FIPS-197 + LZMA) на обычной машине;
2. модульные тесты движка (`:engine:testDebugUnitTest`);
3. сборку `:runtime:assembleRelease` и `:app:assembleRelease`;
4. проверку подписи через `apksigner verify`;
5. проверку, что APK меньше 10 МБ;
6. упаковку исходников в `handoff/LS-Engine-source.zip` и копирование APK.

Локальная сборка: `./gradlew :app:assembleRelease` (JDK 17, Android SDK 36,
NDK 27.0.12077973, CMake 3.22.1).

---

## Язык LS (кратко)

```lua
-- комментарий
local paddle = { x = 150, y = 520, w = 120, h = 16 }

function update(dt)
  if input.down then
    paddle.x = input.x - paddle.w / 2
  end
end

function draw()
  draw.clear("#0B0B12")
  draw.rect(paddle.x, paddle.y, paddle.w, paddle.h, "#14B8A6")
  draw.text(20, 40, "Очки: " .. score, 20, "#FFFFFF")
  ui.button(40, 600, 220, 60, "Играть", function() print("старт!") end)
end
```

* Переменные: `local`, `global`, функции, замыкания, рекурсия.
* Управление: `if/elseif/else`, `while`, `repeat/until`, `for` (числовой и `in`).
* Данные: таблицы, `#t`, `t.k`, `t[1]`, `table.insert/remove/concat`,
  `string.*`, `math.*`, `pairs`, `ipairs`, методы `s:upper()`.
* API движка: `draw.*`, `input.*`, `time.*`, `ui.button`, `sound.beep`,
  `app.width/height`, `print`.

Полное описание — в [docs/LANGUAGE.md](docs/LANGUAGE.md), архитектура — в
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Требования

* Android 8.0 (API 26) и новее, target SDK 36 (Android 16).
* Полностью офлайн: приложение не запрашивает доступ в интернет
  (`usesCleartextTraffic="false"`, разрешений INTERNET нет).
* Размер APK — меньше 10 МБ (проверяется в CI).
