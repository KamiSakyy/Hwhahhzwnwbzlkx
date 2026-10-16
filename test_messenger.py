"""Offline tests for the one-message messenger. Run: python -m pytest test_messenger.py -q"""
import os
import tempfile

import messenger as ms

P = os.path.join(tempfile.mkdtemp(), "test.db")
IVAN_MAIL = "ivan" + "@" + "mail.ru"
MARIA_MAIL = "maria" + "@" + "mail.ru"
ms.init_db(P)


def _mk(tg, email, pw, name):
    u, err = ms.create_user(tg, email, pw, name, P)
    assert u, err
    return u


def test_register_login_and_no_plain_password():
    u = _mk(111, IVAN_MAIL, "secret1", "ivan")
    assert u["username"] == "ivan"
    assert "secret1" not in u["pass_hash"]
    assert ms.check_login(IVAN_MAIL, "secret1", P)["id"] == u["id"]
    assert ms.check_login(IVAN_MAIL, "wrong", P) is None
    dup, err = ms.create_user(222, IVAN_MAIL, "xxxx1", "other", P)
    assert dup is None and "почта" in err.lower()
    dup2, err2 = ms.create_user(333, IVAN_MAIL, "xxxx1", "@IVAN", P)
    assert dup2 is None and "юзернейм" in err2.lower()


def test_search_by_username():
    _mk(222, MARIA_MAIL, "maria123", "maria")
    found = ms.search_users("@mar", exclude_id=1, path=P)
    assert [u["username"] for u in found] == ["maria"]
    assert ms.find_by_username("MARIA", P)["email"] == MARIA_MAIL


def test_messaging_unread_read_and_chatlist():
    ivan = ms.find_by_username("ivan", P)
    maria = ms.find_by_username("maria", P)
    ms.send_message(ivan["id"], maria["id"], "привет!", P)
    ms.send_message(ivan["id"], maria["id"], "ты тут?", P)
    assert ms.unread_total(maria["id"], P) == 2
    conv = ms.conversation(ivan["id"], maria["id"], path=P)
    assert [m["text"] for m in conv] == ["привет!", "ты тут?"]
    ms.mark_read(maria["id"], ivan["id"], P)
    assert ms.unread_total(maria["id"], P) == 0
    ms.send_message(maria["id"], ivan["id"], "привет, да!", P)
    chats = ms.chat_list(ivan["id"], P)
    assert chats[0]["partner"]["username"] == "maria"
    assert chats[0]["unread"] == 1


def test_screens_fit_telegram_and_hide_password():
    ivan = ms.find_by_username("ivan", P)
    maria = ms.find_by_username("maria", P)
    for screen in (ms.scr_auth(), ms.scr_main(ivan, path=P), ms.scr_chat(ivan, maria, path=P),
                   ms.scr_profile(ivan), ms.scr_registered(ivan), ms.scr_search(ivan)):
        assert len(screen) <= 4096
        assert "secret1" not in screen and "maria123" not in screen
    assert "@maria" in ms.scr_main(ivan, path=P)
    assert "привет, да!" in ms.scr_chat(ivan, maria, path=P)


def test_session_and_screen_persist():
    ms.set_session(999, P, state="main", temp={"a": 1})
    s = ms.get_session(999, P)
    assert s["state"] == "main" and s["temp"] == {"a": 1}
    ms.set_screen(999, 424242, P)
    assert ms.get_screen(999, P) == 424242
    ms.drop_screen(999, P)
    assert ms.get_screen(999, P) is None


def test_bot_vocabulary():
    import bot
    assert bot.norm("  НаЗаД ") == "назад"
    assert bot.BOT_TOKEN.count(":") == 1 and len(bot.BOT_TOKEN) > 30  # token embedded


if __name__ == "__main__":
    for n, f in sorted(dict(globals()).items()):
        if n.startswith("test_"):
            f(); print("ok", n)
    print("ALL TESTS PASSED")
