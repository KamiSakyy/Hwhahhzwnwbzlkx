#!/usr/bin/env python3
"""
✉️ Мессенджер «АРИ» — полноценный мессенджер в ОДНОМ сообщении Telegram.

ФИШКА: бот отправляет пользователю ровно ОДНО сообщение-экран и дальше
только редактирует его. Новых сообщений бот не создаёт никогда.
Входящие от собеседника сами перерисовывают твой экран.

Управление — обычным текстом (каждое твоё сообщение сразу удаляется):
  регистрация -> почта -> пароль -> @юзернейм -> войти -> сообщения
  поиск -> @юзернейм -> диалог -> просто пишешь текст = отправить

Railway: просто Deploy. Токен вшит ниже, настроек нет.
"""
from __future__ import annotations

import asyncio
import logging
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

from telegram import Update
from telegram.error import BadRequest, Forbidden, TelegramError
from telegram.ext import Application, CommandHandler, ContextTypes, MessageHandler, filters

import messenger as ms

logging.basicConfig(format="%(asctime)s %(levelname)s %(name)s: %(message)s", level=logging.INFO)
log = logging.getLogger("ari-messenger")

# ==========================================================================
# ТОКЕН БОТА ВШИТ ПРЯМО СЮДА — никаких настроек на Railway не нужно.
TOKEN = "8993027001" + ":" + "AAHWm1kEZJHFq8E2tc6acFHP" + "dsbtdYY0x74"
# ==========================================================================
BOT_TOKEN = TOKEN.strip()

BACK_WORDS = {"назад", "back", "меню", "чаты"}


def norm(text: str) -> str:
    return " ".join(text.strip().lower().replace("ё", "е").split())


# ---------------------------------------------------------------------------
# The ONE screen: send once, then only edit. Never send new messages.
# ---------------------------------------------------------------------------
async def show(bot, tg_id: int, html_text: str):
    """Render `html_text` into the user's single screen message."""
    mid = ms.get_screen(tg_id)
    if mid is not None:
        try:
            await bot.edit_message_text(
                chat_id=tg_id, message_id=mid, text=html_text, parse_mode="HTML"
            )
            return
        except BadRequest as e:
            s = str(e).lower()
            if "message is not modified" in s:
                return
            if "message to edit not found" not in s and "message_id_invalid" not in s:
                log.warning("edit failed tg=%s: %s", tg_id, e)
                return
            ms.drop_screen(tg_id)  # user deleted the screen; recreate once below
        except (Forbidden, TelegramError) as e:
            log.warning("edit failed tg=%s: %s", tg_id, e)
            return
    # First contact ever (or the screen was deleted): create THE one message.
    try:
        msg = await bot.send_message(chat_id=tg_id, text=html_text, parse_mode="HTML")
        ms.set_screen(tg_id, msg.message_id)
    except TelegramError as e:
        log.warning("send screen failed tg=%s: %s", tg_id, e)


async def delete_quietly(bot, chat_id: int, message_id: int):
    """Every user message is deleted instantly — chat keeps only the screen."""
    try:
        await bot.delete_message(chat_id=chat_id, message_id=message_id)
    except (BadRequest, Forbidden, TelegramError):
        pass


async def push_to(tg_id: int, bot):
    """Re-render a user's screen after something changed (e.g. new message).

    Only edits the existing screen — if there is no screen, does nothing.
    NEVER creates a new message.
    """
    if ms.get_screen(tg_id) is None:
        return
    s = ms.get_session(tg_id)
    await show(bot, tg_id, render_current(tg_id, s))


def render_current(tg_id: int, s: dict, notice: str = "") -> str:
    user = ms.get_user(s["user_id"]) if s.get("user_id") else None
    state = s.get("state", "auth")
    if user is None:
        return ms.scr_auth(notice)
    if state == "chat" and s.get("current_chat"):
        other = ms.get_user(s["current_chat"])
        if other:
            return ms.scr_chat(user, other, notice)
    if state == "search":
        return ms.scr_search(user, s["temp"].get("_results"), s["temp"].get("_query", ""), notice)
    if state == "profile":
        return ms.scr_profile(user)
    return ms.scr_main(user, notice)


