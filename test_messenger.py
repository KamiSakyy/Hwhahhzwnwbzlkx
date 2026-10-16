import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from main import Messenger
from storage import Store, hash_password


class FakeBot:
    def __init__(self) -> None:
        self.sent = []
        self.edited = []

    async def send_message(self, **kwargs):
        self.sent.append(kwargs)
        return SimpleNamespace(message_id=900 + len(self.sent))

    async def edit_message_text(self, **kwargs):
        self.edited.append(kwargs)
        return None


class MessengerScreenTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp_dir.name) / "test.sqlite3")
        self.messenger = Messenger(self.store)
        self.bot = FakeBot()

    async def asyncTearDown(self) -> None:
        self.temp_dir.cleanup()

    async def test_reuses_one_bot_message_for_screen_updates(self) -> None:
        await self.messenger.update_screen(self.bot, 501)
        self.assertEqual(len(self.bot.sent), 1)
        self.assertIn("АРИ · МЕССЕНДЖЕР", self.bot.sent[0]["text"])

        await self.messenger.update_screen(self.bot, 501)
        self.assertEqual(len(self.bot.sent), 1)
        self.assertEqual(len(self.bot.edited), 1)
        self.assertEqual(self.bot.edited[0]["message_id"], 901)

    async def test_new_message_updates_search_screen_with_unread_count(self) -> None:
        alice = self.store.create_account(
            601, "alice@example.com", "alice_1", hash_password("a very long password")
        )
        bob = self.store.create_account(
            602, "bob@example.com", "bob_2", hash_password("another long password")
        )
        self.messenger.session(601).stage = "search"

        await self.messenger.update_screen(self.bot, 601)
        self.assertIn("ПОИСК", self.bot.sent[0]["text"])

        self.store.add_message(bob["id"], alice["id"], "Новое сообщение")
        await self.messenger.update_screen(self.bot, 601)

        self.assertEqual(len(self.bot.sent), 1)
        self.assertEqual(len(self.bot.edited), 1)
        self.assertIn("ПОИСК", self.bot.edited[0]["text"])
        self.assertIn("Непрочитанных сообщений: 1", self.bot.edited[0]["text"])


if __name__ == "__main__":
    unittest.main()
