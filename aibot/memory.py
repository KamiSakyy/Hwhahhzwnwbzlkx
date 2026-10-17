# -*- coding: utf-8 -*-
"""Память диалогов: SQLite bot.db, до 3 МБ истории на пользователя."""
import sqlite3
import threading
import time

from . import config


class Memory:
    def __init__(self, path=None):
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path or config.DB_FILE, check_same_thread=False)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS msgs("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, role TEXT, text TEXT, ts REAL)"
        )
        self._db.execute("CREATE INDEX IF NOT EXISTS idx_msgs_user ON msgs(user_id, id)")
        self._db.commit()

    def add(self, user_id, role, text):
        text = (text or "")[:8000]
        with self._lock:
            self._db.execute(
                "INSERT INTO msgs(user_id, role, text, ts) VALUES(?,?,?,?)",
                (user_id, role, text, time.time()),
            )
            self._trim_locked(user_id)
            self._db.commit()

    def _trim_locked(self, user_id):
        total = self._db.execute(
            "SELECT COALESCE(SUM(LENGTH(text)),0) FROM msgs WHERE user_id=?", (user_id,)
        ).fetchone()[0]
        limit = config.MAX_HISTORY_BYTES_PER_USER
        while total > limit:
            row = self._db.execute(
                "SELECT id, LENGTH(text) FROM msgs WHERE user_id=? ORDER BY id ASC LIMIT 1",
                (user_id,),
            ).fetchone()
            if not row:
                break
            self._db.execute("DELETE FROM msgs WHERE id=?", (row[0],))
            total -= row[1]

    def context(self, user_id):
        with self._lock:
            rows = self._db.execute(
                "SELECT role, text FROM msgs WHERE user_id=? ORDER BY id DESC LIMIT ?",
                (user_id, config.CONTEXT_MESSAGES),
            ).fetchall()
        rows.reverse()
        msgs, total = [], 0
        for role, text in rows:
            if total + len(text) > config.CONTEXT_CHARS and msgs:
                continue
            msgs.append({"role": role, "content": text})
            total += len(text)
        return msgs

    def clear(self, user_id):
        with self._lock:
            self._db.execute("DELETE FROM msgs WHERE user_id=?", (user_id,))
            self._db.commit()

    def size_bytes(self, user_id):
        with self._lock:
            return self._db.execute(
                "SELECT COALESCE(SUM(LENGTH(text)),0) FROM msgs WHERE user_id=?", (user_id,)
            ).fetchone()[0]
