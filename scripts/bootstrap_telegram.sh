#!/usr/bin/env bash
# Source checkout only: no Gradle/SDK invocation, no APK, no emulator.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SHA=f32ffeec763f0bdbf4bec25b52d654f77bff733d
git -C "$ROOT" submodule update --init vendor/telegram
UPSTREAM="$ROOT/vendor/telegram"
test "$(git -C "$UPSTREAM" rev-parse HEAD)" = "$SHA"
if test "$(git -C "$UPSTREAM" config --bool core.sparseCheckout || true)" = true; then
    git -C "$UPSTREAM" sparse-checkout disable
fi
git -C "$UPSTREAM" submodule init
# Same libyuv commit, GitHub mirror because googlesource is unavailable here.
git -C "$UPSTREAM" config submodule.TMessagesProj/jni/third_party/libyuv.url https://github.com/lemenkov/libyuv.git
git -C "$UPSTREAM" submodule update --init --recursive --depth 1 --jobs 4
STATUS=$(git -C "$UPSTREAM" submodule status --recursive)
if printf '%s\n' "$STATUS" | grep -E '^[-+U]'; then
    echo 'ERROR: missing, mismatched or conflicted upstream submodule' >&2
    exit 1
fi
printf '%s\n' "$STATUS"
echo 'Full source checkout ready; no APK or emulator was created/launched.'
