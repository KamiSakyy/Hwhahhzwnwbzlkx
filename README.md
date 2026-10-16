# 🌸 Tsuyu — мессенджер на СВОЁМ сервере (FastAPI)

**Твой сервер. Твои данные. Сквозное шифрование Signal. Никакого Firebase — вообще.**

Tsuyu v1.51 (дизайн и приложение — точь-в-точь оригинал) полностью переведён
на собственный FastAPI-сервер: регистрация и вход, профили и поиск,
публичные ключи Signal, сообщения, присутствие/«печатает», квитанции о
прочтении, реакции, опросы, сигнализация звонков и фоновая синхронизация —
всё идёт только через твой сервер.

- 📱 Android-приложение (Java + XML, Material, тёмная тема)
- 🔐 **E2EE**: настоящий Signal Protocol (libsignal: PQXDH + Double Ratchet).
  Сервер хранит **только шифротекст** — plaintext он отклоняет (HTTP 400)
- 📷 Фото, 🎬 видео, ⭕ кружки, 🎤 голосовые — медиа шифруются AES-256-GCM на устройстве
- 📞 P2P-звонки (свой UDP/STUN/AES-GCM), сигнализация — через твой сервер
- 🏠 **Self-hosted**: при первом запуске приложение спрашивает адрес ТВОЕГО сервера
- ⚖️ APK ≈ 7,9 МБ (minify + ProGuard/R8 + strip нативных библиотек), лимит 10 МБ в CI
- 🚫 **Firebase вырезан полностью**: нет SDK, нет Auth/RTDB/FCM — в CI стоит
  проверка, которая роняет сборку при любом упоминании Firebase в коде

## 📥 Скачать APK

👉 **https://github.com/KamiSakyy/Hwhahhzwnwbzlkx/releases/tag/apk-latest** → файл **`Tsuyu.apk`**
(собирается GitHub Actions при каждом пуше: `.github/workflows/build-apk.yml`)

Установи APK → введи адрес своего сервера (ниже) → регистрация → настрой профиль → пользуйся.

## 🚀 Сервер на Railway

Railway → **New Project** → **Deploy from GitHub repo** → этот репозиторий → **Deploy**.
В корне лежит `Dockerfile` — Railway находит и использует его сам, без railway.json.
Потом в Railway: **Settings → Networking → Generate Domain** — это адрес сервера
для приложения.

### ⚠️ ЖЁСТКОЕ УСЛОВИЕ ПРО БАЗУ (так задумано!)

**Все данные лежат строго в `/app/messenger.db` внутри контейнера.**
Никаких внешних баз. При редеплое Railway без тома база создаётся заново
пустой. Сервер при старте проверяет файл и **не запустится, если
`messenger.db` не создан**. Это обязательное условие архитектуры.

## 🏗 Структура

- `server.py` — сервер FastAPI: `/api/auth/*`, `/api/profile`, `/api/search`,
  `/api/keys/{uid}`, `/api/messages/*`, `/api/dialogs`, `/api/presence`,
  `/api/typing`, `/api/receipts`, `/api/reactions`, `/api/polls/vote`,
  `/api/chats/{id}/state`, `/api/calls/*` (включая узел сигналинга P2P-звонков),
  `/api/upload`, `/api/files/{id}`, WebSocket `/ws`
- `app/` — Android-приложение (`com.tsuyu.messenger`, дизайн и экраны оригинала v1.51)
  - транспорт: только `net/TsuyuServerClient.java` → твой FastAPI
  - фоновые уведомления: поллинг-сервис `TsuyuSyncService` (без внешних пушей)
- `tools/prepare_release_apk.py` — strip `.so`, один ABI (arm64-v8a), подпись, контроль ≤10 МБ
- `signing/` — постоянный release-ключ Tsuyu (подпись обновлений совместима с оригиналом)
