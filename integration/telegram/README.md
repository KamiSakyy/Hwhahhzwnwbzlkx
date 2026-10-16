# Интеграция настоящего Telegram UI — НЕ завершена

Официальный код: `../../vendor/telegram`, Git submodule, commit
`f32ffeec763f0bdbf4bec25b52d654f77bff733d` (13.10.2026).

`EditorThemePreviewActivity` наследует настоящий `ThemePreviewActivity` и
вызывает его `createView`, без собственной отрисовки чата. Использует официальный
`ResourcesProvider` и `ThemeDescription.setColor(..., false, false)` для
непостоянного изменения цветов. Это исходник адаптера, НЕ подключённый модуль,
НЕ проверенная компиляцией интеграция и НЕ готовое приложение.

## Проверенные зависимости и препятствия
- `ThemePreviewActivity` наследует `BaseFragment`, не Android Activity. Нельзя
  просто прописать его в manifest старого приложения.
- Настоящий экран создаёт `DialogCell`, `ChatMessageCell`, `MessageObject`/TLRPC,
  использует `ApplicationLoader`, `UserConfig`, `MessagesController`,
  `MessagesStorage`, `ImageLoader`, `NotificationCenter`, `ConnectionsManager`.
- `ChatMessageCell` зависит от множества Components, изображений, emoji,
  анимированных drawable и состояния messenger. Подменять их пустыми заглушками
  ради компиляции запрещено требованиями точности.
- Официальный Gradle-проект использует собственный build plugin, media-модули,
  AndroidX и native-библиотеки. Подключить только один Java-файл недостаточно.
- Некоторые цвета/paint/drawable глобальны. ResourcesProvider сам по себе ещё
  не гарантирует независимость проектов и пиксельное совпадение всех состояний.

## Следующие обязательные работы (ещё НЕ выполнены)
1. Подготовить standalone host и корректную инициализацию offline-preview,
   сохранив реальный Telegram render pipeline, ресурсы, шрифты и fixtures.
2. Подключить адаптер к этому host, убрать старый Canvas preview из продукта.
3. Реализовать hit-testing внутренних областей ChatMessageCell: обычный Android
   View-click НЕ различает текст, пузырь, время, reply, link и media внутри cell.
4. Подключить транзакционный RGBA/HEX, undo/redo, импорт/экспорт и photo picker.
5. Сравнить скриншоты с upstream при одинаковых density/fontScale/locale,
   теме, обоях и fixtures: чат, диалоги, клавиатура, selection и media.
6. Проверить lifecycle, поворот, восстановление, TalkBack и отсутствие запросов
   к аккаунту/сети в offline-preview. Нужны реальные проверки, не только исходник.
7. Измерить достижимость APK <=10 МБ. Полный Telegram НЕ объявляется подходящим
   под этот лимит без измерений. Нельзя урезать точность без согласования.

**Сборка APK запрещена пользователем до завершения.** Тестовую APK не создавать.

## Продолжение: host, транзакции и выбор областей
Добавлены исходники (пока без Android-компиляции/проверки на устройстве):
- `TelegramPreviewHost`: официальный INavigationLayout, проверка main thread,
  attach, resume/pause/low-memory, освобождение фрагментов. Только для уже
  инициализированного Telegram runtime. Это НЕ standalone/offline bootstrap.
- `EditorTouchLayer`: контейнер без собственной отрисовки. Координаты переводятся
  в дочерние View с учётом matrix/scroll; короткий tap отменяет исходный click,
  движение передаётся оригинальному RecyclerView. Без listener работает обычный
  preview. Визуальный порядок overlay/elevation и TalkBack пока не проверены.
- `MessageElementResolver`: реальные StaticLayout/line bounds/RTL/block offsets
  для обычного текста и ClickableSpan, реальные bounds drawable для пузыря.
  Время, reply, quote/code, caption/media, selection, реакции пока НЕ покрыты.
  Для остальных областей внутри пузыря возможен выбор его фона; это не выбор
  каждого элемента и не полное выполнение требований.
- `ColorSession` в `integration/core`: транзакции, снимки, отмена, undo/redo,
  ограничение истории, проверка thread, строгий #AARRGGBB. Адаптер использует
  сессию и штатные ThemeDescription без сохранения в глобальную тему.
- ResourcesProvider теперь возвращает штатные drawable вместо `null`.

Source-only CI компилирует/тестирует JVM-ядро и проверяет запрет APK. Он НЕ
компилирует Telegram-интеграцию, НЕ подтверждает offline-режим и НЕ заменяет
скриншотные/UI-тесты. Новый host пока не подключён к старому `app/`.

### Подключение редактирования к host
`ColorEditorController` зарегистрирован в host: выбор поддерживаемой области
сообщения открывает **штатные Telegram ColorPicker, AlertDialog, SeekBarView и
EditTextBoldCursor**. Alpha сохранён отдельно от RGB, полный HEX — #AARRGGBB;
Apply подтверждает транзакцию, Back/внешний dismiss отменяет, Default удаляет
переопределение. Это написанный исходник, не подтверждённый UI-тестами интерфейс.

`editor-sources.init.gradle` добавляет исходники интеграции и JVM-ядра в
официальный `TMessagesProj`, не изменяя подмодуль. Он требует полноценного
upstream build environment и `THEME_STUDIO_ROOT`. Сейчас НЕ запускался;
Android-компиляция, standalone entry point и проверка native/runtime впереди.
Скрипт не разрешает сборку APK и не входит в CI-проверку JVM-ядра.

### Продолжение: каталог ключей и меню редактора
Добавлены `ThemeKeyBrowser` и `EditorMenuController`. Каталог получает все
имена непосредственно из runtime `ThemeColors`, исключая служебный
`wallpaperFileOffset`, фильтрует по подстроке и показывает штатные TextColorCell
в RecyclerListView. Меню добавляется в настоящие ActionBar обеих страниц preview;
оригинальные обработчики Back и остальных пунктов сохраняются и восстанавливаются
при закрытии. Подключены undo/redo. UI на устройстве пока не проверен.

Resolver также использует caption layout и ссылки в подписи медиа. На время
`animateBackgroundBoundsInner` выбор отключён: вместо угадывания координат
дожидаемся стабильного layout. Сложные quote/code и время/reply не объявляются
поддержанными. На destroy восстанавливаются цвета общих ThemeDescription paints.

Дополнительно `gradle/no-artifact-policy.gradle` блокирует application packaging,
APK/AAB, install и device/emulator tasks. Применяется корневым проектом и
init-скриптом интеграции. Это дополнительный запрет к выключенным APK workflows,
а не разрешение на Android-сборку. Эмулятор НЕ используется.

### Корректировка внешнего вида preview
Предыдущая инъекция editor menu в ActionBar отменена: EditorMenuController теперь
только выполняет команды внешней XML-панели host. Оригинальные меню/шапки не
переписываются. ColorEditorController использует оригинальный BottomSheet вместо
AlertDialog, без dim, с фиксированной строкой действий и прокручиваемыми полями.
Чистый режим отключает editor tap-interception. При сравнении с upstream нужно
использовать одинаковый viewport, тему, density/fontSize и настройки glass mode.
Сравнение скриншотов не проводилось; 100% визуальной готовности не заявляется.
