"use client";

import { useEffect, useMemo, useState } from "react";
import { getAddress } from "ethers";
import QRCode from "qrcode";
import {
  saveContact,
  removeContact,
  type ContactCategory,
  type ContactEntry,
} from "@/lib/vault";
import {
  compressedPubFromPriv,
  computeSafetyFingerprint,
  isValidCompressedPub,
} from "@/lib/crypto";
import { recoverPubkeyFromChain, explorerAddr } from "@/lib/chain";
import { api } from "@/lib/api";
import { shortAddr } from "@/lib/networks";
import { avatarStyle, type AppApi } from "@/lib/session";
import { Badge, Button, Card, CopyBtn, Empty, Input, Modal, Select, Spinner } from "./ui";
import { haptic } from "@/lib/haptics";
import {
  IconChat,
  IconCheck,
  IconExternal,
  IconKey,
  IconLock,
  IconQr,
  IconRefresh,
  IconSearch,
  IconPhone,
  IconSend,
  IconShield,
  IconStar,
  IconTrash,
  IconUserPlus,
  IconUsers,
  IconVideo,
} from "./icons";

const EMOJI_OPTIONS = ["", "💎", "🚀", "🛡️", "⚡", "👑", "🔥", "🦊", "🐉", "🛰️", "🔮", "🐋"];

const CATEGORY_META: Record<
  ContactCategory,
  { ru: string; en: string; tone: "acc" | "info" | "warn" | "ok" | "sub" }
> = {
  friend: { ru: "Друг", en: "Friend", tone: "acc" },
  trusted: { ru: "Доверенный", en: "Trusted", tone: "ok" },
  vip: { ru: "VIP", en: "VIP", tone: "warn" },
  business: { ru: "Бизнес", en: "Business", tone: "info" },
  defi: { ru: "DeFi / Контракт", en: "DeFi", tone: "sub" },
};

