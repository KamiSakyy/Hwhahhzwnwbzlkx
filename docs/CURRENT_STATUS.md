# Текущее состояние — НЕ готовый продукт

## Ограничения пользователя
- Никаких эмуляторов.
- Никаких новых APK до завершения проекта.
- Java/XML, настоящий код DrKLO/Telegram, не Canvas-имитация.
- Дальнейшая release-сборка только GitHub Actions; arm64, <=10 МБ, без Secrets.

## Реализовано в исходниках новой интеграции
- Закреплённый официальный подмодуль DrKLO/Telegram от 13.10.2026.
- Наследник официального ThemePreviewActivity, официальный render pipeline.
- Host официального INavigationLayout в уже инициализированном Telegram runtime.
- Выбор тела сообщения/ссылки/подписи медиа и фона пузыря по данным cell/layout.
- Официальный ColorPicker + alpha + AARRGGBB; Apply/Cancel/Default.
- Поиск по runtime-реестру ключей через Telegram RecyclerListView/TextColorCell.
- Инструменты редактора в обеих настоящих ActionBar; сохранение оригинальных
  callbacks, undo/redo, снятие добавленных пунктов при закрытии.
- Закрытие редакторских диалогов при уничтожении preview, восстановление общих
  paints, отключение выбора во время изменения размеров пузыря.
- Source/JVM CI без Android-устройств. Ранее ядро прошло 30 JVM assertions.
- Заблокированы APK workflows и packaging/device задачи Gradle.

## НЕ выполнено / НЕ подтверждено
- Старый `app/` НЕ является новым entry point. Добавлен отдельный upstream
  `:ThemeStudioHost` с EditorActivity и XML-контейнером; компиляция ещё не подтверждена.
- Standalone offline bootstrap без аккаунта и побочных сетевых действий.
- Компиляция Android-интеграции с полной upstream dependency graph.
- Выбор каждого элемента: timestamp, reply/quote/code, реакции, media-controls,
  action bar, service cells и поля ввода пока не охвачены точным hit-testing.
- Импорт/экспорт/фотообои старого прототипа ещё не перенесены в новый host.
- Восстановление проекта/поворот и полный жизненный цикл на устройстве.
- Проверка визуальной идентичности, удобства, TalkBack на физическом устройстве.
- Проверенный размер новой реализации и безопасная production-подпись.

Файлы `integration/telegram` нельзя считать работающим готовым приложением
только потому, что исходники написаны. JVM-проверки не проверяют Android UI.
Исторический APK Handoff относится исключительно к отклонённому прототипу.

## Аудит upstream build graph
`python scripts/audit_telegram_preview.py` выполняет только чтение исходников.
Отчёт: `docs/UPSTREAM_DEPENDENCIES.json`. Зафиксированная upstream-версия 13.0.2,
AGP 8.13.2, compileSdk/targetSdk 36. Root старого прототипа не является новой
upstream-конфигурацией. В явном import-графе достижим 1411 source-файл; это нижняя
граница без same-package/reflection/native/resource зависимостей и НЕ оценка APK.
`R`, `BuildConfig` и `LocalizationUtils` генерируются официальной сборкой.

В source-only CI добавлен JDK-парсер синтаксиса integration Java с source=8.
Он не разрешает Android-символы и не заменяет компиляцию/проверку поведения.
Эмулятор, APK, AAB, Android runtime не запускаются.

## Полное развёртывание исходников
Sparse checkout снят. Развёрнуты все 19 вложенных подмодулей на SHA из upstream;
`docs/TELEGRAM_CHECKOUT.json` содержит их перечень. Оригинальный worktree и
зависимости занимают около 2.2 ГБ в этой среде; это НЕ размер APK. Bootstrap
воспроизводится скриптом без сборки. Git-подмодуль хранит ссылку на весь upstream,
а не копирует гигабайты исходников в diff нашего приложения.

Экспериментальный Android-host теперь включается в upstream settings init-скриптом.
Отдельный compile-only workflow проверяет настоящий Java build graph и merged
manifest; никакого эмулятора, assemble, bundle, install или публикации APK.
