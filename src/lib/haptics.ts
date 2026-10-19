/**
 * Obsidian Tactile Haptics (Telegram / Durov-grade micro-interactions)
 * Lightweight vibration patterns for Android and supporting mobile devices.
 */

export function haptic(
  kind: "light" | "medium" | "heavy" | "selection" | "success" | "error" = "light",
) {
  if (typeof window === "undefined") return;
  try {
    if (typeof navigator !== "undefined" && "vibrate" in navigator) {
      switch (kind) {
        case "light":
        case "selection":
          navigator.vibrate(8);
          break;
        case "medium":
          navigator.vibrate(18);
          break;
        case "heavy":
          navigator.vibrate(32);
          break;
        case "success":
          navigator.vibrate([10, 30, 15]);
          break;
        case "error":
          navigator.vibrate([25, 40, 25, 40]);
          break;
      }
    }
  } catch {
    /* ignore */
  }
}
