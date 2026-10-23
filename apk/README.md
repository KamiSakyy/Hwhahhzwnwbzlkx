# apk/ — архив оригинальных APK для white-hat анализа

Здесь лежат **неизменённые** установочные пакеты, скачанные публичными
зеркалами и автоматически проверенные CI.

## Что уже скачано

| файл | package | versionName | versionCode | размер | SHA-256 |
|---|---|---|---|---|---|
| `com.moonshot.kimichat-372_hw.apk` | `com.moonshot.kimichat` | `3.1.1` | `372` | 40 589 944 B (40.59 MB) | `ac72cfa0ae31b82bfa46944582622f5329d40369c5a13bdfe265e929bd169d9a` |

Проверено CI по бинарному `AndroidManifest.xml` (а не по имени файла):
`package`, `versionCode`, `versionName` читаются из AXML собственным парсером.
Дополнительные приметы этой сборки: 1463 zip-записи, 4 dex
(`classes.dex` … `classes4.dex`), SHA-1 манифеста
`7ecf61db0cc326ab268e706045f1fcfa6fe97c31`, подпись v2/v3 (внутри ZIP нет
`META-INF/*.RSA`, есть `stamp-cert-sha256`), нативные библиотеки вынесены в
split-APK, поэтому в `base.apk` папки `lib/` нет.

### Почему 40.59 MB, а не «34.1 MB»

34 113 211 байт = 34.11 MB — это размер **бандла** `.xapk` (сжатый контейнер:
`base.apk` + 8 split-APK: `config.arm64_v8a`, `config.xxhdpi`, языковые и т.д.).
Так его показывает APKMirror/APKPure. В репозиторий же кладётся извлечённый из
бандла **`base.apk`** — 40 589 944 байт несжатого файла. Это одна и та же
сборка `3.1.1 (372)`, просто разные стадии упаковки.

Если нужен именно бандл целиком или другой вариант сборки — запустите воркфлоу
вручную и поменяйте `expected_size` / `urls` (см. ниже): скрипт перебирает все
зеркала и оставляет вариант, ближайший к указанному размеру, а при точном
совпадении размера останавливается сразу.

## Как файл сюда попадает

Воркфлоу [`Fetch Kimi APK 3.1.1 (372)`](../.github/workflows/fetch-kimi-apk.yml):

1. перебирает прямые ссылки (по умолчанию — `d.apkpure.net/b/XAPK/…?versionCode=372&nc=arm64-v8a&sv=26`),
   а если они не отдали файл, идёт по каталогам **APKMirror → APKPure → APKCombo → Uptodown**;
2. каждый скачанный файл открывается как ZIP и его **бинарный** `AndroidManifest.xml`
   парсится собственным AXML-парсером в [`scripts/fetch_apk.py`](../scripts/fetch_apk.py) (без `aapt`);
3. сборка принимается **только если** `package` и `versionCode` совпали с запрошенными —
   иначе шаг падает с ошибкой и в репозиторий ничего не коммитится;
4. если зеркало отдало бандл (`.xapk`/`.apks`/`.apkm`), из него извлекается `base.apk`;
5. из всех подходящих вариантов выбирается лучший по размеру (`--expected-size`);
6. файл коммитится в `apk/`, обновляется `apk/SHA256SUMS.txt`, артефакт прикладывается
   к рану и создаётся GitHub Release `kimi-3.1.1-372`.

Запуск вручную: **Actions → Fetch Kimi APK 3.1.1 (372) → Run workflow**.
Редактируемые параметры: `package`, `version_name`, `version_code`, `filename`,
`urls` (список прямых ссылок через запятую), `expected_size`.

## Проверить файл локально

```bash
sha256sum -c apk/SHA256SUMS.txt
unzip -l apk/com.moonshot.kimichat-372_hw.apk | tail -3
python3 scripts/fetch_apk.py --offline --package com.moonshot.kimichat \
        --version-code 372 --out /tmp/check.apk --workdir /tmp/w \
        --url "file://$PWD/apk/com.moonshot.kimichat-372_hw.apk"
```

## Важно

* Файлы здесь — **оригиналы** из публичных каталогов, для исследования
  (разбор разрешений, SDK, эндпоинтов, декомпиляция в рамках white-hat анализа).
* `SHA256SUMS.txt` и SHA-1 манифеста позволяют убедиться, что файл не менялся.
* Репозиторий публичный: не складывайте сюда то, что не готовы опубликовать.
