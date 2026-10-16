"""SQLite storage and password handling for the Telegram messenger."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

PASSWORD_ITERATIONS = 310_000
PASSWORD_SALT_BYTES = 16
MAX_LOGIN_ATTEMPTS = 8
LOGIN_WINDOW_SECONDS = 15 * 60
LOGIN_LOCK_SECONDS = 15 * 60


class AccountConflict(Exception):
    """Raised when an email address or username is already registered."""

    def __init__(self, field: str) -> None:
        super().__init__(field)
        self.field = field


def hash_password(password: str) -> str:
    """Return a salted PBKDF2-SHA256 password hash suitable for database storage."""

    salt = secrets.token_bytes(PASSWORD_SALT_BYTES)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS
    )
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, encoded_hash: str) -> bool:
    """Constant-time verification of a password hash produced by ``hash_password``."""

    try:
        algorithm, iterations_text, salt_hex, digest_hex = encoded_hash.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        iterations = int(iterations_text)
        if not 100_000 <= iterations <= 2_000_000:
            return False
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
    except (ValueError, TypeError):
        return False

    actual = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, iterations
    )
    return hmac.compare_digest(actual, expected)


# A dummy hash keeps failed lookups from returning immediately when an email is
# unknown. It contains no credential and is never accepted as a user password.
_DUMMY_PASSWORD_HASH = hash_password(secrets.token_urlsafe(32))


class Store:
    """Small SQLite repository. Use a Railway Volume for a durable database."""

    def __init__(self, database_path: str | Path) -> None:
        raw_path = str(database_path)
        self._database_uri = raw_path == ":memory:"
        self._memory_keeper: sqlite3.Connection | None = None
        if self._database_uri:
            self.database_path = f"file:ari_messenger_{id(self)}?mode=memory&cache=shared"
            self._memory_keeper = self._connect()
        else:
            self.database_path = str(Path(raw_path).expanduser())
            Path(self.database_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(
            self.database_path, timeout=30, uri=self._database_uri
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    telegram_user_id   INTEGER UNIQUE,
                    email               TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    username            TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    password_hash       TEXT NOT NULL,
                    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender_id           INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    recipient_id        INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    body                TEXT NOT NULL CHECK(length(body) <= 4000),
                    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at             TEXT
                );

                CREATE INDEX IF NOT EXISTS messages_pair_id
                    ON messages(sender_id, recipient_id, id);
                CREATE INDEX IF NOT EXISTS messages_unread
                    ON messages(recipient_id, read_at, sender_id);

                CREATE TABLE IF NOT EXISTS ui_messages (
                    telegram_user_id   INTEGER PRIMARY KEY,
                    chat_id             INTEGER NOT NULL,
                    message_id          INTEGER NOT NULL,
                    updated_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS login_attempts (
                    telegram_user_id   INTEGER PRIMARY KEY,
                    attempts            INTEGER NOT NULL,
                    window_started      REAL NOT NULL,
                    locked_until        REAL NOT NULL DEFAULT 0
                );
                """
            )

    def get_account_by_telegram(self, telegram_user_id: int) -> sqlite3.Row | None:
        with self._connection() as connection:
            return connection.execute(
                "SELECT * FROM accounts WHERE telegram_user_id = ?",
                (telegram_user_id,),
            ).fetchone()

    def get_account_by_email(self, email: str) -> sqlite3.Row | None:
        with self._connection() as connection:
            return connection.execute(
                "SELECT * FROM accounts WHERE email = ? COLLATE NOCASE",
                (email,),
            ).fetchone()

    def get_account_by_username(self, username: str) -> sqlite3.Row | None:
        with self._connection() as connection:
            return connection.execute(
                "SELECT * FROM accounts WHERE username = ? COLLATE NOCASE",
                (username,),
            ).fetchone()

    def get_account_by_id(self, account_id: int) -> sqlite3.Row | None:
        with self._connection() as connection:
            return connection.execute(
                "SELECT * FROM accounts WHERE id = ?", (account_id,)
            ).fetchone()

    def create_account(
        self,
        telegram_user_id: int,
        email: str,
        username: str,
        password_hash: str,
    ) -> sqlite3.Row:
        try:
            with self._connection() as connection:
                connection.execute(
                    """
                    INSERT INTO accounts (telegram_user_id, email, username, password_hash)
                    VALUES (?, ?, ?, ?)
                    """,
                    (telegram_user_id, email, username, password_hash),
                )
        except sqlite3.IntegrityError as error:
            if self.get_account_by_email(email) is not None:
                raise AccountConflict("email") from error
            if self.get_account_by_username(username) is not None:
                raise AccountConflict("username") from error
            raise AccountConflict("telegram") from error

        account = self.get_account_by_telegram(telegram_user_id)
        if account is None:  # Defensive; the insert above should always create it.
            raise RuntimeError("Account was inserted but could not be loaded")
        return account

    def authenticate(
        self, email: str, password: str, telegram_user_id: int
    ) -> tuple[sqlite3.Row | None, str]:
        """Verify credentials, enforce a short lockout, then bind the Telegram ID.

        Returns ``(account, status)`` where status is ``ok``, ``invalid``,
        ``linked``, or ``rate_limited``. Password text is never stored.
        """

        now = time.time()
        with self._connection() as connection:
            attempt = connection.execute(
                "SELECT * FROM login_attempts WHERE telegram_user_id = ?",
                (telegram_user_id,),
            ).fetchone()
        if attempt is not None and attempt["locked_until"] > now:
            return None, "rate_limited"

        account = self.get_account_by_email(email)
        candidate_hash = (
            account["password_hash"] if account is not None else _DUMMY_PASSWORD_HASH
        )
        password_ok = verify_password(password, candidate_hash)
        linked_elsewhere = (
            account is not None
            and account["telegram_user_id"] is not None
            and account["telegram_user_id"] != telegram_user_id
        )

        if account is None or not password_ok or linked_elsewhere:
            self._record_failed_login(telegram_user_id, now)
            if linked_elsewhere and password_ok:
                return None, "linked"
            return None, "invalid"

        with self._connection() as connection:
            cursor = connection.execute(
                """
                UPDATE accounts
                SET telegram_user_id = ?
                WHERE id = ? AND (telegram_user_id IS NULL OR telegram_user_id = ?)
                """,
                (telegram_user_id, account["id"], telegram_user_id),
            )
            binding_failed = cursor.rowcount != 1
            if not binding_failed:
                connection.execute(
                    "DELETE FROM login_attempts WHERE telegram_user_id = ?",
                    (telegram_user_id,),
                )

        if binding_failed:
            self._record_failed_login(telegram_user_id, now)
            return None, "linked"
        return self.get_account_by_telegram(telegram_user_id), "ok"

    def _record_failed_login(self, telegram_user_id: int, now: float) -> None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM login_attempts WHERE telegram_user_id = ?",
                (telegram_user_id,),
            ).fetchone()
            if row is None or now - row["window_started"] >= LOGIN_WINDOW_SECONDS:
                connection.execute(
                    """
                    INSERT INTO login_attempts (telegram_user_id, attempts, window_started, locked_until)
                    VALUES (?, 1, ?, 0)
                    ON CONFLICT(telegram_user_id) DO UPDATE SET
                        attempts = 1,
                        window_started = excluded.window_started,
                        locked_until = 0
                    """,
                    (telegram_user_id, now),
                )
                return

            attempts = row["attempts"] + 1
            locked_until = now + LOGIN_LOCK_SECONDS if attempts >= MAX_LOGIN_ATTEMPTS else 0
            connection.execute(
                """
                UPDATE login_attempts
                SET attempts = ?, locked_until = ?
                WHERE telegram_user_id = ?
                """,
                (attempts, locked_until, telegram_user_id),
            )

    def unlink_telegram(self, telegram_user_id: int) -> None:
        with self._connection() as connection:
            connection.execute(
                "UPDATE accounts SET telegram_user_id = NULL WHERE telegram_user_id = ?",
                (telegram_user_id,),
            )
            connection.execute(
                "DELETE FROM login_attempts WHERE telegram_user_id = ?",
                (telegram_user_id,),
            )

    def save_ui_message(
        self, telegram_user_id: int, chat_id: int, message_id: int
    ) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO ui_messages (telegram_user_id, chat_id, message_id)
                VALUES (?, ?, ?)
                ON CONFLICT(telegram_user_id) DO UPDATE SET
                    chat_id = excluded.chat_id,
                    message_id = excluded.message_id,
                    updated_at = CURRENT_TIMESTAMP
                """,
                (telegram_user_id, chat_id, message_id),
            )

    def get_ui_message(self, telegram_user_id: int) -> sqlite3.Row | None:
        with self._connection() as connection:
            return connection.execute(
                "SELECT * FROM ui_messages WHERE telegram_user_id = ?",
                (telegram_user_id,),
            ).fetchone()

    def add_message(self, sender_id: int, recipient_id: int, body: str) -> int:
        with self._connection() as connection:
            cursor = connection.execute(
                "INSERT INTO messages (sender_id, recipient_id, body) VALUES (?, ?, ?)",
                (sender_id, recipient_id, body),
            )
            return int(cursor.lastrowid)

    def get_conversation(
        self, first_account_id: int, second_account_id: int, limit: int = 12
    ) -> list[sqlite3.Row]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT m.id, m.sender_id, m.recipient_id, m.body, m.created_at,
                       sender.username AS sender_username
                FROM messages AS m
                JOIN accounts AS sender ON sender.id = m.sender_id
                WHERE (m.sender_id = ? AND m.recipient_id = ?)
                   OR (m.sender_id = ? AND m.recipient_id = ?)
                ORDER BY m.id DESC
                LIMIT ?
                """,
                (
                    first_account_id,
                    second_account_id,
                    second_account_id,
                    first_account_id,
                    limit,
                ),
            ).fetchall()
        return list(reversed(rows))

    def mark_read(self, recipient_id: int, sender_id: int) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                UPDATE messages
                SET read_at = CURRENT_TIMESTAMP
                WHERE recipient_id = ? AND sender_id = ? AND read_at IS NULL
                """,
                (recipient_id, sender_id),
            )

    def unread_count(self, account_id: int) -> int:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS count FROM messages WHERE recipient_id = ? AND read_at IS NULL",
                (account_id,),
            ).fetchone()
        return int(row["count"])

    def inbox(self, account_id: int, limit: int = 12) -> list[dict[str, Any]]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT peer.id AS peer_id,
                       peer.username AS username,
                       latest.body AS last_body,
                       latest.created_at AS last_created_at,
                       (
                           SELECT COUNT(*)
                           FROM messages AS unread
                           WHERE unread.sender_id = peer.id
                             AND unread.recipient_id = ?
                             AND unread.read_at IS NULL
                       ) AS unread
                FROM (
                    SELECT CASE
                               WHEN sender_id = ? THEN recipient_id
                               ELSE sender_id
                           END AS peer_id,
                           MAX(id) AS latest_message_id
                    FROM messages
                    WHERE sender_id = ? OR recipient_id = ?
                    GROUP BY peer_id
                ) AS conversations
                JOIN accounts AS peer ON peer.id = conversations.peer_id
                JOIN messages AS latest ON latest.id = conversations.latest_message_id
                ORDER BY latest.id DESC
                LIMIT ?
                """,
                (account_id, account_id, account_id, account_id, limit),
            ).fetchall()
        return [dict(row) for row in rows]
