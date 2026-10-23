# CutPro — видеоредактор для Android (Java + XML)

Полноценный монтаж видео на телефоне в стиле CapCut, но **без Kotlin**: только Java,
обычные XML-разметки и официальные библиотеки **AndroidX Media3 1.5.1**
(`media3-exoplayer`, `media3-ui`, `media3-transformer`, `media3-effect`).

Приложение полностью локальное: ни аккаунтов, ни загрузки в облако, ни интернета.

## Возможности

**Импорт и монтаж**
- Добавление нескольких видео сразу из системного проводника (SAF, доступ сохраняется
  после перезапуска).
- Лента клипов с миниатюрами, цветами, подсветкой выбранного фрагмента и таймлайном:
  тап по ленте — переход к моменту, перетаскивание — прокрутка, авто-скролл за плейхедом.
- **Обрезка** (тримминг) с двумя ползунками и живым предпросмотром кадра.
- **Разрезание** клипа по текущей позиции (split) с корректным переносом текстовых слоёв.
- Перемещение клипа влево/вправо, дублирование, удаление.
- **Скорость** 0.25× / 0.5× / 0.75× / 1× / 1.25× / 1.5× / 2× / 3× / 4× — в экспорте
  применяется настоящий `SpeedChangeEffect` Media3 (меняется длительность, звук
  пересчитывается через `SpeedChangingAudioProcessor`).
- **Громкость** каждого клипа 0–100 % и громкость музыкальной дорожки.

**Изображение**
- 8 цветовых фильтров: оригинал, ч/б, негатив, тёплый, холодный, сочный, ночь, кино
  (`RgbFilter`, `RgbAdjustment`).
- Форматы кадра: 9:16 (Reels/TikTok), 16:9 (YouTube), 1:1, 4:5 — превью и экспорт
  используют выбранное соотношение.
- Превью в `PlayerView` (ExoPlayer) с эффектами, наложенными на лету.

**Текст**
- Текстовые слои поверх видео: содержимое, 6 цветов, размер, позиция (сверху/центр/снизу),
  время показа в пределах клипа.
- В превью текст — настоящие `TextView` (долгое нажатие — удалить), в экспорте —
  `BitmapOverlay` + `OverlayEffect`: то, что видно, попадает в файл.

**Музыка**
- Отдельная аудиодорожка из проводника, зацикливается на всю длину ролика,
  своя громкость, смешивание с оригинальным звуком (`Composition` из двух `EditedMediaItemSequence`).

**Экспорт**
- Рендер через **Media3 Transformer** в H.264/AAC MP4: 720p или 1080p,
  битрейт 7 / 12 Мбит/с, прогресс в реальном времени, отмена.
- Готовый файл автоматически публикуется в Галерею: `Movies/CutPro`
  (MediaStore + `IS_PENDING`), после чего его можно сразу открыть плеером.
- Работа сохраняется автоматически: список проектов с обложкой, длительностью и форматом,
  переименование и удаление долгим нажатием.

## Структура

```
app/src/main/java/com/kamisakyy/cutpro/
  MainActivity.java        — список проектов: создать, открыть, переименовать, удалить
  EditorActivity.java      — монтаж: превью, таймлайн, все инструменты, экспорт
  VideoExporter.java       — сборка Composition + Transformer, прогресс, отмена
  TimelineView.java        — кастомная лента клипов: миниатюры, скролл, плейхед
  AspectFrameLayout.java   — превью с точным соотношением сторон
  Project.java             — модель проекта/клипа/текста и JSON
  ProjectStore.java        — хранение проектов в приватной папке приложения
  FilterPresets.java       — общие цветовые эффекты для превью и экспорта
  GainAudioProcessor.java  — усиление/ослабление PCM-звука (громкость клипов и музыки)
  MediaSaver.java          — сохранение результата в Галерею через MediaStore
app/src/main/res/
  layout/  activity_main, activity_editor, item_project,
           dialog_trim, dialog_volume, dialog_text, dialog_export, dialog_progress
  drawable/ фоны, чипы цветов, иконка
  values/  цвета, стили, строки
.github/workflows/build-apk.yml — сборка подписанного APK и публикация релиза
signing/                         — демонстрационный ключ подписи для CI
```

## Сборка

Локально: Android Studio (AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk 35, minSdk 29) →
`./gradlew :app:assembleRelease`.

Через GitHub Actions: workflow **Build APK** запускается на каждый push
(`Actions → Build APK → Run workflow` тоже работает). Собранный APK публикуется
в релиз **latest-apk**:

```
https://github.com/KamiSakyy/Hwhahhzwnwbzlkx/releases/download/latest-apk/CutPro-latest.apk
```

Подпись: демонстрационный ключ из `signing/` (SHA-256 в `signing/CERT-SHA256.txt`),
поэтому обновления ставятся поверх без удаления приложения. Для настоящего релиза
задайте секреты `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Что внутри Media3

- `Composition` из основного `EditedMediaItemSequence` (клипы) и зацикленной аудио-дорожки.
- Для каждого клипа: `ClippingConfiguration` (трим), `Presentation` (формат кадра),
  цветовые эффекты, `Effects.createExperimentalSpeedChangingEffect` и `GainAudioProcessor`.
- Текстовые слои — эффекты уровня `Composition`, поэтому тайминги считаются по общей
  шкале ролика, а не по отдельному клипу.
- `Transformer` с `DefaultEncoderFactory` и настройками битрейта; прогресс через
  `getProgress(ProgressHolder)`.
