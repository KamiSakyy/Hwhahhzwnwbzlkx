"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Wallet } from "ethers";
import { Onboarding, LockScreen, type NewWalletPayload } from "@/components/Onboarding";
import { Dashboard } from "@/components/Dashboard";
import { SendView } from "@/components/SendView";
import { DefiView } from "@/components/DefiView";
import { MessagesView } from "@/components/MessagesView";
import { ContactsView } from "@/components/ContactsView";
import { WalletView } from "@/components/WalletView";
import { SettingsView } from "@/components/SettingsView";
import { CommandPalette } from "@/components/CommandPalette";
import { CallModal, type ActiveCallState } from "@/components/CallModal";
import {
  createKeystore,
  deleteKeystore,
  compressedPubFromPriv,
  keystoreExistsAsync,
  unlockKeystore,
} from "@/lib/crypto";
import {
  ACCENT_PRESETS,
  DEFAULT_PREFS,
  idbClearAll,
  listContacts,
  loadPreferences,
  savePreferences,
  WALLPAPER_PRESETS,
  type ContactEntry,
  type UIPreferences,
} from "@/lib/vault";
import { allChains, chainById, shortAddr, type ChainDef } from "@/lib/networks";
import { getNetworkStatus } from "@/lib/chain";
import { api } from "@/lib/api";
import { avatarStyle, type AppApi, type Session, type View } from "@/lib/session";
import { Badge, Spinner, Toasts, type ToastItem } from "@/components/ui";
import { haptic } from "@/lib/haptics";
import {
  IconChat,
  IconChevronDown,
  IconCommand,
  IconGear,
  IconGrid,
  IconLayers,
  IconLock,
  IconLogout,
  IconPhone,
  IconSend,
  IconUsers,
  IconWallet,
  LogoMark,
} from "@/components/icons";

type Phase = "boot" | "onboard" | "locked" | "unlocked";

const VIEW_META: Record<View, { ruTitle: string; enTitle: string; ruSub: string; enSub: string }> = {
  dashboard: {
    ruTitle: "Обзор",
    enTitle: "Dashboard",
    ruSub: "Балансы во всех сетях, график P&L и активность",
    enSub: "Multi-chain balances, P&L chart & activity",
  },
  wallet: {
    ruTitle: "Кошелёк",
    enTitle: "Wallet",
    ruSub: "QR-код, токены ERC-20, NFT галерея и история",
    enSub: "Receive QR, ERC-20 tokens, NFT gallery & history",
  },
  send: {
    ruTitle: "Отправить",
    enTitle: "Send",
    ruSub: "Локальная подпись, пакетные транзакции, отправка в сеть",
    enSub: "Sign locally, batch transfer, broadcast on-chain",
  },
  defi: {
    ruTitle: "DeFi & Свопы",
    enTitle: "DeFi & Swaps",
    ruSub: "DEX свопы 1inch, стейкинг ETH Lido и кроссчейн мосты",
    enSub: "Zero-fee DEX aggregator, Lido staking & bridge",
  },
  messages: {
    ruTitle: "Мессенджер",
    enTitle: "Messenger",
    ruSub: "E2E шифрование через блокчейн · Голосовые, кружочки и звонки",
    enSub: "On-chain E2E encrypted · Voice, video notes & live calls",
  },
  contacts: {
    ruTitle: "Контакты",
    enTitle: "Contacts",
    ruSub: "Адресная книга Web3 (0x/ENS) и отпечатки безопасности",
    enSub: "Web3 address book, ENS resolver & safety fingerprints",
  },
  settings: {
    ruTitle: "Настройки",
    enTitle: "Settings",
    ruSub: "Обои Google Фото, шрифты, биометрия и аудит 10/10",
    enSub: "Google Photos wallpapers, fonts, biometrics & 10/10 audit",
  },
};

