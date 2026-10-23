#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pretty-print apk-work/report.json in a GitHub Actions log (stdlib only)."""
import json
import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "apk-work/report.json"
    try:
        with open(path, "r", encoding="utf-8") as fh:
            d = json.load(fh)
    except Exception as exc:  # noqa: BLE001
        print("report %s unreadable: %s" % (path, exc))
        return 1

    print("status: %s" % d.get("status"))
    req = d.get("requested") or {}
    print("requested: %s %s (%s) -> %s" % (req.get("package"), req.get("version_name"),
                                           req.get("version_code"), req.get("out")))
    f = d.get("file") or {}
    if f:
        print("")
        print("=== VERIFIED FILE ===")
        print("  path          : %s" % f.get("path"))
        print("  package       : %s" % f.get("package"))
        print("  versionCode   : %s" % f.get("version_code"))
        print("  versionName   : %s" % f.get("version_name"))
        print("  size          : %s bytes (%s MB)" % (format(f.get("size", 0), ","), f.get("size_mb")))
        print("  zip entries   : %s" % f.get("entries"))
        print("  sha256        : %s" % f.get("sha256"))
        print("  manifest sha1 : %s" % f.get("manifest_sha1"))

    attempts = d.get("attempts") or []
    print("")
    print("=== ATTEMPTS (%d) ===" % len(attempts))
    for i, a in enumerate(attempts, 1):
        src = a.get("source", "?")
        url = (a.get("file_url") or a.get("url") or "")[:120]
        if a.get("ok"):
            res = "downloaded %s bytes" % format(a.get("size", 0), ",")
        elif a.get("error"):
            res = "FAIL: %s" % a["error"]
        else:
            res = "n/a"
        extra = ""
        if a.get("version_code") is not None:
            extra = " | pkg=%s vc=%s vn=%s score=%s" % (
                a.get("package"), a.get("version_code"), a.get("version_name"), a.get("score"))
        print("%2d. [%s] %s%s\n      %s" % (i, src, res, extra, url))
        if a.get("score_reasons"):
            print("      why: %s" % a["score_reasons"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
