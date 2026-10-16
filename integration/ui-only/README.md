# UI-only extraction — not yet a standalone editor

The first extracted component is the **verbatim generatePath routine** from pinned
Telegram MessageDrawable, GPL-2.0-or-later. Source, commit and routine SHA256 are in
UPSTREAM.json. Surrounding adapter changes are explicitly recorded. Upstream tail
arcs, corner cases, near-grouping and clipped path branches are not redrawn from
memory or replaced with rounded rectangles.

`python scripts/extract_bubble_geometry.py --check` checks byte identity of the
routine and reproducibility of the adapter. It does NOT establish Android runtime
correctness, screenshot identity or a finished chat screen.

Standalone Gradle settings include only an Android rendering library; no dependency
on :TMessagesProj, ApplicationLoader, SharedConfig, Theme, TLRPC, ConnectionsManager,
Firebase, Play Services, media libraries or JNI. Display density and layout state
are supplied as local inputs. The original TYPE_PREVIEW density factor of 3 is
retained. All build dimensions are pixels supplied by the caller; this class does
not invent message layout dimensions.

Not included yet: real text/message layout, ActionBar/list/composer, shadows,
gradients, animation, wallpaper import, editor UI integration. This module is NOT
connected to either rejected app. No new APK is produced by these scripts.

## License
Derived routine remains GPL-2.0-or-later, consistent with Telegram's root README;
the original GPL text is included in LICENSE. Full upstream source remains pinned
in vendor/telegram. Retain provenance and license when distributing derived code.
