# Theme Studio — переработка на настоящем DrKLO/Telegram UI

**Проект НЕ готов. Сборка и передача новых APK запрещены пользователем до
завершения.** Предыдущий Canvas-предпросмотр признан не соответствующим заданию.
Старый APK в Handoff — исторический прототип, НЕ одобренная версия продукта.

## Официальный репозиторий
DrKLO/Telegram подключён как Git submodule в `vendor/telegram`, закреплён SHA
`f32ffeec763f0bdbf4bec25b52d654f77bff733d` (13 октября 2026).
Это оригинальный репозиторий, а не имитация компонентов. В рабочей среде sparse
checkout содержит Java, Android resources, Gradle и buildSrc; native/медиа и
остальные каталоги доступны в Git, но не материализованы в этом checkout.
Для полного checkout после `git submodule update --init vendor/telegram`:
`git -C vendor/telegram sparse-checkout disable` (потребуется много места).

## Текущее состояние
- Оба APK workflow переведены в ручной режим и безусловно заблокированы
  `if: ${{ false }}`. Никакой сборки при push.
- Официальные ThemePreviewActivity, ChatMessageCell, DialogCell,
  ThemeEditorView, Theme/ThemeDescription доступны в подмодуле.
- Начат адаптер настоящего preview: [integration/telegram](integration/telegram).
  Он ещё НЕ подключён к приложению и НЕ проверен на устройстве.
- `app/` остаётся старым прототипом; его наличие не означает готовую интеграцию.
- Нельзя заявлять 100% готовности по успешной компиляции или созданию APK.

Проверка только исходников без APK: `python scripts/check_source_stage.py`.
План интеграции, реальные зависимости и непроверенные требования:
[integration/telegram/README.md](integration/telegram/README.md).

Требования: настоящие Telegram-компоненты без перерисовки-имитации; точное
редактирование нажатием; RGBA/HEX; обои; серьёзный удобный интерфейс Java/XML;
arm64 release <=10 МБ, только GitHub Actions, без Secrets. Размер и полная
точность остаются непроверенными для нового движка. Лицензия GPL-2.0.
