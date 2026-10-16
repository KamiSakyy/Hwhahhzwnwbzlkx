# Official Telegram source snapshot
Source: https://github.com/DrKLO/Telegram
Commit: f32ffeec763f0bdbf4bec25b52d654f77bff733d
Commit date: 2026-10-13T10:24:56Z
Retrieved: 2026-10-16. License: GNU GPL v2 (repository root LICENSE).

A shallow sparse clone of the official repository was used to obtain these
unmodified source files. They are reference material, not compiled into our APK.
The full messenger and its build dependencies are intentionally not vendored.
ThemeColors.java supplies the literal default palette in assets/defaults.json.
Theme.java defines import/export semantics; ThemeDescription.java documents the
mapping of component properties to theme keys. ThemePreviewActivity.java is the
official preview reference. Telegram's ChatMessageCell and full UI are NOT linked
into this lightweight implementation. No claim of pixel-identical rendering.
