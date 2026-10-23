# Web Recorder — браузер с записью экрана внутри приложения

Android-приложение (**Java + XML**) со встроенным веб-браузером (WebView) и записью экрана
через `MediaProjection`. Ищете видео в браузере внутри приложения, включаете полноэкранный
плеер — и запись сохраняет именно это состояние, даже если свернуть приложение.

## Возможности

- **Браузер внутри приложения**: адресная строка, поиск видео (YouTube), переходы назад/вперёд,
  полноэкранный HTML5-плеер (WebView `onShowCustomView`).
- **Запись экрана**: `MediaProjection` + `MediaRecorder` (H.264/AAC) в foreground-сервисе —
  запись продолжается при свёрнутом приложении и при выключенном экране плеера.
- **Запись только этого приложения**: на Android 14+ системный диалог даёт выбор
  «Одно приложение» → Web Recorder. На Android 10–13 система записывает весь экран.
- **Выбор качества**: 480p / 720p / 1080p / «Экран» (нативное разрешение), битрейт 4–20 Мбит/с, 30 fps.
- **Звук**: микрофон (галочка «Мик») либо **внутренний звук приложения** через
  `AudioPlaybackCapture` (Android 10+), с последующей склейкой дорожек через `MediaMuxer`.
- **Сохранение**: после остановки видео автоматически попадает в Галерею
  (`Movies/WebRecorder`). Кнопка «В папку…» сохраняет копию в **любую папку** через системный
  проводник (Storage Access Framework). Кнопка «Открыть» запускает плеер.
- **Управление**: стоп из приложения или из уведомления; таймер и размер файла в уведомлении.
- Без сторонних библиотек и без интернета для сборки (только Android SDK).

## Структура

```
app/src/main/java/com/kamisakyy/webrecorder/
  MainActivity.java      — WebView-браузер, панель записи, полноэкранный плеер, сохранение
  RecorderService.java   — foreground-сервис: MediaProjection/MediaRecorder, звук, MediaMuxer
  RecorderStore.java     — сохранение в MediaStore (Галерея) и в папку через SAF
  App.java               — каналы уведомлений
app/src/main/res/layout/activity_main.xml
app/src/main/assets/home.html
.github/workflows/build-apk.yml — сборка подписанного APK
```

## Сборка через GitHub Actions

Workflow **Build APK** запускается на каждый push и вручную (`Actions → Build APK → Run workflow`).

- Подписанный release-APK и debug-APK выкладываются в **Artifacts**.
- Подписанный APK автоматически публикуется в **Releases** под тегом `latest-apk`.

Прямая ссылка на последнюю сборку:

```
https://github.com/KamiSakyy/Hwhahhzwnwbzlkx/releases/download/latest-apk/WebRecorder-latest.apk
```

### Своя подпись (необязательно)

Добавьте secrets репозитория:

| Secret | Значение |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | alias ключа |
| `KEY_PASSWORD` | пароль ключа |

Без secrets workflow подписывает APK **демо-ключом** `signing/web-recorder.p12.b64`
(alias `webrecorder`, пароль `android`), поэтому все сборки имеют одинаковую подпись и
обновляются «поверх» без удаления. Отпечаток сертификата — `signing/CERT-SHA256.txt`,
CI проверяет его на каждом билде. Этот ключ публичный: он годится для сайдлока,
но **не** для Google Play — для реального релиза задайте secrets выше.

Если secrets не заданы и демо-ключа нет, workflow сгенерирует одноразовый ключ.

## Локальная сборка

```bash
export KEYSTORE_PATH=/path/to/release.jks KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

## Разрешения

`INTERNET`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`, `WRITE_EXTERNAL_STORAGE` (только Android ≤ 9).

## Ограничения

- Запись идёт с физического дисплея. Выбор «только это приложение» доступен силами системы
  на Android 14+; на более старых версиях ограничить область средствами API нельзя.
- Внутренний звук приложения доступен с Android 10 и только для приложений, не запретивших захват.
- Ориентация интерфейса — портретная, чтобы размеры видео совпадали с экраном при записи.
