# Формат темы Telegram Android
Проверено по официальному исходному коду DrKLO/Telegram, commit
`f32ffeec763f0bdbf4bec25b52d654f77bff733d` (13 октября 2026).

Первичные источники (не документация Telegram Desktop):
- [Загрузчик и сохранение темы: Theme.java](https://github.com/DrKLO/Telegram/blob/f32ffeec763f0bdbf4bec25b52d654f77bff733d/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java)
  — методы `getThemeFileValues`, `saveCurrentTheme`.
- [Официальные ключи и значения](https://github.com/DrKLO/Telegram/blob/f32ffeec763f0bdbf4bec25b52d654f77bff733d/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java).
- [Официальный предпросмотр](https://github.com/DrKLO/Telegram/blob/f32ffeec763f0bdbf4bec25b52d654f77bff733d/TMessagesProj/src/main/java/org/telegram/ui/ThemePreviewActivity.java).

`.attheme` — UTF-8 строки `ключ=значение\n`. Цвет — signed decimal int
ARGB (например, -1 = #FFFFFFFF). Загрузчик Telegram также принимает HEX.
Редактор вводит HEX как #AARRGGBB и экспортирует decimal.
A=0 прозрачно, A=255 непрозрачно; результат композиции зависит от элемента.
Незаписанные ключи используют стандартную палитру клиента.

`WLS=` задаёт ссылку обоев. Встроенное изображение начинается после `WPS\n`,
завершается `\nWPE\n`; содержимое изображения — бинарное, не текст.
Импорт сохраняет неизвестные числовые ключи и ссылку; сетевые обои не загружает.
Выбранные фото уменьшаются до максимум 2048px по стороне и кодируются в JPEG.
Предел входного файла 16 МиБ. Не все градиенты, паттерны, анимации, реакции,
состояния выделения и специальные ключи представлены в облегчённом preview.
Редактирование всех известных стандартных цветов доступно через «Все ключи».

Просмотр → нажатие → RGBA/HEX → Применить. Файл → Экспортировать тему.
Отправьте файл в Избранное Telegram и откройте для применения. Проверяйте тему
в официальном клиенте: результат зависит от его версии, фона и настроек.
