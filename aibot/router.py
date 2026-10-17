# -*- coding: utf-8 -*-
"""АВТОРОУТИНГ: 50+ моделей — один ум.

Никакой гонки за скоростью. На каждое сообщение несколько моделей от разных
провайдеров отвечают ПАРАЛЛЕЛЬНО, затем модель-судья сравнивает черновики,
проверяет факты и собирает один лучший ответ голосом Аи.
Ботовские/канцелярские черновики бракуются ещё до судьи.
"""
import asyncio
import re
import time

from . import providers

_REMINDER = (
    "Ты — Ая, живая девушка, а не служба поддержки. Отвечай эмоционально и "
    "по-своему: со своим мнением, шутками, теплом, иногда коротко, как в "
    "переписке. Запрещены сервисные фразы: «конечно!», «надеюсь, поможет», "
    "«чем могу помочь», «если есть ещё вопросы — спрашивай». Не выдумывай "
    "факты (имена, аниме, даты): не уверена — используй тег поиска."
)

_BANNED = (
    "как языковая модель", "как искусственный интеллект", "как ии,",
    "я — искусственный интеллект", "я - искусственный интеллект",
    "я искусственный интеллект", "я — бот", "я бот,",
    "as an ai", "as a language model", "i'm an ai",
    "чем могу помочь?", "чем могу помочь!",
    "если у тебя есть ещё вопросы", "если у вас есть ещё вопросы",
    "надеюсь, это поможет", "надеюсь, мой ответ поможет",
    "обращайся, если что", "дай знать, если понадобится",
)


def acceptable(text):
    """Отсев ботовских, канцелярских ответов."""
    if not text or len(text.strip()) < 2:
        return False
    low = text.lower()
    return not any(b in low for b in _BANNED)


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


async def _collect(units, messages, want=4, deadline=55.0, grace=6.0):
    """Запускает модели параллельно и СОБИРАЕТ черновики (не первый попавшийся!).

    Ждём, пока наберётся `want` живых ответов от разных провайдеров
    (+ grace секунд на опоздавших сильных), либо истечёт дедлайн.
    """
    good, emergency = [], []

    async def run(unit):
        return unit, await providers.chat(unit, messages)

    tasks = {asyncio.ensure_future(run(u)): u for u in units[:10]}
    started = time.monotonic()
    first_good_at = None
    try:
        pending = set(tasks)
        while pending:
            elapsed = time.monotonic() - started
            if elapsed >= deadline:
                break
            if len(good) >= want and first_good_at is not None \
                    and time.monotonic() - first_good_at >= grace:
                break
            done, pending = await asyncio.wait(
                pending, timeout=2.0, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                try:
                    unit, text = task.result()
                except Exception:
                    continue
                if acceptable(text):
                    good.append((unit, text))
                    if first_good_at is None:
                        first_good_at = time.monotonic()
                else:
                    emergency.append((unit, text))
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
    return good, emergency


def _diverse(drafts):
    seen, out = set(), []
    for unit, text in drafts:
        if unit["provider"] not in seen:
            out.append((unit, text))
            seen.add(unit["provider"])
    return out or drafts


async def _judge(persona, question, drafts, vision=False):
    """Судья: сравнивает черновики, проверяет факты, собирает лучший ответ."""
    blocks = []
    for i, (unit, text) in enumerate(drafts[:4]):
        blocks.append(f"--- ЧЕРНОВИК {i + 1} ---\n{text[:3500]}")
    judge_messages = [
        {"role": "system", "content": persona},
        {"role": "user", "content": (
            "Дай итоговый ответ человеку по черновикам ниже. ЖЁСТКИЕ ПРАВИЛА:\n"
            "1. Ответь прямо на вопрос. Если спрашивают «кто это?» — итог НАЗЫВАЕТ, "
            "кто это (самое конкретное имя/название из черновиков), а не уходит в "
            "«какой-то персонаж из тёмного фэнтези». Если черновики называют имя — "
            "не размывай его.\n"
            "2. Никаких повторов: ни одна мысль, фраза или предложение не встречается "
            "дважды. Не склеивай черновики подряд.\n"
            "3. Никакой воды и театра: без «ну ты даёшь», «сейчас гляну», «взрыв мозга», "
            "«конечно!», «надеюсь, поможет». Без выдуманных цитат и фантазий не по делу.\n"
            "4. Факты сверь между черновиками: противоречия в именах/названиях/датах — "
            "бери общеизвестную правду (Асуна — Sword Art Online, не Re:Zero). Если ни "
            "один черновик не знает ответа — честно «точно не знаю», без выдумок.\n"
            "5. Теги (<search>, <image>, <file>) из черновиков сохраняй, только если "
            "действие ещё не выполнено.\n"
            "Отвечай сразу итоговым текстом, черновики не упоминай.\n\n"
            f"Сообщение человека:\n{question[:1500]}\n\n" + "\n\n".join(blocks)
        )},
    ]
    judge_units = providers.candidates(vision=vision, limit=8)
    good, emergency = await _collect(judge_units, judge_messages, want=1, deadline=60.0, grace=0.0)
    pool = good or emergency
    if pool:
        return pool[0][1]
    return max(drafts, key=lambda d: len(d[1]))[1]


async def answer(persona, history, user_text, images=None):
    """Главный вход: ансамбль думает вместе, судья выбирает лучшее."""
    messages = [{"role": "system", "content": persona}] + history
    vision = bool(images)
    if images:
        messages = _with_images(messages, images)
    units = providers.candidates(vision=vision, limit=10)
    if not units:
        raise RuntimeError("no units")

    drafts, emergency = await _collect(
        units, messages, want=3 if vision else 4, deadline=55.0)
    drafts = _diverse(drafts)
    if len(drafts) >= 2:
        return await _judge(persona, user_text, drafts, vision=vision)
    if drafts:
        return drafts[0][1]
    if emergency:
        return emergency[0][1]
    # полный второй шанс всем списком
    drafts, emergency = await _collect(
        providers.candidates(vision=vision), messages, want=4, deadline=55.0)
    drafts = _diverse(drafts)
    if len(drafts) >= 2:
        return await _judge(persona, user_text, drafts, vision=vision)
    if drafts:
        return drafts[0][1]
    if emergency:
        return emergency[0][1]
    raise RuntimeError("all providers down")


def dedupe(text):
    """Вырезает дословные дубли абзацев и предложений (черновики иногда склеиваются)."""
    if not text:
        return text
    seen, out_par = set(), []
    for par in re.split(r"\n\s*\n", text):
        par = par.strip()
        key = re.sub(r"\s+", " ", par).lower()
        if par and key not in seen:
            seen.add(key)
            out_par.append(par)
    result = []
    seen_sent = set()
    for par in out_par:
        sentences = re.split(r"(?<=[.!?…])\s+", par)
        kept = []
        for s in sentences:
            k = re.sub(r"\s+", " ", s).strip().lower()
            if k and k in seen_sent and len(k) > 12:
                continue
            if k:
                seen_sent.add(k)
            kept.append(s)
        result.append(" ".join(kept))
    return "\n\n".join(result).strip()


def strip_internal(text):
    """Убирает случайные технические оговорки моделей о себе."""
    if not text:
        return text
    text = re.sub(r"(?i)as an ai language model,?\s*", "", text)
    text = re.sub(r"(?i)как (ии|искусственный интеллект|языковая модель),?\s*", "", text)
    return text.strip()
