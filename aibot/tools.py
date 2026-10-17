# -*- coding: utf-8 -*-
"""Инструменты: веб-поиск (DuckDuckGo), открытие страниц, поиск фото (DDG/Openverse/Picsum).

Модель вызывает их скрытыми тегами:
  <search>запрос</search>            — поиск в интернете
  <browse>https://...</browse>       — открыть страницу и прочитать
  <image>запрос картинки</image>     — найти и прислать фото из интернета
  <file name="имя.txt">текст</file>  — создать и отправить файл
"""
import re

import httpx
from bs4 import BeautifulSoup

_UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                     "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"}

TAG_SEARCH = re.compile(r"<search>(.*?)</search>", re.S | re.I)
TAG_BROWSE = re.compile(r"<browse>(.*?)</browse>", re.S | re.I)
TAG_IMAGE = re.compile(r"<image>(.*?)</image>", re.S | re.I)
TAG_FILE = re.compile(r'<file\s+name="([^"]+)">(.*?)</file>', re.S | re.I)


def parse_tags(text):
    files = [(m.group(1).strip(), m.group(2)) for m in TAG_FILE.finditer(text or "")]
    out = {
        "searches": [m.group(1).strip() for m in TAG_SEARCH.finditer(text or "")][:2],
        "urls": [m.group(1).strip() for m in TAG_BROWSE.finditer(text or "")][:1],
        "images": [m.group(1).strip() for m in TAG_IMAGE.finditer(text or "")][:2],
        "files": files[:3],
    }
    clean = TAG_FILE.sub("", text or "")
    clean = TAG_SEARCH.sub("", clean)
    clean = TAG_BROWSE.sub("", clean)
    clean = TAG_IMAGE.sub("", clean)
    clean = re.sub(r"\s+—\s*\.", ".", clean)   # дырки вида «чтобы сказать — .»
    clean = re.sub(r"\s+—\s*,", ",", clean)
    out["clean"] = re.sub(r"\n{3,}", "\n\n", clean).strip()
    return out


async def web_search(query, limit=5):
    """DuckDuckGo HTML — без ключей. Возвращает список {title, url, snippet}."""
    try:
        async with httpx.AsyncClient(timeout=20.0, headers=_UA, follow_redirects=True) as c:
            resp = await c.post("https://html.duckduckgo.com/html/", data={"q": query})
        soup = BeautifulSoup(resp.text, "html.parser")
        results = []
        for res in soup.select(".result")[:limit]:
            a = res.select_one(".result__a")
            sn = res.select_one(".result__snippet")
            if not a:
                continue
            href = a.get("href", "")
            m = re.search(r"uddg=([^&]+)", href)
            if m:
                from urllib.parse import unquote
                href = unquote(m.group(1))
            results.append({
                "title": a.get_text(" ", strip=True),
                "url": href,
                "snippet": sn.get_text(" ", strip=True) if sn else "",
            })
        if results:
            return results
    except Exception:
        pass
    # запасной путь — мгновенные ответы DDG
    try:
        async with httpx.AsyncClient(timeout=15.0, headers=_UA) as c:
            resp = await c.get("https://api.duckduckgo.com/", params={
                "q": query, "format": "json", "no_html": 1, "skip_disambig": 1})
        data = resp.json()
        out = []
        if data.get("AbstractText"):
            out.append({"title": data.get("Heading", query), "url": data.get("AbstractURL", ""),
                        "snippet": data["AbstractText"]})
        for topic in (data.get("RelatedTopics") or [])[:limit]:
            if isinstance(topic, dict) and topic.get("Text"):
                out.append({"title": topic["Text"][:60], "url": topic.get("FirstURL", ""),
                            "snippet": topic["Text"]})
        return out
    except Exception:
        return []


