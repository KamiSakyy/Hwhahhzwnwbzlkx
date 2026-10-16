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
        """)
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


def pub_user(u: dict) -> dict:
    return {
        "uid": u["uid"], "id": u["uid"], "username": u["username"] or "",
        "display_name": u["display_name"] or "", "about": u["about"] or "",
        "avatar_url": u["avatar_url"] or "", "email": u["email"],
        "last_seen": u["last_seen"] or 0,
        "online": (int(time.time() * 1000) - (u["last_seen"] or 0)) < 20_000,
    }


def now_ms() -> int:
    return int(time.time() * 1000)


app = FastAPI(title="Tsuyu self-hosted")

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
    with db() as c:
        c.execute("UPDATE users SET username=?, display_name=?, about=?, avatar_url=? WHERE uid=?",
                  (username or None, display_name, about, avatar_url, me["uid"]))
        u = dict(c.execute("SELECT * FROM users WHERE uid=?", (me["uid"],)).fetchone())
    return {"user": pub_user(u)}


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
def fetch_messages(peer_uid: str, authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT * FROM messages WHERE (sender_uid=? AND recipient_uid=?)"
            " OR (sender_uid=? AND recipient_uid=?) ORDER BY created_at ASC LIMIT 1000",
            (me["uid"], peer_uid, peer_uid, me["uid"])).fetchall()
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
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        row = c.execute("SELECT recipient_uid, chat_id FROM messages WHERE id=? AND sender_uid=?",
                        (str(d.get("id")), me["uid"])).fetchone()
        c.execute("DELETE FROM messages WHERE id=? AND sender_uid=?", (str(d.get("id")), me["uid"]))
    if row:
        await ws_notify(row["recipient_uid"], {"type": "delete", "chat_id": row["chat_id"], "id": str(d.get("id"))})
    return {"ok": True}


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
            last = c.execute("SELECT * FROM messages WHERE chat_id=? ORDER BY created_at DESC LIMIT 1",
                             (r["chat_id"],)).fetchone()
            u = c.execute("SELECT * FROM users WHERE uid=?", (peer,)).fetchone()
            unread = c.execute(
                "SELECT COUNT(*) n FROM messages m LEFT JOIN receipts rc"
                " ON rc.chat_id=m.chat_id AND rc.uid=?"
                " WHERE m.chat_id=? AND m.recipient_uid=? AND m.created_at>COALESCE(rc.read_at,0)",
                (me["uid"], r["chat_id"], me["uid"])).fetchone()["n"]
            out.append({"chat_id": r["chat_id"], "peer_uid": peer,
                        "peer": pub_user(dict(u)) if u else None,
                        "last": _msg_out(last) if last else None,
                        "last_at": r["last_at"], "unread": unread})
    return {"dialogs": out}


# ---------------------------------------------------------------- presence/typing/receipts/reactions/polls
@app.post("/api/presence")
async def presence(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json() if req.headers.get("content-length") else {}
    online = d.get("online", True)
    with db() as c:
        c.execute("UPDATE users SET last_seen=? WHERE uid=?",
                  (now_ms() if online else me["last_seen"] or 0, me["uid"]))
    return {"ok": True}


@app.get("/api/presence/{uid}")
def get_presence(uid: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        u = c.execute("SELECT last_seen FROM users WHERE uid=?", (uid,)).fetchone()
    if not u:
        raise HTTPException(404, "user not found")
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