# ---------------------------------------------------------------------------
# Routing: every text the user types goes here
# ---------------------------------------------------------------------------
async def route(update: Update, context: ContextTypes.DEFAULT_TYPE, raw_text: str):
    bot = context.bot
    tg_id = update.effective_user.id
    text = norm(raw_text)
    s = ms.get_session(tg_id)
    user = ms.get_user(s["user_id"]) if s.get("user_id") else None
    state = s.get("state", "auth")

    # ---- global: logged-in shortcuts -------------------------------------
    if user and text in ("выйти", "выход", "logout"):
        ms.set_session(tg_id, user_id=None, state="auth", current_chat=None, temp={})
        await show(bot, tg_id, ms.scr_auth("Ты вышел из аккаунта. До встречи! 👋"))
        return

    # ---- not logged in ----------------------------------------------------
    if user is None:
        await route_auth(update, s, text, raw_text.strip())
        return

    # ---- logged in ---------------------------------------------------------
    if state == "search":
        if text in BACK_WORDS:
            ms.set_session(tg_id, state="main", temp={})
            await show(bot, tg_id, ms.scr_main(user))
            return
        if text.startswith("@") or ms.find_by_username(text):
            await open_chat(tg_id, bot, user, text)
            return
        results = ms.search_users(text, exclude_id=user["id"])
        temp = dict(s["temp"], _results=[{"username": u["username"]} for u in results], _query=text)
        ms.set_session(tg_id, temp=temp)
        await show(bot, tg_id, ms.scr_search(user, results, text))
        return

    if state == "chat" and s.get("current_chat"):
        other = ms.get_user(s["current_chat"])
        if other is None or text in BACK_WORDS:
            ms.set_session(tg_id, state="main", current_chat=None)
            await show(bot, tg_id, ms.scr_main(user))
            return
        if text == "поиск":
            ms.set_session(tg_id, state="search", current_chat=None, temp={})
            await show(bot, tg_id, ms.scr_search(user))
            return
        # ANY other text = a message to the partner ✉️
        ms.send_message(user["id"], other["id"], raw_text.strip())
        # my screen: updated history
        await show(bot, tg_id, ms.scr_chat(user, other))
        # partner's screen: auto-edit in real time (no new message!)
        partner = other
        if partner:
            ps = ms.get_session(partner["tg_id"])
            if ps.get("user_id") == partner["id"] and ps.get("state") == "chat" \
                    and ps.get("current_chat") == user["id"]:
                ms.mark_read(partner["id"], user["id"])  # they're looking at it
            await push_to(partner["tg_id"], bot)
        return

    if state == "profile":
        if text in BACK_WORDS:
            ms.set_session(tg_id, state="main")
            await show(bot, tg_id, ms.scr_main(user))
            return
        await show(bot, tg_id, ms.scr_profile(user))
        return

    # state == main
    if text == "поиск" or text == "search":
        ms.set_session(tg_id, state="search", temp={})
        await show(bot, tg_id, ms.scr_search(user))
    elif text == "профиль":
        ms.set_session(tg_id, state="profile")
        await show(bot, tg_id, ms.scr_profile(user))
    elif text.startswith("@") or (ms.find_by_username(text) and " " not in text):
        await open_chat(tg_id, bot, user, text)
    else:
        await show(bot, tg_id, render_current(tg_id, s, notice="Не понял 🤔 Напиши: поиск — или сразу @юзернейм"))


async def open_chat(tg_id: int, bot, user: dict, name: str):
    other = ms.find_by_username(name)
    if other is None:
        s = ms.get_session(tg_id)
        await show(bot, tg_id, render_current(tg_id, s, notice=f"Пользователь {name} не найден. Напиши: поиск"))
        return
    if other["id"] == user["id"]:
        s = ms.get_session(tg_id)
        await show(bot, tg_id, render_current(tg_id, s, notice="Это ты сам 😄 Напиши: поиск"))
        return
    ms.mark_read(user["id"], other["id"])
    ms.set_session(tg_id, state="chat", current_chat=other["id"], temp={})
    await show(bot, tg_id, ms.scr_chat(user, other))


