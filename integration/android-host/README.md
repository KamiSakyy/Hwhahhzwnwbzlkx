# Реальный Android host — стадия компиляции, НЕ готовый APK
Этот модуль подключается к оригинальному Gradle-проекту через
`integration/telegram/editor-sources.init.gradle` как `:ThemeStudioHost`.
Зависит непосредственно от официального `:TMessagesProj`, применяет оригинальный
`org.telegram.build-app-plugin`, использует SDK 36/Gradle 8.13/AGP 8.13.2.

Launcher: XML-контейнер → EditorActivity → настоящий ApplicationLoader/runtime →
TelegramPreviewHost → оригинальный ThemePreviewActivity. Старый `app/` не связан
с этим путём. Нет fallback на нарисованный Canvas-чат.

Manifest удаляет права клиента (включая INTERNET), services/receivers/providers
и клиентские Activity. Иконные aliases сохраняются с перенаправлением на editor:
оригинальный LauncherIconController обращается к ним при старте ApplicationLoader.
Это должно проверяться по **merged manifest**, не по одному исходному XML.

**Ограничения:** оригинальный runtime по-прежнему инициализирует контроллеры,
нативный код и может пытаться обращаться к сети; декларативный запрет INTERNET
не является проверкой корректного offline-поведения. Публикация без runtime-аудита
запрещена. Photo picker, проекты и экспорт ещё не перенесены в этот entry point.
Поворот/восстановление состояния, полное hit-testing и визуальная точность не
проверены. Размер полного движка не подтверждён и не обещается <=10 МБ.

Workflow `Compile original Telegram sources (NO APK, NO emulator)` вызывает
только Java compile и manifest processing, не assemble/bundle/install. Gradle
policy запрещает упаковку APK/AAB и device/emulator tasks. Workflow не публикует
APK, только текстовый лог компиляции. Успех JVM/syntax CI не означает успех
Android-компиляции этого модуля.

Пересоздание Activity: подтверждённые overrides проходят через Bundle и
ColorSession.committedSnapshot(); незавершённая color-транзакция в saved state
не попадает. Это пока source-реализация, поведение на устройстве не проверено.

Первый compile-only запуск выявил инъекцию host в `buildSrc`: init-скрипт Gradle
исполняется также для внутренних builds. Внесена проверка canonical settingsDir,
так что host включается только в корневой официальный Telegram build.

### Отдельные инструменты, неизменённая шапка preview
XML activity_editor теперь отделяет контейнер оригинального preview от панели
команд. Нет маркетинговой шапки/телефонной рамки/масштабирования до 360px.
Чистый режим скрывает панель и выключает editor touch interception. Back возвращает
панель, включая Android 13+ OnBackInvoked callback. Каталог/undo/redo вызываются
из host, оригинальные ActionBar больше не модифицируются EditorMenuController.
Это проверено по исходникам, но НЕ по скриншотам/устройству.

Viewport rule: the original preview is a match-parent child of a FrameLayout.
The editor toolbar is a bottom overlay, never a weighted sibling shrinking the
upstream screen. Pure mode removes that overlay without resizing the preview.
Editing mode still occludes the bottom 48dp; pixel identity is NOT asserted for
that mode or for the as-yet-unvalidated system-window insets.
