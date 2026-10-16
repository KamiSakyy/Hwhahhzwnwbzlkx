#!/usr/bin/env python3
"""
✉️ ARI — self-hosted messenger server (Railway).

STRICT RULE (by design): ALL data lives ONLY in SQLite files inside this
container — /app/messenger.db. No external DB, no volumes required.
On redeploy without a volume the DB is recreated empty. The server
REFUSES to start if it cannot create/open messenger.db.

The server is E2EE-blind: it stores only ciphertext (Double Ratchet),
public keys and encrypted media blobs. It can never read messages.
"""
from __future__ import annotations

import base64
import hashlib
import hmac as hmac_mod
import json
import os
import sqlite3
import time

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, Response

DB_PATH = os.getenv("DB_PATH", "/app/messenger.db")

# --------------------------------------------------------------------------
# DB — strictly a local .db file inside the container
# --------------------------------------------------------------------------
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
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            email TEXT UNIQUE NOT NULL,
            username TEXT UNIQUE NOT NULL,
            pass_hash TEXT NOT NULL, salt TEXT NOT NULL,
            identity_key TEXT, signed_prekey TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender_id INTEGER NOT NULL, recipient_id INTEGER NOT NULL,
            ciphertext TEXT NOT NULL, header TEXT NOT NULL, prelude TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS media(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_id INTEGER NOT NULL, data BLOB NOT NULL, created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS media_access(media_id INTEGER, user_id INTEGER);
        """)
        if c.execute("SELECT v FROM meta WHERE k='secret'").fetchone() is None:
            c.execute("INSERT INTO meta(k,v) VALUES('secret',?)", (os.urandom(32).hex(),))
    # HARD REQUIREMENT: server dies if messenger.db is not a real file here
    if not os.path.isfile(DB_PATH) or os.path.getsize(DB_PATH) == 0:
        raise SystemExit(f"FATAL: {DB_PATH} was not created — refusing to start")
    print(f"[ari] DB ready: {DB_PATH} ({os.path.getsize(DB_PATH)} bytes)", flush=True)


def _secret() -> bytes:
    with db() as c:
        return bytes.fromhex(c.execute("SELECT v FROM meta WHERE k='secret'").fetchone()["v"])


# --------------------------------------------------------------------------
# Auth: PBKDF2 passwords, HMAC tokens
# --------------------------------------------------------------------------
def _hash(pw: str, salt_hex: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), 120_000).hex()


def make_token(uid: int) -> str:
    payload = base64.urlsafe_b64encode(json.dumps({"uid": uid, "exp": int(time.time()) + 30 * 86400}).encode()).decode()
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
        u = c.execute("SELECT * FROM users WHERE id=?", (data["uid"],)).fetchone()
    if not u:
        raise HTTPException(401, "user gone")
    return dict(u)


def public_user(u: dict) -> dict:
    return {"id": u["id"], "username": u["username"], "email": u["email"]}


app = FastAPI(title="ARI Messenger")


@app.get("/health")
def health():
    ok = os.path.isfile(DB_PATH)
    return {"status": "ok" if ok else "no-db", "db": DB_PATH, "app": "ari"}


@app.post("/auth/register")
async def register(req: Request):
    d = await req.json()
    email = str(d.get("email", "")).strip().lower()
    username = str(d.get("username", "")).strip().lstrip("@").lower()
    password = str(d.get("password", ""))
    if "@" not in email or "." not in email:
        raise HTTPException(400, "invalid email")
    if len(username) < 3 or not all(ch.isalnum() or ch == "_" for ch in username):
        raise HTTPException(400, "username: 3+ chars, letters/digits/_")
    if len(password) < 4:
        raise HTTPException(400, "password too short (min 4)")
    salt = os.urandom(16).hex()
    try:
        with db() as c:
            cur = c.execute(
                "INSERT INTO users(email,username,pass_hash,salt,created_at) VALUES(?,?,?,?,?)",
                (email, username, _hash(password, salt), salt, int(time.time())),
            )
            uid = cur.lastrowid
    except sqlite3.IntegrityError:
        raise HTTPException(409, "email or username already taken")
    with db() as c:
        u = dict(c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone())
    return {"token": make_token(uid), "user": public_user(u)}


@app.post("/auth/login")
async def login(req: Request):
    d = await req.json()
    email = str(d.get("email", "")).strip().lower()
    password = str(d.get("password", ""))
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
    if not u or dict(u)["pass_hash"] != _hash(password, dict(u)["salt"]):
        raise HTTPException(401, "wrong email or password")
    u = dict(u)
    return {"token": make_token(u["id"]), "user": public_user(u)}


@app.post("/keys")
async def upload_keys(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        c.execute("UPDATE users SET identity_key=?, signed_prekey=? WHERE id=?",
                  (d.get("identity_key"), d.get("signed_prekey"), me["id"]))
    return {"ok": True}


@app.get("/users/search")
def search(q: str = "", authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT * FROM users WHERE username LIKE ? AND id != ? ORDER BY username LIMIT 30",
            (f"%{q.strip().lstrip('@').lower()}%", me["id"]),
        ).fetchall()
    return [public_user(dict(r)) for r in rows]


@app.get("/users/{username}/bundle")
def bundle(username: str, authorization: str | None = Header(None)):
    auth(authorization)
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE username=?", (username.lstrip("@").lower(),)).fetchone()
    if not u:
        raise HTTPException(404, "user not found")
    u = dict(u)
    if not u["identity_key"] or not u["signed_prekey"]:
        raise HTTPException(409, "user has no keys yet (they must open the app once)")
    return {"user": public_user(u), "identity_key": u["identity_key"], "signed_prekey": u["signed_prekey"]}


@app.post("/messages")
async def send_message(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    to_id = int(d["to_id"])
    with db() as c:
        if not c.execute("SELECT id FROM users WHERE id=?", (to_id,)).fetchone():
            raise HTTPException(404, "recipient not found")
        cur = c.execute(
            "INSERT INTO messages(sender_id,recipient_id,ciphertext,header,prelude,created_at)"
            " VALUES(?,?,?,?,?,?)",
            (me["id"], to_id, d["ciphertext"], d["header"], d.get("prelude"), int(time.time())),
        )
        mid = cur.lastrowid
    return {"id": mid}


@app.get("/messages/sync")
def sync(since: int = 0, authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        rows = c.execute(
            "SELECT m.*, u.username AS sender_name, r.username AS recipient_name"
            " FROM messages m JOIN users u ON u.id=m.sender_id JOIN users r ON r.id=m.recipient_id"
            " WHERE (m.sender_id=? OR m.recipient_id=?) AND m.id>?"
            " ORDER BY m.id ASC LIMIT 500",
            (me["id"], me["id"], since),
        ).fetchall()
    return [dict(r) for r in rows]


@app.post("/media")
async def upload_media(req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    data = await req.body()
    if len(data) > 64 * 1024 * 1024:
        raise HTTPException(413, "too big (max 64MB)")
    with db() as c:
        cur = c.execute("INSERT INTO media(owner_id,data,created_at) VALUES(?,?,?)",
                        (me["id"], data, int(time.time())))
        mid = cur.lastrowid
        c.execute("INSERT INTO media_access(media_id,user_id) VALUES(?,?)", (mid, me["id"]))
    return {"id": mid}


@app.post("/media/{media_id}/share")
async def share_media(media_id: int, req: Request, authorization: str | None = Header(None)):
    me = auth(authorization)
    d = await req.json()
    with db() as c:
        if not c.execute("SELECT 1 FROM media WHERE id=? AND owner_id=?", (media_id, me["id"])).fetchone():
            raise HTTPException(404, "media not found")
        c.execute("INSERT INTO media_access(media_id,user_id) VALUES(?,?)", (media_id, int(d["user_id"])))
    return {"ok": True}


@app.get("/media/{media_id}")
def get_media(media_id: int, authorization: str | None = Header(None)):
    me = auth(authorization)
    with db() as c:
        if not c.execute("SELECT 1 FROM media_access WHERE media_id=? AND user_id=?",
                         (media_id, me["id"])).fetchone():
            raise HTTPException(404, "media not found")
        row = c.execute("SELECT data FROM media WHERE id=?", (media_id,)).fetchone()
    if not row:
        raise HTTPException(404, "media not found")
    return Response(content=row["data"], media_type="application/octet-stream")


@app.on_event("startup")
def _startup():
    init_db()
