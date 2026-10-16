import tempfile
import unittest
from pathlib import Path

from storage import AccountConflict, Store, hash_password, verify_password


class StoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp_dir.name) / "test.sqlite3")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _account(self, telegram_id: int, email: str, username: str):
        return self.store.create_account(
            telegram_id,
            email,
            username,
            hash_password("correct horse battery staple"),
        )

    def test_password_hash_is_salted_and_verifiable(self) -> None:
        first = hash_password("a long unique password")
        second = hash_password("a long unique password")
        self.assertNotEqual(first, second)
        self.assertNotIn("a long unique password", first)
        self.assertTrue(verify_password("a long unique password", first))
        self.assertFalse(verify_password("wrong password", first))

    def test_account_creation_and_case_insensitive_lookup(self) -> None:
        account = self._account(101, "person@example.com", "cool_user")
        self.assertEqual(account["id"], self.store.get_account_by_telegram(101)["id"])
        self.assertEqual(
            account["id"], self.store.get_account_by_email("PERSON@example.com")["id"]
        )
        self.assertEqual(
            account["id"], self.store.get_account_by_username("COOL_USER")["id"]
        )
        with self.assertRaises(AccountConflict) as error:
            self.store.create_account(
                102,
                "another@example.com",
                "COOL_USER",
                hash_password("another long password"),
            )
        self.assertEqual(error.exception.field, "username")

    def test_login_binds_account_and_logout_releases_it(self) -> None:
        self._account(201, "person@example.com", "cool_user")
        self.store.unlink_telegram(201)

        account, status = self.store.authenticate(
            "person@example.com", "correct horse battery staple", 202
        )
        self.assertEqual(status, "ok")
        self.assertEqual(account["telegram_user_id"], 202)

        self.store.unlink_telegram(202)
        account, status = self.store.authenticate(
            "person@example.com", "incorrect password", 203
        )
        self.assertIsNone(account)
        self.assertEqual(status, "invalid")

        account, status = self.store.authenticate(
            "person@example.com", "correct horse battery staple", 203
        )
        self.assertEqual(status, "ok")
        self.assertEqual(account["telegram_user_id"], 203)

    def test_messages_inbox_unread_and_history(self) -> None:
        alice = self._account(301, "alice@example.com", "alice_1")
        bob = self._account(302, "bob@example.com", "bob_2")

        self.store.add_message(alice["id"], bob["id"], "Hello Bob")
        self.assertEqual(self.store.unread_count(bob["id"]), 1)
        inbox = self.store.inbox(bob["id"])
        self.assertEqual(len(inbox), 1)
        self.assertEqual(inbox[0]["username"], "alice_1")
        self.assertEqual(inbox[0]["unread"], 1)
        self.assertEqual(inbox[0]["last_body"], "Hello Bob")

        conversation = self.store.get_conversation(bob["id"], alice["id"])
        self.assertEqual([row["body"] for row in conversation], ["Hello Bob"])
        self.assertEqual(conversation[0]["sender_username"], "alice_1")

        self.store.mark_read(bob["id"], alice["id"])
        self.assertEqual(self.store.unread_count(bob["id"]), 0)
        self.assertEqual(self.store.inbox(bob["id"])[0]["unread"], 0)

    def test_single_ui_message_reference_is_updated(self) -> None:
        self.store.save_ui_message(401, 401, 777)
        self.assertEqual(self.store.get_ui_message(401)["message_id"], 777)
        self.store.save_ui_message(401, 401, 778)
        self.assertEqual(self.store.get_ui_message(401)["message_id"], 778)


if __name__ == "__main__":
    unittest.main()
