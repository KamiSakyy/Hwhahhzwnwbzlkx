# apk/ — архив оригинальных APK для white-hat анализа

Здесь лежат **неизменённые** установочные пакеты, скачанные публичными
зеркалами и автоматически проверенные CI.

## Как файл сюда попадает

Воркфлоу [`Fetch Kimi APK 3.1.1 (372)`](../.github/workflows/fetch-kimi-apk.yml):

1. перебирает прямые ссылки (по умолчанию — `d.apkpure.net` с `versionCode=372`),
   а если они не отдали файл, идёт по каталогам APKMirror → APKPure → APKCombo → Uptodown;
2. открывает каждый скачанный файл как ZIP и парсит **бинарный** `AndroidManifest.xml`
   (собственный AXML-парсер в [`scripts/fetch_apk.py`](../scripts/fetch_apk.py), без `aapt`);
3. принимает сборку, **только если** `package` и `versionCode` совпали с запрошенными —
   иначе шаг падает и в репозиторий ничего не коммитится;
4. если вместо обычного APK зеркало отдало бандл (`.xapk`/`.apks`/`.apkm`), из него
   извлекается `base.apk`;
5. коммитит файл в `apk/`, обновляет `apk/SHA256SUMS.txt`, прикладывает артефакт к рану
   и создаёт GitHub Release с тем же файлом.

Запуск вручную: **Actions → Fetch Kimi APK 3.1.1 (372) → Run workflow**
(можно поменять package / versionName / versionCode / имя файла / список ссылок).

## Целевая сборка

| поле | значение |
|---|---|
| package | `com.moonshot.kimichat` |
| versionName | `3.1.1` |
| versionCode | `372` |
| ABI / minSdk | `arm64-v8a` / 26 (Android 8.0+) |
| ожидаемый размер | `34 113 211` байт = **34.1 MB** |
| подпись APK (SHA-1) | `6ea0c94de82e3d793f07bffcd099aa3d35544a8b` |
| языки | 14, включая русский |
| файл | `apk/com.moonshot.kimichat-372_hw.apk` |

## Проверить файл локально

```bash
sha256sum -c apk/SHA256SUMS.txt
unzip -l apk/com.moonshot.kimichat-372_hw.apk | head
python3 scripts/fetch_apk.py --offline --package com.moonshot.kimichat \
        --version-code 372 --out /tmp/check.apk --workdir /tmp/w \
        --url "file://$PWD/apk/com.moonshot.kimichat-372_hw.apk"
```

## Важно

* Файлы здесь — **оригиналы**, скачанные из публичных каталогов, для исследования
  (разбор разрешений, SDK, эндпоинтов, декомпиляция в рамках white-hat анализа).
* `SHA256SUMS.txt` и SHA-1 подписи манифеста позволяют убедиться, что файл не менялся.
* Репозиторий публичный: не складывайте сюда то, что не готовы опубликовать.
