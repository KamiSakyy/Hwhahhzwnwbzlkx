"""
One-message Telegram messenger: storage + logic + screen rendering.

Everything the user sees lives in ONE bot message per Telegram user.
This module is pure logic (SQLite + text screens) — no Telegram code,
so it is fully testable offline (see test_messenger.py).

Security: passwords are NEVER stored or shown in plain text
(PBKDF2-HMAC-SHA256 with a per-user salt). The screen shows •••• only.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import time

DB_PATH = "/app/data/messenger.db" if os.path.isdir("/app/data") else "messenger.db"

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
USERNAME_RE = re.compile(r"^[a-zA-Z0-9_]{3,20}$")

MAX_MSG_LEN = 1000
CHAT_HISTORY = 12  # messages shown on the chat screen (fits 4096 chars)


# --------------------------------------------------------------------------
# DB
# --------------------------------------------------------------------------
def db(path: str | None = None) -> sqlite3.Connection:
    conn = sqlite3.connect(path or DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db(path: str | None = None):
    with db(path) as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS users(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tg_id INTEGER UNIQUE NOT NULL,
            email TEXT UNIQUE NOT NULL,
            pass_hash TEXT NOT NULL,
            salt TEXT NOT NULL,
            username TEXT UNIQUE NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender_id INTEGER NOT NULL,
            recipient_id INTEGER NOT NULL,
            text TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            read_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS screens(
            tg_id INTEGER PRIMARY KEY,
            message_id INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions(
            tg_id INTEGER PRIMARY KEY,
            user_id INTEGER,
            state TEXT NOT NULL DEFAULT 'auth',
            current_chat INTEGER,
            temp TEXT NOT NULL DEFAULT '{}'
        );
        """)


# --------------------------------------------------------------------------
# Passwords (hashed, never plain)
# --------------------------------------------------------------------------
def _hash(password: str, salt_hex: str) -> str:
    return hashlib.pbkdf2_hmac(
        "sha256", password.encode(), bytes.fromhex(salt_hex), 120_000
    ).hex()


def make_hash(password: str) -> tuple[str, str]:
    """(hash, salt) — call this the second a password arrives, drop the plain text."""
    salt = os.urandom(16).hex()
    return _hash(password, salt), salt


def create_user(tg_id: int, email: str, password: str, username: str,
                path: str | None = None) -> tuple[dict | None, str]:
    pass_hash, salt = make_hash(password)
    return create_user_hashed(tg_id, email, pass_hash, salt, username, path)


def create_user_hashed(tg_id: int, email: str, pass_hash: str, salt: str,
                       username: str, path: str | None = None) -> tuple[dict | None, str]:
    email, username = email.strip().lower(), username.strip().lstrip("@")
    if not EMAIL_RE.match(email):
        return None, "❌ Почта похожа на неверную. Пример: " + "name" + "@" + "example.com"
    if not USERNAME_RE.match(username):
        return None, "❌ Юзернейм: 3–20 символов, латиница, цифры и _ . Пример: @ivan_1"
    try:
        with db(path) as c:
            cur = c.execute(
                "INSERT INTO users(tg_id,email,pass_hash,salt,username,created_at)"
                " VALUES(?,?,?,?,?,?)",
                (tg_id, email, pass_hash, salt, username.lower(), int(time.time())),
            )
            uid = cur.lastrowid
    except sqlite3.IntegrityError as e:
        s = str(e)
        if "email" in s:
            return None, "❌ Эта почта уже зарегистрирована. Напиши: войти"
        if "username" in s:
            return None, "❌ Этот @юзернейм уже занят. Придумай другой."
        if "tg_id" in s:
            return None, "❌ У тебя уже есть аккаунт. Напиши: войти"
        return None, "❌ Не удалось зарегистрироваться. Попробуй ещё раз."
    return get_user(uid, path), "✅ Аккаунт создан!"


