"use client";

import { useEffect } from "react";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Obsidian global error:", error);
  }, [error]);

  return (
    <html lang="ru">
      <body
        style={{
          margin: 0,
          minHeight: "100dvh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "#06080b",
          color: "#e9edf2",
          fontFamily:
            'ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
          padding: 20,
        }}
      >
        <div
          style={{
            maxWidth: 420,
            width: "100%",
            border: "1px solid #1a212b",
            borderRadius: 16,
            padding: 24,
            textAlign: "center",
            background: "#0b0e13",
          }}
        >
          <h1 style={{ fontSize: 17, fontWeight: 600, margin: "0 0 8px" }}>OBSIDIAN</h1>
          <p style={{ fontSize: 13, lineHeight: 1.6, color: "#8b95a2", margin: 0 }}>
            Не удалось загрузить интерфейс. Ключи в безопасности — они хранятся только в зашифрованном
            хранилище вашего браузера. Попробуйте перезагрузить страницу.
            <br />
            <span style={{ color: "#5a6470" }}>
              Failed to load the UI. Your encrypted keys remain safe on this device.
            </span>
          </p>
          <button
            onClick={reset}
            style={{
              marginTop: 16,
              width: "100%",
              height: 42,
              borderRadius: 10,
              border: "none",
              background: "#a8e62e",
              color: "#131a04",
              fontWeight: 600,
              fontSize: 14,
              cursor: "pointer",
            }}
          >
            Перезагрузить / Reload
          </button>
        </div>
      </body>
    </html>
  );
}
