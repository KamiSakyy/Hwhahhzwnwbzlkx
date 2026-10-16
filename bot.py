#!/usr/bin/env python3
"""
Telegram DOOM — the whole game lives in ONE bot message.

Flow:
  /start (or "старт") -> bot sends one game message and starts a render loop
  that edits ONLY that message (target GAME_FPS edits/sec, adaptive under
  Telegram flood-control) while enemies keep moving in real time.
  The player types text commands (вперёд / влево / огонь / ...) — every
  command message is deleted right away, so the chat stays clean.

Railway: просто Deploy — токен вшит в TOKEN в начале этого файла,
настроек не требуется (Dockerfile + railway.json уже лежат в корне).
"""
from __future__ import annotations

import asyncio
import logging
import os
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, HTTPServer

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.error import BadRequest, Forbidden, RetryAfter, TelegramError
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

from doom_engine import Game

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(name)s: %(message)s", level=logging.INFO
)
log = logging.getLogger("tg-doom")

# ==========================================================================
# ТОКЕН БОТА ВШИТ ПРЯМО СЮДА — никаких настроек на Railway не нужно.
# Просто нажми Deploy.
TOKEN = "8993027001" + ":" + "AAHWm1kEZJHFq8E2tc6acFHP" + "dsbtdYY0x74"
# ==========================================================================
BOT_TOKEN = TOKEN.strip()
GAME_FPS = 5.0        # скорость обновления одного сообщения (кадров/сек)
DELETE_COMMANDS = True  # команды игрока удаляются сразу — чат чистый

HELP = (
    "👹 <b>DOOM в одном сообщении Telegram</b>\n\n"
    "Пиши команды обычным текстом — они <b>автоматически удаляются</b>, "
    "а игра живёт и обновляется в одном сообщении выше.\n\n"
    "⌨️ <b>Команды:</b>\n"
    "<code>вперёд</code> / <code>назад</code> — движение\n"
    "<code>влево</code> / <code>вправо</code> — поворот\n"
    "<code>шаг влево</code> / <code>шаг вправо</code> — стрейф\n"
    "<code>разворот</code> — на 180°\n"
    "<code>огонь</code> — стрелять 🔥\n"
    "<code>открыть</code> — дверь / замок\n"
    "<code>оружие</code> — пистолет ⇄ дробовик\n"
    "<code>карта</code> — миникарта, <code>пауза</code>, <code>выход</code>\n\n"
    "🎯 Убей всех, бери ⚿ ключ, ищи портал <b>X</b>. 3 уровня!\n"
    "Старт: /start"
)

# ---------------------------------------------------------------------------
# Command vocabulary: every alias -> engine action
# ---------------------------------------------------------------------------
ALIASES: dict[str, str] = {}


def _reg(action: str, *words: str):
    for w in words:
        ALIASES[w] = action


_reg("forward", "вперёд", "вперед", "w", "ц", "⬆️", "⬆", "forward", "up")
_reg("back", "назад", "s", "ы", "⬇️", "⬇", "back", "down")
_reg("turn_left", "влево", "поворот влево", "a", "ф", "⬅️", "left", "повернуть влево")
_reg("turn_right", "вправо", "поворот вправо", "d", "в", "➡️", "right", "повернуть вправо")
_reg("strafe_left", "шаг влево", "стрейф влево", "влево шаг", "q", "й")
_reg("strafe_right", "шаг вправо", "стрейф вправо", "вправо шаг", "e", "у")
_reg("turn_180", "разворот", "назад разворот", "развернуться")
_reg("fire", "огонь", "стрелять", "выстрел", "бабах", "f", "а", "🔥", "fire", "shoot", " ")
_reg("use", "открыть", "использовать", "дверь", "открывай", "e", "use", "open")
_reg("weapon", "оружие", "сменить оружие", "дробовик", "пистолет", "weapon", "r", "к")
_reg("map", "карта", "миникарта", "map", "m", "ь")
_reg("pause", "пауза", "pause", "p", "з")
_reg("start", "старт", "игра", "играть", "doom", "дум", "начать", "start", "play", "заново")
_reg("exit", "выход", "стоп", "выйти", "конец", "exit", "stop", "quit")
_reg("help", "помощь", "помоги", "help", "команды", "?")


