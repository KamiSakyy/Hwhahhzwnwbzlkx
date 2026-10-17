# -*- coding: utf-8 -*-
"""Telegram-бот: текст, фото, документы, видео, голосовые."""
import asyncio
import base64
import io
import os
import shutil
import subprocess
import tempfile
import time

import httpx
from telegram import Update
from telegram.constants import ChatAction
from telegram.ext import (Application, CommandHandler, ContextTypes,
                          MessageHandler, filters)

from . import config, providers, router, tools
from .memory import Memory
from .persona import SYSTEM_PROMPT

memory = Memory()
_last_seen = {}
_gen_lock = asyncio.Semaphore(4)


def _split(text):
    text = (text or "").strip() or "…"
    chunks, rest = [], text
    while len(rest) > config.TG_MAX_MESSAGE:
        cut = rest.rfind("\n", 0, config.TG_MAX_MESSAGE)
        if cut < 800:
            cut = config.TG_MAX_MESSAGE
        chunks.append(rest[:cut])
        rest = rest[cut:].lstrip("\n")
    chunks.append(rest)
    return chunks


async def _typing_loop(bot, chat_id, stop: asyncio.Event):
    while not stop.is_set():
        try:
            await bot.send_chat_action(chat_id=chat_id, action=ChatAction.TYPING)
        except Exception:
            pass
        try:
            await asyncio.wait_for(stop.wait(), timeout=4.0)
        except asyncio.TimeoutError:
            pass


async def _generate(user_id, user_text, images=None):
    """Диалог с автовыбором моделей + цикл инструментов (поиск/страницы)."""
    history = memory.context(user_id)
    raw = await router.answer(SYSTEM_PROMPT, history, user_text, images=images)
    for _ in range(2):
        parsed = tools.parse_tags(raw)
        if not parsed["searches"] and not parsed["urls"]:
            break
        notes = []
        for q in parsed["searches"]:
            found = await tools.web_search(q)
            if found:
                notes.append(tools.format_search_results(q, found))
        for url in parsed["urls"]:
            page = await tools.fetch_page(url)
            if page:
                notes.append(f"Содержимое страницы {url}:\n{page}")
        if not notes:
            break
        follow = history + [
            {"role": "assistant", "content": parsed["clean"] or "Смотрю…"},
            {"role": "user", "content": (
                "(тихо, только для тебя — вот что нашлось; ответь человеку своими "
                "словами, не упоминая, что это результаты поиска)\n" + "\n\n".join(notes))},
        ]
        raw = await router.answer(SYSTEM_PROMPT, follow, user_text)
    return raw


async def _send_found_photo(update, query):
    """Фото ТОЛЬКО из веб-поиска. Каждое скачивается и проверяется перед отправкой."""
    urls = await tools.image_search(query, limit=6)
    for url in urls:
        data = await tools.fetch_image_bytes(url)
        if not data:
            continue
        try:
            await update.message.reply_photo(photo=io.BytesIO(data))
            return True
        except Exception:
            continue
    return False


async def _deliver(update, user_id, raw):
    parsed = tools.parse_tags(raw)
    text = router.strip_internal(parsed["clean"])
    memory.add(user_id, "assistant", text)
    for chunk in _split(text):
        await update.message.reply_text(chunk)
    for query in parsed["images"]:
        await _send_found_photo(update, query)
    for name, content in parsed["files"]:
        data = content.encode("utf-8", errors="ignore")
        if 0 < len(data) <= config.TG_MAX_FILE:
            bio = io.BytesIO(data)
            bio.name = os.path.basename(name)[:64] or "file.txt"
            await update.message.reply_document(document=bio)


async def _handle(update, user_text, images=None):
    user_id = update.effective_user.id
    now = time.time()
    if now - _last_seen.get(user_id, 0) < config.USER_COOLDOWN_SEC:
        return
    _last_seen[user_id] = now
    chat_id = update.effective_chat.id
    stop = asyncio.Event()
    typing = asyncio.ensure_future(_typing_loop(update.get_bot(), chat_id, stop))
    try:
        async with _gen_lock:
            raw = await _generate(user_id, user_text, images=images)
        await _deliver(update, user_id, raw)
    except Exception:
        import traceback
        traceback.print_exc()  # причина видна в логах Railway
        try:
            await update.message.reply_text(
                "Что-то пошло не так. Попробуй ещё раз.")
        except Exception:
            pass
    finally:
        stop.set()
        typing.cancel()


def _group_allows(update):
    msg = update.message
    if update.effective_chat.type == "private":
        return True
    text = (msg.text or msg.caption or "")
    bot_name = (update.get_bot().username or "")
    if bot_name and ("@" + bot_name.lower()) in text.lower():
        return True
    if msg.reply_to_message and msg.reply_to_message.from_user and \
            msg.reply_to_message.from_user.id == update.get_bot().id:
        return True
    return "ая" in text.lower().split() or text.lower().startswith("ая")


async def on_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    memory.clear(update.effective_user.id)
    await update.message.reply_text(
        "Привет. Пиши текстом или присылай фото, файл, видео, голосовое — посмотрю и отвечу.")


async def on_reset(update: Update, context: ContextTypes.DEFAULT_TYPE):
    memory.clear(update.effective_user.id)
    await update.message.reply_text("История разговора очищена.")


async def on_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not _group_allows(update):
        return
    memory.add(update.effective_user.id, "user", update.message.text)
    await _handle(update, update.message.text)


async def on_photo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    photo = update.message.photo[-1]
    file = await photo.get_file()
    data = bytes(await file.download_as_bytearray())
    uri = "data:image/jpeg;base64," + base64.b64encode(data).decode()
    caption = update.message.caption or "Что на этом фото? Ответь конкретно."
    memory.add(update.effective_user.id, "user", "(прислал фото) " + caption)
    await _handle(update, caption, images=[uri])


