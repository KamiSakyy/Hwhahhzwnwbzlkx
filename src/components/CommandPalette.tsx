"use client";

import { useEffect, useMemo, useState } from "react";
import type { AppApi, View } from "@/lib/session";
import {
  IconBolt,
  IconBridge,
  IconChat,
  IconCommand,
  IconCopy,
  IconGear,
  IconGlobe,
  IconGrid,
  IconLayers,
  IconLock,
  IconPalette,
  IconSearch,
  IconSend,
  IconSwap,
  IconUsers,
  IconWallet,
  IconX,
} from "./icons";
import { haptic } from "@/lib/haptics";
import { shortAddr } from "@/lib/networks";

export function CommandPalette({
  open,
  onClose,
  app,
}: {
  open: boolean;
  onClose: () => void;
  app: AppApi;
}) {
  const { chains, contacts, go, setChainId, t, lock } = app;
  const [query, setQuery] = useState("");
  const [selectedIndex, setSelectedIndex] = useState(0);

  useEffect(() => {
    if (!open) {
      setQuery("");
      setSelectedIndex(0);
    }
  }, [open]);

  // Global keyboard shortcut Cmd+K / Ctrl+K
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        onClose(); // toggle
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const items = useMemo(() => {
    const q = query.toLowerCase().trim();
    const list: Array<{
      id: string;
      category: string;
      title: string;
      subtitle?: string;
      icon: React.ReactNode;
      action: () => void;
    }> = [];

    // Quick Actions
    list.push({
      id: "action-send",
      category: t("Быстрые действия", "Quick Actions"),
      title: t("Отправить криптовалюту", "Send Crypto"),
      subtitle: t("Перевод ETH / ERC-20 с локальной подписью", "Local signing & broadcast"),
      icon: <IconSend size={16} className="text-acc" />,
      action: () => {
        go("send");
        onClose();
      },
    });

    list.push({
      id: "action-swap",
      category: t("Быстрые действия", "Quick Actions"),
      title: t("DEX Своп токенов", "Swap Tokens on DEX"),
      subtitle: t("1inch / Uniswap агрегатор без комиссий", "Zero-fee DEX route"),
      icon: <IconSwap size={16} className="text-info" />,
      action: () => {
        go("defi");
        onClose();
      },
    });

    list.push({
      id: "action-msg",
      category: t("Быстрые действия", "Quick Actions"),
      title: t("Написать сообщение", "New Encrypted Message"),
      subtitle: t("On-chain E2E calldata messenger", "Direct calldata transaction"),
      icon: <IconChat size={16} className="text-acc" />,
      action: () => {
        go("messages");
        onClose();
      },
    });

    list.push({
      id: "action-copy",
      category: t("Быстрые действия", "Quick Actions"),
      title: t("Скопировать мой адрес", "Copy My Address"),
      subtitle: shortAddr(app.address, 6),
      icon: <IconCopy size={16} className="text-sub" />,
      action: async () => {
        try {
          await navigator.clipboard.writeText(app.address);
          app.toast("ok", t("Адрес скопирован в буфер!", "Address copied to clipboard!"));
        } catch {}
        onClose();
      },
    });

    list.push({
      id: "action-lock",
      category: t("Быстрые действия", "Quick Actions"),
      title: t("Заблокировать кошелёк", "Lock Wallet"),
      subtitle: t("Очистить ключи из оперативной памяти", "Wipe keys from active memory"),
      icon: <IconLock size={16} className="text-warn" />,
      action: () => {
        lock();
        onClose();
      },
    });

    // Navigation Views
    const views: Array<{ id: View; title: string; enTitle: string; icon: React.ReactNode }> = [
      { id: "dashboard", title: "Обзор портфеля", enTitle: "Portfolio Dashboard", icon: <IconGrid size={16} /> },
      { id: "wallet", title: "Кошелёк и QR-код", enTitle: "Wallet & Receive QR", icon: <IconWallet size={16} /> },
      { id: "defi", title: "DeFi, Стейкинг и Мосты", enTitle: "DeFi, Staking & Bridge", icon: <IconLayers size={16} /> },
      { id: "messages", title: "On-Chain Мессенджер", enTitle: "On-Chain Messenger", icon: <IconChat size={16} /> },
      { id: "contacts", title: "Адресная книга контактов", enTitle: "Address Book", icon: <IconUsers size={16} /> },
      { id: "settings", title: "Настройки и кастомизация", enTitle: "Settings & Customization", icon: <IconGear size={16} /> },
    ];

    for (const v of views) {
      list.push({
        id: `nav-${v.id}`,
        category: t("Разделы", "Navigation"),
        title: t(v.title, v.enTitle),
        icon: v.icon,
        action: () => {
          go(v.id);
          onClose();
        },
      });
    }

    // Contacts
    for (const c of contacts) {
      list.push({
        id: `contact-${c.address}`,
        category: t("Контакты", "Contacts"),
        title: `${c.avatarEmoji ?? "💎"} ${c.name}`,
        subtitle: `${shortAddr(c.address, 4)}${c.ensName ? ` (${c.ensName})` : ""}`,
        icon: <IconUsers size={16} className="text-acc" />,
        action: () => {
          app.openChatWith(c.address);
          onClose();
        },
      });
    }

    // Chains Switcher
    for (const ch of chains) {
      list.push({
        id: `chain-${ch.chainId}`,
        category: t("Сети блокчейна", "Networks"),
        title: `${ch.name} (${ch.symbol})`,
        subtitle: `Chain ID ${ch.chainId}${ch.testnet ? " · Testnet" : " · Mainnet"}`,
        icon: <IconGlobe size={16} className="text-info" />,
        action: () => {
          setChainId(ch.chainId);
          app.toast("ok", t(`Переключено на сеть ${ch.name}`, `Switched to ${ch.name}`));
          onClose();
        },
      });
    }

    if (!q) return list;
    return list.filter(
      (item) =>
        item.title.toLowerCase().includes(q) ||
        (item.subtitle && item.subtitle.toLowerCase().includes(q)) ||
        item.category.toLowerCase().includes(q)
    );
  }, [query, contacts, chains, app, go, setChainId, t, lock, onClose]);

  if (!open) return null;

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelectedIndex((prev) => (prev + 1) % Math.max(1, items.length));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelectedIndex((prev) => (prev - 1 + items.length) % Math.max(1, items.length));
    } else if (e.key === "Enter" && items[selectedIndex]) {
      e.preventDefault();
      haptic("light");
      items[selectedIndex].action();
    } else if (e.key === "Escape") {
      onClose();
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16 sm:pt-24">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-ink/80 backdrop-blur-md animate-fade-in" onClick={onClose} />

      {/* Modal Dialog */}
      <div className="relative w-full max-w-xl overflow-hidden rounded-2xl border border-line2 bg-panel shadow-[0_24px_80px_-15px_rgba(0,0,0,0.9)] animate-scale-in">
        {/* Search Header */}
        <div className="flex items-center gap-3 border-b border-line px-4 py-3">
          <IconSearch size={18} className="text-dim shrink-0" />
          <input
            autoFocus
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setSelectedIndex(0);
            }}
            onKeyDown={handleKeyDown}
            placeholder={t("Поиск действий, контактов, сетей (Cmd+K)…", "Search actions, contacts, chains (Cmd+K)…")}
            className="w-full bg-transparent text-[14.5px] font-medium text-fg outline-none placeholder:text-dim"
          />
          <button onClick={onClose} className="rounded-lg p-1 text-dim hover:bg-panel2 hover:text-fg">
            <IconX size={16} />
          </button>
        </div>

        {/* Results List */}
        <div className="max-h-96 overflow-y-auto p-2 space-y-1">
          {items.length === 0 ? (
            <div className="py-10 text-center text-[13px] text-dim">
              {t("Ничего не найдено", "No results found")}
            </div>
          ) : (
            items.map((item, idx) => {
              const selected = idx === selectedIndex;
              return (
                <button
                  key={item.id}
                  onClick={() => {
                    haptic("light");
                    item.action();
                  }}
                  onMouseEnter={() => setSelectedIndex(idx)}
                  className={`flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-left transition-colors ${
                    selected ? "bg-panel3 text-fg shadow-[inset_0_0_0_1px_var(--color-line2)]" : "text-sub hover:bg-panel2"
                  }`}
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <span className="shrink-0 flex h-8 w-8 items-center justify-center rounded-lg bg-panel2 border border-line/60">
                      {item.icon}
                    </span>
                    <div className="min-w-0">
                      <p className="truncate text-[13.5px] font-medium text-fg">{item.title}</p>
                      {item.subtitle && (
                        <p className="truncate font-mono text-[11px] text-dim">{item.subtitle}</p>
                      )}
                    </div>
                  </div>
                  <span className="shrink-0 text-[10px] uppercase font-mono tracking-wider text-dim">
                    {item.category}
                  </span>
                </button>
              );
            })
          )}
        </div>

        {/* Footer Hint */}
        <div className="flex items-center justify-between border-t border-line bg-ink/40 px-4 py-2 text-[11px] text-dim">
          <span>↑↓ {t("навигация", "navigate")} · Enter {t("выбрать", "select")}</span>
          <span className="flex items-center gap-1 font-mono">
            <kbd className="rounded border border-line bg-panel2 px-1">ESC</kbd> {t("закрыть", "close")}
          </span>
        </div>
      </div>
    </div>
  );
}
