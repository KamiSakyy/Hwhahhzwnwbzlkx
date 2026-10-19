"use client";

import {
  useEffect,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { formatUnits } from "ethers";
import { IconCheck, IconCopy, IconChevronDown, IconInfo, IconX } from "./icons";

/* ---------------- format helpers ---------------- */

export function fmtUnits(wei: bigint | string, decimals = 18, maxFrac = 6): string {
  try {
    const raw = typeof wei === "string" ? BigInt(wei) : wei;
    const s = formatUnits(raw, decimals);
    const [int, frac] = s.split(".");
    const f = (frac ?? "").slice(0, maxFrac).replace(/0+$/, "");
    return f ? `${int}.${f}` : int;
  } catch {
    return "0";
  }
}

export function compactAddr(a: string | null | undefined, n = 4): string {
  if (!a) return "";
  return `${a.slice(0, n + 2)}…${a.slice(-4)}`;
}

export function timeAgo(iso: string): string {
  const t = new Date(iso).getTime();
  const d = Math.max(0, Date.now() - t);
  const m = Math.floor(d / 60000);
  if (m < 1) return "now";
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  const days = Math.floor(h / 24);
  if (days < 7) return `${days}d`;
  return new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}
export function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString(undefined, { month: "short", day: "numeric" })} · ${fmtTime(iso)}`;
}

/* ---------------- primitives ---------------- */

type BtnVariant = "primary" | "ghost" | "soft" | "danger" | "outline";
type BtnSize = "sm" | "md" | "lg";

export function Button({
  variant = "soft",
  size = "md",
  loading,
  className = "",
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: BtnVariant;
  size?: BtnSize;
  loading?: boolean;
}) {
  const base =
    "focus-ring inline-flex items-center justify-center gap-2 font-medium tracking-tight transition-all duration-150 select-none disabled:opacity-45 disabled:pointer-events-none";
  const sizes: Record<BtnSize, string> = {
    sm: "h-8 px-3 text-[13px] rounded-lg",
    md: "h-10 px-4 text-sm rounded-[10px]",
    lg: "h-12 px-5 text-[15px] rounded-xl",
  };
  const variants: Record<BtnVariant, string> = {
    primary: "bg-acc text-accink hover:bg-acc2 active:scale-[0.98] shadow-[0_0_24px_-6px_rgba(168,230,46,0.5)]",
    ghost: "text-sub hover:text-fg hover:bg-panel2",
    soft: "bg-panel2 text-fg hover:bg-panel3 border border-line",
    danger: "bg-bad/12 text-bad hover:bg-bad/20 border border-bad/25",
    outline: "border border-line2 text-fg hover:border-acc/50 hover:text-acc",
  };
  return (
    <button className={`${base} ${sizes[size]} ${variants[variant]} ${className}`} disabled={disabled || loading} {...rest}>
      {loading && <Spinner size={14} className={variant === "primary" ? "text-accink" : "text-sub"} />}
      {children}
    </button>
  );
}

export function Card({ className = "", children, onClick }: { className?: string; children: ReactNode; onClick?: () => void }) {
  return (
    <div onClick={onClick} className={`glass-panel border border-line/90 ${className}`}>
      {children}
    </div>
  );
}

export function Input({
  label,
  hint,
  className = "",
  left,
  right,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label?: string; hint?: string; left?: ReactNode; right?: ReactNode }) {
  return (
    <label className="block">
      {label && <span className="mb-1.5 block text-[12px] font-medium tracking-wide text-sub">{label}</span>}
      <div className="relative">
        {left && <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-dim">{left}</span>}
        <input
          className={`focus-ring h-11 w-full rounded-[10px] border border-line bg-ink/60 px-3.5 text-sm text-fg placeholder:text-dim transition-colors hover:border-line2 focus:border-acc/40 ${
            left ? "pl-10" : ""
          } ${right ? "pr-10" : ""} ${className}`}
          {...rest}
        />
        {right && <span className="absolute right-2.5 top-1/2 -translate-y-1/2">{right}</span>}
      </div>
      {hint && <span className="mt-1.5 block text-[12px] text-dim">{hint}</span>}
    </label>
  );
}

export function Textarea({
  label,
  className = "",
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: string }) {
  return (
    <label className="block">
      {label && <span className="mb-1.5 block text-[12px] font-medium tracking-wide text-sub">{label}</span>}
      <textarea
        className={`focus-ring w-full rounded-[10px] border border-line bg-ink/60 px-3.5 py-2.5 text-sm text-fg placeholder:text-dim transition-colors hover:border-line2 focus:border-acc/40 ${className}`}
        {...rest}
      />
    </label>
  );
}

export function Select({
  label,
  className = "",
  children,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & { label?: string }) {
  return (
    <label className="block">
      {label && <span className="mb-1.5 block text-[12px] font-medium tracking-wide text-sub">{label}</span>}
      <div className="relative">
        <select
          className={`focus-ring h-11 w-full appearance-none rounded-[10px] border border-line bg-ink/60 px-3.5 pr-9 text-sm text-fg transition-colors hover:border-line2 focus:border-acc/40 ${className}`}
          {...rest}
        >
          {children}
        </select>
        <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-dim">
          <IconChevronDown size={15} />
        </span>
      </div>
    </label>
  );
}

export function Spinner({ size = 16, className = "" }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={`animate-spin ${className}`}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2.5" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  );
}

export function Modal({
  open,
  onClose,
  title,
  children,
  width = "max-w-md",
  locked,
}: {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  width?: string;
  locked?: boolean;
}) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !locked) onClose();
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open, onClose, locked]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/75 backdrop-blur-[3px] animate-fade-in" onClick={locked ? undefined : onClose} />
      <div className={`relative w-full ${width} animate-scale-in rounded-2xl border border-line2 bg-panel shadow-[0_24px_80px_-20px_rgba(0,0,0,0.8)]`}>
        {title && (
          <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
            <h3 className="text-[15px] font-semibold tracking-tight">{title}</h3>
            {!locked && (
              <button onClick={onClose} className="focus-ring rounded-lg p-1 text-dim hover:bg-panel2 hover:text-fg">
                <IconX size={16} />
              </button>
            )}
          </div>
        )}
        <div className="p-5">{children}</div>
      </div>
    </div>
  );
}

export function CopyBtn({ text, className = "", small }: { text: string; className?: string; small?: boolean }) {
  const [ok, setOk] = useState(false);
  return (
    <button
      className={`focus-ring inline-flex items-center gap-1.5 rounded-lg text-dim transition-colors hover:text-acc ${
        small ? "p-1.5" : "p-2"
      } ${className}`}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
        } catch {
          const ta = document.createElement("textarea");
          ta.value = text;
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          ta.remove();
        }
        setOk(true);
        setTimeout(() => setOk(false), 1200);
      }}
      title="Copy"
    >
      {ok ? <IconCheck size={small ? 13 : 15} className="text-acc" /> : <IconCopy size={small ? 13 : 15} />}
    </button>
  );
}

type Tone = "acc" | "ok" | "bad" | "warn" | "info" | "sub";
const toneCls: Record<Tone, string> = {
  acc: "bg-acc/12 text-acc border-acc/25",
  ok: "bg-ok/12 text-ok border-ok/25",
  bad: "bg-bad/12 text-bad border-bad/25",
  warn: "bg-warn/12 text-warn border-warn/25",
  info: "bg-info/12 text-info border-info/25",
  sub: "bg-panel2 text-sub border-line",
};

export function Badge({ tone = "sub", children, className = "" }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-medium tracking-wide ${toneCls[tone]} ${className}`}
    >
      {children}
    </span>
  );
}

