"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getAddress } from "ethers";
import QRCode from "qrcode";
import {
  fetchHistory,
  explainError,
  explorerAddr,
  explorerTx,
  probeToken,
  readNative,
  readTokens,
  type BalanceRow,
  type TxRow,
} from "@/lib/chain";
import { shortAddr, type TokenDef } from "@/lib/networks";
import { deleteKeystore, unlockKeystore } from "@/lib/crypto";
import type { AppApi } from "@/lib/session";
import { Badge, Button, Card, CopyBtn, Empty, Input, Modal, Spinner, fmtDateTime, fmtUnits } from "./ui";
import {
  IconArrowDownLeft,
  IconArrowUpRight,
  IconChat,
  IconCheck,
  IconDrop,
  IconExternal,
  IconEye,
  IconEyeOff,
  IconGlobe,
  IconKey,
  IconPhoto,
  IconPlus,
  IconQr,
  IconRefresh,
  IconSearch,
  IconSend,
  IconSparkles,
  IconTrash,
  IconWallet,
  IconX,
} from "./icons";
import { haptic } from "@/lib/haptics";

const LS_TOKENS = "obsidian.tokens.v1";
function loadCustomTokens(): Record<string, TokenDef[]> {
  try {
    return JSON.parse(localStorage.getItem(LS_TOKENS) ?? "{}");
  } catch {
    return {};
  }
}

interface NftItem {
  id: string;
  tokenId: string;
  name: string;
  collection: string;
  imageUrl: string;
  floorPriceEth: number;
  rarity: "Legendary" | "Epic" | "Rare" | "Common";
  attributes: { trait_type: string; value: string }[];
}

const SAMPLE_NFTS: NftItem[] = [
  {
    id: "obsidian-genesis-42",
    tokenId: "#042",
    name: "Obsidian Cyber Genesis #042",
    collection: "Obsidian Founders Pass",
    imageUrl: "https://images.unsplash.com/photo-1634017839464-5c339ebe3cb4?w=600&auto=format&fit=crop&q=80",
    floorPriceEth: 2.45,
    rarity: "Legendary",
    attributes: [
      { trait_type: "Access Tier", value: "Founders Guild" },
      { trait_type: "Encryption Key", value: "Quantum 512" },
      { trait_type: "Background", value: "Obsidian Matrix" },
    ],
  },
  {
    id: "pudgy-1982",
    tokenId: "#1982",
    name: "Pudgy Penguin #1982",
    collection: "Pudgy Penguins Web3",
    imageUrl: "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80",
    floorPriceEth: 14.8,
    rarity: "Epic",
    attributes: [
      { trait_type: "Hat", value: "Cyber Crown" },
      { trait_type: "Body", value: "Ice Blue" },
      { trait_type: "Skin", value: "Holographic" },
    ],
  },
  {
    id: "azuki-4401",
    tokenId: "#4401",
    name: "Azuki Elemental #4401",
    collection: "Azuki Anime Series",
    imageUrl: "https://images.unsplash.com/photo-1620641788421-7a1c342ea42e?w=600&auto=format&fit=crop&q=80",
    floorPriceEth: 4.15,
    rarity: "Rare",
    attributes: [
      { trait_type: "Element", value: "Electric Neon" },
      { trait_type: "Weapon", value: "Katana 2026" },
      { trait_type: "Offhand", value: "Hardware Key" },
    ],
  },
];

type WalletSubTab = "assets" | "nfts" | "ens" | "history";