def get_user(uid: int, path: str | None = None) -> dict | None:
    with db(path) as c:
        r = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    return dict(r) if r else None


def get_user_by_tg(tg_id: int, path: str | None = None) -> dict | None:
    with db(path) as c:
        r = c.execute("SELECT * FROM users WHERE tg_id=?", (tg_id,)).fetchone()
    return dict(r) if r else None


def find_by_email(email: str, path: str | None = None) -> dict | None:
    with db(path) as c:
        r = c.execute("SELECT * FROM users WHERE email=?", (email.strip().lower(),)).fetchone()
    return dict(r) if r else None


def find_by_username(username: str, path: str | None = None) -> dict | None:
    with db(path) as c:
        r = c.execute(
            "SELECT * FROM users WHERE username=?", (username.strip().lstrip("@").lower(),)
        ).fetchone()
    return dict(r) if r else None


def check_login(email: str, password: str, path: str | None = None) -> dict | None:
    u = find_by_email(email, path)
    if u and u["pass_hash"] == _hash(password, u["salt"]):
        return u
    return None


def search_users(query: str, exclude_id: int, path: str | None = None) -> list[dict]:
    q = query.strip().lstrip("@").lower()
    if not q:
        return []
    with db(path) as c:
        rows = c.execute(
            "SELECT * FROM users WHERE username LIKE ? AND id != ? ORDER BY username LIMIT 10",
            (f"%{q}%", exclude_id),
        ).fetchall()
    return [dict(r) for r in rows]


# --------------------------------------------------------------------------
# Messages
# --------------------------------------------------------------------------
def send_message(sender_id: int, recipient_id: int, text: str,
                 path: str | None = None) -> dict:
    text = text.strip()[:MAX_MSG_LEN]
    with db(path) as c:
        cur = c.execute(
            "INSERT INTO messages(sender_id,recipient_id,text,created_at) VALUES(?,?,?,?)",
            (sender_id, recipient_id, text, int(time.time())),
        )
        mid = cur.lastrowid
        r = c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
    return dict(r)


def conversation(a: int, b: int, limit: int = CHAT_HISTORY,
                 path: str | None = None) -> list[dict]:
    with db(path) as c:
        rows = c.execute(
            "SELECT * FROM messages WHERE (sender_id=? AND recipient_id=?)"
            " OR (sender_id=? AND recipient_id=?) ORDER BY id DESC LIMIT ?",
            (a, b, b, a, limit),
        ).fetchall()
    return [dict(r) for r in reversed(rows)]


def mark_read(reader_id: int, other_id: int, path: str | None = None):
    with db(path) as c:
        c.execute(
            "UPDATE messages SET read_at=? WHERE sender_id=? AND recipient_id=? AND read_at IS NULL",
            (int(time.time()), other_id, reader_id),
        )


def unread_total(user_id: int, path: str | None = None) -> int:
    with db(path) as c:
        return c.execute(
            "SELECT COUNT(*) n FROM messages WHERE recipient_id=? AND read_at IS NULL",
            (user_id,),
        ).fetchone()["n"]


def chat_list(user_id: int, path: str | None = None) -> list[dict]:
    """Dialogues: partner, last message, unread count. Newest first."""
    with db(path) as c:
        rows = c.execute(
            "SELECT CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END partner,"
            " MAX(id) last_id FROM messages WHERE sender_id=? OR recipient_id=?"
            " GROUP BY partner ORDER BY last_id DESC",
            (user_id, user_id, user_id),
        ).fetchall()
        out = []
        for r in rows:
            partner = get_user(r["partner"], path)
            last = dict(c.execute("SELECT * FROM messages WHERE id=?", (r["last_id"],)).fetchone())
            unread = c.execute(
                "SELECT COUNT(*) n FROM messages WHERE sender_id=? AND recipient_id=?"
                " AND read_at IS NULL", (partner["id"], user_id),
            ).fetchone()["n"]
            out.append({"partner": partner, "last": last, "unread": unread})
    return out