_TEXT_EXT = (".txt", ".md", ".py", ".js", ".ts", ".json", ".csv", ".log", ".html",
             ".xml", ".yml", ".yaml", ".ini", ".java", ".c", ".cpp", ".h", ".sh",
             ".sql", ".go", ".rs", ".php", ".rb", ".kt", ".swift", ".lua")


def _extract_text(name, data):
    low = name.lower()
    if low.endswith(".pdf"):
        try:
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(data))
            return "\n".join((p.extract_text() or "") for p in reader.pages)[:24000]
        except Exception:
            return ""
    if low.endswith(_TEXT_EXT):
        for enc in ("utf-8", "cp1251", "latin-1"):
            try:
                return data.decode(enc)[:24000]
            except Exception:
                continue
    return ""


async def on_document(update: Update, context: ContextTypes.DEFAULT_TYPE):
    doc = update.message.document
    if doc.file_size and doc.file_size > 20 * 1024 * 1024:
        await update.message.reply_text("Файл слишком большой: могу принять до 20 МБ.")
        return
    file = await doc.get_file()
    data = bytes(await file.download_as_bytearray())
    name = doc.file_name or "файл"
    text = _extract_text(name, data)
    caption = update.message.caption or "Посмотри этот файл, пожалуйста"
    if text:
        prompt = f"{caption}\n\nВот содержимое файла «{name}»:\n{text}"
    else:
        prompt = (f"Мне прислали файл «{name}», но его содержимое ты прочитать не смогла "
                  f"(неподдерживаемый формат). Честно и мило скажи об этом.")
    memory.add(update.effective_user.id, "user", f"(прислал файл {name}) " + caption)
    await _handle(update, prompt)


async def _video_frames(path):
    """Кадры из видео для зрения (ffmpeg). SmolVLM не нужен — смотрят большие модели."""
    if not shutil.which("ffmpeg"):
        return []
    frames = []
    tmp = tempfile.mkdtemp(prefix="aya_frames_")
    try:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", path],
            capture_output=True, text=True, timeout=20)
        try:
            duration = float(probe.stdout.strip())
        except Exception:
            duration = 10.0
        stamps = [duration * k / 5 for k in (0.5, 1.5, 2.5, 3.5, 4.5)]
        for i, ts in enumerate(stamps):
            out = os.path.join(tmp, f"f{i}.jpg")
            subprocess.run(
                ["ffmpeg", "-y", "-ss", str(ts), "-i", path, "-frames:v", "1",
                 "-q:v", "4", out], capture_output=True, timeout=30)
            if os.path.exists(out) and os.path.getsize(out) > 3000:
                with open(out, "rb") as f:
                    frames.append("data:image/jpeg;base64," +
                                  base64.b64encode(f.read()).decode())
    except Exception:
        pass
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return frames


async def on_video(update: Update, context: ContextTypes.DEFAULT_TYPE):
    video = update.message.video or update.message.video_note
    if video is None:
        return
    if video.file_size and video.file_size > 20 * 1024 * 1024:
        await update.message.reply_text("Видео слишком большое: могу принять до 20 МБ.")
        return
    file = await video.get_file()
    tmp = tempfile.mktemp(suffix=".mp4", prefix="aya_vid_")
    try:
        await file.download_to_drive(tmp)
        frames = await _video_frames(tmp)
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
    caption = update.message.caption or "Посмотри это видео, что там происходит?"
    memory.add(update.effective_user.id, "user", "(прислал видео) " + caption)
    if frames:
        await _handle(update, caption, images=frames)
    else:
        await _handle(update, caption + " (кадры из видео вытащить не вышло — "
                                        "ответь, что видео не открылось, и попроси скриншоты)")


async def _transcribe_voice(data):
    """Голос -> текст через Mistral Voxtral (если доступен)."""
    try:
        files = {"file": ("voice.ogg", data, "audio/ogg")}
        payload = {"model": "voxtral-mini-latest"}
        async with httpx.AsyncClient(timeout=60.0) as c:
            resp = await c.post(
                "https://api.mistral.ai/v1/audio/transcriptions",
                headers={"Authorization": "Bearer " + config.MISTRAL_API_KEY},
                files=files, data=payload)
        if resp.status_code == 200:
            return (resp.json().get("text") or "").strip()
    except Exception:
        pass
    return ""


async def on_voice(update: Update, context: ContextTypes.DEFAULT_TYPE):
    voice = update.message.voice or update.message.audio
    if voice is None:
        return
    file = await voice.get_file()
    data = bytes(await file.download_as_bytearray())
    text = await _transcribe_voice(data)
    if not text:
        await update.message.reply_text("Не удалось распознать голосовое. Напиши текстом.")
        return
    memory.add(update.effective_user.id, "user", text)
    await update.message.reply_text(f"🎧 «{text}»", reply_to_message_id=update.message.message_id)
    await _handle(update, text)


def main():
    if not config.BOT_TOKEN:
        raise SystemExit("BOT_TOKEN пуст — впиши токен от @BotFather в aibot/config.py")
    app = Application.builder().token(config.BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", on_start))
    app.add_handler(CommandHandler("reset", on_reset))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    app.add_handler(MessageHandler(filters.PHOTO, on_photo))
    app.add_handler(MessageHandler(filters.Document.ALL, on_document))
    app.add_handler(MessageHandler(filters.VIDEO | filters.VIDEO_NOTE, on_video))
    app.add_handler(MessageHandler(filters.VOICE | filters.AUDIO, on_voice))
    print(f"Aya is alive. Models: {len(config.all_units())}", flush=True)
    app.run_polling(drop_pending_updates=True)


if __name__ == "__main__":
    main()
