# -*- coding: utf-8 -*-
"""Автовыбор: 50+ моделей думают вместе.

- Обычный вопрос: гонка нескольких моделей — побеждает первый хороший ответ.
- Сложный вопрос: три модели отвечают параллельно, четвёртая (судья) собирает
  из черновиков самый сильный итоговый ответ.
- Мёртвые/лимитированные модели автоматически уходят на кулдаун.
"""
import asyncio
import re

from . import providers

_COMPLEX_WORDS = (
    "код", "программ", "скрипт", "функци", "алгоритм", "реши", "задач",
    "объясни подробно", "подробно", "сравни", "сочини", "эссе", "рассказ",
    "план", "стратеги", "переведи", "анализ", "посчитай", "докажи",
    "почему", "как работает", "придумай", "составь", "напиши",
)


def is_complex(text):
    t = (text or "").lower()
    if len(t) > 300 or "```" in (text or ""):
        return True
    return any(w in t for w in _COMPLEX_WORDS)


def _with_images(messages, images):
    """К последнему user-сообщению цепляет картинки (data: URI)."""
    if not images:
        return messages
    out = list(messages)
    for i in range(len(out) - 1, -1, -1):
        if out[i].get("role") == "user":
            text = out[i].get("content")
            if not isinstance(text, str):
                text = "Что здесь?"
            parts = [{"type": "text", "text": text}]
            for img in images:
                parts.append({"type": "image_url", "image_url": {"url": img}})
            out[i] = {"role": "user", "content": parts}
            break
    return out


async def _race(units, messages, need=1, timeout=75.0):
    """Запускает модели параллельно, собирает первые `need` хороших ответов."""
    results = []
    tasks = {}

    async def run(unit):
        return unit, await providers.chat(unit, messages)

    for unit in units:
        tasks[asyncio.ensure_future(run(unit))] = unit
    try:
        pending = set(tasks)
        while pending and len(results) < need:
            done, pending = await asyncio.wait(
                pending, timeout=timeout, return_when=asyncio.FIRST_COMPLETED
            )
            if not done:
                break
            for task in done:
                try:
                    unit, text = task.result()
                except Exception:
                    continue
                results.append((unit, text))
                if len(results) >= need:
                    break
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
    return results


async def _judge(persona, question, drafts):
    units = [u for u in providers.candidates(vision=False, limit=6)]
    blocks = []
    for i, (unit, text) in enumerate(drafts):
        blocks.append(f"--- ЧЕРНОВИК {i + 1} ---\n{text[:3500]}")
    judge_messages = [
        {"role": "system", "content": persona},
        {"role": "user", "content": (
            "Тебе нужно дать итоговый ответ человеку. Ниже — несколько черновиков "
            "ответа на его сообщение. Собери из них ОДИН лучший: возьми самое верное, "
            "полное и живое, исправь ошибки и противоречия, отбрось мусор. "
            "Отвечай сразу итоговым текстом, без упоминания черновиков.\n\n"
            f"Сообщение человека:\n{question[:1500]}\n\n" + "\n\n".join(blocks)
        )},
    ]
    try:
        got = await _race(units, judge_messages, need=1, timeout=80.0)
        if got:
            return got[0][1]
    except Exception:
        pass
    # судья недоступен — отдаём самый длинный черновик
    return max(drafts, key=lambda d: len(d[1]))[1]


async def answer(persona, history, user_text, images=None):
    """Главный вход: возвращает итоговый текст ответа (с тегами инструментов, если надо)."""
    messages = [{"role": "system", "content": persona}] + history
    vision = bool(images)
    if images:
        messages = _with_images(messages, images)
    units = providers.candidates(vision=vision, limit=10)
    if not units:
        raise RuntimeError("no units")

    if is_complex(user_text) and not vision:
        drafts = await _race(units, messages, need=3, timeout=85.0)
        # ответы должны быть от разных провайдеров, если получилось
        seen, diverse = set(), []
        for unit, text in drafts:
            if unit["provider"] not in seen:
                diverse.append((unit, text))
                seen.add(unit["provider"])
        drafts = diverse or drafts
        if len(drafts) >= 2:
            return await _judge(persona, user_text, drafts)
        if drafts:
            return drafts[0][1]
        # все упали — пробуем ещё раз обычной гонкой с другим набором
        units = providers.candidates(vision=False, limit=10)
        got = await _race(units, messages, need=1, timeout=85.0)
        if got:
            return got[0][1]
        raise RuntimeError("all providers down")

    got = await _race(units, messages, need=1)
    if not got:
        # второй шанс: полный список, вдруг кулдауны мешали
        got = await _race(providers.candidates(vision=vision), messages, need=1)
    if not got:
        raise RuntimeError("all providers down")
    return got[0][1]


def strip_internal(text):
    """Убирает случайные технические оговорки моделей о себе."""
    if not text:
        return text
    text = re.sub(r"(?i)as an ai language model,?\s*", "", text)
    text = re.sub(r"(?i)как (ии|искусственный интеллект|языковая модель),?\s*", "", text)
    return text.strip()
