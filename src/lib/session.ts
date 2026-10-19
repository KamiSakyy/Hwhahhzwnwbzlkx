import type * as React from "react";
import type { ChainDef } from "./networks";
import type { ContactEntry, UIPreferences } from "./vault";

export interface Session {
  privateKey: string;
  mnemonic: string | null;
  label: string;
}

export type View = "dashboard" | "wallet" | "send" | "defi" | "messages" | "contacts" | "settings";

export type ToastFn = (kind: "ok" | "err" | "info", msg: string) => void;

export interface AppApi {
  session: Session;
  address: string;
  chain: ChainDef;
  chains: ChainDef[];
  setChainId: (id: number) => void;
  go: (v: View) => void;
  toast: ToastFn;
  /** bump to trigger views to reload data */
  refreshTick: number;
  bump: () => void;
  lock: () => void;
  reloadChains: () => void;
  onErase: () => void;
  onLabelChanged: (label: string) => void;
  // Contacts book
  contacts: ContactEntry[];
  reloadContacts: () => Promise<void>;
  getContact: (addr: string) => ContactEntry | undefined;
  displayName: (addr: string) => string;
  // Deep-link targets
  chatTarget: string | null;
  sendTarget: string | null;
  openChatWith: (addr: string) => void;
  openSendTo: (addr: string) => void;
  startCall: (peerAddress: string, peerName?: string, type?: "voice" | "video") => void;
  // Customization & Localization
  prefs: UIPreferences;
  updatePrefs: (patch: Partial<UIPreferences>) => Promise<void>;
  t: (ru: string, en: string) => string;
}

export function hueFor(addr: string): number {
  if (!addr || addr.length < 10) return 160;
  let h = 0;
  for (let i = 2; i < Math.min(22, addr.length); i += 2) {
    const v = parseInt(addr.slice(i, i + 2), 16);
    if (!Number.isNaN(v)) h = (h * 31 + v) % 360;
  }
  return h;
}

export function avatarStyle(addr: string): React.CSSProperties {
  const h = hueFor(addr);
  return {
    background: `linear-gradient(135deg, hsl(${h} 68% 24%), hsl(${(h + 42) % 360} 72% 13%))`,
    border: `1px solid hsl(${h} 60% 42% / 0.55)`,
    color: `hsl(${h} 85% 76%)`,
  };
}