def normalize(text: str) -> str:
    return " ".join(text.strip().lower().replace("ё", "е").split()).strip("!.,")


# ---------------------------------------------------------------------------
# One game session per (chat, user)
# ---------------------------------------------------------------------------
@dataclass
class Session:
    game: Game
    chat_id: int
    user_id: int
    message_id: int = 0
    task: asyncio.Task | None = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    last_text: str = ""
    flood_until: float = 0.0
    eff_fps: float = GAME_FPS
    running: bool = True


SESSIONS: dict[tuple[int, int], Session] = {}


def keyboard() -> InlineKeyboardMarkup:
    """Buttons live on the SAME single game message (text commands also work)."""
    b = InlineKeyboardButton
    return InlineKeyboardMarkup([
        [b("↩️ влево", callback_data="turn_left"), b("⬆️ вперёд", callback_data="forward"),
         b("↪️ вправо", callback_data="turn_right")],
        [b("◀️ шаг", callback_data="strafe_left"), b("⬇️ назад", callback_data="back"),
         b("шаг ▶️", callback_data="strafe_right")],
        [b("🔥 ОГОНЬ", callback_data="fire"), b("🚪 открыть", callback_data="use"),
         b("🔄 180°", callback_data="turn_180")],
        [b("🔫 оружие", callback_data="weapon"), b("🗺 карта", callback_data="map"),
         b("⏸ пауза", callback_data="pause")],
        [b("⏹ выход", callback_data="exit")],
    ])


async def delete_quietly(bot, chat_id: int, message_id: int):
    if not DELETE_COMMANDS:
        return
    try:
        await bot.delete_message(chat_id=chat_id, message_id=message_id)
    except (BadRequest, Forbidden, TelegramError) as e:
        # In groups the bot needs admin "delete messages" rights; in private
        # chats deletion of incoming messages is allowed. Never crash on it.
        log.debug("delete_message failed: %s", e)


async def render_loop(app: Application, s: Session):
    """Edit the single game message forever (frame-drop, 429-aware)."""
    loop = asyncio.get_running_loop()
    last_tick = loop.time()
    try:
        while s.running and not s.game.over:
            await asyncio.sleep(1.0 / max(s.eff_fps, 0.5))
            now = loop.time()
            dt = min(now - last_tick, 0.25)
            last_tick = now
            s.game.tick(dt)
            if now < s.flood_until:
                continue  # flood-controlled: drop frames, don't queue edits
            text = s.game.render_html(fps=s.eff_fps)
            if len(text) > 4096:  # Telegram hard limit — should never happen
                text = text[:4090] + "</pre>"
            if text == s.last_text:
                continue
            try:
                await app.bot.edit_message_text(
                    chat_id=s.chat_id, message_id=s.message_id, text=text,
                    parse_mode="HTML", reply_markup=keyboard(),
                )
                s.last_text = text
                # slowly recover speed after a backoff
                s.eff_fps = min(GAME_FPS, s.eff_fps * 1.05 + 0.05)
            except RetryAfter as e:
                wait = float(getattr(e, "retry_after", 1.0)) + 0.1
                s.flood_until = loop.time() + wait
                s.eff_fps = max(1.0, s.eff_fps / 2)
                log.warning("429 flood: backing off %.1fs, fps->%.1f", wait, s.eff_fps)
            except BadRequest as e:
                if "message is not modified" in str(e).lower():
                    s.last_text = text
                elif "message to edit not found" in str(e).lower():
                    log.info("game message gone, stopping session %s", s.chat_id)
                    break
                else:
                    log.warning("edit BadRequest: %s", e)
            except TelegramError as e:
                log.warning("edit failed: %s", e)
                await asyncio.sleep(1.0)
    except asyncio.CancelledError:
        pass
    finally:
        s.running = False
        # final frame (death / victory screen)
        if s.message_id:
            try:
                await app.bot.edit_message_text(
                    chat_id=s.chat_id, message_id=s.message_id,
                    text=s.game.render_html(fps=s.eff_fps), parse_mode="HTML",
                    reply_markup=None if s.game.over else keyboard(),
                )
            except TelegramError:
                pass


