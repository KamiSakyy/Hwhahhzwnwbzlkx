# -*- coding: utf-8 -*-
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from aibot import config, router, tools
from aibot.memory import Memory


def test_units_50_plus_and_vision():
    units = config.all_units()
    assert len(units) >= 50, len(units)
    assert sum(1 for u in units if u["vision"]) >= 5
    names = {u["provider"] for u in units}
    assert {"mistral", "openrouter", "ovh", "kilo", "llm7", "pollinations"} <= names


def test_keys_embedded_shape():
    assert config.MISTRAL_API_KEY.startswith("mstrl_") and len(config.MISTRAL_API_KEY) == 45
    k = config.OPENROUTER_API_KEY
    assert k.startswith("sk-or-v1-") and len(k) == 73
    assert config.BOT_TOKEN.count(":") == 1 and len(config.BOT_TOKEN) == 46


def test_memory_cap_and_context():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        m = Memory(path)
        m.add(1, "user", "привет")
        m.add(1, "assistant", "привет~")
        ctx = m.context(1)
        assert [c["role"] for c in ctx] == ["user", "assistant"]
        old = config.MAX_HISTORY_BYTES_PER_USER
        config.MAX_HISTORY_BYTES_PER_USER = 300
        try:
            for i in range(30):
                m.add(1, "user", "x" * 50)
            assert m.size_bytes(1) <= 300 + 200
            assert m.context(1), "context must survive trims"
        finally:
            config.MAX_HISTORY_BYTES_PER_USER = old
        m.clear(1)
        assert m.context(1) == []
    finally:
        os.remove(path)


def test_parse_tags():
    raw = ('<search>погода завтра</search>Секунду~ <image>закат в горах</image>'
           '<file name="a.txt">hello</file>Готово!')
    parsed = tools.parse_tags(raw)
    assert parsed["searches"] == ["погода завтра"]
    assert parsed["images"] == ["закат в горах"]
    assert parsed["files"] == [("a.txt", "hello")]
    assert "<" not in parsed["clean"] and "Готово!" in parsed["clean"]


def test_acceptable_filter():
    assert not router.acceptable("Конечно! Как языковая модель, я помогу. Надеюсь, это поможет!")
    assert not router.acceptable("Привет! Чем могу помочь?")
    assert router.acceptable("привет~ ну что, как день прошёл? ^^")


def test_looks_like_image():
    assert tools.looks_like_image(b"\xff\xd8\xff\xe0" + b"x" * 9000)       # JPEG
    assert tools.looks_like_image(b"\x89PNG\r\n\x1a\n" + b"x" * 9000)      # PNG
    assert not tools.looks_like_image(b"\xff\xd8\xff\xe0" + b"x" * 100)    # слишком маленькая
    assert not tools.looks_like_image(b"<html>404</html>" + b"x" * 9000)   # не картинка
    assert not tools.looks_like_image(b"")


def test_strip_internal():
    assert router.strip_internal("Как языковая модель, я отвечу: да") != ""
    assert "модель" not in router.strip_internal("Я обычная девушка~")
