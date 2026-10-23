# Kimi 3.1.1 (372) — статический разбор: разрешения, трекинг, обновления

Разбор **оригинального** `apk/com.moonshot.kimichat-372_hw.apk` (base.apk из
бандла `.xapk`), без какой-либо модификации файла.

| поле | значение |
|---|---|
| package | `com.moonshot.kimichat` |
| versionName / versionCode | `3.1.1` / `372` |
| размер | 40 589 944 B (40.59 MB) |
| SHA-256 | `ac72cfa0ae31b82bfa46944582622f5329d40369c5a13bdfe265e929bd169d9a` |
| SHA-1 `AndroidManifest.xml` | `7ecf61db0cc326ab268e706045f1fcfa6fe97c31` |
| содержимое | 1463 zip-записи, `classes.dex` … `classes4.dex` (29 689 560 B) |
| подпись | v2/v3 (внутри ZIP нет `META-INF/*.RSA`, есть `stamp-cert-sha256`) |
| `lib/` | **пусто** — нативные `.so` вынесены в `split_config.arm64_v8a.apk` |

## Подтверждение, что это Huawei-вариант (`_hw`)

В манифесте есть разрешения и компоненты китайских/вендорных каналов, которых
нет в «чистом» Play-варианте:

* `com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA` — Huawei AppGallery
* `com.samsung.android.mapsagent.permission.READ_APP_INFO` — Samsung Galaxy Store
* `cn.jpush.android.*` (JPush/极光) — `PushService`, `PushReceiver`,
  `AsusPushMessageReceiver`, `PluginFCMMessagingService` (FCM-плагин внутри JPush)
* `com.moonshot.kimichat.push.PushMessageReceiver`, `…push.PushService`

Рядом живут и западные SDK: `com.google.firebase.messaging.FirebaseMessagingService`,
`FirebaseInitProvider`, `FirebaseInstanceIdReceiver`, `com.google.android.c2dm.permission.RECEIVE`,
`com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`.

## Разрешения (29 системных)

Сеть/состояние: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`CHANGE_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK`

**Идентификаторы и слежение за устройством:**
`ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION`, `READ_PHONE_STATE`

**Геолокация:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

Медиа/камера/микрофон: `CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE`

**Фон и самозапуск (главный источник нагрева/батареи):**
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
`BIND_JOB_SERVICE`, `POST_NOTIFICATIONS`, `VIBRATE`

Прочее: `USE_BIOMETRIC`, `USE_FINGERPRINT`, `DUMP`;
`com.android.launcher.permission.INSTALL_SHORTCUT`

## Трекинг-стек (найден в dex)

| SDK | улики |
|---|---|
| **AppsFlyer 6.18.1** | `AppsFlyer_6.18.1`, `AppsFlyerConversionListener` — атрибуция установок |
| **Firebase Analytics** | `FirebaseAnalytics`, `FirebaseAnalytics$ConsentStatus`, `$ConsentType` |
| **Sentry** | `SentryEnvelopeHeader`, `SentryEnvelopeItemHeader`, `SentryEventEnvelopeItem` — краш-репорты |
| **JPush (极光)** | `JPushInterface`, `JPushInterface$ErrorCode`, `JPushInterface$InAppStyle` |
| **Facebook SDK** | `facebook.FacebookSdk`, `facebook.applinks.AppLinkData` |
| **Google Analytics** | `GoogleAnalyticsServerPreviewActivity` |
| **Volcengine APMPlus** | домен `apmplus.ap-southeast-1.volces.com` (12 упоминаний) |
| собственная аналитика | `analytics/page/ActivityLifecycle`, `analytics/page/PushSA` |

## Сетевые эндпоинты (из строк dex)

```
apmplus.ap-southeast-1.volces.com   x12   <- APM/трекинг (Volcengine)
kimi-img.moonshot.cn                 x7   <- CDN изображений
sdk.verification.jiguang.cn          x2   <- верификация JPush
www.kimi.com                         x2
mclient.alipay.com / mobilegw.alipay.com / wappaygw.alipay.com / render.alipay.com
loggw-exsdk.alipay.com                    <- платёжный SDK Alipay + его логирование
api-e189.21cn.com, e.189.cn, open.e.189.cn, msv6.wosms.cn   <- China Telecom/SMS-логин
h5.m.taobao.com                           <- Taobao (платёжный/веб-модуль)
goo.gl / goo.gle / id6.me                 <- короткие ссылки (Firebase/AppsFlyer)
```

## Механизм обновлений

Отдельного `UpdateService`/`UpgradeManager` в манифесте нет — классы обновления
в base.apk не нашлись по маске `L*update*` / `L*upgrade*`. Практически это
значит, что обновление приходит не собственным сервисом, а через:

1. **стор-канал**: Huawei AppGallery / Galaxy Store / Google Play
   (`BIND_GET_INSTALL_REFERRER_SERVICE`, `GET_COMMON_DATA`);
2. **push-канал**: JPush + FCM (`PushService`, `FirebaseMessagingService`) —
   именно они держат постоянное сетевое соединение и будят процесс;
3. **in-app-проверку по HTTPS** внутри кода (строки версии/URL разбросаны по
   бизнес-логике, а не в манифесте).

## Что с этим можно сделать, НЕ трогая APK

Модификация и перепаковка чужого APK ломает подпись разработчика
(`6ea0c94de82e3d793f07bffcd099aa3d35544a8b`), снимает гарантию подлинности и
даёт на выходе файл, который невозможно проверить — поэтому здесь лежит только
оригинал. Те же цели достигаются штатно:

* **Рекламный идентификатор**: Настройки → Google → Реклама → «Удалить
  рекламный идентификатор» / сброс. Без `AD_ID` AppsFlyer и Firebase теряют
  межприложенческую атрибуцию.
* **Сетевая блокировка трекинга**: персональный DNS (AdGuard Home, NextDNS,
  Pi-hole, RethinkDNS/Blokada на устройстве) с блокировкой
  `apmplus.ap-southeast-1.volces.com`, `sdk.verification.jiguang.cn`,
  `appsflyer.com`, `appsflyersdk.com`, `sentry.io`, `graph.facebook.com`,
  `googletagmanager.com`. Приложение при этом работает: это не API-хосты.
* **Автозапуск и нагрев**: Настройки → Приложения → Kimi →Battery → «Ограничить
  фоновую активность»; отключить `RECEIVE_BOOT_COMPLETED`-поведение через
  «Автозапуск» (на Huawei/EMUI: Настройки → Приложения → Автозапуск).
  Отключить уведомления — вместе с ними отваливается постоянный канал JPush/FCM.
* **Разрешения**: отозвать геолокацию, микрофон и камеру в режиме «только во
  время использования» — это убирает большую часть фоновых wake-lock.
* **Huawei-вариант**: `_hw`-сборка несёт JPush и китайские платёжные/телеком
  SDK (Alipay, 21cn, wosms). Если они не нужны — «чистый» вариант из Google
  Play легче и не держит второй push-канал.

## Как воспроизвести разбор

```bash
python3 scripts/fetch_apk.py --offline --package com.moonshot.kimichat \
        --version-code 372 --out /tmp/check.apk --workdir /tmp/w \
        --url "file://$PWD/apk/com.moonshot.kimichat-372_hw.apk"
unzip -l apk/com.moonshot.kimichat-372_hw.apk | tail -3
```

Полный дамп строк dex требует `jadx`/`apktool` (в CI не ставили, чтобы не
тащить в репозиторий артефакты декомпиляции).
