# Editor transaction core (plain Java)
No Android runtime dependencies, no theme rendering, no network.

Compile and run in GitHub `Source checks (NO APK)` workflow. Only `javac` and
`java` run; no Gradle, Android SDK, dex, APK, signing or artifact publication.

One ColorSession per document on its owning/UI thread. Start with explicit
`.attheme` overrides, not a copy of every default. `begin(key)` opens a transaction;
`preview(argb)` changes the live snapshot; `previewDefault()` removes override;
`commit()` creates one undo entry; `cancel()` restores both value and absence.
No-op transactions don't erase redo. undo/redo are forbidden while editing.
Export/autosave must only use committed state (`isEditing() == false`).

Tests cover absent/explicit values, live edits, cancellation, defaults, one-step
slider undo, redo invalidation, history bounds, snapshot isolation, no-op commits,
invalid HEX/keys, missing/nested transaction and cross-thread access.
