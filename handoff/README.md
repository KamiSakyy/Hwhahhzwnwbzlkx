# handoff — готовые артефакты LS Engine

Эта папка заполняется автоматически GitHub Actions (workflow
`.github/workflows/build-apk.yml`) после успешной сборки.

| Файл | Что это |
|---|---|
| `LS-Engine-source.zip` | Полный исходный код проекта (архив git с префиксом `LS-Engine-source/`) |
| `LS-Studio.apk` | Собранное приложение LS Studio (редактор + офлайн-сборщик APK), подписано |
| `LS-Runtime-unsigned.apk` | Оболочка рантайма — из неё офлайн-сборщик делает пользовательские APK |
| `LS-Android-ARM64-test.apk` | Подписанный ARM64 APK интеграционного примера для установки на Android-устройство |

Как получить артефакты:

```bash
gh run download           # последний прогон
gh run download <run-id>  # конкретный прогон
```

Сборка APK выполняется только через GitHub Actions: локальная сборка требует
JDK 17, Android SDK 36 и NDK 27.0.12077973.

Проверка подписи (в CI выполняется автоматически):

```bash
apksigner verify --verbose --print-certs LS-Studio.apk
```
