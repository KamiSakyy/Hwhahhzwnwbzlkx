# Theme Studio — переработка на настоящем DrKLO/Telegram UI

**Проект НЕ готов. Сборка и передача новых APK запрещены пользователем до
завершения.** Предыдущий Canvas-предпросмотр признан не соответствующим заданию.
Старый APK в Handoff — исторический прототип, НЕ одобренная версия продукта.

## Официальный репозиторий
DrKLO/Telegram подключён как Git submodule в `vendor/telegram`, закреплён SHA
`f32ffeec763f0bdbf4bec25b52d654f77bff733d` (13 октября 2026).
Это оригинальный репозиторий. Sparse checkout снят: полный worktree, все
19 вложенных native/media-подмодулей развёрнуты на upstream SHA. История Git
shallow, но файлы закреплённых версий полные. Повторить развёртывание:
`bash scripts/bootstrap_telegram.sh`. Точные SHA: [манифест checkout](docs/TELEGRAM_CHECKOUT.json).
В условиях доступа среды libyuv берётся из GitHub-зеркала на идентичном SHA.

## Текущее состояние
- Оба APK workflow переведены в ручной режим и безусловно заблокированы
  `if: ${{ false }}`. Никакой сборки при push.
- Официальные ThemePreviewActivity, ChatMessageCell, DialogCell,
  ThemeEditorView, Theme/ThemeDescription доступны в подмодуле.
- Реализованы исходники адаптера настоящего preview: [integration/telegram](integration/telegram).
  Подключён экспериментальный upstream-модуль `:ThemeStudioHost`, пока без
  подтверждённой Android-компиляции и без проверки на устройстве.
- `app/` остаётся старым прототипом; его наличие не означает готовую интеграцию.
- Нельзя заявлять 100% готовности по успешной компиляции или созданию APK.

Проверка только исходников без APK: `python scripts/check_source_stage.py`.
План интеграции, реальные зависимости и непроверенные требования:
[integration/telegram/README.md](integration/telegram/README.md).

Требования: настоящие Telegram-компоненты без перерисовки-имитации; точное
редактирование нажатием; RGBA/HEX; обои; серьёзный удобный интерфейс Java/XML;
arm64 release <=10 МБ, только GitHub Actions, без Secrets. Размер и полная
точность остаются непроверенными для нового движка. Лицензия GPL-2.0.

## Актуальный ход переработки
[Подробный статус: реализованные исходники и оставшиеся работы](docs/CURRENT_STATUS.md).
Новые инструменты используют штатные ActionBar, ColorPicker, RecyclerListView и
TextColorCell. Введён запрет packaging/device-задач Gradle; эмуляторы запрещены.

## Экспериментальный Android entry point
[integration/android-host](integration/android-host/README.md) зависит от
оригинального `:TMessagesProj`, а не старого прототипа. Отдельный workflow
проверяет Java compile и merged manifest, НЕ создавая APK и НЕ запуская эмулятор.
