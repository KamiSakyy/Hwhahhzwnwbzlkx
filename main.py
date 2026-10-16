"""Single-message Telegram messenger bot.

The bot creates one screen message per Telegram user, stores its message ID,
and edits that message for all future UI updates. Incoming credentials are
never printed to logs or displayed back to the user.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Final
from urllib.parse import urlsplit

from telegram import Update
from telegram.error import BadRequest, TelegramError
from telegram.ext import (
    Application,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

from storage import AccountConflict, Store, hash_password

LOGGER = logging.getLogger("ari_messenger")
MAX_MESSAGE_LENGTH: Final = 4000
MAX_SCREEN_LENGTH: Final = 3900
EMAIL_PATTERN: Final = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")
USERNAME_PATTERN: Final = re.compile(r"^[a-z0-9_]{3,24}$", re.IGNORECASE | re.ASCII)
PASSWORD_STAGES: Final = {"register_password", "login_password"}
EMAIL_STAGES: Final = {"register_email", "login_email"}


def _utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _truncate_for_telegram(value: str, max_units: int) -> str:
    if _utf16_units(value) <= max_units:
        return value
    result: list[str] = []
    units = 0
    for character in value:
        width = 2 if ord(character) > 0xFFFF else 1
        if units + width > max_units - 1:
            break
        result.append(character)
        units += width
    return "".join(result).rstrip() + "…"


@dataclass
class Session:
    """Short-lived UI state. Passwords are never kept here in plain text."""

    stage: str | None = None
    register_email: str | None = None
    pending_password_hash: str | None = None
    login_email: str | None = None
    active_peer_id: int | None = None
    view: str = "home"
    notice: str | None = None

    def clear_flow(self) -> None:
        self.stage = None
        self.register_email = None
        self.pending_password_hash = None
        self.login_email = None


class Messenger:
    def __init__(self, store: Store) -> None:
        self.store = store
        self.sessions: dict[int, Session] = {}
        self._screen_locks: dict[int, asyncio.Lock] = {}

    def session(self, telegram_user_id: int) -> Session:
        return self.sessions.setdefault(telegram_user_id, Session())

    @staticmethod
    def _private_chat(update: Update) -> bool:
        return (
            update.effective_chat is not None
            and update.effective_chat.type == "private"
            and update.effective_user is not None
        )

    async def _delete_auth_input(self, update: Update) -> None:
        message = update.effective_message
        if message is None:
            return
        try:
            await message.delete()
        except TelegramError:
            # Deletion is best-effort: Bot API permissions/availability vary.
            # Never log the message text because it may contain a credential.
            LOGGER.info("Could not delete an authentication input message")

    async def _show_screen(self, bot, telegram_user_id: int, text: str) -> None:
        text = _truncate_for_telegram(text, MAX_SCREEN_LENGTH)
        lock = self._screen_locks.setdefault(telegram_user_id, asyncio.Lock())
        async with lock:
            saved = self.store.get_ui_message(telegram_user_id)
            if saved is None:
                try:
                    sent = await bot.send_message(
                        chat_id=telegram_user_id,
                        text=text,
                        disable_web_page_preview=True,
                    )
                except TelegramError as error:
                    LOGGER.warning(
                        "Could not create the UI message for Telegram user %s (%s)",
                        telegram_user_id,
                        type(error).__name__,
                    )
                    return
                self.store.save_ui_message(
                    telegram_user_id, telegram_user_id, sent.message_id
                )
                return

            try:
                await bot.edit_message_text(
                    chat_id=saved["chat_id"],
                    message_id=saved["message_id"],
                    text=text,
                    disable_web_page_preview=True,
                )
            except BadRequest as error:
                error_text = str(error).casefold()
                if "message is not modified" in error_text:
                    return
                if "message to edit not found" in error_text or "message_id_invalid" in error_text:
                    # The previous UI message no longer exists. Only in that
                    # recovery case do we create a replacement screen.
                    try:
                        sent = await bot.send_message(
                            chat_id=telegram_user_id,
                            text=text,
                            disable_web_page_preview=True,
                        )
                    except TelegramError as send_error:
                        LOGGER.warning(
                            "Could not restore the UI message for Telegram user %s (%s)",
                            telegram_user_id,
                            type(send_error).__name__,
                        )
                        return
                    self.store.save_ui_message(
                        telegram_user_id, telegram_user_id, sent.message_id
                    )
                    return
                LOGGER.warning(
                    "Could not edit the UI message for Telegram user %s (%s)",
                    telegram_user_id,
                    type(error).__name__,
                )
            except TelegramError as error:
                LOGGER.warning(
                    "Could not edit the UI message for Telegram user %s (%s)",
                    telegram_user_id,
                    type(error).__name__,
                )

    def _render_auth(self, session: Session) -> str:
        stage_copy = {
            "register_email": (
                "РЕГИСТРАЦИЯ\nВведите почту. Мы попробуем удалить это сообщение после обработки."
            ),
            "register_password": (
                "РЕГИСТРАЦИЯ\nВведите новый пароль длиной от 10 до 128 символов.\n"
                "Не используйте пароль от почты или других сервисов."
            ),
            "register_username": (
                "РЕГИСТРАЦИЯ\nПридумайте уникальный юзернейм: @name (3–24 символа, латиница, цифры или _)."
            ),
            "login_email": (
                "ВХОД\nВведите почту аккаунта. Мы попробуем удалить это сообщение после обработки."
            ),
            "login_password": (
                "ВХОД\nВведите пароль. Он проверяется по хешу и не отображается в этом окне."
            ),
        }
        if session.stage in stage_copy:
            body = stage_copy[session.stage]
            if session.stage == "register_password" or session.stage == "login_password":
                body += (
                    "\nTelegram-боты не используют сквозное E2E-шифрование. "
                    "Удаление исходного сообщения — best effort; не используйте пароль повторно."
                )
        else:
            body = (
                "АРИ · МЕССЕНДЖЕР\n"
                "━━━━━━━━━━━━━━━━━━\n"
                "Чаты работают внутри одного редактируемого сообщения бота.\n\n"
                "Напишите команду:\n"
                "• регистрация — создать аккаунт\n"
                "• войти — войти в аккаунт\n\n"
                "Можно также использовать /register или /login."
            )
        return body

    def _render_dashboard(self, account, view: str) -> str:
        inbox = self.store.inbox(account["id"], limit=15 if view == "inbox" else 6)
        unread = self.store.unread_count(account["id"])
        title = "ВХОДЯЩИЕ" if view == "inbox" else "АРИ · МЕССЕНДЖЕР"
        lines = [
            title,
            "━━━━━━━━━━━━━━━━━━",
            f"Аккаунт: @{account['username']} · {account['email']}",
        ]
        if unread:
            lines.append(f"Непрочитанных сообщений: {unread}")
        lines.append("")

        if inbox:
            lines.append("Последние диалоги:" if view != "inbox" else "Диалоги:")
            for item in inbox:
                preview = self._preview(item["last_body"], 72)
                unread_badge = f" · новых: {item['unread']}" if item["unread"] else ""
                lines.append(f"• @{item['username']}{unread_badge} — {preview}")
        else:
            lines.append("Пока нет диалогов. Найдите собеседника по @юзернейму.")

        lines.extend(
            [
                "",
                "Команды: поиск · входящие · профиль · выйти · помощь",
                "Откройте диалог через поиск; затем отправляйте обычный текст.",
            ]
        )
        return "\n".join(lines)

    def _render_profile(self, account) -> str:
        unread = self.store.unread_count(account["id"])
        lines = [
            "ПРОФИЛЬ",
            "━━━━━━━━━━━━━━━━━━",
            f"Юзернейм: @{account['username']}",
            f"Почта: {account['email']}",
            "Пароль: хранится только в виде стойкого salted-хеша.",
        ]
        if unread:
            lines.append(f"Непрочитанных сообщений: {unread}")
        lines.extend(["", "Для возврата напишите «назад» или /back."])
        return "\n".join(lines)

    def _render_search(self, account) -> str:
        unread = self.store.unread_count(account["id"])
        lines = [
            "ПОИСК",
            "━━━━━━━━━━━━━━━━━━",
            f"Аккаунт: @{account['username']}",
            "Введите юзернейм собеседника, например @name.",
        ]
        if unread:
            lines.append(f"Непрочитанных сообщений: {unread}")
        lines.append("Для отмены напишите «отмена» или /cancel.")
        return "\n".join(lines)

    def _render_conversation(self, account, peer) -> str:
        self.store.mark_read(account["id"], peer["id"])
        messages = self.store.get_conversation(account["id"], peer["id"], limit=12)
        unread = self.store.unread_count(account["id"])
        lines = [
            f"ДИАЛОГ С @{peer['username']}",
            "━━━━━━━━━━━━━━━━━━",
        ]
        if unread:
            lines.append(f"Есть новые сообщения в других чатах: {unread}")
        if messages:
            for message in messages:
                sender = "Вы" if message["sender_id"] == account["id"] else f"@{message['sender_username']}"
                body = self._preview(message["body"], 220)
                lines.append(f"{sender}: {body}")
        else:
            lines.append("Это начало диалога. Напишите первое сообщение ниже.")
        lines.extend(
            [
                "",
                "Отправьте текст — он появится здесь у обоих собеседников.",
                "Команды: поиск · входящие · назад · профиль · помощь",
            ]
        )
        return "\n".join(lines)

    @staticmethod
    def _preview(text: str, limit: int) -> str:
        clean = " ".join(text.replace("\x00", "").split())
        if len(clean) > limit:
            return clean[: limit - 1] + "…"
        return clean or "(пустое сообщение)"

    async def update_screen(self, bot, telegram_user_id: int) -> None:
        session = self.session(telegram_user_id)
        account = self.store.get_account_by_telegram(telegram_user_id)
        if account is None:
            text = self._render_auth(session)
        elif session.stage == "search":
            text = self._render_search(account)
        elif session.active_peer_id is not None:
            peer = self.store.get_account_by_id(session.active_peer_id)
            if peer is None or peer["id"] == account["id"]:
                session.active_peer_id = None
                text = self._render_dashboard(account, session.view)
            else:
                text = self._render_conversation(account, peer)
        elif session.view == "profile":
            text = self._render_profile(account)
        else:
            text = self._render_dashboard(account, session.view)

        if session.notice:
            text = f"{text}\n\n{session.notice}"
            session.notice = None
        await self._show_screen(bot, telegram_user_id, text)

    async def _set_stage(
        self, update: Update, context: ContextTypes.DEFAULT_TYPE, stage: str
    ) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.active_peer_id = None
        session.view = "home"
        account = self.store.get_account_by_telegram(telegram_user_id)
        if account is not None and stage in {"register_email", "login_email"}:
            session.notice = f"Вы уже вошли как @{account['username']}."
        else:
            session.stage = stage
        await self.update_screen(context.bot, telegram_user_id)

    async def start(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.active_peer_id = None
        session.view = "home"
        session.notice = None
        await self.update_screen(context.bot, telegram_user_id)

    async def register(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        await self._set_stage(update, context, "register_email")

    async def login(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        await self._set_stage(update, context, "login_email")

    async def search(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        if self.store.get_account_by_telegram(telegram_user_id) is None:
            session = self.session(telegram_user_id)
            session.notice = "Сначала зарегистрируйтесь или войдите: регистрация / войти."
            await self.update_screen(context.bot, telegram_user_id)
            return
        await self._set_stage(update, context, "search")

    async def inbox_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        await self._show_view(update, context, "inbox")

    async def profile(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        await self._show_view(update, context, "profile")

    async def home(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        await self._show_view(update, context, "home")

    async def back(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.active_peer_id = None
        session.view = "inbox" if self.store.get_account_by_telegram(telegram_user_id) else "home"
        session.notice = None
        await self.update_screen(context.bot, telegram_user_id)

    async def cancel(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.notice = "Действие отменено."
        await self.update_screen(context.bot, telegram_user_id)

    async def logout(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        self.store.unlink_telegram(telegram_user_id)
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.active_peer_id = None
        session.view = "home"
        session.notice = "Вы вышли. Данные аккаунта и сообщения сохранены."
        await self.update_screen(context.bot, telegram_user_id)

    async def help(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.notice = (
            "Напишите: регистрация, войти, поиск, входящие, профиль, назад, выйти или помощь. "
            "В открытом диалоге обычный текст отправляется собеседнику."
        )
        await self.update_screen(context.bot, telegram_user_id)

    async def _show_view(
        self, update: Update, context: ContextTypes.DEFAULT_TYPE, view: str
    ) -> None:
        if not self._private_chat(update):
            return
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        session.clear_flow()
        session.active_peer_id = None
        session.view = view
        session.notice = None
        if self.store.get_account_by_telegram(telegram_user_id) is None:
            session.notice = "Сначала зарегистрируйтесь или войдите."
        await self.update_screen(context.bot, telegram_user_id)

    async def text_message(
        self, update: Update, context: ContextTypes.DEFAULT_TYPE
    ) -> None:
        if not self._private_chat(update) or update.effective_message is None:
            return
        message = update.effective_message
        raw_text = message.text or ""
        stripped = raw_text.strip()
        telegram_user_id = update.effective_user.id
        session = self.session(telegram_user_id)
        normalized = stripped.casefold()
        if not stripped and session.stage not in PASSWORD_STAGES:
            return

        if normalized == "отмена" or (
            normalized == "cancel" and session.stage not in PASSWORD_STAGES
        ):
            session.clear_flow()
            session.notice = "Действие отменено."
            await self.update_screen(context.bot, telegram_user_id)
            return

        if session.stage is not None:
            stage = session.stage
            delete_input = stage in EMAIL_STAGES or stage in PASSWORD_STAGES
            try:
                await self._handle_stage(telegram_user_id, session, stage, raw_text)
            finally:
                if delete_input:
                    await self._delete_auth_input(update)
            await self.update_screen(context.bot, telegram_user_id)
            return

        account = self.store.get_account_by_telegram(telegram_user_id)
        action = self._text_action(normalized)
        if action is not None:
            await self._apply_text_action(update, context, action)
            return

        if account is None:
            session.notice = "Чтобы начать, напишите «регистрация» или «войти»."
            await self.update_screen(context.bot, telegram_user_id)
            return

        if stripped.startswith("@"):
            await self._open_chat(telegram_user_id, session, stripped)
            await self.update_screen(context.bot, telegram_user_id)
            return

        if session.active_peer_id is None:
            session.notice = "Сначала найдите собеседника: напишите «поиск» и его @юзернейм."
            await self.update_screen(context.bot, telegram_user_id)
            return

        body = raw_text
        if _utf16_units(body) > MAX_MESSAGE_LENGTH:
            session.notice = f"Сообщение слишком длинное. Лимит — {MAX_MESSAGE_LENGTH} символов."
            await self.update_screen(context.bot, telegram_user_id)
            return
        if not body.strip():
            session.notice = "Пустое сообщение не отправлено."
            await self.update_screen(context.bot, telegram_user_id)
            return

        peer = self.store.get_account_by_id(session.active_peer_id)
        if peer is None:
            session.active_peer_id = None
            session.notice = "Этот аккаунт больше недоступен. Найдите собеседника снова."
            await self.update_screen(context.bot, telegram_user_id)
            return

        self.store.add_message(account["id"], peer["id"], body)
        session.view = "home"
        await self.update_screen(context.bot, telegram_user_id)
        if peer["telegram_user_id"] is not None:
            await self.update_screen(context.bot, peer["telegram_user_id"])

    async def _handle_stage(
        self, telegram_user_id: int, session: Session, stage: str, raw_text: str
    ) -> None:
        stripped = raw_text.strip()

        if stage == "register_email":
            email = stripped.casefold()
            if len(email) > 254 or not EMAIL_PATTERN.fullmatch(email):
                session.notice = "Почта выглядит неверно. Введите адрес ещё раз или /cancel."
                return
            if self.store.get_account_by_email(email) is not None:
                session.clear_flow()
                session.stage = "login_email"
                session.notice = "Такая почта уже зарегистрирована. Для входа введите почту ещё раз."
                return
            session.register_email = email
            session.stage = "register_password"
            session.notice = "Почта принята. Теперь введите новый пароль (10–128 символов)."
            return

        if stage == "register_password":
            if not 10 <= len(raw_text) <= 128 or not raw_text.strip():
                session.notice = "Пароль должен содержать от 10 до 128 символов, не одни пробелы. Попробуйте ещё раз."
                return
            session.pending_password_hash = hash_password(raw_text)
            session.stage = "register_username"
            session.notice = "Пароль обработан. Придумайте юзернейм, например @alex_24."
            return

        if stage == "register_username":
            username = stripped.removeprefix("@").casefold()
            if not USERNAME_PATTERN.fullmatch(username):
                session.notice = "Нужен юзернейм 3–24 символа: латиница, цифры или _. Например @alex_24."
                return
            if self.store.get_account_by_username(username) is not None:
                session.notice = "Этот юзернейм занят. Введите другой @юзернейм."
                return
            if not session.register_email or not session.pending_password_hash:
                session.clear_flow()
                session.notice = "Регистрация сброшена. Начните заново: регистрация."
                return
            try:
                self.store.create_account(
                    telegram_user_id,
                    session.register_email,
                    username,
                    session.pending_password_hash,
                )
            except AccountConflict as conflict:
                if conflict.field == "username":
                    session.notice = "Этот юзернейм уже занят. Введите другой."
                    return
                if conflict.field == "email":
                    session.clear_flow()
                    session.stage = "login_email"
                    session.notice = "Почта уже занята. Для входа введите почту ещё раз."
                    return
                session.clear_flow()
                session.notice = "Не удалось создать аккаунт. Попробуйте начать регистрацию заново."
                return
            session.clear_flow()
            session.active_peer_id = None
            session.view = "home"
            session.notice = f"Аккаунт создан. Ваш юзернейм: @{username}. Найдите собеседника через «поиск»."
            return

        if stage == "login_email":
            email = stripped.casefold()
            if len(email) > 254 or not EMAIL_PATTERN.fullmatch(email):
                session.notice = "Почта выглядит неверно. Введите адрес ещё раз или /cancel."
                return
            session.login_email = email
            session.stage = "login_password"
            session.notice = "Теперь введите пароль. Для безопасности сообщение удаляется, если Telegram разрешит."
            return

        if stage == "login_password":
            if not session.login_email:
                session.clear_flow()
                session.notice = "Вход сброшен. Напишите «войти», чтобы начать заново."
                return
            if len(raw_text) > 128:
                session.notice = "Пароль слишком длинный. Введите пароль ещё раз или /cancel."
                return
            account, status = self.store.authenticate(
                session.login_email, raw_text, telegram_user_id
            )
            if status == "rate_limited":
                session.clear_flow()
                session.notice = "Слишком много попыток входа. Подождите 15 минут и попробуйте снова."
                return
            if status == "linked":
                session.clear_flow()
                session.notice = "Этот аккаунт уже привязан к другому Telegram-профилю."
                return
            if account is None:
                session.notice = "Почта или пароль неверны. Введите пароль ещё раз либо /cancel."
                return
            session.clear_flow()
            session.active_peer_id = None
            session.view = "home"
            session.notice = f"Вы вошли как @{account['username']}."
            return

        if stage == "search":
            username = stripped.removeprefix("@").casefold()
            if not USERNAME_PATTERN.fullmatch(username):
                session.notice = "Введите юзернейм в формате @name (латиница, цифры или _)."
                return
            await self._open_chat(telegram_user_id, session, username)
            return

        session.clear_flow()
        session.notice = "Неизвестное состояние. Напишите /start и попробуйте снова."

    async def _open_chat(
        self, telegram_user_id: int, session: Session, username_input: str
    ) -> None:
        username = username_input.strip().removeprefix("@").casefold()
        if not USERNAME_PATTERN.fullmatch(username):
            session.notice = "Введите юзернейм в формате @name."
            return
        current = self.store.get_account_by_telegram(telegram_user_id)
        if current is None:
            session.clear_flow()
            session.notice = "Сначала зарегистрируйтесь или войдите."
            return
        peer = self.store.get_account_by_username(username)
        if peer is None:
            session.notice = f"Пользователь @{username} не найден. Попробуйте другой @юзернейм."
            return
        if peer["id"] == current["id"]:
            session.notice = "Нельзя открыть диалог с самим собой. Введите другой @юзернейм."
            return
        session.clear_flow()
        session.active_peer_id = peer["id"]
        session.view = "home"
        session.notice = f"Открыт диалог с @{peer['username']}. Отправьте сообщение обычным текстом."

    @staticmethod
    def _text_action(text: str) -> str | None:
        actions = {
            "регистрация": "register",
            "зарегистрироваться": "register",
            "рег": "register",
            "войти": "login",
            "вход": "login",
            "логин": "login",
            "поиск": "search",
            "найти": "search",
            "входящие": "inbox",
            "чаты": "inbox",
            "назад": "back",
            "домой": "home",
            "профиль": "profile",
            "выйти": "logout",
            "выход": "logout",
            "помощь": "help",
            "help": "help",
        }
        return actions.get(text)

    async def _apply_text_action(
        self,
        update: Update,
        context: ContextTypes.DEFAULT_TYPE,
        action: str,
    ) -> None:
        handler = {
            "register": self.register,
            "login": self.login,
            "search": self.search,
            "inbox": self.inbox_command,
            "back": self.back,
            "home": self.home,
            "profile": self.profile,
            "logout": self.logout,
            "help": self.help,
        }[action]
        await handler(update, context)


class HealthHandler(BaseHTTPRequestHandler):
    server_version = "AriMessenger"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        path = urlsplit(self.path).path
        if path in {"/", "/health"}:
            status, body = 200, b"ok\n"
        else:
            status, body = 404, b"not found\n"
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        # Avoid noisy request logs; these endpoints contain no user content.
        return


def start_health_server(port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("0.0.0.0", port), HealthHandler)
    server.daemon_threads = True
    thread = threading.Thread(
        target=server.serve_forever,
        name="health-http-server",
        daemon=True,
    )
    thread.start()
    LOGGER.info("Health endpoint listening on 0.0.0.0:%s", port)
    return server


def build_application(token: str, store: Store) -> Application:
    messenger = Messenger(store)
    application = Application.builder().token(token).concurrent_updates(False).build()

    application.add_handler(CommandHandler("start", messenger.start))
    application.add_handler(CommandHandler("register", messenger.register))
    application.add_handler(CommandHandler("login", messenger.login))
    application.add_handler(CommandHandler("search", messenger.search))
    application.add_handler(CommandHandler("inbox", messenger.inbox_command))
    application.add_handler(CommandHandler("profile", messenger.profile))
    application.add_handler(CommandHandler("home", messenger.home))
    application.add_handler(CommandHandler("back", messenger.back))
    application.add_handler(CommandHandler("cancel", messenger.cancel))
    application.add_handler(CommandHandler("logout", messenger.logout))
    application.add_handler(CommandHandler("help", messenger.help))
    application.add_handler(
        MessageHandler(filters.TEXT & ~filters.COMMAND, messenger.text_message)
    )
    application.bot_data["messenger"] = messenger
    application.add_error_handler(log_error)
    return application


async def log_error(update: object, context: ContextTypes.DEFAULT_TYPE) -> None:
    error = context.error
    # Do not log exception contents or Update objects: they can contain a bot
    # token in a request URL, private messages, email addresses, or passwords.
    LOGGER.error("Telegram update failed (%s)", type(error).__name__)


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    # HTTPX request URLs contain the Telegram token as a path segment. Keep
    # library logs quiet even if the application LOG_LEVEL is set to DEBUG.
    logging.getLogger("httpx").setLevel(logging.ERROR)
    logging.getLogger("httpcore").setLevel(logging.ERROR)
    logging.getLogger("telegram").setLevel(logging.WARNING)

    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        raise SystemExit(
            "Missing TELEGRAM_BOT_TOKEN. Set it in Railway Variables; do not hard-code it."
        )

    database_path = os.getenv("DATABASE_PATH", "./messenger.sqlite3")
    try:
        port = int(os.getenv("PORT", "8080"))
    except ValueError as error:
        raise SystemExit("PORT must be an integer") from error
    if not 1 <= port <= 65535:
        raise SystemExit("PORT must be between 1 and 65535")

    store = Store(database_path)
    application = build_application(token, store)
    health_server = start_health_server(port)
    try:
        # This is a single long-polling process. Do not scale it to multiple
        # replicas with the same bot token.
        application.run_polling(drop_pending_updates=False)
    finally:
        health_server.shutdown()
        health_server.server_close()


if __name__ == "__main__":
    main()