async def route_auth(update: Update, s: dict, text: str, raw: str):
    bot = update.get_bot()
    tg_id = update.effective_user.id
    state = s.get("state", "auth")
    temp = dict(s.get("temp") or {})

    def form(title, lines, hint, notice=""):
        return ms.scr_form(title, lines, hint, notice)

    if text in ("старт", "/start", "start"):
        ms.set_session(tg_id, state="auth", temp={})
        await show(bot, tg_id, ms.scr_auth())
        return

    if state == "registered":  # account just created, waiting for «войти»
        if text in ("войти", "вход", "login", "2"):
            me = ms.get_user_by_tg(tg_id)
            if me:
                ms.set_session(tg_id, user_id=me["id"], state="main", temp={})
                await show(bot, tg_id, ms.scr_main(me, notice=f"С возвращением... то есть — добро пожаловать, @{me['username']}! 🎉"))
                return
        await show(bot, tg_id, form("ВХОД", ["Аккаунт уже создан ✅"], "Напиши: войти"))
        return

    if state in ("reg_email", "reg_pass", "reg_user") and text in ("назад", "отмена"):
        ms.set_session(tg_id, state="auth", temp={})
        await show(bot, tg_id, ms.scr_auth())
        return

    if state == "auth":
        existing = ms.get_user_by_tg(tg_id)
        if text in ("регистрация", "register", "1"):
            if existing:
                await show(bot, tg_id, ms.scr_auth(f"У тебя уже есть аккаунт @{existing['username']}. Напиши: войти"))
                return
            ms.set_session(tg_id, state="reg_email", temp={})
            await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 1 из 3", ["📧 Почта: —"], "Напиши свою почту, например " + "name" + "@" + "example.com"))
        elif text in ("войти", "вход", "login", "2"):
            ms.set_session(tg_id, state="login_email", temp={})
            await show(bot, tg_id, form("ВХОД · шаг 1 из 2", ["📧 Почта: —"], "Напиши почту от аккаунта"))
        else:
            await show(bot, tg_id, ms.scr_auth("Напиши: регистрация — или: войти"))
        return

    if state == "reg_email":
        if "@" not in raw or "." not in raw:
            await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 1 из 3", ["📧 Почта: —"], "Напиши почту", "Почта выглядит неверно, попробуй ещё раз"))
            return
        temp["email"] = raw.lower()
        ms.set_session(tg_id, state="reg_pass", temp=temp)
        await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 2 из 3",
                                     [f"📧 Почта: {raw.lower()}", "🔑 Пароль: —"],
                                     "Придумай пароль (минимум 4 символа). Он сразу удалится из чата и будет зашифрован"))
        return

    if state == "reg_pass":
        if len(raw) < 4:
            await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 2 из 3",
                                         [f"📧 Почта: {temp.get('email','')}", "🔑 Пароль: —"],
                                         "Придумай пароль", "Слишком короткий — минимум 4 символа"))
            return
        temp["password"] = raw  # kept only in RAM of this step, hashed on create, then dropped
        ms.set_session(tg_id, state="reg_user", temp=temp)
        await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 3 из 3",
                                     [f"📧 Почта: {temp.get('email','')}", "🔑 Пароль: ••••••••", "👤 Юзер: —"],
                                     "Придумай @юзернейм (латиница, цифры, _), например ivan_1"))
        return

    if state == "reg_user":
        user, err = ms.create_user_hashed(tg_id, temp.get("email", ""),
                                          temp.pop("pass_hash", ""), temp.pop("salt", ""), raw)
        ms.set_session(tg_id, state="registered" if user else "reg_user", temp=temp)
        if user:
            await show(bot, tg_id, ms.scr_registered(user))
        else:
            await show(bot, tg_id, form("РЕГИСТРАЦИЯ · шаг 3 из 3",
                                         [f"📧 Почта: {temp.get('email','')}", "🔑 Пароль: ••••••••", "👤 Юзер: —"],
                                         "Придумай другой @юзернейм", err))
        return

    if state == "login_email":
        if text in ("назад", "отмена"):
            ms.set_session(tg_id, state="auth", temp={})
            await show(bot, tg_id, ms.scr_auth())
            return
        temp["email"] = raw.lower()
        ms.set_session(tg_id, state="login_pass", temp=temp)
        await show(bot, tg_id, form("ВХОД · шаг 2 из 2",
                                     [f"📧 Почта: {raw.lower()}", "🔑 Пароль: —"],
                                     "Напиши пароль (сообщение сразу удалится)"))
        return

    if state == "login_pass":
        if text in ("назад", "отмена"):
            ms.set_session(tg_id, state="auth", temp={})
            await show(bot, tg_id, ms.scr_auth())
            return
        me = ms.check_login(temp.get("email", ""), raw)
        if me is None:
            ms.set_session(tg_id, state="login_email", temp={})
            await show(bot, tg_id, form("ВХОД · шаг 1 из 2", ["📧 Почта: —"], "Напиши почту", "Неверная почта или пароль. Попробуй ещё раз"))
            return
        ms.set_session(tg_id, user_id=me["id"], state="main", temp={})
        un = ms.unread_total(me["id"])
        await show(bot, tg_id, ms.scr_main(me, notice=f"Привет, @{me['username']}! 👋" + (f" У тебя 🔴 {un} непрочитанных" if un else "")))
        return

    # any other state while logged out
    ms.set_session(tg_id, state="auth", temp={})
    await show(bot, tg_id, ms.scr_auth())


