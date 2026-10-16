# Tuman

Обход ТСПУ / DPI на Android **без root**, для **всех приложений** и мобильного трафика.

Один APK, одна кнопка, никаких зависимостей. Размер сборки — порядка 1 МБ.

> **Скачать APK:** [Tuman.apk (релиз `latest`)](../../releases/latest/download/Tuman.apk)
> или страница релизов: [Releases](../../releases)

---

## Как это работает

Механика — готовая и проверенная: ядро `tpws` из [bol-van/zapret](https://github.com/bol-van/zapret)
в режиме SOCKS5 + собственный userspace TUN-мост.

```
   приложение
        │  (любой TCP/UDP трафик системы)
        ▼
   VpnService → TUN-интерфейс (10.124.0.1/24)
        │
        ▼
   TUN-мост (наш код, чистая Java, без нативных библиотек)
        ├── TCP-сессии: рукопожатие, ретрансмиты, MSS 1400 → SOCKS5 CONNECT
        ├── DNS (порт 53): UDP-релей, свой резолвер, без утечек
        └── UDP: блокируется (QUIC) либо ретранслируется
        ▼
   tpws --socks --port=19821   (запускается из nativeLibraryDir)
        │   сегментация / disorder / OOB / TLS-rec / замена Host-заголовка
        ▼
   Интернет
```

DPI-фильтр видит «рваный» TLS ClientHello или HTTP-запрос и не может сопоставить
его со своей сигнатурой — соединение проходит.

**Почему `jniLibs`, а не `assets`:** начиная с Android 10 (targetSdk 29) приложение не
может выполнять файлы из своей записываемой директории (W^X). Поэтому `tpws` упакован как
`jniLibs/<abi>/libtpws.so` и запускается из `nativeLibraryDir` — это единственный
надёжный способ запустить сторонний бинарник без root.

## Стратегии

15 готовых профилей `tpws`. По умолчанию используется официальный рабочий набор
из документации zapret:

```
--filter-tcp=80  --methodeol
--new
--filter-tcp=443 --split-pos=1,midsld --disorder
```

Есть **автоподбор**: приложение по очереди поднимает каждый профиль на свободном порту
(21001–21030), прогоняет зонды `google.com/generate_204`, `youtube.com/generate_204`,
`discord.com/` и выбирает профиль с наибольшим score (при равенстве — с меньшей задержкой).
Кнопка «Проверить» тестирует текущий профиль вручную.

Отдельный режим — **свои аргументы**: строка передаётся в `tpws` как есть.

## Возможности

- системный VPN → работает **во всех приложениях**, включая браузеры без прокси-настроек;
- **сплит-туннелирование**: весь трафик / только выбранные приложения / все, кроме выбранных;
- блокировка QUIC (UDP/443) и IPv6-утечек;
- свой DNS (`1.1.1.1`, `8.8.8.8`, …) — вводится прямо в интерфейсе;
- автозапуск после загрузки устройства и после обновления приложения;
- плитка в шторке быстрых настроек;
- foreground-сервис с уведомлением, watchdog с перезапуском туннеля;
- живой лог: сессии, трафик, аптайм, ошибки.

## Дизайн

Полностью собственный: тёмная сине-аквамариновая палитра, 18 dp скруглённые карточки,
кнопка питания рисуется вручную (`SweepGradient` + `ValueAnimator`, без PNG).
Ни одной сторонней библиотеки — только framework API: **0 зависимостей**,
ни AppCompat, ни Material, ни Kotlin, ни Compose.

## Системные требования

| | |
|---|---|
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 |
| compileSdk | 35 |
| ABI | arm64-v8a, armeabi-v7a, x86, x86_64 (universal) |
| Root | не требуется |

## Сборка

Сборка выполняется в GitHub Actions (`.github/workflows/build-apk.yml`)
на `ubuntu-latest`: Android Gradle Plugin 8.13.2 + Gradle 8.13 + JDK 17.

```
push / tag v* / workflow_dispatch
   → setup-java 17 (temurin)
   → sdkmanager: platform-tools, platforms;android-35, build-tools;35.0.0
   → keytool: генерация подписи (env TUMAN_*)
   → ./gradlew assembleRelease        (R8 minify + shrinkResources)
   → проверка размера < 10 МБ (иначе job падает)
   → upload-artifact (Tuman.apk + mapping.txt)
   → gh release create/upload
```

Локально:

```bash
./gradlew assembleRelease
```

Без переменных `TUMAN_KEYSTORE`, `TUMAN_STORE_PASSWORD`, `TUMAN_KEY_ALIAS`,
`TUMAN_KEY_PASSWORD` сборка подписывается debug-ключом, так что APK ставится на устройство
в любом случае.

Минификация — настоящая: R8 в full mode, `shrinkResources`, никаких
`-keep class com.tuman.free.** { *; }`. `mapping.txt` прикладывается к артефактам
каждой сборки, чтобы краш-логи можно было расшифровать.

## Установка

1. Скачать `Tuman.apk` из [Releases](../../releases).
2. Разрешить установку из неизвестных источников.
3. Запустить приложение → нажать кнопку питания → подтвердить запрос на VPN.
4. Если трафик не пошёл — «Автоподбор» на главном экране.

Android может показать предупреждение «приложение может отслеживать трафик» — это
штатное сообщение системы на любой VPN-профиль; трафик никуда не отправляется,
он обрабатывается локально в `tpws`.

## Структура

```
app/src/main/
├── AndroidManifest.xml
├── assets/hostlist/blocked.txt      список доменов для режимов с --hostlist
├── jniLibs/<abi>/libtpws.so         ядро zapret (tpws), 4 ABI
├── java/com/tuman/free/
│   ├── MainActivity.java            4 вкладки в одном Activity (ViewFlipper)
│   ├── AppsActivity.java            выбор приложений для сплит-туннеля
│   ├── BootReceiver.java            автозапуск
│   ├── TunnelTile.java              плитка быстрых настроек
│   ├── core/     Engine, Strategy(-ies), Prefs, Probe, AutoDetector, Ui
│   ├── vpn/      TunnelVpnService, TunBridge, TcpSession, UdpRelay,
│   │             TunWriter, PacketUtil, TpwsRunner, NetGuard
│   └── ui/       PowerButton, LogAdapter, UiUtil
└── res/                             вёрстка, палитра, векторные иконки
```

## Дисклеймер

Приложение предназначено для доступа к легальному контенту, заблокированному
ошибочно или неправомерно. Ответственность за использование несёт пользователь.

Ядро `tpws` — из проекта [zapret](https://github.com/bol-van/zapret) (GPLv3).
Интерфейс, TUN-мост, автоподбор и весь остальной код — собственные.