async def fetch_page(url, limit=8000):
    """Открывает страницу и возвращает чистый текст."""
    try:
        async with httpx.AsyncClient(timeout=25.0, headers=_UA, follow_redirects=True) as c:
            resp = await c.get(url)
        if resp.status_code != 200:
            return ""
        soup = BeautifulSoup(resp.text, "html.parser")
        for tag in soup(["script", "style", "nav", "footer", "header", "form", "aside"]):
            tag.decompose()
        text = soup.get_text("\n", strip=True)
        text = re.sub(r"\n{2,}", "\n", text)
        return text[:limit]
    except Exception:
        return ""


async def image_search(query, limit=5):
    """Фото из интернета (не генерация): DDG Images -> Openverse -> Wikimedia."""
    # 1) DuckDuckGo Images (нужен vqd-токен со страницы)
    try:
        async with httpx.AsyncClient(timeout=20.0, headers=_UA, follow_redirects=True) as c:
            page = await c.get("https://duckduckgo.com/", params={
                "q": query, "iax": "images", "ia": "images"})
            m = re.search(r"vqd=([\d-]+)", page.text) or re.search(r"vqd='([^']+)'", page.text)
            if m:
                resp = await c.get("https://duckduckgo.com/i.js", params={
                    "l": "us-en", "o": "json", "q": query, "vqd": m.group(1),
                    "f": ",,,", "p": "1"})
                data = resp.json()
                urls = [r["image"] for r in (data.get("results") or []) if r.get("image")]
                if urls:
                    return urls[:limit]
    except Exception:
        pass
    # 2) Openverse (WordPress, без ключа)
    try:
        async with httpx.AsyncClient(timeout=20.0, headers=_UA) as c:
            resp = await c.get("https://api.openverse.org/v1/images/",
                               params={"q": query, "page_size": limit, "license_type": "all"})
            data = resp.json()
            urls = [r["url"] for r in (data.get("results") or []) if r.get("url")]
            if urls:
                return urls[:limit]
    except Exception:
        pass
    # 3) Wikimedia Commons
    try:
        async with httpx.AsyncClient(timeout=20.0, headers=_UA) as c:
            resp = await c.get("https://commons.wikimedia.org/w/api.php", params={
                "action": "query", "generator": "search", "gsrsearch": query + " filetype:bitmap",
                "gsrnamespace": 6, "gsrlimit": limit, "prop": "imageinfo",
                "iiprop": "url", "format": "json"})
            data = resp.json()
            pages = ((data.get("query") or {}).get("pages") or {}).values()
            urls = []
            for p in pages:
                info = (p.get("imageinfo") or [{}])[0]
                if info.get("url"):
                    urls.append(info["url"])
            if urls:
                return urls[:limit]
    except Exception:
        pass
    return []  # лучше ничего, чем случайное левое фото


def looks_like_image(data):
    """Проверка, что скачанное — настоящая картинка, а не заглушка/мусор."""
    if not data or not (6000 <= len(data) <= 10 * 1024 * 1024):
        return False
    return (data[:2] == b"\xff\xd8"                       # JPEG
            or data[:8] == b"\x89PNG\r\n\x1a\n"           # PNG
            or data[:6] in (b"GIF87a", b"GIF89a")         # GIF
            or (data[:4] == b"RIFF" and data[8:12] == b"WEBP"))


async def fetch_image_bytes(url):
    """Скачивает картинку для отправки файлом (URL-адреса в Телеге часто битые)."""
    try:
        async with httpx.AsyncClient(timeout=25.0, follow_redirects=True) as c:
            resp = await c.get(url, headers={
                **_UA, "Referer": "https://duckduckgo.com/",
                "Accept": "image/avif,image/webp,image/png,image/jpeg,*/*"})
        if resp.status_code == 200 and looks_like_image(resp.content):
            return resp.content
    except Exception:
        pass
    return None


def format_search_results(query, results):
    lines = [f"Результаты поиска по запросу «{query}»:"]
    for i, r in enumerate(results, 1):
        lines.append(f"{i}. {r['title']}\n{r['url']}\n{r['snippet']}")
    return "\n".join(lines)[:6000]
