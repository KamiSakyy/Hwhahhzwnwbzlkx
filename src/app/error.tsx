"use client";

import { useEffect } from "react";

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Obsidian runtime error:", error);
  }, [error]);

  return (
    <div className="flex min-h-dvh items-center justify-center px-5">
      <div className="w-full max-w-md rounded-2xl border border-line bg-panel p-6 text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-bad/12 text-bad">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <path d="M12 3.5 1.9 20.1h20.2L12 3.5Z" />
            <path d="M12 10v4.5" />
            <circle cx="12" cy="17.3" r="0.9" fill="currentColor" stroke="none" />
          </svg>
        </div>
        <h1 className="text-[17px] font-semibold">Что-то пошло не так / Something went wrong</h1>
        <p className="mt-2 text-[13px] leading-relaxed text-sub">
          Интерфейс столкнулся с ошибкой, но ваши ключи и сообщения не пострадали: они хранятся только в
          зашифрованном IndexedDB Vault на этом устройстве.
          <br />
          <span className="text-dim">
            The UI hit an error. Your encrypted keys and messages stay safe in your local vault.
          </span>
        </p>
        <div className="mt-4 flex flex-col gap-2 sm:flex-row">
          <button
            onClick={reset}
            className="focus-ring h-10 flex-1 rounded-[10px] bg-acc px-4 text-sm font-medium text-accink hover:bg-acc2"
          >
            Повторить / Retry
          </button>
          <button
            onClick={() => {
              try {
                window.location.href = "/";
              } catch {
                window.location.reload();
              }
            }}
            className="focus-ring h-10 flex-1 rounded-[10px] border border-line bg-panel2 px-4 text-sm font-medium text-fg hover:bg-panel3"
          >
            На главную / Home
          </button>
        </div>
        {error.digest && <p className="mt-3 font-mono text-[10.5px] text-dim">digest: {error.digest}</p>}
      </div>
    </div>
  );
}
