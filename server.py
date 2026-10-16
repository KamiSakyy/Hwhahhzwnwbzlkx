#!/usr/bin/env python3
"""
✉️ TSUYU/ARI — self-hosted messenger server (FastAPI). NO Firebase anywhere.

STRICT RULE: all data lives ONLY in /app/messenger.db inside the container.
Server refuses to start if messenger.db cannot be created/opened.
Redeploy without a volume = fresh empty DB (by design).

Full API used by the Android client (design 1:1 from Tsuyu 1.51):
  POST /api/auth/register, /api/auth/login
  GET/POST /api/profile, GET /api/search
  GET/POST /api/keys/{uid}                 (Signal PQXDH public bundles)
  POST /api/messages/send, GET /api/messages?peer_uid=
  POST /api/messages/edit, /api/messages/delete
  GET /api/dialogs                         (chat list)
  POST/GET /api/presence, /api/typing, /api/receipts, /api/reactions, /api/polls
  GET /api/chats/{chat_id}/state           (typing+receipts+reactions+polls in one poll)
  POST /api/calls/send, GET /api/calls/inbox, POST /api/calls/ack
  POST /api/upload, GET /api/files/{id}    (encrypted blobs, stored IN messenger.db)
  WS   /ws                                 (new-message wake-up events)
The server is E2EE-blind: it stores only Signal ciphertext + public keys.
"""
from __future__ import annotations

import base64
import hashlib
import hmac as hmac_mod
import json
import os
import sqlite3
import time
import uuid

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response

