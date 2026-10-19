# -*- coding: utf-8 -*-
"""Единый OpenAI-совместимый клиент для всех провайдеров + учёт здоровья моделей."""
import time
import asyncio

import httpx

from . import config

_health = {}  # (provider, model) -> {"fails": int, "until": float, "lat": float}
_lock = asyncio.Lock()


def _h(unit):
    key = (unit["provider"], unit["model"])
    if key not in _health:
        _health[key] = {"fails": 0, "until": 0.0, "lat": 8.0}
    return _health[key]


def is_available(unit):
    return _h(unit)["until"] <= time.time()


async def mark_ok(unit, latency):
    async with _lock:
        h = _h(unit)
        h["fails"] = 0
        h["until"] = 0.0
        h["lat"] = latency if h["lat"] == 8.0 else h["lat"] * 0.6 + latency * 0.4


async def mark_fail(unit, status=0):
    async with _lock:
        h = _h(unit)
        h["fails"] += 1
        # 429 — долгий кулдаун (лимиты), остальное — короткий
        cooldown = 90.0 if status == 429 else 25.0
        if h["fails"] >= 3:
            cooldown = max(cooldown, 120.0)
        h["until"] = time.time() + cooldown


# Приоритет провайдеров: сначала те, у кого щедрые лимиты.
# OpenRouter :free — всего 50 запросов/сутки на аккаунт, его бережём (последний).
PROVIDER_RANK = {"mistral": 0, "kilo": 1, "ovh": 2, "llm7": 3, "openrouter": 4}


def sort_key(unit):
    h = _h(unit)
    # Доступные -> по приоритету провайдера -> сильные -> быстрые
    return (0 if is_available(unit) else 1,
            PROVIDER_RANK.get(unit["provider"], 9), -unit["tier"], h["lat"])


def candidates(vision=False, limit=None):
    units = config.all_units()
    if vision:
        vis = [u for u in units if u["vision"]]
        if vis:
            units = vis
    units = sorted(units, key=sort_key)
    return units[:limit] if limit else units


def _content_to_text(content):
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, dict) and part.get("type") in ("text", "output_text"):
                parts.append(str(part.get("text", "")))
        return "\n".join(p for p in parts if p).strip()
    return ""


async def chat(unit, messages, timeout=90.0, max_tokens=4096):
    """Один запрос к модели. Возвращает текст или бросает исключение."""
    headers = {"Content-Type": "application/json"}
    if unit["api_key"]:
        headers["Authorization"] = "Bearer " + unit["api_key"]
    if unit["provider"] == "openrouter":
        headers["HTTP-Referer"] = "https://telegram.org"
        headers["X-Title"] = "Aya"
    if unit.get("think"):
        max_tokens = 8192  # думающим моделям нужен запас токенов на рассуждение
    payload = {
        "model": unit["model"],
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": 0.7,
    }
    url = unit["base_url"] + "/chat/completions"
    started = time.time()
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=15.0)) as client:
            resp = await client.post(url, headers=headers, json=payload)
        if resp.status_code != 200:
            await mark_fail(unit, resp.status_code)
            raise RuntimeError(f"http {resp.status_code}: {resp.text[:200]}")
        try:
            data = resp.json()
        except Exception:
            text = resp.text.strip()
            if len(text) >= 2:  # Pollinations может вернуть чистый текст
                await mark_ok(unit, time.time() - started)
                return text
            await mark_fail(unit)
            raise RuntimeError("bad body")
        choices = data.get("choices") or []
        text = ""
        if choices:
            msg = choices[0].get("message") or {}
            text = _content_to_text(msg.get("content")) or _content_to_text(choices[0].get("text"))
        if len(text) < 2:
            await mark_fail(unit)
            raise RuntimeError("empty answer")
        await mark_ok(unit, time.time() - started)
        return text
    except httpx.HTTPError:
        await mark_fail(unit)
        raise