# --------------------------------------------------------------------------
# Sessions & the single screen (persisted — survives bot restarts)
# --------------------------------------------------------------------------
def get_session(tg_id: int, path: str | None = None) -> dict:
    with db(path) as c:
        r = c.execute("SELECT * FROM sessions WHERE tg_id=?", (tg_id,)).fetchone()
        if r is None:
            c.execute("INSERT INTO sessions(tg_id) VALUES(?)", (tg_id,))
            r = c.execute("SELECT * FROM sessions WHERE tg_id=?", (tg_id,)).fetchone()
    s = dict(r)
    s["temp"] = json.loads(s["temp"] or "{}")
    return s


def set_session(tg_id: int, path: str | None = None, **kw):
    s = get_session(tg_id, path)
    s.update(kw)
    with db(path) as c:
        c.execute(
            "INSERT INTO sessions(tg_id,user_id,state,current_chat,temp) VALUES(?,?,?,?,?)"
            " ON CONFLICT(tg_id) DO UPDATE SET user_id=excluded.user_id,"
            " state=excluded.state, current_chat=excluded.current_chat, temp=excluded.temp",
            (tg_id, s.get("user_id"), s.get("state", "auth"),
             s.get("current_chat"), json.dumps(s.get("temp", {}))),
        )


def get_screen(tg_id: int, path: str | None = None) -> int | None:
    with db(path) as c:
        r = c.execute("SELECT message_id FROM screens WHERE tg_id=?", (tg_id,)).fetchone()
    return r["message_id"] if r else None


def set_screen(tg_id: int, message_id: int, path: str | None = None):
    with db(path) as c:
        c.execute(
            "INSERT INTO screens(tg_id,message_id) VALUES(?,?)"
            " ON CONFLICT(tg_id) DO UPDATE SET message_id=excluded.message_id",
            (tg_id, message_id),
        )


def drop_screen(tg_id: int, path: str | None = None):
    with db(path) as c:
        c.execute("DELETE FROM screens WHERE tg_id=?", (tg_id,))


# --------------------------------------------------------------------------
# Screens (text + symbols only; always rendered into the SAME message)
# --------------------------------------------------------------------------
def _t(ts: int) -> str:
    return time.strftime("%H:%M", time.localtime(ts))


def scr_auth(notice: str = "") -> str:
    return (
        "✉️ <b>МЕССЕНДЖЕР «АРИ»</b>\n"
        "━━━━━━━━━━━━━━━\n"
        "Всё живёт в этом одном сообщении.\n"
        "Просто пиши команды текстом 👇\n\n"
        "〔 1 〕 <b>регистрация</b> — новый аккаунт\n"
        "〔 2 〕 <b>войти</b> — у меня уже есть аккаунт\n"
        + (f"\n⚠️ {notice}" if notice else "")
    )


def scr_form(title: str, lines: list[str], hint: str, notice: str = "") -> str:
    body = "\n".join(lines)
    return (
        f"✉️ <b>{title}</b>\n━━━━━━━━━━━━━━━\n{body}\n\n👉 {hint}\n"
        "〔 назад 〕— отмена"
        + (f"\n\n⚠️ {notice}" if notice else "")
    )


def scr_registered(user: dict) -> str:
    return (
        "✅ <b>РЕГИСТРАЦИЯ ГОТОВА</b>\n"
        "━━━━━━━━━━━━━━━\n"
        f"📧 Почта: <code>{user['email']}</code>\n"
        "🔑 Пароль: •••••••• <i>(скрыт и зашифрован)</i>\n"
        f"👤 Юзер: <b>@{user['username']}</b>\n\n"
        "👉 Напиши: <b>войти</b> — и попадёшь в свои сообщения"
    )