export function Empty({ icon, title, hint }: { icon: ReactNode; title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-14 text-center">
      <div className="mb-1 flex h-12 w-12 items-center justify-center rounded-2xl border border-line bg-panel2 text-dim">{icon}</div>
      <p className="text-sm font-medium text-sub">{title}</p>
      {hint && <p className="max-w-xs text-[12.5px] leading-relaxed text-dim">{hint}</p>}
    </div>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: ReactNode }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex rounded-[10px] border border-line bg-ink/60 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          className={`focus-ring rounded-lg px-3.5 py-1.5 text-[13px] font-medium transition-all ${
            value === o.value ? "bg-panel3 text-fg shadow-[inset_0_0_0_1px_var(--color-line2)]" : "text-sub hover:text-fg"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

/* ---------------- toasts ---------------- */

export interface ToastItem {
  id: number;
  kind: "ok" | "err" | "info";
  msg: string;
}

export function Toasts({ items, dismiss }: { items: ToastItem[]; dismiss: (id: number) => void }) {
  return (
    <div className="pointer-events-none fixed bottom-5 right-5 z-[70] flex w-[min(360px,calc(100vw-40px))] flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          className={`pointer-events-auto animate-scale-in flex items-start gap-2.5 rounded-xl border px-3.5 py-3 text-[13px] leading-snug shadow-[0_16px_48px_-12px_rgba(0,0,0,0.7)] backdrop-blur-md ${
            t.kind === "ok"
              ? "border-ok/30 bg-[#0b1510]/95 text-ok"
              : t.kind === "err"
                ? "border-bad/30 bg-[#170d0e]/95 text-bad"
                : "border-line2 bg-panel/95 text-fg"
          }`}
        >
          <span className="mt-0.5 shrink-0">
            {t.kind === "ok" ? <IconCheck size={15} /> : t.kind === "err" ? <IconX size={15} /> : <IconInfo size={15} />}
          </span>
          <span className="flex-1 text-[13px] text-fg/90">{t.msg}</span>
          <button onClick={() => dismiss(t.id)} className="mt-0.5 shrink-0 text-dim hover:text-fg">
            <IconX size={13} />
          </button>
        </div>
      ))}
    </div>
  );
}