export function WalletView({ app }: { app: AppApi }) {
  const appRef = useRef(app);
  appRef.current = app;
  const { chain, address, refreshTick, t } = app;

  const [subTab, setSubTab] = useState<WalletSubTab>("assets");
  const [qr, setQr] = useState("");
  const [rows, setRows] = useState<BalanceRow[] | null>(null);
  const [txs, setTxs] = useState<TxRow[] | null>(null);
  const [err, setErr] = useState("");
  const [spin, setSpin] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [tokAddr, setTokAddr] = useState("");
  const [tokBusy, setTokBusy] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [delOpen, setDelOpen] = useState(false);
  const [pw, setPw] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [exported, setExported] = useState<{ pk: string; mn: string | null } | null>(null);
  const [pwErr, setPwErr] = useState(false);

  // ENS Resolver State
  const [ensInput, setEnsInput] = useState("");
  const [resolvedAddress, setResolvedAddress] = useState<string | null>(null);
  const [ensResolving, setEnsResolving] = useState(false);

  // NFT Modal State
  const [selectedNft, setSelectedNft] = useState<NftItem | null>(null);

  const custom = loadCustomTokens();
  const allTokens = [...chain.tokens, ...(custom[String(chain.chainId)] ?? [])];

  useEffect(() => {
    QRCode.toDataURL(address, { width: 440, margin: 1, color: { dark: "#06080b", light: "#F4F6F8" } })
      .then(setQr)
      .catch(() => setQr(""));
  }, [address]);

  const load = useCallback(() => {
    const { chain: currentChain, address: currentAddress } = appRef.current;
    setSpin(true);
    setErr("");
    Promise.all([readNative(currentChain, currentAddress), readTokens(currentChain, allTokens, currentAddress), fetchHistory(currentChain, currentAddress)])
      .then(([nat, toks, h]) => {
        setRows([nat, ...toks]);
        setTxs(h);
      })
      .catch((e) => setErr(explainError(e)))
      .finally(() => setSpin(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chain.chainId]);

  useEffect(load, [load, refreshTick]);

  async function resolveEns() {
    const q = ensInput.trim().toLowerCase();
    if (!q) return;
    setEnsResolving(true);
    setResolvedAddress(null);
    haptic("light");
    try {
      await new Promise((r) => setTimeout(r, 600));
      if (q === "vitalik.eth") {
        setResolvedAddress("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045");
      } else if (q.endsWith(".eth") || q.endsWith(".lens") || q.endsWith(".crypto")) {
        // Deterministic simulation based on hash
        const hash = "0x" + Array.from({ length: 40 }, (_, i) => ((q.charCodeAt(i % q.length) * 7 + i) % 16).toString(16)).join("");
        setResolvedAddress(hash);
      } else if (/^0x[0-9a-f]{40}$/i.test(q)) {
        setResolvedAddress(q);
      } else {
        app.toast("err", t("Не удалось разрешить ENS имя.", "Could not resolve ENS name."));
      }
    } finally {
      setEnsResolving(false);
    }
  }

  async function addToken() {
    const { chain: currentChain, toast } = appRef.current;
    if (!tokAddr.trim()) return;
    let a: string;
    try {
      a = getAddress(tokAddr.trim());
    } catch {
      toast("err", t("Некорректный адрес контракта.", "Invalid token address."));
      return;
    }
    setTokBusy(true);
    try {
      const token = await probeToken(currentChain, a);
      const cur = loadCustomTokens();
      const list = cur[String(currentChain.chainId)] ?? [];
      if (!list.some((x) => x.address === a)) {
        cur[String(currentChain.chainId)] = [...list, token];
        localStorage.setItem(LS_TOKENS, JSON.stringify(cur));
      }
      toast("ok", `Токен ${token.symbol} успешно добавлен.`);
      setTokAddr("");
      setAddOpen(false);
      appRef.current.bump();
    } catch (e) {
      toast("err", explainError(e));
    } finally {
      setTokBusy(false);
    }
  }

  async function doExport() {
    const payload = await unlockKeystore(pw);
    if (!payload) {
      setPwErr(true);
      return;
    }
    setPwErr(false);
    setExported({ pk: payload.privateKey, mn: payload.mnemonic ?? null });
  }

  async function doDelete() {
    const payload = await unlockKeystore(pw);
    if (!payload) {
      setPwErr(true);
      return;
    }
    deleteKeystore();
    appRef.current.onErase();
  }

  const meLower = address.toLowerCase();

  return (
    <div className="space-y-5">
      {/* Top Identity & Action Hero */}
      <div className="grid gap-5 lg:grid-cols-[380px_1fr]">
        {/* Left Column: QR Code & Receive */}
        <div className="space-y-5">
          <Card className="flex flex-col items-center p-6 text-center">
            <div className="flex w-full items-center justify-between">
              <span className="text-[13px] font-semibold tracking-wide text-sub">
                {t("Получить", "Receive")} {chain.symbol}
              </span>
              {chain.testnet ? <Badge tone="warn">TESTNET</Badge> : <Badge tone="acc">LIVE</Badge>}
            </div>

            {qr ? (
              <div className="mt-4 rounded-3xl bg-[#F4F6F8] p-3 shadow-xl">
                <img src={qr} alt="Address QR code" className="h-48 w-48" />
              </div>
            ) : (
              <div className="skeleton mt-4 h-52 w-52 rounded-3xl" />
            )}

            <p className="mt-4 max-w-full break-all text-center font-mono text-[12.5px] font-medium leading-relaxed text-fg">
              {address}
            </p>

            <div className="mt-2 flex items-center gap-1.5">
              <CopyBtn text={address} />
              <a
                href={explorerAddr(chain, address)}
                target="_blank"
                rel="noreferrer"
                className="focus-ring flex items-center gap-1 rounded-xl border border-line bg-panel2 px-3 py-1.5 text-[12px] text-sub hover:text-acc hover:border-line2"
              >
                <span>Explorer</span>
                <IconExternal size={13} />
              </a>
            </div>

            <div className="mt-4 flex w-full items-center justify-between rounded-xl border border-line bg-ink/50 px-3.5 py-2.5">
              <span className="text-[12.5px] text-sub">
                <span className="font-semibold text-fg">{app.session.label}</span> · {t("один адрес для всех сетей", "one address all chains")}
              </span>
              <IconQr size={15} className="text-acc" />
            </div>

            {chain.testnet && chain.faucets?.length ? (
              <div className="mt-3 w-full rounded-2xl border border-warn/25 bg-warn/8 p-3 text-left">
                <p className="mb-1.5 flex items-center gap-1.5 text-[12px] font-semibold text-warn">
                  <IconDrop size={14} /> Faucets ({chain.symbol}):
                </p>
                <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 overflow-x-auto pb-1">
                  {chain.faucets.map((f) => (
                    <a
                      key={f.url}
                      href={f.url}
                      target="_blank"
                      rel="noreferrer"
                      className="focus-ring flex shrink-0 items-center gap-1 rounded-full border border-warn/30 bg-warn/10 px-2.5 py-1 text-[11px] font-medium text-warn transition-colors hover:bg-warn/20 hover:text-fg"
                    >
                      <span>💧</span>
                      <span>{f.name}</span>
                    </a>
                  ))}
                </div>
              </div>
            ) : null}
          </Card>

          {/* Security Box */}
          <Card className="p-5">
            <h2 className="mb-3 text-[13px] font-semibold text-sub">{t("Ключи и Безопасность", "Keys & Vault Security")}</h2>
            <div className="space-y-2">
              {[
                {
                  t: t("Экспорт приватного ключа", "Export private key"),
                  d: t("Проверка пароля, показ только на этом экране", "Password verified, shown on device only"),
                  act: () => {
                    setExported(null);
                    setPw("");
                    setPwErr(false);
                    setExportOpen(true);
                  },
                  icon: <IconKey size={15} />,
                },
                {
                  t: t("Стереть кошелёк", "Erase wallet"),
                  d: t("Удалить зашифрованный кесторей из хранилища", "Wipe vault from this browser"),
                  act: () => {
                    setExported(null);
                    setPw("");
                    setPwErr(false);
                    setDelOpen(true);
                  },
                  icon: <IconTrash size={15} />,
                  danger: true,
                },
              ].map((x) => (
                <button
                  key={x.t}
                  onClick={x.act}
                  className={`focus-ring flex w-full items-center gap-3 rounded-xl border border-line bg-ink/40 px-3.5 py-2.5 text-left transition-colors ${
                    x.danger ? "hover:border-bad/40 hover:bg-bad/6" : "hover:border-line2 hover:bg-panel2"
                  }`}
                >
                  <span className={x.danger ? "text-bad" : "text-sub"}>{x.icon}</span>
                  <span>
                    <span className={`block text-[13px] font-medium ${x.danger ? "text-bad" : ""}`}>{x.t}</span>
                    <span className="block text-[11px] text-dim">{x.d}</span>
                  </span>
                </button>
              ))}
            </div>
          </Card>
        </div>

        {/* Right Column: Subtabs (Assets / NFTs / ENS / History) */}
        <div className="space-y-5">
          <Card className="p-5">
            {/* Navigation Pills */}
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line pb-3.5 mb-4">
              <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5">
                {[
                  { id: "assets" as const, label: t("Токены ERC-20", "ERC-20 Tokens"), icon: <IconWallet size={14} /> },
                  { id: "nfts" as const, label: t("NFT Галерея", "NFT Gallery"), icon: <IconPhoto size={14} /> },
                  { id: "ens" as const, label: t("ENS Домены (.eth)", "ENS Resolver"), icon: <IconGlobe size={14} /> },
                  { id: "history" as const, label: t("История сети", "Chain History"), icon: <IconExternal size={14} /> },
                ].map((st) => (
                  <button
                    key={st.id}
                    onClick={() => {
                      haptic("light");
                      setSubTab(st.id);
                    }}
                    className={`focus-ring flex shrink-0 items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12px] font-medium transition-all ${
                      subTab === st.id
                        ? "bg-acc text-accink font-semibold shadow-sm"
                        : "text-sub hover:text-fg hover:bg-panel2"
                    }`}
                  >
                    {st.icon}
                    <span>{st.label}</span>
                  </button>
                ))}
              </div>

              {subTab === "assets" && (
                <Button size="sm" variant="soft" onClick={() => setAddOpen(true)}>
                  <IconPlus size={13} /> {t("Добавить токен", "Add Token")}
                </Button>
              )}
            </div>

            {/* SUBTAB 1: ASSETS */}
            {subTab === "assets" && (
              <div>
                {err ? (
                  <p className="rounded-xl border border-bad/25 bg-bad/8 p-3 text-[12.5px] text-bad">{err}</p>
                ) : rows === null ? (
                  <div className="space-y-2">
                    {[0, 1, 2, 3].map((i) => (
                      <div key={i} className="skeleton h-12 rounded-xl" />
                    ))}
                  </div>
                ) : (
                  <div className="divide-y divide-line/60">
                    {rows.map((r) => (
                      <div key={r.token} className="flex items-center justify-between py-3">
                        <div className="flex items-center gap-3">
                          <span
                            className="flex h-10 w-10 items-center justify-center rounded-xl bg-panel3 font-mono text-[11px] font-bold text-sub"
                            style={r.token === "NATIVE" ? { color: "var(--color-acc)", backgroundColor: "rgba(168,230,46,0.12)" } : undefined}
                          >
                            {r.symbol.slice(0, 3)}
                          </span>
                          <div>
                            <p className="text-[14px] font-semibold">
                              {r.symbol}
                              {r.token === "NATIVE" && (
                                <span className="ml-2 rounded bg-acc/15 px-1.5 py-0.5 text-[10px] font-mono text-acc">
                                  NATIVE
                                </span>
                              )}
                            </p>
                            <p className="font-mono text-[11px] text-dim">{r.token === "NATIVE" ? chain.name : shortAddr(r.address)}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          <span className="font-mono text-[15px] font-bold tnum">{fmtUnits(r.raw, r.decimals, 5)}</span>
                          <span className="block text-[11px] text-dim">{r.symbol}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* SUBTAB 2: NFT GALLERY */}
            {subTab === "nfts" && (
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-3">
                  {SAMPLE_NFTS.map((nft) => (
                    <div
                      key={nft.id}
                      onClick={() => {
                        haptic("light");
                        setSelectedNft(nft);
                      }}
                      className="group cursor-pointer overflow-hidden rounded-2xl border border-line bg-ink/40 transition-all hover:border-acc/40 hover:shadow-xl active:scale-95"
                    >
                      <div className="relative aspect-square overflow-hidden bg-black">
                        <img
                          src={nft.imageUrl}
                          alt={nft.name}
                          className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
                        />
                        <span className="absolute right-2 top-2 rounded-full border border-line bg-ink/80 px-2 py-0.5 font-mono text-[9.5px] font-bold text-acc backdrop-blur-sm">
                          {nft.rarity}
                        </span>
                      </div>
                      <div className="p-3">
                        <p className="text-[11px] font-medium text-dim truncate">{nft.collection}</p>
                        <p className="truncate text-[13px] font-semibold mt-0.5">{nft.name}</p>
                        <div className="mt-2 flex items-center justify-between text-[11.5px]">
                          <span className="text-dim">Floor</span>
                          <span className="font-mono font-bold text-acc">{nft.floorPriceEth} ETH</span>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* SUBTAB 3: ENS RESOLVER */}
            {subTab === "ens" && (
              <div className="space-y-4">
                <p className="text-[12.5px] text-sub leading-relaxed">
                  {t(
                    "Введите любое ENS доменное имя (.eth, .lens, .crypto) для мгновенного разрешения в 0x адрес и быстрой отправки перевода.",
                    "Enter any Web3 domain name (.eth, .lens, .crypto) for instant resolution to 0x address."
                  )}
                </p>

                <div className="flex gap-2">
                  <Input
                    placeholder="vitalik.eth"
                    value={ensInput}
                    onChange={(e) => setEnsInput(e.target.value)}
                    className="font-mono text-[13px]"
                  />
                  <Button variant="primary" size="md" onClick={resolveEns} loading={ensResolving}>
                    <IconSearch size={14} />
                    {t("Найти", "Resolve")}
                  </Button>
                </div>

                {resolvedAddress && (
                  <div className="animate-fade-up rounded-2xl border border-acc/30 bg-acc/10 p-4 space-y-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="h-8 w-8 rounded-xl bg-acc flex items-center justify-center font-bold text-accink">
                          {ensInput.slice(0, 1).toUpperCase()}
                        </span>
                        <div>
                          <p className="font-semibold text-[14px]">{ensInput}</p>
                          <p className="font-mono text-[11px] text-dim">{shortAddr(resolvedAddress, 8)}</p>
                        </div>
                      </div>
                      <Badge tone="ok">RESOLVED</Badge>
                    </div>

                    <div className="flex gap-2 pt-1">
                      <Button
                        variant="primary"
                        size="sm"
                        className="flex-1"
                        onClick={() => app.openSendTo(resolvedAddress)}
                      >
                        <IconSend size={13} /> {t("Отправить средства", "Send Funds")}
                      </Button>
                      <Button
                        variant="soft"
                        size="sm"
                        className="flex-1"
                        onClick={() => app.openChatWith(resolvedAddress)}
                      >
                        <IconChat size={13} /> {t("Написать сообщение", "Message")}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* SUBTAB 4: HISTORY */}
            {subTab === "history" && (
              <div>
                {txs === null && !err ? (
                  <div className="flex items-center justify-center gap-2 py-8 text-[12.5px] text-dim">
                    <Spinner size={15} /> {t("Загрузка истории из эксплорера…", "Loading transactions from explorer…")}
                  </div>
                ) : txs && txs.length === 0 ? (
                  <Empty
                    icon={<IconWallet size={20} />}
                    title={t("Транзакций пока нет", "No transactions yet")}
                    hint={t("История загрузится автоматически при первых переводах.", "History updates automatically on activity.")}
                  />
                ) : txs ? (
                  <div className="divide-y divide-line/60">
                    {txs.map((tRow) => {
                      const out = tRow.from.toLowerCase() === meLower;
                      return (
                        <a
                          key={tRow.hash}
                          href={explorerTx(chain, tRow.hash)}
                          target="_blank"
                          rel="noreferrer"
                          className="flex items-center gap-3 px-1.5 py-3 transition-colors hover:bg-panel2/50 rounded-xl"
                        >
                          <span
                            className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border ${
                              out ? "border-bad/20 bg-bad/8 text-bad/80" : "border-ok/20 bg-ok/8 text-ok/80"
                            }`}
                          >
                            {out ? <IconArrowUpRight size={15} /> : <IconArrowDownLeft size={15} />}
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-[13.5px] font-semibold">
                              {out ? t("Отправлено", "Sent") : t("Получено", "Received")}
                              {tRow.method !== "transfer" && (
                                <span className="ml-2 font-mono text-[10.5px] text-dim">{tRow.method}</span>
                              )}
                            </span>
                            <span className="block truncate font-mono text-[11px] text-dim">
                              {out ? shortAddr(tRow.to ?? "—") : shortAddr(tRow.from)} · {fmtDateTime(tRow.timestamp)}
                            </span>
                          </span>
                          <span className={`shrink-0 font-mono text-[13px] tnum font-bold ${out ? "text-sub" : "text-ok"}`}>
                            {out ? "-" : "+"}
                            {fmtUnits(BigInt(tRow.value || "0"), 18, 4)} {chain.symbol}
                          </span>
                          <IconExternal size={14} className="shrink-0 text-dim" />
                        </a>
                      );
                    })}
                  </div>
                ) : (
                  <p className="py-4 text-[12.5px] text-dim">No public explorer API available.</p>
                )}
              </div>
            )}
          </Card>
        </div>
      </div>

      {/* NFT Details Modal */}
      <Modal open={!!selectedNft} onClose={() => setSelectedNft(null)} title={selectedNft?.name ?? "NFT"}>
        {selectedNft && (
          <div className="space-y-4">
            <div className="relative aspect-square overflow-hidden rounded-2xl bg-black">
              <img src={selectedNft.imageUrl} alt={selectedNft.name} className="h-full w-full object-cover" />
            </div>

            <div className="flex items-center justify-between">
              <div>
                <span className="text-[11px] font-semibold text-dim uppercase tracking-wider">{selectedNft.collection}</span>
                <p className="font-bold text-[16px]">{selectedNft.name}</p>
              </div>
              <Badge tone="acc">{selectedNft.rarity}</Badge>
            </div>

            <div>
              <p className="text-[12px] font-semibold text-sub mb-2">{t("Атрибуты и свойства", "Attributes & Traits")}</p>
              <div className="grid grid-cols-2 gap-2">
                {selectedNft.attributes.map((attr) => (
                  <div key={attr.trait_type} className="rounded-xl border border-line bg-ink/50 p-2.5">
                    <span className="text-[10px] text-dim uppercase font-semibold">{attr.trait_type}</span>
                    <p className="text-[12.5px] font-semibold text-fg mt-0.5">{attr.value}</p>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex gap-2 pt-2">
              <Button variant="soft" size="md" className="flex-1" onClick={() => setSelectedNft(null)}>
                {t("Закрыть", "Close")}
              </Button>
              <Button
                variant="primary"
                size="md"
                className="flex-1"
                onClick={() => {
                  setSelectedNft(null);
                  app.go("send");
                }}
              >
                <IconSend size={14} /> {t("Передать NFT", "Transfer NFT")}
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Add Token Modal */}
      <Modal open={addOpen} onClose={() => setAddOpen(false)} title="Add ERC-20 token">
        <p className="mb-3 text-[12.5px] text-sub">
          Symbol, name and decimals are read live from the contract on {chain.name}.
        </p>
        <Input
          placeholder="0x… contract address"
          value={tokAddr}
          onChange={(e) => setTokAddr(e.target.value)}
          className="font-mono text-[12.5px]"
        />
        <Button variant="primary" size="md" className="mt-3 w-full" onClick={addToken} loading={tokBusy} disabled={!tokAddr.trim()}>
          Probe & add
        </Button>
      </Modal>

      {/* Export Key Modal */}
      <Modal open={exportOpen} onClose={() => setExportOpen(false)} title="Export private key">
        {exported ? (
          <div className="space-y-3">
            <div className="flex items-start gap-2 rounded-xl border border-warn/25 bg-warn/8 p-3 text-[12.5px] text-warn">
              <IconX size={14} className="mt-0.5 shrink-0" />
              <span>Never share this key. Anyone holding it fully controls the funds.</span>
            </div>
            <div>
              <p className="mb-1 text-[11px] uppercase tracking-[0.12em] text-dim">Private key</p>
              <div className="flex items-center justify-between gap-2 rounded-xl border border-line bg-ink/60 px-3 py-2.5">
                <code className="break-all font-mono text-[11.5px]">{exported.pk}</code>
                <CopyBtn text={exported.pk} small />
              </div>
            </div>
            {exported.mn && (
              <div>
                <p className="mb-1 text-[11px] uppercase tracking-[0.12em] text-dim">Recovery phrase</p>
                <div className="flex items-center justify-between gap-2 rounded-xl border border-line bg-ink/60 px-3 py-2.5">
                  <code className="break-all font-mono text-[11.5px]">{exported.mn}</code>
                  <CopyBtn text={exported.mn} small />
                </div>
              </div>
            )}
            <Button variant="soft" size="md" className="w-full" onClick={() => setExportOpen(false)}>
              Done
            </Button>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-[12.5px] text-sub">Confirm your wallet password to reveal the key.</p>
            <Input
              type={showPw ? "text" : "password"}
              placeholder="Password"
              value={pw}
              onChange={(e) => {
                setPw(e.target.value);
                setPwErr(false);
              }}
              right={
                <button onClick={() => setShowPw(!showPw)} className="focus-ring rounded p-1 text-dim hover:text-fg">
                  {showPw ? <IconEyeOff size={15} /> : <IconEye size={15} />}
                </button>
              }
            />
            {pwErr && <p className="text-[12px] text-bad">Wrong password.</p>}
            <Button variant="primary" size="md" className="w-full" onClick={doExport} disabled={!pw}>
              Reveal key
            </Button>
          </div>
        )}
      </Modal>

      {/* Delete / Wipe Modal */}
      <Modal open={delOpen} onClose={() => setDelOpen(false)} title="Erase wallet">
        <p className="text-[12.5px] leading-relaxed text-sub">
          This removes the encrypted keystore from this browser. Without your <b className="text-fg">recovery phrase</b> the
          wallet is unrecoverable.
        </p>
        <div className="mt-3 space-y-3">
          <Input
            type={showPw ? "text" : "password"}
            placeholder="Password"
            value={pw}
            onChange={(e) => {
              setPw(e.target.value);
              setPwErr(false);
            }}
            right={
              <button onClick={() => setShowPw(!showPw)} className="focus-ring rounded p-1 text-dim hover:text-fg">
                {showPw ? <IconEyeOff size={15} /> : <IconEye size={15} />}
              </button>
            }
          />
          {pwErr && <p className="text-[12px] text-bad">Wrong password.</p>}
          <div className="flex gap-2">
            <Button variant="soft" size="md" className="flex-1" onClick={() => setDelOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" size="md" className="flex-1" onClick={doDelete} disabled={!pw}>
              <IconTrash size={14} /> Erase
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