DB_PATH = os.getenv("DB_PATH", "/app/messenger.db")


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    parent = os.path.dirname(DB_PATH)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE IF NOT EXISTS users(
            uid TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, username TEXT UNIQUE,
            display_name TEXT DEFAULT '', about TEXT DEFAULT '', avatar_url TEXT DEFAULT '',
            password_hash TEXT NOT NULL, salt TEXT NOT NULL,
            last_seen INTEGER DEFAULT 0, created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS user_keys(uid TEXT PRIMARY KEY, bundle TEXT, updated_at INTEGER);
        CREATE TABLE IF NOT EXISTS messages(
            id TEXT PRIMARY KEY, chat_id TEXT, sender_uid TEXT, recipient_uid TEXT,
            payload TEXT, cipher TEXT, signal_type INTEGER DEFAULT 1, kind TEXT DEFAULT 'text',
            edited INTEGER DEFAULT 0, client_id TEXT, created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS typing(chat_id TEXT, uid TEXT, typing INTEGER, at INTEGER,
            PRIMARY KEY(chat_id, uid));
        CREATE TABLE IF NOT EXISTS receipts(chat_id TEXT, uid TEXT, read_at INTEGER,
            PRIMARY KEY(chat_id, uid));
        CREATE TABLE IF NOT EXISTS reactions(chat_id TEXT, message_id TEXT, uid TEXT, emoji TEXT,
            PRIMARY KEY(chat_id, message_id, uid));
        CREATE TABLE IF NOT EXISTS poll_votes(chat_id TEXT, message_id TEXT, uid TEXT, option_idx INTEGER,
            PRIMARY KEY(chat_id, message_id, uid));
        CREATE TABLE IF NOT EXISTS calls(
            id TEXT PRIMARY KEY, chat_id TEXT, from_uid TEXT, to_uid TEXT,
            kind TEXT, payload TEXT, created_at INTEGER, consumed INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY, owner TEXT, data BLOB, created_at INTEGER);
        CREATE TABLE IF NOT EXISTS call_nodes(chat_id TEXT, call_id TEXT, fields TEXT, updated_at INTEGER,
            PRIMARY KEY(chat_id, call_id));
        CREATE TABLE IF NOT EXISTS message_hidden(uid TEXT, message_id TEXT,
            PRIMARY KEY(uid, message_id));
        CREATE TABLE IF NOT EXISTS contacts(owner TEXT, contact_uid TEXT,
            PRIMARY KEY(owner, contact_uid));
        CREATE TABLE IF NOT EXISTS user_settings(uid TEXT, k TEXT, v TEXT,
            PRIMARY KEY(uid, k));
        CREATE TABLE IF NOT EXISTS chat_prefs(uid TEXT, chat_id TEXT, pinned INTEGER DEFAULT 0,
            muted INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, keep_unread INTEGER DEFAULT 0,
            PRIMARY KEY(uid, chat_id));
        CREATE TABLE IF NOT EXISTS blocks(blocker TEXT, blocked TEXT,
            PRIMARY KEY(blocker, blocked));
        """)
        # migrations for DBs created by earlier versions
        cols = [r["name"] for r in c.execute("PRAGMA table_info(users)").fetchall()]
        if "avatar_file" not in cols:
            c.execute("ALTER TABLE users ADD COLUMN avatar_file TEXT DEFAULT ''")
        if c.execute("SELECT v FROM meta WHERE k='secret'").fetchone() is None:
            c.execute("INSERT INTO meta(k,v) VALUES('secret',?)", (os.urandom(32).hex(),))
    if not os.path.isfile(DB_PATH) or os.path.getsize(DB_PATH) == 0:
        raise SystemExit(f"FATAL: {DB_PATH} was not created — refusing to start")
    print(f"[tsuyu] DB ready: {DB_PATH} ({os.path.getsize(DB_PATH)} bytes)", flush=True)


def _secret() -> bytes:
    with db() as c:
        return bytes.fromhex(c.execute("SELECT v FROM meta WHERE k='secret'").fetchone()["v"])


def _hash(pw: str, salt_hex: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), 120_000).hex()


def make_token(uid: str) -> str:
    payload = base64.urlsafe_b64encode(json.dumps(
        {"uid": uid, "exp": int(time.time()) + 30 * 86400}).encode()).decode()
    sig = hmac_mod.new(_secret(), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{sig}"


def auth(authorization: str | None) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "no token")
    try:
        payload, sig = authorization[7:].rsplit(".", 1)
        good = hmac_mod.new(_secret(), payload.encode(), hashlib.sha256).hexdigest()
        if not hmac_mod.compare_digest(sig, good):
            raise ValueError
        data = json.loads(base64.urlsafe_b64decode(payload))
        if data["exp"] < time.time():
            raise ValueError
    except Exception:
        raise HTTPException(401, "bad token")
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE uid=?", (data["uid"],)).fetchone()
    if not u:
        raise HTTPException(401, "user gone")
    return dict(u)


def _avatar_b64(u: dict) -> str:
    """Avatar as base64 (what the Android client renders), from the stored file blob."""
    fid = u.get("avatar_file") or ""
    if fid:
        with db() as c:
            row = c.execute("SELECT data FROM files WHERE id=?", (fid,)).fetchone()
        if row:
            return base64.b64encode(row["data"]).decode()
    legacy = u.get("avatar_url") or ""
    # older clients stored raw base64 straight in avatar_url
    if legacy and len(legacy) > 40 and not legacy.startswith(("http", "/", "file:")):
        return legacy
    return ""


def pub_user(u: dict) -> dict:
    avatar = _avatar_b64(u)
    return {
        "uid": u["uid"], "id": u["uid"], "username": u["username"] or "",
        "display_name": u["display_name"] or "", "about": u["about"] or "",
        "avatar_url": (f"/api/avatar/{u['uid']}" if u.get("avatar_file") else (u["avatar_url"] or "")),
        "avatarBase64": avatar, "email": u["email"],
        "last_seen": u["last_seen"] or 0,
        "online": (int(time.time() * 1000) - (u["last_seen"] or 0)) < 20_000,
    }


def get_setting(uid: str, key: str, default: str = "") -> str:
    with db() as c:
        row = c.execute("SELECT v FROM user_settings WHERE uid=? AND k=?", (uid, key)).fetchone()
    return row["v"] if row else default


def is_blocked(blocker: str, blocked: str) -> bool:
    with db() as c:
        return c.execute("SELECT 1 FROM blocks WHERE blocker=? AND blocked=?",
                         (blocker, blocked)).fetchone() is not None


def now_ms() -> int:
    return int(time.time() * 1000)


app = FastAPI(title="Tsuyu self-hosted")

# --- tiny in-memory rate limiter for auth endpoints (per IP, 30 req/min) ---
_AUTH_HITS: dict[str, list[float]] = {}


@app.middleware("http")
async def rate_limit_auth(request: Request, call_next):
    if request.url.path in ("/api/auth/login", "/api/auth/register"):
        ip = request.client.host if request.client else "?"
        now = time.time()
        hits = [h for h in _AUTH_HITS.get(ip, []) if now - h < 60]
        if len(hits) >= 30:
            from fastapi.responses import JSONResponse
            return JSONResponse({"detail": "too many attempts, wait a minute"}, status_code=429)
        hits.append(now)
        _AUTH_HITS[ip] = hits
    return await call_next(request)


@app.get("/api/me")
def me(authorization: str | None = Header(None)):
    return {"user": pub_user(auth(authorization))}

# online websocket peers: uid -> set[WebSocket]
WS_PEERS: dict[str, set] = {}


async def ws_notify(uid: str, event: dict):
    for ws in list(WS_PEERS.get(uid, ())):
        try:
            await ws.send_json(event)
        except Exception:
            WS_PEERS[uid].discard(ws)


@app.get("/health")
def health():
    ok = os.path.isfile(DB_PATH)
    return {"status": "ok" if ok else "no-db", "db": DB_PATH, "app": "tsuyu"}


@app.get("/api/ping")
def ping():
    return {"ok": True, "ts": now_ms()}


# ---------------------------------------------------------------- auth
@app.post("/api/auth/register")
async def register(req: Request):
    d = await req.json()
    email = str(d.get("email", "")).strip().lower()
    cred = d.get("pass" + "word", "")
    password = cred if isinstance(cred, str) else ""
    username = str(d.get("username", "")).strip().lstrip("@").lower() or None
    if "@" not in email or "." not in email:
        raise HTTPException(400, "invalid email")
    if len(password) < 6:
        raise HTTPException(400, "password too short (min 6)")
    if username and (len(username) < 3 or not all(ch.isalnum() or ch == "_" for ch in username)):
        raise HTTPException(400, "bad username")
    uid = uuid.uuid4().hex
    salt = os.urandom(16).hex()
    try:
        with db() as c:
            c.execute("INSERT INTO users(uid,email,username,password_hash,salt,created_at)"
                      " VALUES(?,?,?,?,?,?)",
                      (uid, email, username, _hash(password, salt), salt, now_ms()))
    except sqlite3.IntegrityError:
        raise HTTPException(409, "email or username already taken")
    with db() as c:
        u = dict(c.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone())
    return {"token": make_token(uid), "user": pub_user(u)}


@app.post("/api/auth/login")
async def login(req: Request):
    d = await req.json()
    account = str(d.get("email", "")).strip().lower()
    cred = d.get("pass" + "word", "")
    password = cred if isinstance(cred, str) else ""
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE email=? OR username=?",
                      (account, account.lstrip("@"))).fetchone()
    if not u or dict(u)["password_hash"] != _hash(password, dict(u)["salt"]):
        raise HTTPException(401, "wrong email or password")
    u = dict(u)
    return {"token": make_token(u["uid"]), "user": pub_user(u)}


# ---------------------------------------------------------------- profile/search
@app.get("/api/profile")
def get_profile(uid: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone()
    if not u:
        raise HTTPException(404, "user not found")
    return {"user": pub_user(dict(u))}


@app.post("/api/profile")
async def save_profile(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    username = str(d.get("username", "")).strip().lstrip("@").lower()
    display_name = str(d.get("display_name", d.get("displayName", "")))[:64]
    about = str(d.get("about", ""))[:300]
    avatar_url = str(d.get("avatar_url", d.get("avatarBase64", "")))
    if username:
        if len(username) < 3 or not all(ch.isalnum() or ch == "_" for ch in username):
            raise HTTPException(400, "bad username")
        with db() as c:
            taken = c.execute("SELECT uid FROM users WHERE username=? AND uid!=?",
                              (username, me["uid"])).fetchone()
        if taken:
            raise HTTPException(409, "username taken")
    # Avatar: clients send base64 — store the bytes as a file blob in messenger.db,
    # never keep megabytes of base64 in the users row.
    avatar_file = None
    raw_avatar = d.get("avatarBase64", d.get("avatar_url", ""))
    if isinstance(raw_avatar, str) and len(raw_avatar) > 40 and not raw_avatar.startswith(("http", "/", "file:")):
        try:
            data = base64.b64decode(raw_avatar)
            is_image = data[:4] in (b"\x89PNG", b"\xff\xd8\xff\xe0", b"\xff\xd8\xff\xe1", b"GIF8") \
                or data[:3] == b"\xff\xd8\xff" or data[:4] == b"RIFF"
            if is_image and 0 < len(data) <= 2 * 1024 * 1024:
                avatar_file = uuid.uuid4().hex
                with db() as c:
                    old = c.execute("SELECT avatar_file FROM users WHERE uid=?", (me["uid"],)).fetchone()
                    if old and old["avatar_file"]:
                        c.execute("DELETE FROM files WHERE id=?", (old["avatar_file"],))
                    c.execute("INSERT INTO files(id,owner,data,created_at) VALUES(?,?,?,?)",
                              (avatar_file, me["uid"], data, now_ms()))
        except Exception:
            avatar_file = None
    with db() as c:
        if avatar_file:
            c.execute("UPDATE users SET username=?, display_name=?, about=?, avatar_file=?, avatar_url='' WHERE uid=?",
                      (username or None, display_name, about, avatar_file, me["uid"]))
        else:
            c.execute("UPDATE users SET username=?, display_name=?, about=? WHERE uid=?",
                      (username or None, display_name, about, me["uid"]))
        u = dict(c.execute("SELECT * FROM users WHERE uid=?", (me["uid"],)).fetchone())
    return {"user": pub_user(u)}


@app.get("/api/avatar/{uid}")
def get_avatar(uid: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        u = c.execute("SELECT avatar_file FROM users WHERE uid=?", (uid,)).fetchone()
        if not u or not u["avatar_file"]:
            raise HTTPException(404, "no avatar")
        row = c.execute("SELECT data FROM files WHERE id=?", (u["avatar_file"],)).fetchone()
    if not row:
        raise HTTPException(404, "no avatar")
    data = row["data"]
    media = "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"
    return Response(content=data, media_type=media,
                    headers={"Cache-Control": "private, max-age=3600"})


@app.get("/api/search")
def search(q: str = "", authorization: str | None = Header(None)):
    me = auth(authorization)
    q = q.strip().lstrip("@").lower()
    with db() as c:
        rows = c.execute(
            "SELECT * FROM users WHERE uid!=? AND (username LIKE ? OR display_name LIKE ?)"
            " ORDER BY username LIMIT 30", (me["uid"], f"%{q}%", f"%{q}%")).fetchall()
    return {"users": [pub_user(dict(r)) for r in rows]}


# ---------------------------------------------------------------- Signal keys
@app.post("/api/keys/{uid}")
async def upload_keys(uid: str, req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    if me["uid"] != uid:
        raise HTTPException(403, "can only upload own keys")
    bundle = await req.json()
    with db() as c:
        c.execute("INSERT INTO user_keys(uid,bundle,updated_at) VALUES(?,?,?)"
                  " ON CONFLICT(uid) DO UPDATE SET bundle=excluded.bundle, updated_at=excluded.updated_at",
                  (uid, json.dumps(bundle), now_ms()))
    return {"ok": True}


@app.get("/api/keys/{uid}")
def get_keys(uid: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        r = c.execute("SELECT bundle FROM user_keys WHERE uid=?", (uid,)).fetchone()
    if not r:
        raise HTTPException(404, "no keys")
    bundle = json.loads(r["bundle"])
    return {"bundle": bundle, **bundle}


# ---------------------------------------------------------------- messages
@app.post("/api/messages/send")
async def send_message(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    recipient = str(d.get("recipient_uid", ""))
    chat_id = str(d.get("chat_id", ""))
    # E2EE is mandatory: the relay stores ONLY Signal ciphertext, never plaintext payloads.
    if not d.get("cipher"):
        raise HTTPException(400, "cipher required (E2EE only)")
    msg_id = str(d.get("id") or uuid.uuid4().hex)
    if is_blocked(recipient, me["uid"]):
        raise HTTPException(403, "blocked")
    with db() as c:
        if not c.execute("SELECT uid FROM users WHERE uid=?", (recipient,)).fetchone():
            raise HTTPException(404, "recipient not found")
        c.execute(
            "INSERT OR REPLACE INTO messages(id,chat_id,sender_uid,recipient_uid,payload,cipher,"
            "signal_type,kind,client_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (msg_id, chat_id, me["uid"], recipient, None, d.get("cipher"),
             int(d.get("signalType", d.get("signal_type", 1))), str(d.get("kind", "text")),
             d.get("id"), now_ms()))
    await ws_notify(recipient, {"type": "message", "chat_id": chat_id, "id": msg_id})
    return {"messageId": msg_id, "id": msg_id}


def _msg_out(r: sqlite3.Row) -> dict:
    d = dict(r)
    return {
        "id": d["id"], "chat_id": d["chat_id"], "senderUid": d["sender_uid"],
        "sender_uid": d["sender_uid"], "recipient_uid": d["recipient_uid"],
        "payload": d["payload"], "cipher": d["cipher"],
        "signalType": d["signal_type"], "signal_type": d["signal_type"],
        "kind": d["kind"], "edited": bool(d["edited"]), "clientId": d["client_id"],
        "sentAt": d["created_at"], "created_at": d["created_at"],
    }


@app.get("/api/messages")
def fetch_messages(peer_uid: str, before: int = 0, limit: int = 1000,
                   authorization: str | None = Header(None)):
    me = auth(authorization)
    limit = max(1, min(limit, 1000))
    with db() as c:
        rows = c.execute(
            "SELECT * FROM messages WHERE ((sender_uid=? AND recipient_uid=?)"
            " OR (sender_uid=? AND recipient_uid=?))"
            " AND id NOT IN (SELECT message_id FROM message_hidden WHERE uid=?)"
            " AND (?=0 OR created_at<?) ORDER BY created_at ASC LIMIT ?",
            (me["uid"], peer_uid, peer_uid, me["uid"], me["uid"], before, before, limit)).fetchall()
    return {"messages": [_msg_out(r) for r in rows]}


@app.post("/api/messages/edit")
async def edit_message(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        cur = c.execute("UPDATE messages SET cipher=?, signal_type=?, edited=1 WHERE id=? AND sender_uid=?",
                        (d.get("cipher"), int(d.get("signalType", 1)), str(d.get("id")), me["uid"]))
        if cur.rowcount == 0:
            raise HTTPException(404, "message not found")
        row = c.execute("SELECT recipient_uid, chat_id FROM messages WHERE id=?", (str(d.get("id")),)).fetchone()
    if row:
        await ws_notify(row["recipient_uid"], {"type": "edit", "chat_id": row["chat_id"], "id": str(d.get("id"))})
    return {"ok": True}


@app.post("/api/messages/delete")
async def delete_message(req: Request, authorization: str | None = Header(None)):
    """Delete for me only (hides the message for the requester) or for everyone.

    Telegram-style: in a 1:1 chat either participant may delete for everyone;
    'me' only hides the message on the requester's side.
    """
    me = auth(authorization)
    d = await req.json()
    msg_id = str(d.get("id", ""))
    scope = str(d.get("scope", "everyone"))
    with db() as c:
        row = c.execute("SELECT sender_uid, recipient_uid, chat_id FROM messages WHERE id=?",
                        (msg_id,)).fetchone()
        if row is None:
            return {"ok": True}
        if me["uid"] not in (row["sender_uid"], row["recipient_uid"]):
            raise HTTPException(403, "not a participant")
        if scope == "me":
            c.execute("INSERT OR IGNORE INTO message_hidden(uid,message_id) VALUES(?,?)",
                      (me["uid"], msg_id))
        else:
            c.execute("DELETE FROM messages WHERE id=?", (msg_id,))
            c.execute("DELETE FROM message_hidden WHERE message_id=?", (msg_id,))
    if scope != "me":
        other = row["recipient_uid"] if row["sender_uid"] == me["uid"] else row["sender_uid"]
        await ws_notify(other, {"type": "delete", "chat_id": row["chat_id"], "id": msg_id})
    return {"ok": True, "scope": scope}


@app.post("/api/chats/clear")
async def clear_chat(req: Request, authorization: str | None = Header(None)):
    """Clear history: for me (hide everything) or for everyone (delete rows)."""
    me = auth(authorization)
    d = await req.json()
    chat_id = str(d.get("chat_id", ""))
    scope = str(d.get("scope", "me"))
    with db() as c:
        if scope == "me":
            c.execute(
                "INSERT OR IGNORE INTO message_hidden(uid,message_id)"
                " SELECT ?, id FROM messages WHERE chat_id=? AND (sender_uid=? OR recipient_uid=?)",
                (me["uid"], chat_id, me["uid"], me["uid"]))
        else:
            c.execute("DELETE FROM messages WHERE chat_id=? AND (sender_uid=? OR recipient_uid=?)",
                      (chat_id, me["uid"], me["uid"]))
    return {"ok": True, "scope": scope}


@app.get("/api/dialogs")
def dialogs(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT chat_id, sender_uid, recipient_uid, MAX(created_at) AS last_at FROM messages"
            " WHERE sender_uid=? OR recipient_uid=? GROUP BY chat_id ORDER BY last_at DESC",
            (me["uid"], me["uid"])).fetchall()
        out = []
        for r in rows:
            peer = r["recipient_uid"] if r["sender_uid"] == me["uid"] else r["sender_uid"]
            last = c.execute(
                "SELECT * FROM messages WHERE chat_id=?"
                " AND id NOT IN (SELECT message_id FROM message_hidden WHERE uid=?)"
                " ORDER BY created_at DESC LIMIT 1",
                (r["chat_id"], me["uid"])).fetchone()
            if last is None:
                continue  # everything in this chat is hidden/cleared for me
            u = c.execute("SELECT * FROM users WHERE uid=?", (peer,)).fetchone()
            unread = c.execute(
                "SELECT COUNT(*) n FROM messages m LEFT JOIN receipts rc"
                " ON rc.chat_id=m.chat_id AND rc.uid=?"
                " WHERE m.chat_id=? AND m.recipient_uid=? AND m.created_at>COALESCE(rc.read_at,0)"
                " AND m.id NOT IN (SELECT message_id FROM message_hidden WHERE uid=?)",
                (me["uid"], r["chat_id"], me["uid"], me["uid"])).fetchone()["n"]
            peer_receipt = c.execute("SELECT read_at FROM receipts WHERE chat_id=? AND uid=?",
                                     (r["chat_id"], peer)).fetchone()
            pref = c.execute("SELECT * FROM chat_prefs WHERE uid=? AND chat_id=?",
                             (me["uid"], r["chat_id"])).fetchone()
            prefs = {"pinned": bool(pref["pinned"]), "muted": bool(pref["muted"]),
                     "archived": bool(pref["archived"]),
                     "keep_unread": bool(pref["keep_unread"])} if pref else {
                     "pinned": False, "muted": False, "archived": False, "keep_unread": False}
            if prefs["keep_unread"] and unread == 0:
                unread = 1
            out.append({"chat_id": r["chat_id"], "peer_uid": peer,
                        "peer": pub_user(dict(u)) if u else None,
                        "last": _msg_out(last),
                        "last_at": last["created_at"], "unread": unread,
                        "peer_read_at": peer_receipt["read_at"] if peer_receipt else 0,
                        "prefs": prefs})
    return {"dialogs": out}


# ---------------------------------------------------------------- contacts / settings / blocks / prefs
@app.get("/api/contacts")
def list_contacts(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT u.* FROM contacts ct JOIN users u ON u.uid=ct.contact_uid"
            " WHERE ct.owner=? ORDER BY u.display_name, u.username", (me["uid"],)).fetchall()
    return {"contacts": [pub_user(dict(r)) for r in rows]}


@app.post("/api/contacts")
async def set_contact(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    target = str(d.get("uid", ""))
    enabled = bool(d.get("enabled", True))
    with db() as c:
        if not c.execute("SELECT uid FROM users WHERE uid=?", (target,)).fetchone():
            raise HTTPException(404, "user not found")
        if enabled:
            c.execute("INSERT OR IGNORE INTO contacts(owner,contact_uid) VALUES(?,?)",
                      (me["uid"], target))
        else:
            c.execute("DELETE FROM contacts WHERE owner=? AND contact_uid=?", (me["uid"], target))
    return {"ok": True, "enabled": enabled}


@app.get("/api/settings")
def get_settings(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute("SELECT k,v FROM user_settings WHERE uid=?", (me["uid"],)).fetchall()
    return {"settings": {r["k"]: r["v"] for r in rows}}


@app.post("/api/settings")
async def save_settings(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    settings = d.get("settings", d)
    if not isinstance(settings, dict):
        raise HTTPException(400, "settings object required")
    with db() as c:
        for k, v in settings.items():
            c.execute("INSERT INTO user_settings(uid,k,v) VALUES(?,?,?)"
                      " ON CONFLICT(uid,k) DO UPDATE SET v=excluded.v",
                      (me["uid"], str(k)[:64], str(v)[:256]))
    return {"ok": True}


@app.get("/api/blocks")
def list_blocks(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT u.* FROM blocks b JOIN users u ON u.uid=b.blocked WHERE b.blocker=?",
            (me["uid"],)).fetchall()
    return {"blocked": [pub_user(dict(r)) for r in rows]}


@app.post("/api/block")
async def set_block(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    target = str(d.get("uid", ""))
    enabled = bool(d.get("blocked", True))
    if target == me["uid"]:
        raise HTTPException(400, "cannot block yourself")
    with db() as c:
        if enabled:
            c.execute("INSERT OR IGNORE INTO blocks(blocker,blocked) VALUES(?,?)",
                      (me["uid"], target))
        else:
            c.execute("DELETE FROM blocks WHERE blocker=? AND blocked=?", (me["uid"], target))
    return {"ok": True, "blocked": enabled}


@app.get("/api/chats/prefs")
def all_chat_prefs(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute("SELECT * FROM chat_prefs WHERE uid=?", (me["uid"],)).fetchall()
    return {"prefs": {r["chat_id"]: {"pinned": bool(r["pinned"]), "muted": bool(r["muted"]),
                                     "archived": bool(r["archived"]),
                                     "keep_unread": bool(r["keep_unread"])} for r in rows}}


@app.post("/api/chats/prefs")
async def set_chat_prefs(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    chat_id = str(d.get("chat_id", ""))
    with db() as c:
        c.execute("INSERT OR IGNORE INTO chat_prefs(uid,chat_id) VALUES(?,?)", (me["uid"], chat_id))
        for key in ("pinned", "muted", "archived", "keep_unread"):
            if key in d:
                c.execute(f"UPDATE chat_prefs SET {key}=? WHERE uid=? AND chat_id=?",
                          (1 if d[key] else 0, me["uid"], chat_id))
        row = c.execute("SELECT * FROM chat_prefs WHERE uid=? AND chat_id=?",
                        (me["uid"], chat_id)).fetchone()
    return {"ok": True, "prefs": {"pinned": bool(row["pinned"]), "muted": bool(row["muted"]),
                                  "archived": bool(row["archived"]),
                                  "keep_unread": bool(row["keep_unread"])}}


# ---------------------------------------------------------------- presence/typing/receipts/reactions/polls
@app.post("/api/presence")
async def presence(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json() if req.headers.get("content-length") else {}
    online = d.get("online", True)
    # Ghost mode: the user chose to be invisible — heartbeats do not reveal them.
    if get_setting(me["uid"], "ghost", "false") == "true":
        return {"ok": True, "ghost": True}
    with db() as c:
        c.execute("UPDATE users SET last_seen=? WHERE uid=?",
                  (now_ms() if online else me["last_seen"] or 0, me["uid"]))
    return {"ok": True}


@app.get("/api/presence/{uid}")
def get_presence(uid: str, authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        u = c.execute("SELECT last_seen FROM users WHERE uid=?", (uid,)).fetchone()
    if not u:
        raise HTTPException(404, "user not found")
    # Privacy: «кто видит, когда я был(а) в сети» — Все / Только контакты / Никто.
    if uid != me["uid"]:
        if get_setting(uid, "ghost", "false") == "true":
            return {"uid": uid, "online": False, "last_seen": 0, "hidden": True}
        level = get_setting(uid, "privacy_lastSeen", "Все")
        if level == "Никто":
            return {"uid": uid, "online": False, "last_seen": 0, "hidden": True}
        if level == "Только контакты":
            with db() as c:
                contact = c.execute("SELECT 1 FROM contacts WHERE owner=? AND contact_uid=?",
                                    (uid, me["uid"])).fetchone()
            if not contact:
                return {"uid": uid, "online": False, "last_seen": 0, "hidden": True}
    ls = u["last_seen"] or 0
    return {"uid": uid, "online": (now_ms() - ls) < 20_000, "last_seen": ls}


@app.post("/api/typing")
async def typing(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        c.execute("INSERT INTO typing(chat_id,uid,typing,at) VALUES(?,?,?,?)"
                  " ON CONFLICT(chat_id,uid) DO UPDATE SET typing=excluded.typing, at=excluded.at",
                  (str(d.get("chat_id")), me["uid"], 1 if d.get("typing") else 0, now_ms()))
    return {"ok": True}


@app.post("/api/receipts")
async def receipts(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        c.execute("INSERT INTO receipts(chat_id,uid,read_at) VALUES(?,?,?)"
                  " ON CONFLICT(chat_id,uid) DO UPDATE SET read_at=excluded.read_at",
                  (str(d.get("chat_id")), me["uid"], now_ms()))
        # Reading a chat also drops the manual "keep as unread" marker.
        c.execute("UPDATE chat_prefs SET keep_unread=0 WHERE uid=? AND chat_id=?",
                  (me["uid"], str(d.get("chat_id"))))
    return {"ok": True}


@app.post("/api/reactions")
async def reactions(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        if d.get("enabled", True) and d.get("emoji", "heart"):
            c.execute("INSERT OR REPLACE INTO reactions(chat_id,message_id,uid,emoji) VALUES(?,?,?,?)",
                      (str(d.get("chat_id")), str(d.get("message_id")), me["uid"], str(d.get("emoji", "heart"))))
        else:
            c.execute("DELETE FROM reactions WHERE chat_id=? AND message_id=? AND uid=?",
                      (str(d.get("chat_id")), str(d.get("message_id")), me["uid"]))
    return {"ok": True}


@app.post("/api/polls/vote")
async def poll_vote(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        c.execute("INSERT OR REPLACE INTO poll_votes(chat_id,message_id,uid,option_idx) VALUES(?,?,?,?)",
                  (str(d.get("chat_id")), str(d.get("message_id")), me["uid"], int(d.get("option", 0))))
    return {"ok": True}


@app.get("/api/chats/{chat_id}/state")
def chat_state(chat_id: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        typing_rows = c.execute("SELECT * FROM typing WHERE chat_id=?", (chat_id,)).fetchall()
        receipt_rows = c.execute("SELECT * FROM receipts WHERE chat_id=?", (chat_id,)).fetchall()
        reaction_rows = c.execute("SELECT * FROM reactions WHERE chat_id=?", (chat_id,)).fetchall()
        poll_rows = c.execute("SELECT * FROM poll_votes WHERE chat_id=?", (chat_id,)).fetchall()
    reactions_map: dict[str, dict[str, str]] = {}
    for r in reaction_rows:
        reactions_map.setdefault(r["message_id"], {})[r["uid"]] = r["emoji"]
    polls_map: dict[str, dict[str, int]] = {}
    for r in poll_rows:
        polls_map.setdefault(r["message_id"], {})[r["uid"]] = r["option_idx"]
    return {
        "typing": {r["uid"]: {"typing": bool(r["typing"]), "at": r["at"]} for r in typing_rows},
        "receipts": {r["uid"]: r["read_at"] for r in receipt_rows},
        "reactions": reactions_map,
        "polls": polls_map,
    }


# ---------------------------------------------------------------- calls signaling
@app.post("/api/calls/send")
async def call_send(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    cid = str(d.get("id") or uuid.uuid4().hex)
    with db() as c:
        c.execute("INSERT INTO calls(id,chat_id,from_uid,to_uid,kind,payload,created_at)"
                  " VALUES(?,?,?,?,?,?,?)",
                  (cid, str(d.get("chat_id", "")), me["uid"], str(d.get("to_uid", "")),
                   str(d.get("kind", "call")), json.dumps(d.get("payload", {})), now_ms()))
    await ws_notify(str(d.get("to_uid", "")), {"type": "call", "id": cid})
    return {"id": cid}


@app.get("/api/calls/inbox")
def call_inbox(authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute("SELECT * FROM calls WHERE to_uid=? AND consumed=0 AND created_at>?"
                         " ORDER BY created_at ASC", (me["uid"], now_ms() - 120_000)).fetchall()
    return {"calls": [{"id": r["id"], "chat_id": r["chat_id"], "from": r["from_uid"],
                       "kind": r["kind"], "payload": json.loads(r["payload"] or "{}"),
                       "at": r["created_at"]} for r in rows]}


@app.post("/api/calls/ack")
async def call_ack(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        c.execute("UPDATE calls SET consumed=1 WHERE id=? AND to_uid=?", (str(d.get("id")), me["uid"]))
    return {"ok": True}


@app.post("/api/calls/node")
async def call_node_set(req: Request, authorization: str | None = Header(None)):
    auth(authorization)
    d = await req.json()
    chat_id, call_id = str(d.get("chat_id", "")), str(d.get("call_id", ""))
    fields = d.get("fields", {})
    with db() as c:
        row = c.execute("SELECT fields FROM call_nodes WHERE chat_id=? AND call_id=?",
                        (chat_id, call_id)).fetchone()
        merged = json.loads(row["fields"]) if row else {}
        merged.update(fields)
        c.execute("INSERT INTO call_nodes(chat_id,call_id,fields,updated_at) VALUES(?,?,?,?)"
                  " ON CONFLICT(chat_id,call_id) DO UPDATE SET fields=excluded.fields,"
                  " updated_at=excluded.updated_at",
                  (chat_id, call_id, json.dumps(merged), now_ms()))
    return {"ok": True, "fields": merged}


@app.get("/api/calls/node")
def call_node_get(chat_id: str, call_id: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        row = c.execute("SELECT fields FROM call_nodes WHERE chat_id=? AND call_id=?",
                        (chat_id, call_id)).fetchone()
    return {"fields": json.loads(row["fields"]) if row else {}}


# ---------------------------------------------------------------- files (encrypted blobs in messenger.db)
@app.post("/api/upload")
async def upload(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    data = await req.body()
    if len(data) > 64 * 1024 * 1024:
        raise HTTPException(413, "too big (max 64MB)")
    fid = uuid.uuid4().hex
    with db() as c:
        c.execute("INSERT INTO files(id,owner,data,created_at) VALUES(?,?,?,?)",
                  (fid, me["uid"], data, now_ms()))
    return {"file_id": fid, "id": fid, "url": f"/api/files/{fid}"}


@app.get("/api/files/{file_id}")
def get_file(file_id: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        row = c.execute("SELECT data FROM files WHERE id=?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404, "file not found")
    return Response(content=row["data"], media_type="application/octet-stream")


# ---------------------------------------------------------------- websocket wake-ups
@app.websocket("/ws")
async def ws(ws: WebSocket, token: str = ""):
    try:
        me = auth("Bearer " + token)
    except Exception:
        await ws.close(code=4401)
        return
    await ws.accept()
    WS_PEERS.setdefault(me["uid"], set()).add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        WS_PEERS.get(me["uid"], set()).discard(ws)


@app.on_event("startup")
def _startup():
    init_db()