def scr_main(user: dict, notice: str = "", path: str | None = None) -> str:
    chats = chat_list(user["id"], path)
    total_unread = unread_total(user["id"], path)
    lines = [
        "✉️ <b>СООБЩЕНИЯ</b>" + (f"  🔴 {total_unread} новых" if total_unread else ""),
        "━━━━━━━━━━━━━━━",
        f"👤 @{user['username']}  ·  📧 {user['email']}",
        "",
    ]
    if not chats:
        lines.append("📭 Диалогов пока нет.")
    else:
        for ch in chats:
            p, last, un = ch["partner"], ch["last"], ch["unread"]
            who = "ты: " if last["sender_id"] == user["id"] else ""
            preview = last["text"].replace("\n", " ")
            if len(preview) > 34:
                preview = preview[:34] + "…"
            badge = f" 🔴{un}" if un else ""
            lines.append(f"💬 <b>@{p['username']}</b>{badge}  <i>{_t(last['created_at'])}</i>")
            lines.append(f"    {who}{preview}")
    lines += [
        "",
        "⌨️ <b>Команды:</b>",
        "〔 поиск 〕— найти по @юзернейму",
        "〔 @username 〕— открыть диалог",
        "〔 профиль 〕·  〔 выйти 〕",
    ]
    if notice:
        lines.append(f"\n⚠️ {notice}")
    return "\n".join(lines)[:4000]


def scr_search(user: dict, results: list[dict] | None = None, query: str = "",
               notice: str = "") -> str:
    lines = [
        "🔍 <b>ПОИСК ПО @ЮЗЕРНЕЙМУ</b>",
        "━━━━━━━━━━━━━━━",
    ]
    if results is None:
        lines.append("Напиши юзернейм, например: <code>ivan</code> или <code>@ivan</code>")
    elif not results:
        lines.append(f"По запросу «{query}» никого не нашлось 😕")
        lines.append("Напиши другой юзернейм или 〔 назад 〕")
    else:
        lines.append("Нашлось:")
        for u in results:
            lines.append(f"👤 <b>@{u['username']}</b>")
        lines.append("\n👉 Напиши <b>@юзернейм</b> из списка — откроется диалог")
    lines.append("〔 назад 〕— к сообщениям")
    if notice:
        lines.append(f"\n⚠️ {notice}")
    return "\n".join(lines)[:4000]


def scr_chat(user: dict, other: dict, notice: str = "", path: str | None = None) -> str:
    msgs = conversation(user["id"], other["id"], path=path)
    lines = [
        f"💬 <b>@{other['username']}</b>",
        "━━━━━━━━━━━━━━━",
    ]
    if not msgs:
        lines.append("📭 Сообщений пока нет. Напиши первое! 👇")
    for m in msgs:
        txt = m["text"].replace("<", "‹")
        if len(txt) > 220:
            txt = txt[:220] + "…"
        if m["sender_id"] == user["id"]:
            ticks = " ✓✓" if m["read_at"] else " ✓"
            lines.append(f"        🟦 <b>ты:</b> {txt} <i>{_t(m['created_at'])}{ticks}</i>")
        else:
            lines.append(f"🟩 <b>@{other['username']}:</b> {txt} <i>{_t(m['created_at'])}</i>")
    lines += [
        "━━━━━━━━━━━━━━━",
        "✍️ Просто напиши текст — он уйдёт собеседнику",
        "〔 назад 〕— к списку диалогов",
    ]
    if notice:
        lines.append(f"\n⚠️ {notice}")
    return "\n".join(lines)[:4000]


def scr_profile(user: dict) -> str:
    return (
        "👤 <b>ПРОФИЛЬ</b>\n"
        "━━━━━━━━━━━━━━━\n"
        f"📧 Почта: <code>{user['email']}</code>\n"
        "🔑 Пароль: •••••••• <i>(зашифрован)</i>\n"
        f"👤 Юзер: <b>@{user['username']}</b>\n"
        f"🗓 С нами с: {time.strftime('%d.%m.%Y', time.localtime(user['created_at']))}\n\n"
        "〔 назад 〕— к сообщениям · 〔 выйти 〕— выйти из аккаунта"
    )