export function ContactsView({ app }: { app: AppApi }) {
  const { contacts, reloadContacts, t, chain, session, address, toast } = app;
  const myPub = useMemo(() => compressedPubFromPriv(session.privateKey), [session.privateKey]);

  const [query, setQuery] = useState("");
  const [catFilter, setCatFilter] = useState<string>("all");
  const [selected, setSelected] = useState<ContactEntry | null>(null);
  const [qrUrl, setQrUrl] = useState<string>("");
  const [recoveringPub, setRecoveringPub] = useState(false);

  // Add / Edit modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [fAddr, setFAddr] = useState("");
  const [fName, setFName] = useState("");
  const [fHandle, setFHandle] = useState("");
  const [fCat, setFCat] = useState<ContactCategory>("friend");
  const [fEmoji, setFEmoji] = useState("💎");
  const [fPubkey, setFPubkey] = useState("");
  const [fPpsk, setFPpsk] = useState("");
  const [fNotes, setFNotes] = useState("");
  const [fFav, setFFav] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (selected) {
      const updated = contacts.find((c) => c.address === selected.address);
      if (updated) setSelected(updated);
      QRCode.toDataURL(selected.address, {
        width: 280,
        margin: 1,
        color: { dark: "#06080b", light: "#f4f6f8" },
      })
        .then(setQrUrl)
        .catch(() => setQrUrl(""));
    }
  }, [contacts, selected]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return contacts.filter((c) => {
      if (catFilter === "fav" && !c.favorite) return false;
      if (catFilter !== "all" && catFilter !== "fav" && c.category !== catFilter) return false;
      if (!q) return true;
      return (
        c.name.toLowerCase().includes(q) ||
        c.address.toLowerCase().includes(q) ||
        (c.handle && c.handle.toLowerCase().includes(q)) ||
        (c.notes && c.notes.toLowerCase().includes(q))
      );
    });
  }, [contacts, query, catFilter]);

  function openCreateModal() {
    setFAddr("");
    setFName("");
    setFHandle("");
    setFCat("friend");
    setFEmoji("💎");
    setFPubkey("");
    setFPpsk("");
    setFNotes("");
    setFFav(false);
    setModalOpen(true);
  }

  function openEditModal(c: ContactEntry) {
    setFAddr(c.address);
    setFName(c.name);
    setFHandle(c.handle ?? "");
    setFCat(c.category);
    setFEmoji(c.avatarEmoji ?? "");
    setFPubkey(c.pubkey ?? "");
    setFPpsk(c.sharedSecretPhrase ?? "");
    setFNotes(c.notes ?? "");
    setFFav(!!c.favorite);
    setModalOpen(true);
  }

  async function autoFetchPubkey(addrInput: string) {
    const clean = addrInput.trim().toLowerCase();
    if (!/^0x[0-9a-f]{40}$/i.test(clean)) return;
    try {
      const w = await api.getWallet(clean).catch(() => null);
      if (w?.pubkey && isValidCompressedPub(w.pubkey)) {
        setFPubkey(w.pubkey);
        if (!fName && w.label) setFName(w.label);
        return;
      }
      const onChainPub = await recoverPubkeyFromChain(chain, clean);
      if (onChainPub && isValidCompressedPub(onChainPub)) {
        setFPubkey(onChainPub);
      }
    } catch {
      /* ignore */
    }
  }

  async function handleSave() {
    let checksummed: string;
    try {
      checksummed = getAddress(fAddr.trim()).toLowerCase();
    } catch {
      return toast("err", t("Введите корректный 0x-адрес кошелька (42 символа).", "Enter a valid 0x wallet address."));
    }
    if (!fName.trim()) {
      return toast("err", t("Укажите имя контакта.", "Enter a contact name."));
    }
    if (fPubkey.trim() && !isValidCompressedPub(fPubkey.trim())) {
      return toast("err", t("Публичный ключ должен быть 33-байтным secp256k1 (0x02… или 0x03…).", "Public key must be a 33-byte compressed secp256k1 hex key."));
    }
    setSaving(true);
    try {
      const existing = contacts.find((c) => c.address === checksummed);
      const saved = await saveContact({
        address: checksummed,
        name: fName.trim(),
        handle: fHandle.trim() || undefined,
        category: fCat,
        avatarEmoji: fEmoji || undefined,
        pubkey: fPubkey.trim() || existing?.pubkey || null,
        sharedSecretPhrase: fPpsk.trim() || null,
        notes: fNotes.trim() || undefined,
        favorite: fFav,
        createdAt: existing?.createdAt || new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      });
      await reloadContacts();
      setSelected(saved);
      setModalOpen(false);
      toast("ok", t(`Контакт «${saved.name}» сохранён в IndexedDB Vault.`, `Contact "${saved.name}" saved to IndexedDB Vault.`));
    } finally {
      setSaving(false);
    }
  }

  async function handleRecoverPubkeyForSelected() {
    if (!selected) return;
    setRecoveringPub(true);
    try {
      const w = await api.getWallet(selected.address).catch(() => null);
      let found = w?.pubkey && isValidCompressedPub(w.pubkey) ? w.pubkey : null;
      if (!found) {
        found = await recoverPubkeyFromChain(chain, selected.address);
      }
      if (found && isValidCompressedPub(found)) {
        const updated = await saveContact({ ...selected, pubkey: found });
        await reloadContacts();
        setSelected(updated);
        toast("ok", t("Публичный ключ secp256k1 успешно извлечён из блокчейна!", "secp256k1 public key recovered from blockchain!"));
      } else {
        toast(
          "info",
          t(
            "У этого адреса ещё нет исходящих транзакций в этой сети. Вы можете задать общую секретную фразу (PPSK) в свойствах контакта.",
            "No outgoing transactions found on this chain yet. You can set a Pre-Shared Passphrase (PPSK) for this contact.",
          ),
        );
      }
    } finally {
      setRecoveringPub(false);
    }
  }

  async function toggleFavorite(c: ContactEntry) {
    const updated = await saveContact({ ...c, favorite: !c.favorite });
    await reloadContacts();
    if (selected?.address === c.address) setSelected(updated);
  }

  async function handleDelete(c: ContactEntry) {
    await removeContact(c.address);
    await reloadContacts();
    if (selected?.address === c.address) setSelected(null);
    toast("info", t(`Контакт «${c.name}» удалён.`, `Contact "${c.name}" removed.`));
  }

  const safety = useMemo(() => {
    if (!selected) return null;
    return computeSafetyFingerprint(address, myPub, selected.address, selected.pubkey);
  }, [address, myPub, selected]);

  return (
    <div className="grid gap-5 lg:grid-cols-[360px_1fr]">
      {/* Left column: Telegram-style contact list */}
      <Card className="flex h-[calc(100dvh-165px)] min-h-[460px] flex-col overflow-hidden p-4 md:h-[calc(100dvh-125px)]">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div>
            <h2 className="text-[15px] font-semibold tracking-tight">
              {t("Контакты (Web3 Telegram)", "Web3 Contacts")}
            </h2>
            <p className="text-[11.5px] text-dim">
              {t("Вместо номера телефона — адрес кошелька 0x", "Wallet address 0x instead of phone number")}
            </p>
          </div>
          <Button size="sm" variant="primary" onClick={openCreateModal}>
            <IconUserPlus size={14} /> {t("Добавить", "Add")}
          </Button>
        </div>

        {/* Search */}
        <div className="relative mb-2.5">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-dim">
            <IconSearch size={14} />
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("Поиск по имени, @handle или 0x…", "Search name, @handle or 0x…")}
            className="focus-ring h-9 w-full rounded-lg border border-line bg-ink/60 pl-9 pr-3 text-[12.5px] placeholder:text-dim"
          />
        </div>

        {/* Category pills with Smooth Horizontal Touch Scroll */}
        <div className="mb-3 horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 overflow-x-auto pb-1">
          {[
            { id: "all", label: t("Все", "All") },
            { id: "fav", label: "★ " + t("Избранное", "Favorites") },
            { id: "friend", label: t("Друзья", "Friends") },
            { id: "trusted", label: t("Доверенные", "Trusted") },
            { id: "vip", label: "VIP" },
            { id: "business", label: t("Бизнес", "Business") },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => {
                haptic("light");
                setCatFilter(tab.id);
              }}
              className={`focus-ring shrink-0 rounded-full px-3 py-1 text-[11.5px] font-medium transition-all active:scale-95 ${
                catFilter === tab.id
                  ? "bg-acc text-accink font-semibold shadow-sm"
                  : "bg-panel2/80 text-sub hover:text-fg hover:bg-panel3 border border-line"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Contact list */}
        <div className="flex-1 space-y-1 overflow-y-auto pr-1">
          {/* Self "Saved Messages / Избранное" card */}
          <button
            onClick={() => app.openChatWith(address.toLowerCase())}
            className="flex w-full items-center gap-3 rounded-xl border border-line/60 bg-panel2/40 px-3 py-2.5 text-left transition-colors hover:border-acc/40 hover:bg-panel2"
          >
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-acc/15 text-acc">
              <IconLock size={17} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium">
                {t("Избранное (Моё облако на чейне)", "Saved Messages (Self Vault)")}
              </p>
              <p className="font-mono text-[11px] text-dim">{shortAddr(address)} · E2E Self-Note</p>
            </div>
          </button>

          {filtered.length === 0 ? (
            <Empty
              icon={<IconUsers size={20} />}
              title={t("Список контактов пуст", "No contacts yet")}
              hint={t(
                "Добавьте друзей или партнёров по их 0x-адресу. Вы сможете писать им E2E-сообщения и отправлять крипто в 1 клик.",
                "Add friends or partners by their 0x wallet address for 1-tap E2E messaging and transfers.",
              )}
            />
          ) : (
            filtered.map((c) => {
              const isSel = selected?.address === c.address;
              const cat = CATEGORY_META[c.category] ?? CATEGORY_META.friend;
              return (
                <button
                  key={c.address}
                  onClick={() => setSelected(c)}
                  className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-all ${
                    isSel
                      ? "bg-panel3 shadow-[inset_0_0_0_1px_var(--color-line2)]"
                      : "hover:bg-panel2/60"
                  }`}
                >
                  <span
                    className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-base font-semibold"
                    style={avatarStyle(c.address)}
                  >
                    {c.avatarEmoji || c.name.slice(0, 2).toUpperCase()}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="truncate text-[13.5px] font-medium">{c.name}</span>
                      {c.favorite && <span className="text-[12px] text-warn">★</span>}
                      {isValidCompressedPub(c.pubkey) && (
                        <span title={t("Подтверждённый E2E ключ secp256k1", "Verified secp256k1 E2E pubkey")} className="text-acc">
                          <IconShield size={12} />
                        </span>
                      )}
                    </div>
                    <div className="mt-0.5 flex items-center gap-2">
                      {c.handle && <span className="font-mono text-[11px] text-acc">@{c.handle}</span>}
                      <span className="font-mono text-[11px] text-dim">{shortAddr(c.address)}</span>
                    </div>
                  </div>
                  <Badge tone={cat.tone}>{t(cat.ru, cat.en)}</Badge>
                </button>
              );
            })
          )}
        </div>
      </Card>

      {/* Right column: Contact Inspector & Cryptographic Safety Card */}
      <Card className="p-6">
        {!selected ? (
          <div className="flex h-full min-h-[380px] flex-col items-center justify-center text-center">
            <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl border border-line bg-panel2 text-acc">
              <IconUsers size={24} />
            </div>
            <h3 className="text-[16px] font-semibold">
              {t("Выберите контакт или создайте новый", "Select or create a Web3 contact")}
            </h3>
            <p className="mt-1.5 max-w-md text-[13px] leading-relaxed text-sub">
              {t(
                "В Obsidian адрес кошелька заменяет номер телефона. Для каждого контакта автоматически проверяется публичный ключ secp256k1 из блокчейна и формируется криптографический отпечаток безопасности (Safety Number), как в секретных чатах Telegram.",
                "In Obsidian, a wallet address replaces a phone number. Every contact gets automatic on-chain secp256k1 public key verification and a Telegram Secret Chat style Safety Fingerprint.",
              )}
            </p>
            <Button variant="primary" size="md" className="mt-5" onClick={openCreateModal}>
              <IconUserPlus size={15} /> {t("Добавить первый контакт", "Add first contact")}
            </Button>
          </div>
        ) : (
          <div className="space-y-6 animate-fade-up">
            {/* Profile top bar */}
            <div className="flex flex-wrap items-start justify-between gap-4 border-b border-line pb-5">
              <div className="flex items-center gap-4">
                <span
                  className="flex h-16 w-16 items-center justify-center rounded-2xl text-2xl font-bold shadow-lg"
                  style={avatarStyle(selected.address)}
                >
                  {selected.avatarEmoji || selected.name.slice(0, 2).toUpperCase()}
                </span>
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="text-xl font-semibold tracking-tight">{selected.name}</h2>
                    <button
                      onClick={() => toggleFavorite(selected)}
                      className={`text-lg transition-transform hover:scale-110 ${selected.favorite ? "text-warn" : "text-dim"}`}
                      title={t("Избранный контакт", "Favorite")}
                    >
                      ★
                    </button>
                    <Badge tone={CATEGORY_META[selected.category]?.tone ?? "acc"}>
                      {t(CATEGORY_META[selected.category]?.ru ?? "Контакт", CATEGORY_META[selected.category]?.en ?? "Contact")}
                    </Badge>
                  </div>
                  {selected.handle && (
                    <p className="mt-0.5 font-mono text-[13px] text-acc">@{selected.handle}</p>
                  )}
                  <div className="mt-1.5 flex items-center gap-1.5 font-mono text-[12px] text-sub">
                    <span className="break-all">{selected.address}</span>
                    <CopyBtn text={selected.address} small />
                    <a
                      href={explorerAddr(chain, selected.address)}
                      target="_blank"
                      rel="noreferrer"
                      className="rounded p-1 text-dim hover:text-acc"
                    >
                      <IconExternal size={13} />
                    </a>
                  </div>
                </div>
              </div>

              <div className="flex flex-wrap gap-2">
                <Button variant="primary" size="md" onClick={() => app.openChatWith(selected.address)}>
                  <IconChat size={15} /> {t("Написать (E2E Чат)", "Secret Chat")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => app.startCall(selected.address, selected.name, "voice")}
                >
                  <IconPhone size={15} /> {t("Позвонить", "Call")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => app.startCall(selected.address, selected.name, "video")}
                >
                  <IconVideo size={15} /> {t("Видео", "Video")}
                </Button>
                <Button variant="soft" size="md" onClick={() => app.openSendTo(selected.address)}>
                  <IconSend size={15} /> {t("Отправить крипто", "Send Crypto")}
                </Button>
                <Button variant="soft" size="md" onClick={() => openEditModal(selected)}>
                  {t("Изменить", "Edit")}
                </Button>
                <Button variant="danger" size="md" onClick={() => handleDelete(selected)}>
                  <IconTrash size={14} />
                </Button>
              </div>
            </div>

            {/* Security & Safety Number Grid */}
            <div className="grid gap-4 md:grid-cols-[1fr_200px]">
              <div className="space-y-4">
                <div className="rounded-xl border border-line bg-ink/50 p-4">
                  <div className="flex items-center justify-between">
                    <span className="flex items-center gap-2 text-[13px] font-semibold">
                      <IconShield size={15} className="text-acc" />
                      {t("Статус сквозного шифрования (ECDH secp256k1)", "E2E Encryption Status (secp256k1)")}
                    </span>
                    {isValidCompressedPub(selected.pubkey) ? (
                      <Badge tone="ok">
                        <IconCheck size={11} /> {t("Ключ подтверждён", "Verified Pubkey")}
                      </Badge>
                    ) : selected.sharedSecretPhrase ? (
                      <Badge tone="acc">
                        <IconLock size={11} /> PPSK E2E
                      </Badge>
                    ) : (
                      <Badge tone="warn">{t("Ожидает публичный ключ", "Awaiting Pubkey")}</Badge>
                    )}
                  </div>

                  <p className="mt-2 font-mono text-[11px] break-all text-dim">
                    {isValidCompressedPub(selected.pubkey)
                      ? `Pubkey (33B): ${selected.pubkey}`
                      : t(
                          "Публичный ключ ещё не сохранён. Нажмите кнопку ниже, чтобы автоматически извлечь его из любой транзакции этого кошелька в блокчейне (ecrecover).",
                          "Public key not cached yet. Click below to recover it directly from the contact's blockchain transactions (ecrecover).",
                        )}
                  </p>

                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <Button
                      size="sm"
                      variant="soft"
                      onClick={handleRecoverPubkeyForSelected}
                      loading={recoveringPub}
                    >
                      <IconRefresh size={13} />
                      {t("Извлечь ключ из блокчейна (ecrecover)", "Recover Pubkey from Blockchain")}
                    </Button>
                  </div>
                </div>

                {safety && (
                  <div className="rounded-xl border border-line bg-ink/50 p-4">
                    <p className="text-[12px] font-medium text-sub">
                      {t(
                        "Отпечаток безопасности канала (Safety Fingerprint как в Telegram):",
                        "Channel Safety Fingerprint (Telegram Secret Chat style):",
                      )}
                    </p>
                    <div className="mt-2.5 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-line bg-panel2 px-4 py-3">
                      <span className="text-2xl tracking-widest">{safety.emojis.join(" ")}</span>
                      <span className="font-mono text-[14px] font-semibold tracking-wider text-acc">
                        {safety.digits}
                      </span>
                    </div>
                    <p className="mt-2 text-[11.5px] text-dim">
                      {t(
                        "Если эти 4 эмодзи и 16 цифр совпадают у вас и у собеседника — переписка на 100% защищена от перехвата (MITM).",
                        "If these 4 emojis and 16 digits match on both devices, 100% end-to-end authenticity is guaranteed.",
                      )}
                    </p>
                  </div>
                )}

                {selected.notes && (
                  <div className="rounded-xl border border-line bg-ink/40 p-3.5">
                    <p className="text-[11px] uppercase tracking-wider text-dim">{t("Заметка", "Notes")}</p>
                    <p className="mt-1 text-[13px] text-fg">{selected.notes}</p>
                  </div>
                )}
              </div>

              {/* QR Card */}
              <div className="flex flex-col items-center justify-center rounded-xl border border-line bg-ink/50 p-4 text-center">
                {qrUrl ? (
                  <div className="rounded-xl bg-[#f4f6f8] p-2.5">
                    <img src={qrUrl} alt="Contact QR" className="h-36 w-36" />
                  </div>
                ) : (
                  <Spinner size={20} />
                )}
                <p className="mt-2.5 flex items-center gap-1 text-[11.5px] text-dim">
                  <IconQr size={13} /> {t("QR визитка адреса", "Address QR Card")}
                </p>
              </div>
            </div>
          </div>
        )}
      </Card>

      {/* Add / Edit Contact Modal */}
      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={t("Контакт кошелька", "Wallet Contact")}
        width="max-w-lg"
      >
        <div className="space-y-3.5">
          <Input
            label={t("Адрес кошелька (вместо номера телефона)", "Wallet Address (0x…)")}
            placeholder="0x…"
            value={fAddr}
            onChange={(e) => {
              setFAddr(e.target.value);
              if (e.target.value.trim().length === 42) autoFetchPubkey(e.target.value);
            }}
            className="font-mono text-[12.5px]"
          />

          <div className="grid grid-cols-2 gap-3">
            <Input
              label={t("Имя / Никнейм", "Display Name")}
              placeholder={t("Павел Дуров", "Pavel Durov")}
              value={fName}
              onChange={(e) => setFName(e.target.value)}
            />
            <Input
              label={t("Тег (@username)", "Handle (@username)")}
              placeholder="durov"
              value={fHandle}
              onChange={(e) => setFHandle(e.target.value)}
              className="font-mono"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Select
              label={t("Категория", "Category")}
              value={fCat}
              onChange={(e) => setFCat(e.target.value as ContactCategory)}
            >
              <option value="friend">{t("Друзья", "Friend")}</option>
              <option value="trusted">{t("Доверенный", "Trusted")}</option>
              <option value="vip">VIP</option>
              <option value="business">{t("Бизнес", "Business")}</option>
              <option value="defi">DeFi</option>
            </Select>

            <div>
              <span className="mb-1.5 block text-[12px] font-medium text-sub">
                {t("Аватар-эмодзи", "Avatar Emoji")}
              </span>
              <div className="flex flex-wrap gap-1 rounded-[10px] border border-line bg-ink/60 p-1.5">
                {EMOJI_OPTIONS.map((em) => (
                  <button
                    key={em || " initials"}
                    type="button"
                    onClick={() => setFEmoji(em)}
                    className={`h-7 w-7 rounded-md text-sm transition-colors ${
                      fEmoji === em ? "bg-acc text-accink" : "hover:bg-panel2"
                    }`}
                  >
                    {em || "Aa"}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <Input
            label={t("Публичный ключ secp256k1 (определяется автоматически из сети)", "Compressed secp256k1 Public Key (auto-detected from chain)")}
            placeholder="0x02… / 0x03… (33 bytes)"
            value={fPubkey}
            onChange={(e) => setFPubkey(e.target.value)}
            className="font-mono text-[11.5px]"
          />

          <Input
            label={t("Секретная парольная фраза PPSK (опционально для 0-tx кошельков)", "Pre-Shared Passphrase PPSK (optional for 0-tx cold wallets)")}
            placeholder={t("Оставьте пустым для стандартного ECDH", "Leave empty for standard ECDH")}
            value={fPpsk}
            onChange={(e) => setFPpsk(e.target.value)}
            left={<IconKey size={14} />}
          />

          <Input
            label={t("Заметка о контакте", "Notes")}
            placeholder={t("Личный кошелёк для сделок…", "Personal settlement address…")}
            value={fNotes}
            onChange={(e) => setFNotes(e.target.value)}
          />

          <label className="flex cursor-pointer items-center gap-2 text-[13px] text-sub">
            <input
              type="checkbox"
              checked={fFav}
              onChange={(e) => setFFav(e.target.checked)}
              className="accent-[#a8e62e]"
            />
            <IconStar size={14} className="text-warn" />
            {t("Добавить в Избранные контакты (★)", "Mark as Favorite (★)")}
          </label>

          <div className="flex gap-2 pt-2">
            <Button variant="soft" size="md" className="flex-1" onClick={() => setModalOpen(false)}>
              {t("Отмена", "Cancel")}
            </Button>
            <Button variant="primary" size="md" className="flex-1" onClick={handleSave} loading={saving}>
              {t("Сохранить контакт", "Save Contact")}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
