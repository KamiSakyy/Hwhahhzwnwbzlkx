#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fetch_apk.py -- download one *exact* Android build (package + versionCode) from
public APK catalogues and verify what was really downloaded.

Only the Python standard library is used, so this runs anywhere (incl. GitHub
Actions ubuntu runners) without `pip install`.

Sources, in order of preference:
  1. explicit --url  (user supplied direct link)
  2. APKMirror   (www.apkmirror.com)   -- genuine Play Store dumps, best metadata
  3. APKPure     (apkpure.com / .net)  -- has direct `/<versionCode>-APK|XAPK` pages
  4. APKCombo    (apkcombo.com)        -- per-architecture plain APKs
  5. Uptodown    (uptodown.com)        -- old versions archive

Every candidate file is opened as a ZIP and its binary AndroidManifest.xml is
parsed so we can prove package name + versionCode + versionName *before* the
file is accepted. Bundles (.xapk/.apkm/.apks) are unpacked to their base APK.

Exit codes: 0 = target file written, 2 = nothing valid found, 3 = bad usage.
"""

from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import os
import re
import shutil
import ssl
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

# --------------------------------------------------------------------------- #
# HTTP helpers
# --------------------------------------------------------------------------- #

_UAS = [
    # Chrome / Windows
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    # Firefox / Windows
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
    # Chrome / Android
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    # Safari / macOS
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
]

_CTX = ssl.create_default_context()
_TIMEOUT = 60


def log(msg: str) -> None:
    print(msg, flush=True)


def _opener(cookies: http.cookiejar.CookieJar | None = None):
    handlers = [urllib.request.HTTPSHandler(context=_CTX)]
    if cookies is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(cookies))
    return urllib.request.build_opener(*handlers)


def fetch(url: str, cookies=None, referer: str | None = None, ua_index: int = 0,
          timeout: int = _TIMEOUT, max_bytes: int | None = None):
    """GET a URL. Returns (final_url, bytes) or raises."""
    req = urllib.request.Request(url)
    req.add_header("User-Agent", _UAS[ua_index % len(_UAS)])
    req.add_header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                             "image/avif,image/webp,*/*;q=0.8")
    req.add_header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
    req.add_header("Upgrade-Insecure-Requests", "1")
    req.add_header("Sec-Fetch-Mode", "navigate")
    if referer:
        req.add_header("Referer", referer)
    with _opener(cookies).open(req, timeout=timeout) as resp:
        data = resp.read(max_bytes) if max_bytes else resp.read()
        return resp.geturl(), data


def fetch_text(url: str, **kw) -> tuple[str, str]:
    for attempt in range(3):
        try:
            final, data = fetch(url, ua_index=attempt, **kw)
            return final, data.decode("utf-8", "replace")
        except Exception as exc:  # noqa: BLE001
            log("    ! GET %s -> %s" % (url, exc))
            time.sleep(2 + 2 * attempt)
    raise RuntimeError("failed to fetch %s" % url)


def download_file(url: str, dest: str, referer: str | None = None,
                  cookies=None, min_size: int = 1) -> dict:
    """Stream a file to disk. Returns an info dict; never raises on HTTP errors."""
    info = {"url": url, "ok": False, "size": 0, "dest": dest, "error": ""}
    if url.startswith("file://"):
        src = urllib.parse.urlparse(url).path
        if not os.path.exists(src):
            info["error"] = "local file not found"
            return info
        shutil.copyfile(src, dest)
        info["ok"] = True
        info["size"] = os.path.getsize(dest)
        info["final_url"] = url
        info["content_type"] = "application/octet-stream"
        info["sha256"] = sha256_of(dest)
        log("    + copied local file %s (%s bytes)" % (src, format(info["size"], ",")))
        return info
    try:
        req = urllib.request.Request(url)
        req.add_header("User-Agent", _UAS[0])
        req.add_header("Accept", "*/*")
        req.add_header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        if referer:
            req.add_header("Referer", referer)
        with _opener(cookies).open(req, timeout=300) as resp:
            final_url = resp.geturl()
            ctype = resp.headers.get("Content-Type", "")
            total = resp.headers.get("Content-Length")
            info["final_url"] = final_url
            info["content_type"] = ctype
            info["content_length"] = int(total) if total and total.isdigit() else None
            # HTML instead of a binary => we hit an interstitial / block page
            if "text/html" in ctype.lower():
                body = resp.read(200_000)
                info["error"] = "server returned HTML (block/redirect page), not a file"
                info["html_snippet"] = body[:4000].decode("utf-8", "replace")
                log("    ! %s -> HTML page instead of file" % url)
                return info
            tmp = dest + ".part"
            got = 0
            with open(tmp, "wb") as fh:
                while True:
                    chunk = resp.read(1 << 16)
                    if not chunk:
                        break
                    fh.write(chunk)
                    got += len(chunk)
            info["size"] = got
            if got < min_size:
                info["error"] = "file too small (%d bytes)" % got
                os.remove(tmp)
                return info
            os.replace(tmp, dest)
            info["ok"] = True
            info["sha256"] = sha256_of(dest)
            log("    + downloaded %s (%s bytes) from %s" % (os.path.basename(dest), format(got, ","), final_url))
            return info
    except Exception as exc:  # noqa: BLE001
        info["error"] = "%s: %s" % (type(exc).__name__, exc)
        log("    ! download failed: %s" % info["error"])
        return info


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def absolutize(base: str, href: str) -> str:
    return urllib.parse.urljoin(base, href.replace("&amp;", "&"))


def uniq(seq):
    seen, out = set(), []
    for item in seq:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


# --------------------------------------------------------------------------- #
# Binary AndroidManifest.xml (AXML) parsing -- no aapt needed
# --------------------------------------------------------------------------- #

_ATTR_TYPE_INT_DEC = 0x10
_ATTR_TYPE_INT_HEX = 0x11
_ATTR_TYPE_INT_BOOL = 0x12


def _axml_utf16_strings(manifest: bytes) -> list[str]:
    """Best-effort extraction of the UTF-16 string pool of an AXML document."""
    out: list[str] = []
    if manifest[:4] != b"\x03\x00\x08\x00" or len(manifest) < 16:
        return out
    try:
        # ResChunk_header: type(2) headerSize(2) size(4)
        _doc_type, header_size, _doc_size = struct.unpack_from("<HHI", manifest, 0)
        if manifest[header_size:header_size + 2] != b"\x01\x00":  # RES_STRING_POOL_TYPE
            return out
        str_count, _styles, flags, strings_start = struct.unpack_from("<IIII", manifest, header_size + 8)
        # The pool chunk lives at `header_size`; its header is 28 bytes
        # (ResChunk_header 8 + stringCount/stylesCount/flags/stringsStart/stylesStart),
        # and the uint32 offset table follows right after it.
        pool_header_size = struct.unpack_from("<H", manifest, header_size + 2)[0]
        offsets_base = header_size + (pool_header_size if 28 <= pool_header_size <= 64 else 28)
        data_base = header_size + strings_start
        is_utf8 = bool(flags & (1 << 8))
        str_count = min(str_count, 20000)
        for i in range(str_count):
            off = struct.unpack_from("<I", manifest, offsets_base + i * 4)[0]
            pos = data_base + off
            if is_utf8:
                # u16-len (chars), u16-len (bytes) -- both may use the 0x8000 ext form
                n = manifest[pos]
                pos += 2 if n & 0x80 else 1
                m = manifest[pos]
                pos += 2 if m & 0x80 else 1
                try:
                    out.append(manifest[pos:pos + m].decode("utf-8", "replace"))
                except Exception:  # noqa: BLE001
                    out.append("")
            else:
                n = struct.unpack_from("<H", manifest, pos)[0]
                pos += 4 if n & 0x8000 else 2
                try:
                    out.append(manifest[pos:pos + n * 2].decode("utf-16-le", "replace"))
                except Exception:  # noqa: BLE001
                    out.append("")
    except Exception:  # noqa: BLE001
        pass
    return out


def inspect_apk(path: str) -> dict:
    """Return package / versionCode / versionName / signature info of an APK."""
    info: dict = {
        "path": path,
        "size": os.path.getsize(path),
        "size_mb": round(os.path.getsize(path) / 1_000_000, 2),
        "is_zip": False,
        "kind": "unknown",
        "package": None,
        "version_code": None,
        "version_name": None,
        "manifest_sha1": None,
        "sha256": sha256_of(path),
        "entries": 0,
        "has_base_apk": False,
        "warnings": [],
    }
    try:
        zf = zipfile.ZipFile(path)
    except Exception as exc:  # noqa: BLE001
        info["warnings"].append("not a zip: %s" % exc)
        return info
    with zf:
        names = zf.namelist()
        info["entries"] = len(names)
        info["is_zip"] = True
        info["has_base_apk"] = "base.apk" in names
        if "AndroidManifest.xml" in names:
            info["kind"] = "apk"
            raw = zf.read("AndroidManifest.xml")
            info["manifest_sha1"] = hashlib.sha1(raw).hexdigest()
            attrs = read_manifest_attributes(raw)
            info["version_code"] = attrs.get("version_code")
            info["version_name"] = attrs.get("version_name")
            info["package"] = attrs.get("package")
        elif info["has_base_apk"] or any(n.endswith((".apk", ".apks")) for n in names):
            info["kind"] = "bundle"
        else:
            info["kind"] = "zip"
    return info


def _find_version_code(manifest: bytes):
    """Walk the AXML start-tag chunks and return the versionCode attribute value."""
    try:
        strings = _axml_utf16_strings(manifest)
    except Exception:  # noqa: BLE001
        strings = []
    idx = None
    for needle in ("versionCode", "versioncode"):
        if needle in strings:
            idx = strings.index(needle)
            break
    if idx is None:
        return None
    doc_hsize = struct.unpack_from("<H", manifest, 2)[0]
    pos = doc_hsize if 8 <= doc_hsize < len(manifest) else 8
    n = len(manifest)
    while pos + 8 <= n:
        ctype, hsize, csize = struct.unpack_from("<HHI", manifest, pos)
        if hsize < 8 or csize < 8 or pos + csize > n:
            break
        if ctype == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            # ResXMLTree_node: header(8) + lineNumber(4) + comment(4)  -> pos+16
            # ResXMLTree_attrExt: ns(4) + name(4) + attributeStart(2)
            #                     + attributeSize(2) + attributeCount(2) -> pos+24
            base = pos + 16
            if base + 20 <= n:
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", manifest, base + 8)
                if attr_size < 20:
                    attr_size = 20
                # attributeStart is relative to the beginning of ResXMLTree_attrExt
                for i in range(min(attr_count, 500)):
                    a = base + attr_start + i * attr_size
                    if a + 20 > n:
                        break
                    _ns, name_idx = struct.unpack_from("<II", manifest, a)
                    data_type = manifest[a + 15]
                    data = struct.unpack_from("<I", manifest, a + 16)[0]
                    if name_idx == idx and data_type in (_ATTR_TYPE_INT_DEC, _ATTR_TYPE_INT_HEX):
                        return data
        pos += csize
    return None


def read_manifest_attributes(path_or_bytes) -> dict:
    """Parse package / versionCode / versionName out of a binary AndroidManifest.xml."""
    if isinstance(path_or_bytes, (bytes, bytearray)):
        manifest = bytes(path_or_bytes)
    else:
        with zipfile.ZipFile(path_or_bytes) as zf:
            manifest = zf.read("AndroidManifest.xml")
    out: dict = {"package": None, "version_code": None, "version_name": None}
    strings = _axml_utf16_strings(manifest)
    if not strings:
        return out
    idx = {s: i for i, s in enumerate(strings)}
    want = {
        "package": "package",
        "version_code": "versionCode",
        "version_name": "versionName",
    }
    doc_hsize = struct.unpack_from("<H", manifest, 2)[0]
    pos = doc_hsize if 8 <= doc_hsize < len(manifest) else 8
    n = len(manifest)
    while pos + 8 <= n:
        ctype, hsize, csize = struct.unpack_from("<HHI", manifest, pos)
        if hsize < 8 or csize < 8 or pos + csize > n:
            break
        if ctype == 0x0102:
            base = pos + 16
            if base + 20 <= n:
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", manifest, base + 8)
                attr_size = max(attr_size, 20)
                # attributeStart is relative to the beginning of ResXMLTree_attrExt
                for i in range(min(attr_count, 500)):
                    a = base + attr_start + i * attr_size
                    if a + 20 > n:
                        break
                    _ns, name_idx = struct.unpack_from("<II", manifest, a)
                    raw_idx = struct.unpack_from("<i", manifest, a + 8)[0]
                    data_type = manifest[a + 15]
                    data = struct.unpack_from("<I", manifest, a + 16)[0]
                    for key, attr_name in want.items():
                        if out[key] is not None or idx.get(attr_name) != name_idx:
                            continue
                        if data_type in (_ATTR_TYPE_INT_DEC, _ATTR_TYPE_INT_HEX) and key == "version_code":
                            out[key] = data
                        elif data_type == 0x03:  # TYPE_STRING -> string pool index
                            sidx = raw_idx if raw_idx != 0xFFFFFFFF else data
                            if 0 <= sidx < len(strings):
                                out[key] = strings[sidx]
        pos += csize
    if out["version_code"] is None:
        out["version_code"] = _find_version_code_strings(manifest, strings)
    if out["version_name"] is None:
        out["version_name"] = _guess_version_name(manifest, strings)
    if out["package"] is None:
        for cand in strings:
            if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+){1,}", cand or "") and \
                    not cand.startswith("http") and "android" not in cand:
                out["package"] = cand
                break
    return out


def _find_version_code_strings(manifest: bytes, strings: list[str]):
    """Fallback: grab the int that follows the literal 'versionCode' in the blob."""
    for enc in ("utf-16-le", "utf-8"):
        needle = "versionCode".encode(enc)
        start = 0
        while True:
            i = manifest.find(needle, start)
            if i < 0:
                break
            window = manifest[i + len(needle): i + len(needle) + 120]
            for j in range(0, max(1, len(window) - 4)):
                val = struct.unpack_from("<I", window, j)[0]
                if 1 <= val <= 100_000_000:
                    return val
            start = i + len(needle)
    return None


def _guess_version_name(manifest: bytes, strings: list[str], entry_count: int = 0) -> str | None:
    cands = [s for s in strings if re.fullmatch(r"\d+\.\d+(\.\d+)?([.\-][0-9A-Za-z]+)?", s or "")]
    if cands:
        def key(c: str):
            # prefer the most specific (most dotted / longest) version-looking string
            return (-c.count("."), -len(c), c)
        return sorted(cands, key=key)[0]
    # last resort: scan raw UTF-16 data
    for m in re.finditer(rb"(?:\d\x00){1,3}\x00?\.\x00(?:\d\x00){1,4}", manifest):
        try:
            s = m.group(0).decode("utf-16-le", "ignore")
            if re.fullmatch(r"\d+\.\d+(\.\d+)?", s):
                return s
        except Exception:  # noqa: BLE001
            continue
    return None


# --------------------------------------------------------------------------- #
# Candidate model
# --------------------------------------------------------------------------- #

class Candidate:
    def __init__(self, source: str, page: str, file_url: str, suggested_name: str = "",
                 kind: str = "", note: str = ""):
        self.source = source
        self.page = page
        self.file_url = file_url
        self.suggested_name = suggested_name
        self.kind = kind          # apk / xapk / apkm / apks / ""
        self.note = note
        self.info: dict = {}
        self.error: str = ""
        self.path: str = ""

    def as_dict(self) -> dict:
        d = {
            "source": self.source,
            "page": self.page,
            "file_url": self.file_url,
            "suggested_name": self.suggested_name,
            "kind": self.kind,
            "note": self.note,
            "error": self.error,
        }
        d.update(self.info)
        return d


# --------------------------------------------------------------------------- #
# Source: APKMirror
# --------------------------------------------------------------------------- #

def apkmirror_variants(pkg: str, version_name: str) -> list[str]:
    """Find release + variant pages for a given version name."""
    slug_ver = version_name.replace(".", "-")
    tries = [
        "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=%s" % urllib.parse.quote(pkg),
        "https://www.apkmirror.com/?s=%s" % urllib.parse.quote("%s %s" % (pkg, version_name)),
    ]
    found: list[str] = []
    # 1) direct guess at the release URL is unreliable (needs the app slug), so search
    for search_url in tries:
        try:
            final, html = fetch_text(search_url)
        except Exception as exc:  # noqa: BLE001
            log("  apkmirror search failed: %s" % exc)
            continue
        hrefs = re.findall(r'href="(https://www\.apkmirror\.com/apk/[^"]+)"', html)
        for h in hrefs:
            if "release" in h and slug_ver in h:
                found.append(h)
            elif "release" in h and version_name in h:
                found.append(h)
        if found:
            break
    # 2) also accept any /apk/.../<slug>-<ver>-release/ style link
    return uniq(found)[:6]


def source_apkmirror(pkg: str, version_name: str, version_code: int | None,
                     workdir: str) -> list[Candidate]:
    cands: list[Candidate] = []
    cookies = http.cookiejar.CookieJar()
    releases = apkmirror_variants(pkg, version_name)
    if not releases:
        log("  apkmirror: no release page found")
        return cands
    for rel in releases:
        log("  apkmirror: release page %s" % rel)
        try:
            _, html = fetch_text(rel, cookies=cookies)
        except Exception as exc:  # noqa: BLE001
            log("  apkmirror: %s" % exc)
            continue
        variants = uniq(re.findall(
            r'href="(https://www\.apkmirror\.com/apk/[^"]*-apk-download/[^"#?]*)"', html))
        if not variants:
            variants = uniq(re.findall(
                r'href="(https://www\.apkmirror\.com/apk/[^"]+download/[^"#?]*)"', html))
        log("  apkmirror: %d variant page(s)" % len(variants))
        for var in variants[:6]:
            try:
                _, vhtml = fetch_text(var, cookies=cookies, referer=rel)
            except Exception as exc:  # noqa: BLE001
                log("  apkmirror variant %s -> %s" % (var, exc))
                continue
            if version_code and ("(%d)" % version_code) not in vhtml and \
                    not re.search(r">\s*%d\s*<" % version_code, vhtml):
                log("  apkmirror: variant %s does not mention versionCode %s -- still trying" % (var, version_code))
            keys = uniq(re.findall(r'href="([^"]*/download/\?key=[0-9a-f]{20,})"', vhtml))
            if not keys:
                keys = uniq(re.findall(r'"([^"]*/download/\?key=[0-9a-f]{20,})"', vhtml))
            if not keys:
                log("  apkmirror: no download key on %s" % var)
                continue
            for key_url in keys[:3]:
                step1 = absolutize(var, key_url)
                cands.extend(_apkmirror_resolve(step1, var, cookies, workdir))
    return cands


def _apkmirror_resolve(step1: str, referer: str, cookies, workdir: str) -> list[Candidate]:
    """step1 = .../download/?key=...  ->  final CDN file link."""
    out: list[Candidate] = []
    try:
        final1, html = fetch_text(step1, cookies=cookies, referer=referer)
    except Exception as exc:  # noqa: BLE001
        log("  apkmirror step1 failed: %s" % exc)
        return out
    # direct file link on the first page?
    direct = re.findall(r'href="(https://download[^"]+\.(?:apk|xapk|apkm|apks)[^"]*)"', html, re.I)
    if direct:
        out.append(Candidate("apkmirror", step1, absolutize(final1, direct[0])))
        return out
    rels = uniq(re.findall(r'rel="nofollow"\s+href="([^"]+)"', html))
    step2s = [r for r in rels if "/download/" in r]
    for s2 in step2s[:3]:
        s2 = absolutize(final1, s2)
        # APKMirror shows an ad-block interstitial: wait, then re-request
        for delay in (0, 16):
            if delay:
                log("  apkmirror: waiting %ds for the interstitial..." % delay)
                time.sleep(delay)
            try:
                final2, html2 = fetch_text(s2, cookies=cookies, referer=step1)
            except Exception as exc:  # noqa: BLE001
                log("  apkmirror step2 failed: %s" % exc)
                break
            files = re.findall(r'href="(https://download[^"]+)"', html2)
            files = [f for f in files if re.search(r"\.(apk|xapk|apkm|apks)(\?|$)", f, re.I)]
            if files:
                for f in uniq(files)[:3]:
                    out.append(Candidate("apkmirror", s2, absolutize(final2, f)))
                return out
    return out


# --------------------------------------------------------------------------- #
# Source: APKPure
# --------------------------------------------------------------------------- #

_APKPURE_HOSTS = ["apkpure.com", "apkpure.net", "m.apkpure.com"]


def source_apkpure(pkg: str, version_name: str, version_code: int | None,
                   workdir: str) -> list[Candidate]:
    cands: list[Candidate] = []
    seen_urls: set[str] = set()

    def add(page: str, url: str, name: str = "", kind: str = "", note: str = ""):
        if url in seen_urls:
            return
        seen_urls.add(url)
        cands.append(Candidate("apkpure", page, url, name, kind, note))

    for host in _APKPURE_HOSTS:
        base = "https://%s" % host
        app_url = "%s/_/p/%s" % (base, pkg) if host == "m.apkpure.com" else "%s/app/%s" % (base, pkg)
        pages = []
        if version_code:
            pages += [
                "%s/download/%d-APK" % (app_url, version_code),
                "%s/download/%d-XAPK" % (app_url, version_code),
                "%s/download/%s" % (app_url, version_name),
            ]
        pages += ["%s/versions" % app_url, "%s/download" % app_url, app_url]

        for page in pages:
            try:
                final, html = fetch_text(page, timeout=45)
            except Exception as exc:  # noqa: BLE001
                log("  apkpure %s -> %s" % (page, exc))
                continue
            log("  apkpure: scanned %s (final %s)" % (page, final))

            # a) direct CDN / proxy links embedded in the page
            for m in re.finditer(r'https://(?:d|download)\.(?:apkpure|cdnpure|apkpurecdn)\.(?:com|net)/[^"\'\s<>\\]+', html):
                url = m.group(0).replace("\\/", "/")
                fname = urllib.parse.parse_qs(urllib.parse.urlparse(url).query).get("fn", [""])[0]
                kind = re.search(r"\.(apk|xapk|apks|apkm)(\?|$)", fname or url, re.I)
                add(page, url, fname, kind.group(1).lower() if kind else "", "inline cdn link")

            # b) <a class="da" href="..."> download anchors
            for href in re.findall(r'<a[^>]+class="[^"]*\bda\b[^"]*"[^>]+href="([^"]+)"', html):
                url = absolutize(final, href)
                add(page, url, "", "", "download anchor")
            for href in re.findall(r'href="([^"]+)"[^>]+class="[^"]*\bda\b', html):
                add(page, absolutize(final, href), "", "", "download anchor")

            # c) per-version download pages listed on /versions
            for href in re.findall(r'href="([^"]*/download/[^"]*)"', html):
                url = absolutize(final, href)
                if version_code and str(version_code) not in url and version_name not in url:
                    continue
                add(page, url, "", "", "version page")

            if cands:
                return cands
    return cands


def apkpure_resolve(cand: Candidate, cookies) -> None:
    """Follow an APKPure download page to the real file URL."""
    if re.search(r"\.(apk|xapk|apks|apkm)(\?|$)", cand.file_url, re.I) and \
            ("d.apkpure" in cand.file_url or "download.apkpure" in cand.file_url or "cdnpure" in cand.file_url):
        return  # already a direct file link
    try:
        final, html = fetch_text(cand.file_url, cookies=cookies, referer=cand.page, timeout=45)
    except Exception as exc:  # noqa: BLE001
        cand.error = "page fetch failed: %s" % exc
        return
    cand.page = final
    links = re.findall(r'https://(?:d|download)\.(?:apkpure|cdnpure|apkpurecdn)\.(?:com|net)/[^"\'\s<>\\]+', html)
    links = [l.replace("\\/", "/") for l in links]
    da = re.findall(r'<a[^>]+class="[^"]*\bda\b[^"]*"[^>]+href="([^"]+)"', html)
    da += re.findall(r'href="([^"]+)"[^>]+class="[^"]*\bda\b', html)
    ordered = uniq(links + [absolutize(final, d) for d in da])
    if ordered:
        cand.file_url = ordered[0]
        cand.note = (cand.note + " -> resolved").strip(" ->")
        q = urllib.parse.parse_qs(urllib.parse.urlparse(cand.file_url).query)
        if q.get("fn"):
            cand.suggested_name = q["fn"][0]
    else:
        cand.error = "no direct file link found on page"


# --------------------------------------------------------------------------- #
# Source: APKCombo
# --------------------------------------------------------------------------- #

def source_apkcombo(pkg: str, version_name: str, version_code: int | None,
                    workdir: str) -> list[Candidate]:
    cands: list[Candidate] = []
    slug = pkg.replace(".", "-")
    pages = [
        "https://apkcombo.com/%s/%s/old-versions/" % (slug, pkg),
        "https://apkcombo.com/%s/%s/" % (slug, pkg),
    ]
    seen = set()
    for page in pages:
        try:
            final, html = fetch_text(page, timeout=45)
        except Exception as exc:  # noqa: BLE001
            log("  apkcombo %s -> %s" % (page, exc))
            continue
        log("  apkcombo: scanned %s" % page)
        direct = re.findall(r'https://download\.apkcombo\.com/[^"\'\s<>\\]+?\.(?:apk|xapk|apks)[^"\'\s<>\\]*', html)
        for url in uniq(direct):
            name = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(url).path))
            if version_code and ("-%d-" % version_code) not in name and not name.endswith("-%d.apk" % version_code):
                continue
            if url in seen:
                continue
            seen.add(url)
            kind = "apk" if name.lower().endswith(".apk") else "xapk"
            cands.append(Candidate("apkcombo", page, url, name, kind, "direct cdn link"))
        if cands:
            break
        # follow the version-specific page
        for href in re.findall(r'href="(https://apkcombo\.com/[^"]*(?:%s)[^"]*)"' % re.escape(version_name), html):
            try:
                vfinal, vhtml = fetch_text(href, timeout=45)
            except Exception as exc:  # noqa: BLE001
                log("  apkcombo variant %s -> %s" % (href, exc))
                continue
            for url in uniq(re.findall(r'https://download\.apkcombo\.com/[^"\'\s<>\\]+?\.(?:apk|xapk|apks)[^"\'\s<>\\]*', vhtml)):
                if url in seen:
                    continue
                seen.add(url)
                name = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(url).path))
                cands.append(Candidate("apkcombo", vfinal, url, name,
                                       "apk" if name.lower().endswith(".apk") else "xapk", "variant page"))
            if cands:
                break
    return cands


# --------------------------------------------------------------------------- #
# Source: Uptodown
# --------------------------------------------------------------------------- #

def source_uptodown(pkg: str, version_name: str, version_code: int | None,
                    workdir: str) -> list[Candidate]:
    cands: list[Candidate] = []
    base = "https://%s.en.uptodown.com/android" % pkg.replace(".", "-")
    pages = [base + "/versions", base]
    version_pages: list[str] = []
    for page in pages:
        try:
            final, html = fetch_text(page, timeout=45)
        except Exception as exc:  # noqa: BLE001
            log("  uptodown %s -> %s" % (page, exc))
            continue
        log("  uptodown: scanned %s" % page)
        links = uniq(re.findall(r'href="(https://[a-z0-9\-\.]*uptodown\.com/android/(?:post-download|download)[^"]*)"', html))
        for l in links:
            if version_name in l or (version_code and str(version_code) in l):
                version_pages.append(l)
        if not version_pages:
            # locate the version detail page, then its /download child
            detail = uniq(re.findall(r'href="(https://%s[^"]*%s[^"]*)"' % (re.escape(pkg.replace(".", "-")), re.escape(version_name.replace(".", "-"))), html))
            for d in detail[:4]:
                version_pages.append(d.rstrip("/") + "/download")
        if version_pages:
            break
    seen = set()
    for vp in uniq(version_pages)[:4]:
        try:
            vfinal, vhtml = fetch_text(vp, timeout=45)
        except Exception as exc:  # noqa: BLE001
            log("  uptodown %s -> %s" % (vp, exc))
            continue
        files = re.findall(r'https://[a-z0-9\-\.]*uptodown\.(?:com|net)/[^"\'\s<>\\]+?\.(?:apk|xapk)[^"\'\s<>\\]*', vhtml)
        for url in uniq(files):
            if url in seen:
                continue
            seen.add(url)
            cands.append(Candidate("uptodown", vfinal, url, "", "apk", "direct link"))
        if not cands:
            # post-download interstitial: grab the meta-refresh / data-url
            m = re.search(r'(?:data-url|data-post-url|content="0;\s*url)=["\']?(https://[^"\'>\s]+)', vhtml)
            if m:
                cands.append(Candidate("uptodown", vfinal, m.group(1), "", "apk", "interstitial redirect"))
    return cands


# --------------------------------------------------------------------------- #
# Bundle -> base APK
# --------------------------------------------------------------------------- #

def extract_base_apk(bundle: str, out_dir: str) -> str | None:
    """Pull base.apk (or the largest embedded *.apk) out of an .xapk/.apkm/.apks."""
    try:
        zf = zipfile.ZipFile(bundle)
    except Exception as exc:  # noqa: BLE001
        log("  ! cannot open bundle %s: %s" % (bundle, exc))
        return None
    with zf:
        names = zf.namelist()
        target = None
        if "base.apk" in names:
            target = "base.apk"
        else:
            apks = [n for n in names if n.lower().endswith(".apk") and "config." not in n.lower()
                    and "split_config" not in n.lower()]
            if apks:
                target = max(apks, key=lambda n: zf.getinfo(n).file_size)
        if not target:
            log("  ! no base APK inside %s" % os.path.basename(bundle))
            return None
        dest = os.path.join(out_dir, "base-from-%s.apk" % os.path.basename(bundle))
        with zf.open(target) as src, open(dest, "wb") as fh:
            shutil.copyfileobj(src, fh, 1 << 16)
        log("  + extracted %s from bundle (%s bytes)" % (target, format(os.path.getsize(dest), ",")))
        return dest


# --------------------------------------------------------------------------- #
# Main flow
# --------------------------------------------------------------------------- #

def score(cand: Candidate, pkg: str, version_code: int | None, target_name: str,
          expected_size: int | None) -> tuple[int, str]:
    s = 0
    reasons = []
    info = cand.info
    if info.get("package") == pkg:
        s += 100
        reasons.append("package matches")
    elif info.get("package"):
        s -= 500
        reasons.append("package MISMATCH (%s)" % info["package"])
    if version_code:
        if info.get("version_code") == version_code:
            s += 200
            reasons.append("versionCode %d matches" % version_code)
        elif info.get("version_code") is not None:
            s -= 500
            reasons.append("versionCode MISMATCH (%s)" % info["version_code"])
        else:
            s -= 50
            reasons.append("versionCode not readable")
    name = (cand.suggested_name or os.path.basename(urllib.parse.urlparse(cand.file_url).path)).lower()
    if name.endswith(".apk"):
        s += 40
        reasons.append("plain .apk")
    else:
        s += 5
        reasons.append("bundle (%s) -> base apk extracted" % (info.get("kind") or "?"))
    stem = target_name.lower().rsplit(".", 1)[0]
    if stem in name:
        s += 30
        reasons.append("filename matches target")
    if "_hw" in name or "huawei" in name:
        s += 25
        reasons.append("huawei-style build")
    if expected_size and info.get("size"):
        delta = abs(info["size"] - expected_size) / expected_size
        if delta < 0.02:
            s += 60
            reasons.append("size within 2%% of %s bytes" % format(expected_size, ","))
        elif delta < 0.15:
            s += 20
            reasons.append("size within 15%% of expected")
        else:
            s -= 20
            reasons.append("size differs (%s vs %s bytes)" % (format(info["size"], ","), format(expected_size, ",")))
    if cand.source == "apkmirror":
        s += 15
        reasons.append("apkmirror (verified Play dump)")
    return s, "; ".join(reasons)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Download one exact APK build and verify it.")
    ap.add_argument("--package", required=True)
    ap.add_argument("--version-name", default="")
    ap.add_argument("--version-code", type=int, default=0)
    ap.add_argument("--out", required=True, help="target file name/path to write")
    ap.add_argument("--workdir", default="apk-work")
    ap.add_argument("--report-json", default="")
    ap.add_argument("--report-md", default="")
    ap.add_argument("--url", action="append", default=[], help="direct URL to try first (repeatable)")
    ap.add_argument("--expected-size", type=int, default=0, help="expected size in bytes, for scoring")
    ap.add_argument("--expect-sha256", default="", help="if set, only accept a file with this SHA-256")
    ap.add_argument("--max-mb", type=float, default=95.0, help="reject files bigger than this")
    ap.add_argument("--offline", action="store_true",
                    help="only use --url candidates, do not crawl the catalogues")
    ap.add_argument("--no-local-files", action="store_true",
                    help="treat file:// URLs as unsupported instead of hanging")
    ap.add_argument("--stop-at-first-match", action="store_true",
                    help="accept the first build whose package+versionCode match, "
                         "instead of comparing every mirror and keeping the closest size")
    args = ap.parse_args(argv)

    pkg, vname, vcode = args.package, args.version_name, (args.version_code or None)
    os.makedirs(args.workdir, exist_ok=True)
    out_dir = os.path.dirname(os.path.abspath(args.out)) or "."
    os.makedirs(out_dir, exist_ok=True)
    target_name = os.path.basename(args.out)

    if os.path.exists(args.out) and os.path.getsize(args.out) > 1_000_000:
        existing = inspect_apk(args.out)
        if existing.get("version_code") in (vcode, None) and existing.get("kind") == "apk":
            log("Target %s already exists (%s bytes, versionCode=%s) -- nothing to do."
                % (args.out, format(existing["size"], ","), existing.get("version_code")))
            write_reports(args, [Candidate("cache", "", "file://%s" % os.path.abspath(args.out))], existing,
                          "already present in repo")
            return 0

    log("=" * 78)
    log("Target : %s" % args.out)
    log("Package: %s   version: %s   versionCode: %s" % (pkg, vname or "?", vcode or "?"))
    log("=" * 78)

    results: list[dict] = []
    accepted: dict | None = None
    accepted_cand: Candidate | None = None
    best: Candidate | None = None
    best_score = float("-inf")
    best_exact = False

    # ---- candidates: manual URLs first, then the catalogues, lazily --------
    def iter_candidates():
        for u in args.url:
            name = os.path.basename(urllib.parse.urlparse(u).path) or "download"
            yield Candidate("manual", "cli", u, name, "", "user supplied")
        if args.offline:
            return
        source_funcs = [
            ("apkmirror", lambda: source_apkmirror(pkg, vname, vcode, args.workdir)),
            ("apkpure", lambda: source_apkpure(pkg, vname, vcode, args.workdir)),
            ("apkcombo", lambda: source_apkcombo(pkg, vname, vcode, args.workdir)),
            ("uptodown", lambda: source_uptodown(pkg, vname, vcode, args.workdir)),
        ]
        for name, fn in source_funcs:
            log("\n--- source: %s ---" % name)
            try:
                found = fn()
            except Exception as exc:  # noqa: BLE001
                log("  %s crashed: %s" % (name, exc))
                found = []
            log("  %s: %d candidate link(s)" % (name, len(found)))
            for cand in found[:8]:
                yield cand

    for cand in iter_candidates():
        if best is not None and (args.stop_at_first_match or best_exact):
            break
        if accepted:
            break
        log("\n  [try] %s | %s" % (cand.source, cand.file_url[:160]))
        if cand.source == "apkpure" and not cand.error:
            apkpure_resolve(cand, http.cookiejar.CookieJar())
        if cand.error:
            log("    ! %s" % cand.error)
            results.append(cand.as_dict())
            continue

        raw_path = os.path.join(args.workdir, "raw-%02d.bin" % (len(results) + 1))
        info = download_file(cand.file_url, raw_path, referer=cand.page)
        results.append({"stage": "download", **info, "source": cand.source, "page": cand.page})
        if not info.get("ok"):
            cand.error = info.get("error", "download failed")
            results.append(cand.as_dict())
            continue

        probe = inspect_apk(raw_path)
        cand.info = probe
        log("    -> kind=%s package=%s versionCode=%s versionName=%s size=%s sha256=%s"
            % (probe.get("kind"), probe.get("package"), probe.get("version_code"),
               probe.get("version_name"), format(probe.get("size", 0), ","), (probe.get("sha256") or "")[:16]))

        final_path = raw_path
        if probe.get("kind") == "bundle":
            base = extract_base_apk(raw_path, args.workdir)
            if base:
                probe = inspect_apk(base)
                final_path = base
                cand.info = probe
                log("    -> base apk: package=%s versionCode=%s size=%s"
                    % (probe.get("package"), probe.get("version_code"), format(probe.get("size", 0), ",")))

        cand.path = final_path
        s, why = score(cand, pkg, vcode, target_name, args.expected_size or None)
        cand.info["score"] = s
        cand.info["score_reasons"] = why
        results.append(cand.as_dict())
        log("    -> score %d (%s)" % (s, why))

        ok = probe.get("kind") == "apk" and probe.get("package") == pkg and \
            (not vcode or probe.get("version_code") == vcode) and \
            probe.get("size", 0) <= args.max_mb * 1_000_000
        if args.expect_sha256:
            ok = ok and probe.get("sha256") == args.expect_sha256.lower()
            if probe.get("sha256") != args.expect_sha256.lower():
                log("    ! sha256 mismatch: %s" % probe.get("sha256"))
        if ok:
            exact = bool(args.expected_size) and probe.get("size") == args.expected_size
            better = (best is None
                      or (exact and not best_exact)
                      or (exact == best_exact and s > best_score))
            if better:
                best, best_score, best_exact = cand, s, exact
                log("\n*** best so far: %s -- %s bytes%s ***"
                    % (cand.source, format(probe.get("size", 0), ","),
                       " (exact size match)" if exact else ""))

    if best is not None:
        accepted = best.info
        accepted_cand = best
        log("\n*** ACCEPTED build from %s -- %s bytes ***"
            % (accepted_cand.source, format(accepted.get("size", 0), ",")))

    if not accepted or not accepted_cand:
        log("\n" + "=" * 78)
        log("FAILED: no verified %s build (versionCode=%s) could be downloaded." % (pkg, vcode))
        log("See the report for every source/link that was tried.")
        log("=" * 78)
        write_reports(args, [], None, "no verified build found", results)
        return 2

    shutil.copyfile(accepted_cand.path, args.out)
    final = inspect_apk(args.out)
    log("\nSaved %s" % args.out)
    log("  size      : %s bytes (%.2f MB)" % (format(final["size"], ","), final["size_mb"]))
    log("  package   : %s" % final["package"])
    log("  versionCode: %s" % final["version_code"])
    log("  versionName: %s" % final["version_name"])
    log("  sha256    : %s" % final["sha256"])
    log("  manifest sha1: %s" % final["manifest_sha1"])
    write_reports(args, [accepted_cand], final, "ok", results)

    # keep the sha next to the file
    sums = os.path.join(out_dir, "SHA256SUMS.txt")
    line = "%s  %s\n" % (final["sha256"], target_name)
    prev = ""
    if os.path.exists(sums):
        with open(sums, "r", encoding="utf-8") as fh:
            prev = "".join(l for l in fh if l.strip() and not l.endswith("  " + target_name + "\n"))
    with open(sums, "w", encoding="utf-8") as fh:
        fh.write(prev + line)
    return 0


def write_reports(args, cands: list[Candidate], final: dict | None, status: str,
                  results: list[dict] | None = None) -> None:
    results = results or []
    if args.report_json:
        payload = {
            "status": status,
            "requested": {
                "package": args.package,
                "version_name": args.version_name,
                "version_code": args.version_code,
                "out": args.out,
                "expected_size": args.expected_size,
            },
            "file": final,
            "attempts": results,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        with open(args.report_json, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2, ensure_ascii=False)
    if args.report_md:
        lines = ["# APK fetch report", "",
                 "- **Package:** `%s`" % args.package,
                 "- **Version:** `%s` (versionCode `%s`)" % (args.version_name or "?", args.version_code or "?"),
                 "- **Status:** %s" % status, ""]
        if final:
            lines += [
                "## Saved file", "",
                "| field | value |", "|---|---|",
                "| path | `%s` |" % args.out,
                "| size | %s bytes (%.2f MB) |" % (format(final["size"], ","), final["size_mb"]),
                "| package | `%s` |" % final.get("package"),
                "| versionCode | %s |" % final.get("version_code"),
                "| versionName | %s |" % final.get("version_name"),
                "| SHA-256 | `%s` |" % final.get("sha256"),
                "| AndroidManifest SHA-1 | `%s` |" % (final.get("manifest_sha1") or "-"),
                "",
            ]
        lines += ["## Attempts", "", "| # | source | result | url |", "|---|---|---|---|"]
        for i, r in enumerate(results, 1):
            url = (r.get("file_url") or r.get("url") or "")[:110]
            res = r.get("error") or ("ok" if r.get("ok") else "n/a")
            if r.get("version_code"):
                res += " | vc=%s pkg=%s" % (r.get("version_code"), r.get("package"))
            lines.append("| %d | %s | %s | %s |" % (i, r.get("source", "?"), res.replace("|", "/"), url))
        with open(args.report_md, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