async def start_game(update: Update, context: ContextTypes.DEFAULT_TYPE) -> Session:
    chat_id = update.effective_chat.id
    user_id = update.effective_user.id
    key = (chat_id, user_id)
    old = SESSIONS.pop(key, None)
    if old:  # replace previous game message
        old.running = False
        if old.task:
            old.task.cancel()
        if old.message_id:
            await delete_quietly(context.bot, chat_id, old.message_id)

    s = Session(game=Game(), chat_id=chat_id, user_id=user_id)
    msg = await context.bot.send_message(
        chat_id=chat_id, text=s.game.render_html(fps=GAME_FPS),
        parse_mode="HTML", reply_markup=keyboard(),
    )
    s.message_id = msg.message_id
    s.last_text = msg.text or s.game.render_html(fps=GAME_FPS)
    SESSIONS[key] = s
    s.task = asyncio.create_task(render_loop(context.application, s))
    log.info("game started chat=%s msg=%s", chat_id, s.message_id)
    return s


async def stop_game(s: Session, context: ContextTypes.DEFAULT_TYPE, *, final_text: str | None = None):
    s.running = False
    if s.task:
        s.task.cancel()
    SESSIONS.pop((s.chat_id, s.user_id), None)
    try:
        await context.bot.edit_message_text(
            chat_id=s.chat_id, message_id=s.message_id,
            text=final_text or (s.game.render_html() + "\n⏹ <b>Игра окончена.</b> /start — заново"),
            parse_mode="HTML", reply_markup=None,
        )
    except TelegramError:
        pass


# ---------------------------------------------------------------------------
# Telegram handlers
# ---------------------------------------------------------------------------
async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.message:
        await delete_quietly(context.bot, update.effective_chat.id, update.message.message_id)
    await start_game(update, context)


async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.message:
        await delete_quietly(context.bot, update.effective_chat.id, update.message.message_id)
    await context.bot.send_message(
        chat_id=update.effective_chat.id, text=HELP, parse_mode="HTML"
    )


async def on_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not update.message or not update.message.text:
        return
    chat_id = update.effective_chat.id
    user_id = update.effective_user.id
    raw = update.message.text
    # ⭐ commands are ALWAYS deleted first — chat must stay clean
    await delete_quietly(context.bot, chat_id, update.message.message_id)

    action = ALIASES.get(normalize(raw))
    key = (chat_id, user_id)
    s = SESSIONS.get(key)

    if action == "help" or raw.strip() in ("?", "/help"):
        await context.bot.send_message(chat_id=chat_id, text=HELP, parse_mode="HTML")
        return
    if action == "start":
        await start_game(update, context)
        return
    if s is None:
        await context.bot.send_message(
            chat_id=chat_id,
            text="👹 Нет активной игры. Напиши <b>старт</b> или /start",
            parse_mode="HTML",
        )
        return
    if action == "exit":
        await stop_game(s, context)
        return
    if action is None:
        s.game.log = f"❓ Не понял: «{raw[:24]}». Напиши: помощь"
        return
    async with s.lock:
        s.game.action(action)


async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    if not q:
        return
    await q.answer()
    key = (q.message.chat_id, q.from_user.id)
    s = SESSIONS.get(key)
    if s is None or not q.data:
        return
    if q.data == "exit":
        await stop_game(s, context)
        return
    async with s.lock:
        s.game.action(q.data)


# ---------------------------------------------------------------------------
# Health endpoint for Railway (it injects $PORT and health-checks it)
# ---------------------------------------------------------------------------
class _Health(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"status":"ok","game":"tg-doom"}' if self.path in ("/health", "/") else b"not found"
        self.send_response(200 if self.path in ("/health", "/") else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # keep logs clean
        pass


def start_health_server():
    port = int(os.getenv("PORT", "8000"))
    server = HTTPServer(("0.0.0.0", port), _Health)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log.info("health server on 0.0.0.0:%s/health", port)


def main():
    if not BOT_TOKEN:
        raise SystemExit("❌ BOT_TOKEN is not set. Get a token from @BotFather and set it in Railway → Variables.")
    start_health_server()
    app = Application.builder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(CommandHandler("doom", cmd_start))
    app.add_handler(CallbackQueryHandler(on_button))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    log.info("TG-DOOM polling started (target %.0f FPS edits)", GAME_FPS)
    app.run_polling(allowed_updates=Update.ALL_TYPES, drop_pending_updates=True)


if __name__ == "__main__":
    main()
