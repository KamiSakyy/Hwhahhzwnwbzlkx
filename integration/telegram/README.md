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