export default function Home() {
  const [phase, setPhase] = useState<Phase>("boot");
  const [session, setSession] = useState<Session | null>(null);
  const [address, setAddress] = useState("");
  const [view, setView] = useState<View>("dashboard");
  const [chainId, setChainIdState] = useState(11155111); // Default Sepolia ready or saved chain
  const [chains, setChains] = useState<ChainDef[]>([]);
  const [contacts, setContacts] = useState<ContactEntry[]>([]);
  const [prefs, setPrefs] = useState<UIPreferences>(DEFAULT_PREFS);
  const [chatTarget, setChatTarget] = useState<string | null>(null);
  const [sendTarget, setSendTarget] = useState<string | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [activeCall, setActiveCall] = useState<ActiveCallState | null>(null);
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [unread, setUnread] = useState(0);
  const [refreshTick, setRefreshTick] = useState(0);
  const toastId = useRef(0);
  const lastActivity = useRef(Date.now());

  const t = useCallback(
    (ru: string, en: string) => (prefs.lang === "ru" ? ru : en),
    [prefs.lang],
  );

  /* ---------- toasts ---------- */
  const toast = useCallback((kind: "ok" | "err" | "info", msg: string) => {
    const id = ++toastId.current;
    setToasts((prev) => [...prev.slice(-3), { id, kind, msg }]);
    setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), 5200);
  }, []);

  /* ---------- apply dynamic CSS variables for theme, font, glass & radius ---------- */
  useEffect(() => {
    if (typeof document === "undefined") return;
    const root = document.documentElement;
    const accObj = ACCENT_PRESETS[prefs.accent] ?? ACCENT_PRESETS.lime;
    root.style.setProperty("--dyn-acc", accObj.acc);
    root.style.setProperty("--dyn-acc2", accObj.acc2);
    root.style.setProperty("--dyn-accink", accObj.ink);
    root.style.setProperty("--dyn-panel-alpha", String(prefs.panelOpacity / 100));
    root.style.setProperty("--dyn-font-scale", String(prefs.fontSizeScale / 100));
    root.style.setProperty(
      "--dyn-radius",
      prefs.borderRadius === "sharp" ? "8px" : prefs.borderRadius === "round" ? "20px" : "14px",
    );
    document.body.dataset.font = prefs.fontFamily;
  }, [prefs]);

  /* ---------- boot from IndexedDB Secure Vault ---------- */
  const reloadContacts = useCallback(async () => {
    const list = await listContacts();
    setContacts(list);
  }, []);

  useEffect(() => {
    let mounted = true;
    const c = allChains();
    setChains(c);
    try {
      const savedChain = Number(localStorage.getItem("obsidian.chain") ?? 11155111);
      if (c.some((x) => x.chainId === savedChain)) setChainIdState(savedChain);
    } catch {
      /* storage blocked — keep default chain */
    }

    const settle = (exists: boolean) => {
      if (mounted) setPhase(exists ? "locked" : "onboard");
    };

    // Watchdog: never leave the user on the boot spinner, whatever the
    // storage backend does. Falls back to the lock screen (safe: no silent
    // wallet creation) with an "erase vault" escape hatch available.
    const watchdog = setTimeout(async () => {
      try {
        settle(await keystoreExistsAsync());
      } catch {
        settle(true);
      }
    }, 3000);

    (async () => {
      try {
        const [loadedPrefs, exists] = await Promise.all([
          loadPreferences().catch(() => DEFAULT_PREFS),
          keystoreExistsAsync().catch(() => false),
        ]);
        if (!mounted) return;
        setPrefs(loadedPrefs);
        clearTimeout(watchdog);
        settle(exists);
        reloadContacts().catch(() => undefined);
      } catch {
        clearTimeout(watchdog);
        settle(false);
      }
    })();

    return () => {
      mounted = false;
      clearTimeout(watchdog);
    };
  }, [reloadContacts]);

  const updatePrefs = useCallback(async (patch: Partial<UIPreferences>) => {
    setPrefs((prev) => {
      const next = { ...prev, ...patch };
      savePreferences(next).catch(() => undefined);
      return next;
    });
  }, []);

  const chain = useMemo(() => chainById(chains.length ? chains : allChains(), chainId), [chains, chainId]);

  /* ---------- session lifecycle ---------- */
  const registerWallet = useCallback(
    (s: Session) => {
      const addr = new Wallet(s.privateKey).address;
      const pub = compressedPubFromPriv(s.privateKey);
      api
        .upsertWallet({ address: addr, label: s.label, pubkey: pub }, s.privateKey)
        .catch(() => undefined);
    },
    [],
  );

  const adoptSession = useCallback(
    (s: Session) => {
      const addr = new Wallet(s.privateKey).address;
      setSession(s);
      setAddress(addr);
      setPhase("unlocked");
      lastActivity.current = Date.now();
      registerWallet(s);
    },
    [registerWallet],
  );

  const handleCreated = useCallback(
    async (p: NewWalletPayload, password: string) => {
      await createKeystore(
        {
          privateKey: p.privateKey,
          mnemonic: p.mnemonic ?? "",
          label: p.label || "My wallet",
        },
        password,
      );
      adoptSession({ privateKey: p.privateKey, mnemonic: p.mnemonic, label: p.label || "My wallet" });
    },
    [adoptSession],
  );

  const handleUnlock = useCallback(
    async (password: string) => {
      const payload = await unlockKeystore(password);
      if (!payload) return false;
      adoptSession({
        privateKey: payload.privateKey,
        mnemonic: payload.mnemonic || null,
        label: payload.label || "My wallet",
      });
      return true;
    },
    [adoptSession],
  );

  const lock = useCallback(() => {
    setSession(null);
    setAddress("");
    setView("dashboard");
    setPhase("locked");
    toast("info", t("Кошелёк заблокирован. Ключи выгружены из памяти.", "Wallet locked. Keys cleared from memory."));
  }, [toast, t]);

  const erase = useCallback(async () => {
    await deleteKeystore();
    await idbClearAll();
    setSession(null);
    setAddress("");
    setContacts([]);
    setPhase("onboard");
  }, []);

  /* ---------- activity + auto-lock ---------- */
  useEffect(() => {
    if (phase !== "unlocked") return;
    const mark = () => {
      lastActivity.current = Date.now();
    };
    const evs: (keyof WindowEventMap)[] = ["click", "keydown", "mousemove", "touchstart"];
    evs.forEach((e) => window.addEventListener(e, mark, { passive: true }));
    const timer = setInterval(() => {
      if (prefs.lockMinutes > 0 && Date.now() - lastActivity.current > prefs.lockMinutes * 60_000) {
        lock();
      }
    }, 20_000);
    return () => {
      evs.forEach((e) => window.removeEventListener(e, mark));
      clearInterval(timer);
    };
  }, [phase, prefs.lockMinutes, lock]);

  /* ---------- unread poll ---------- */
  useEffect(() => {
    if (phase !== "unlocked" || !address) return;
    let alive = true;
    const me = address.toLowerCase();
    const tick = async () => {
      try {
        const rows = await api.getMessages(me, 60);
        const key = `obsidian.lastRead.${me}`;
        const lastRead = Number(localStorage.getItem(key) ?? 0);
        const n = rows.filter((r) => r.recipient.toLowerCase() === me && r.id > lastRead).length;
        if (alive) setUnread(n);
        if (view === "messages" && rows.length > 0) {
          const maxId = Math.max(...rows.map((r) => r.id));
          if (maxId > lastRead) localStorage.setItem(key, String(maxId));
        }
      } catch {
        /* noop */
      }
    };
    tick();
    const timer = setInterval(tick, 20_000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [phase, address, view]);

  const getContact = useCallback(
    (addr: string) => contacts.find((c) => c.address === addr.trim().toLowerCase()),
    [contacts],
  );

  const displayName = useCallback(
    (addr: string) => {
      if (!addr) return "";
      const c = contacts.find((x) => x.address === addr.trim().toLowerCase());
      if (c) return `${c.avatarEmoji ?? ""} ${c.name}`.trim();
      return shortAddr(addr);
    },
    [contacts],
  );

  /* ---------- app api ---------- */
  const app: AppApi = useMemo(
    () => ({
      session: session as Session,
      address,
      chain,
      chains: chains.length ? chains : allChains(),
      setChainId: (id: number) => {
        setChainIdState(id);
        try {
          localStorage.setItem("obsidian.chain", String(id));
        } catch {
          /* noop */
        }
      },
      go: (v: View) => setView(v),
      toast,
      refreshTick,
      bump: () => setRefreshTick((x) => x + 1),
      lock,
      reloadChains: () => setChains(allChains()),
      onErase: erase,
      onLabelChanged: (label: string) => {
        setSession((s) => (s ? { ...s, label } : s));
        if (address && session) {
          api.upsertWallet({ address, label }, session.privateKey).catch(() => undefined);
        }
      },
      contacts,
      reloadContacts,
      getContact,
      displayName,
      chatTarget,
      sendTarget,
      openChatWith: (addr: string) => {
        setChatTarget(addr.toLowerCase());
        setView("messages");
      },
      openSendTo: (addr: string) => {
        setSendTarget(addr);
        setView("send");
      },
      startCall: (peerAddress: string, peerName?: string, type: "voice" | "video" = "voice") => {
        haptic("medium");
        setActiveCall({
          peerAddress,
          peerName: peerName || displayName(peerAddress),
          type,
          status: "outgoing",
        });
      },
      prefs,
      updatePrefs,
      t,
    }),
    [
      session,
      address,
      chain,
      chains,
      toast,
      refreshTick,
      lock,
      erase,
      contacts,
      reloadContacts,
      getContact,
      displayName,
      chatTarget,
      sendTarget,
      prefs,
      updatePrefs,
      t,
    ],
  );

  /* ---------- render ---------- */
  if (phase === "boot") {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-dim">
          <span className="animate-pulse-soft text-acc">
            <LogoMark size={38} />
          </span>
          <Spinner size={16} />
        </div>
      </div>
    );
  }

  if (phase === "onboard") {
    return (
      <>
        <Onboarding
          onCreated={handleCreated}
          toast={toast}
          lang={prefs.lang}
          onToggleLang={() => updatePrefs({ lang: prefs.lang === "ru" ? "en" : "ru" })}
        />
        <Toasts items={toasts} dismiss={(id) => setToasts((prev) => prev.filter((x) => x.id !== id))} />
      </>
    );
  }

  if (phase === "locked" || !session) {
    return (
      <>
        <LockScreen onUnlock={handleUnlock} onWipe={erase} lang={prefs.lang} />
        <Toasts items={toasts} dismiss={(id) => setToasts((prev) => prev.filter((x) => x.id !== id))} />
      </>
    );
  }

  const meta = VIEW_META[view];
  const presetWp = WALLPAPER_PRESETS.find((p) => p.id === prefs.wallpaperUrl);
  const wpStyle: React.CSSProperties = presetWp
    ? { background: presetWp.css }
    : prefs.wallpaperUrl
      ? {
          backgroundImage: `url(${prefs.wallpaperUrl})`,
          backgroundSize: `${prefs.wallpaperScale}%`,
          backgroundPosition: "center",
        }
      : { background: "#06080b" };

  return (
    <div className="relative flex h-dvh overflow-hidden">
      {/* Global Custom Wallpaper Layer (Google Photos / Presets with Blur & Opacity) */}
      <div
        className="pointer-events-none fixed inset-0 z-0 transition-all duration-300"
        style={{
          ...wpStyle,
          opacity: prefs.wallpaperOpacity / 100,
          filter: prefs.wallpaperBlur > 0 ? `blur(${prefs.wallpaperBlur}px)` : undefined,
          transform: prefs.wallpaperScale !== 100 ? `scale(${prefs.wallpaperScale / 100})` : undefined,
        }}
      />

      {/* Desktop Sidebar */}
      <aside className="relative z-10 hidden w-[236px] shrink-0 flex-col border-r border-line bg-panel/75 backdrop-blur-xl md:flex">
        <div className="flex h-14 items-center justify-between border-b border-line px-5">
          <div className="flex items-center gap-2.5">
            <span className="text-acc">
              <LogoMark size={22} />
            </span>
            <span className="text-[14px] font-semibold tracking-[0.16em]">OBSIDIAN</span>
          </div>
          <Badge tone="acc">2.0</Badge>
        </div>

        <nav className="flex-1 space-y-1 p-3">
          {(Object.keys(VIEW_META) as View[]).map((v) => {
            const activeView = view === v;
            const ic =
              v === "dashboard" ? (
                <IconGrid size={17} />
              ) : v === "wallet" ? (
                <IconWallet size={17} />
              ) : v === "send" ? (
                <IconSend size={17} />
              ) : v === "defi" ? (
                <IconLayers size={17} />
              ) : v === "messages" ? (
                <IconChat size={17} />
              ) : v === "contacts" ? (
                <IconUsers size={17} />
              ) : (
                <IconGear size={17} />
              );
            return (
              <button
                key={v}
                onClick={() => setView(v)}
                className={`focus-ring flex w-full items-center gap-3 rounded-[10px] px-3 py-2.5 text-[13.5px] font-medium transition-all ${
                  activeView
                    ? "bg-panel3 text-fg shadow-[inset_0_0_0_1px_var(--color-line2)]"
                    : "text-sub hover:bg-panel2/80 hover:text-fg"
                }`}
              >
                <span className={activeView ? "text-acc" : ""}>{ic}</span>
                <span className="flex-1 text-left">
                  {t(VIEW_META[v].ruTitle, VIEW_META[v].enTitle)}
                </span>
                {v === "messages" && unread > 0 && (
                  <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-acc px-1.5 font-mono text-[10px] font-bold text-accink">
                    {unread}
                  </span>
                )}
                {v === "contacts" && contacts.length > 0 && (
                  <span className="font-mono text-[10.5px] text-dim">{contacts.length}</span>
                )}
              </button>
            );
          })}
        </nav>

        <div className="border-t border-line p-3">
          <div className="flex items-center gap-2.5 rounded-xl border border-line bg-ink/60 p-2.5">
            <span className="h-9 w-9 shrink-0 rounded-xl" style={avatarStyle(address)} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[12.5px] font-medium">{session.label}</p>
              <p className="font-mono text-[10px] text-dim">{shortAddr(address)}</p>
            </div>
            <button
              onClick={lock}
              className="focus-ring rounded-lg p-1.5 text-dim transition-colors hover:text-warn"
              title={t("Заблокировать хранилище", "Lock vault")}
            >
              <IconLock size={15} />
            </button>
          </div>
          <p className="mt-2 text-center text-[10px] tracking-wide text-dim">
            <span className="mr-1 inline-block h-1.5 w-1.5 rounded-full bg-ok align-middle" />
            IndexedDB Vault · E2E
          </p>
        </div>
      </aside>

      {/* Main Content */}
      <div className="relative z-10 flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-line bg-panel/65 px-4 backdrop-blur-xl lg:px-6">
          <div className="min-w-0">
            <h1 className="truncate text-[15px] font-semibold tracking-tight">
              {t(meta.ruTitle, meta.enTitle)}
            </h1>
            <p className="hidden truncate text-[11px] text-dim sm:block">
              {t(meta.ruSub, meta.enSub)}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {/* Quick Search & Command Palette Trigger (Cmd+K) */}
            <button
              onClick={() => {
                haptic("light");
                setPaletteOpen(true);
              }}
              className="focus-ring flex h-9 items-center gap-2 rounded-[10px] border border-line bg-panel2/80 px-2.5 text-[12px] text-sub transition-colors hover:border-acc/40 hover:text-fg hover:bg-panel3"
              title={t("Командная строка и быстрый поиск (Cmd+K)", "Command Palette & Search (Cmd+K)")}
            >
              <IconCommand size={14} className="text-acc" />
              <span className="hidden sm:inline font-medium">{t("Поиск", "Search")}</span>
              <kbd className="hidden md:inline rounded border border-line bg-ink/70 px-1 font-mono text-[9.5px] text-dim">
                ⌘K
              </kbd>
            </button>

            <button
              onClick={() => updatePrefs({ lang: prefs.lang === "ru" ? "en" : "ru" })}
              className="focus-ring flex h-9 items-center rounded-[10px] border border-line bg-panel2 px-2.5 font-mono text-[11.5px] font-medium text-sub hover:border-line2 hover:text-acc"
              title="Switch Language (RU / EN)"
            >
              {prefs.lang.toUpperCase()}
            </button>

            <NetworkStatusPill app={app} />

            <ChainPicker chains={chains} current={chain} onPick={(id) => app.setChainId(id)} />

            <button
              onClick={lock}
              className="focus-ring flex h-9 items-center gap-2 rounded-[10px] border border-line bg-panel2 px-3 text-[12.5px] text-sub transition-colors hover:text-warn md:hidden"
            >
              <IconLock size={14} />
            </button>
            <button
              onClick={lock}
              className="focus-ring hidden h-9 items-center gap-2 rounded-[10px] border border-line bg-panel2 px-3 text-[12.5px] text-sub transition-colors hover:text-warn md:flex"
              title={t("Заблокировать кошелёк", "Lock wallet")}
            >
              <IconLogout size={14} /> {t("Блок", "Lock")}
            </button>
          </div>
        </header>

        <main className="min-h-0 flex-1 overflow-y-auto px-3.5 pb-24 pt-4 md:px-6 md:pb-6 lg:px-7">
          <div key={view + chain.chainId} className="mx-auto max-w-[1180px] animate-fade-up">
            {view === "dashboard" && <Dashboard app={app} />}
            {view === "send" && <SendView app={app} />}
            {view === "defi" && <DefiView app={app} />}
            {view === "messages" && <MessagesView app={app} />}
            {view === "contacts" && <ContactsView app={app} />}
            {view === "wallet" && <WalletView app={app} />}
            {view === "settings" && <SettingsView app={app} />}
          </div>
        </main>
      </div>

      {/* Android & Mobile Native Bottom Bar with Smooth Horizontal Scrolling & Tactile Pills */}
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-line/90 bg-panel/95 backdrop-blur-2xl safe-pb md:hidden shadow-[0_-10px_35px_rgba(0,0,0,0.7)]">
        <div className="horizontal-scroll-touch no-scrollbar flex items-center justify-start sm:justify-center gap-1.5 px-3 py-2 min-w-full">
          {(Object.keys(VIEW_META) as View[]).map((v) => {
            const active = view === v;
            const ic =
              v === "dashboard" ? (
                <IconGrid size={18} />
              ) : v === "wallet" ? (
                <IconWallet size={18} />
              ) : v === "send" ? (
                <IconSend size={18} />
              ) : v === "defi" ? (
                <IconLayers size={18} />
              ) : v === "messages" ? (
                <IconChat size={18} />
              ) : v === "contacts" ? (
                <IconUsers size={18} />
              ) : (
                <IconGear size={18} />
              );
            return (
              <button
                key={v}
                onClick={(e) => {
                  haptic("light");
                  setView(v);
                  e.currentTarget.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
                }}
                className={`focus-ring relative flex min-w-[62px] shrink-0 flex-col items-center gap-1 rounded-xl px-2.5 py-1.5 text-[10.5px] font-medium transition-all duration-200 active:scale-95 ${
                  active
                    ? "bg-panel3/90 text-acc border border-acc/35 shadow-[0_0_12px_-2px_color-mix(in_srgb,var(--dyn-acc)_35%,transparent)]"
                    : "text-sub hover:text-fg hover:bg-panel2/50 border border-transparent"
                }`}
              >
                <span className={`transition-transform duration-200 ${active ? "scale-110 text-acc" : ""}`}>
                  {ic}
                </span>
                <span className="truncate max-w-[66px] tracking-tight">
                  {t(VIEW_META[v].ruTitle, VIEW_META[v].enTitle)}
                </span>
                {v === "messages" && unread > 0 && (
                  <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-acc px-1 text-[9px] font-bold text-accink shadow-[0_0_8px_var(--dyn-acc)]">
                    {unread > 99 ? "99+" : unread}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </nav>

      <Toasts items={toasts} dismiss={(id) => setToasts((prev) => prev.filter((x) => x.id !== id))} />

      {/* Global Command Palette (Cmd+K) */}
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} app={app} />

      {/* Live P2P WebRTC Audio / Video Call Modal */}
      <CallModal call={activeCall} onClose={() => setActiveCall(null)} t={t} />
    </div>
  );
}

/* ---------- live RPC network status pill ---------- */
function NetworkStatusPill({ app }: { app: AppApi }) {
  const [status, setStatus] = useState<{
    healthy: number;
    latencyMs: number;
    best: string | null;
  } | null>(null);
  const [probing, setProbing] = useState(true);
  const chainId = app.chain.chainId;
  const chainName = app.chain.name;
  const goSettings = app.go;
  const t = app.t;

  useEffect(() => {
    let alive = true;
    setProbing(true);
    setStatus(null);
    getNetworkStatus(app.chain)
      .then((s) => {
        if (!alive) return;
        setStatus({ healthy: s.healthy, latencyMs: s.latencyMs, best: s.best });
      })
      .catch(() => {
        if (alive) setStatus({ healthy: 0, latencyMs: -1, best: null });
      })
      .finally(() => alive && setProbing(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chainId, app.refreshTick]);

  const state = probing ? "probing" : !status || status.healthy === 0 ? "down" : status.latencyMs > 1500 ? "slow" : "up";
  const dot =
    state === "probing" ? "bg-warn animate-pulse-soft" : state === "up" ? "bg-ok" : state === "slow" ? "bg-warn" : "bg-bad";
  const label =
    state === "probing"
      ? t("Поиск RPC…", "Finding RPC…")
      : state === "down"
        ? t("RPC недоступен", "RPC unreachable")
        : `${status?.latencyMs} ms · ${status?.healthy} nodes`;

  return (
    <button
      onClick={() => goSettings("settings")}
      title={
        state === "down"
          ? t(
              "Ни один RPC-узел не ответил. Откройте Настройки → RPC-движок, чтобы пересканировать или добавить свой узел/ключ Alchemy.",
              "No RPC node responded. Open Settings → RPC Engine to re-scan or add your own node / Alchemy key.",
            )
          : `${chainName}: ${status?.healthy ?? 0} ${t("рабочих узлов", "healthy nodes")}${
              status?.best ? ` · ${safeHost(status.best)}` : ""
            }`
      }
      className={`focus-ring flex h-9 items-center gap-2 rounded-[10px] border px-2.5 text-[11.5px] transition-colors ${
        state === "down"
          ? "border-bad/40 bg-bad/10 text-bad"
          : state === "slow"
            ? "border-warn/35 bg-warn/8 text-warn"
            : "border-line bg-panel2 text-sub hover:border-line2"
      }`}
    >
      <span className={`h-2 w-2 shrink-0 rounded-full ${dot}`} />
      <span className="hidden font-mono sm:inline">{label}</span>
      {state === "probing" && <Spinner size={11} />}
    </button>
  );
}

function safeHost(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url.slice(0, 32);
  }
}

/* ---------- chain picker ---------- */
function ChainPicker({
  chains,
  current,
  onPick,
}: {
  chains: ChainDef[];
  current: ChainDef;
  onPick: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener("mousedown", h);
    return () => window.removeEventListener("mousedown", h);
  }, [open]);

  const ql = q.toLowerCase();
  const mains = chains.filter((c) => !c.testnet && c.name.toLowerCase().includes(ql));
  const tests = chains.filter((c) => c.testnet && c.name.toLowerCase().includes(ql));

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="focus-ring flex h-9 items-center gap-2 rounded-[10px] border border-line bg-panel2 px-3 text-[12.5px] font-medium transition-colors hover:border-line2"
      >
        <span className="h-2 w-2 rounded-full" style={{ background: `hsl(${current.hue} 70% 55%)` }} />
        {current.short}
        {current.testnet && <Badge tone="warn">test</Badge>}
        <IconChevronDown size={13} className={open ? "rotate-180 text-acc" : "text-dim"} />
      </button>
      {open && (
        <div className="absolute right-0 top-11 z-50 w-[300px] animate-scale-in rounded-xl border border-line2 bg-panel2 p-2 shadow-[0_24px_60px_-16px_rgba(0,0,0,0.85)]">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search chains (Sepolia, ETH, Base…)"
            autoFocus
            className="focus-ring mb-2 h-9 w-full rounded-lg border border-line bg-ink/60 px-3 text-[12.5px] placeholder:text-dim"
          />
          <div className="max-h-[380px] overflow-y-auto">
            <p className="px-2 pb-1 pt-1 text-[10px] uppercase tracking-[0.14em] text-dim">Mainnet</p>
            {mains.map((c) => (
              <ChainRow
                key={c.chainId}
                c={c}
                active={current.chainId === c.chainId}
                onClick={() => {
                  onPick(c.chainId);
                  setOpen(false);
                }}
              />
            ))}
            <p className="px-2 pb-1 pt-2 text-[10px] uppercase tracking-[0.14em] text-dim">Testnets (Sepolia & L2)</p>
            {tests.map((c) => (
              <ChainRow
                key={c.chainId}
                c={c}
                active={current.chainId === c.chainId}
                onClick={() => {
                  onPick(c.chainId);
                  setOpen(false);
                }}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function ChainRow({ c, active, onClick }: { c: ChainDef; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.5px] transition-colors ${
        active ? "bg-panel3 text-acc" : "text-sub hover:bg-panel3/60 hover:text-fg"
      }`}
    >
      <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: `hsl(${c.hue} 70% 55%)` }} />
      <span className="min-w-0 flex-1 truncate">{c.name}</span>
      <span className="font-mono text-[10.5px] text-dim">{c.symbol}</span>
      {active && (
        <svg
          width="13"
          height="13"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="m4.5 12.5 5 5 10-11" />
        </svg>
      )}
    </button>
  );
}