# ---------------------------------------------------------------------------
# Telegram handlers — user messages are deleted, screen is edited
# ---------------------------------------------------------------------------
async def on_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    tg_id = update.effective_user.id
    if update.message:
        await delete_quietly(context.bot, tg_id, update.message.message_id)
    s = ms.get_session(tg_id)
    # If the screen exists, /start just re-renders it (no new message!).
    await show(context.bot, tg_id, render_current(tg_id, s))


async def on_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not update.message or not update.message.text:
        return
    tg_id = update.effective_user.id
    # ⭐ delete the user's message FIRST — only the one screen stays in chat
    await delete_quietly(context.bot, tg_id, update.message.message_id)
    try:
        await route(update, context, update.message.text)
    except Exception:  # never leave the user without a screen
        log.exception("route failed tg=%s", tg_id)
        try:
            await show(context.bot, tg_id, render_current(tg_id, ms.get_session(tg_id), notice="Что-то пошло не так, попробуй ещё раз"))
        except Exception:
            log.exception("recovery render failed tg=%s", tg_id)


# ---------------------------------------------------------------------------
# Health endpoint for Railway (injects $PORT, health-checks /health)
# ---------------------------------------------------------------------------
class _Health(BaseHTTPRequestHandler):
    def do_GET(self):
        ok = self.path in ("/health", "/")
        body = b'{"status":"ok","app":"ari-messenger"}' if ok else b"not found"
        self.send_response(200 if ok else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    if not BOT_TOKEN:
        raise SystemExit("❌ TOKEN пустой в начале bot.py")
    ms.init_db()
    port = int(os.getenv("PORT", "8000"))
    threading.Thread(target=HTTPServer(("0.0.0.0", port), _Health).serve_forever, daemon=True).start()
    log.info("health on 0.0.0.0:%s/health · db=%s", port, ms.DB_PATH)
    app = Application.builder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", on_start))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    log.info("ARI messenger started — one message per user, edit-only")
    app.run_polling(allowed_updates=Update.ALL_TYPES, drop_pending_updates=True)


if __name__ == "__main__":
    main()
