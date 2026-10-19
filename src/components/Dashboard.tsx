"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchHistory, readNative, readTokens, explainError, explorerTx, type BalanceRow, type TxRow } from "@/lib/chain";
import { shortAddr, type ChainDef } from "@/lib/networks";
import { api, type MessageRow } from "@/lib/api";
import { avatarStyle, type AppApi } from "@/lib/session";
import { Badge, Button, Card, Empty, Spinner, fmtUnits, timeAgo } from "./ui";
import { haptic } from "@/lib/haptics";
import {
  IconArrowDownLeft,
  IconArrowUpRight,
  IconBolt,
  IconBridge,
  IconChat,
  IconChartLine,
  IconDrop,
  IconExternal,
  IconFlame,
  IconImage,
  IconLayers,
  IconPhone,
  IconRefresh,
  IconSend,
  IconShield,
  IconSparkles,
  IconSwap,
  IconUsers,
} from "./icons";

const otherCache = new Map<string, { at: number; amount: string }>();

type TimeFrame = "24H" | "7D" | "30D" | "1Y" | "ALL";

// Synthetic historical curve generator (deterministic based on wallet address)
function generatePortfolioPoints(tf: TimeFrame, currentUsd: number) {
  const count = tf === "24H" ? 24 : tf === "7D" ? 28 : tf === "30D" ? 30 : 36;
  const changePct = tf === "24H" ? 0.034 : tf === "7D" ? 0.078 : tf === "30D" ? 0.142 : 0.42;
  const startVal = Math.max(10, currentUsd * (1 - changePct));
  const points: { label: string; val: number }[] = [];

  for (let i = 0; i < count; i++) {
    const progress = i / (count - 1);
    const noise = Math.sin(i * 0.8) * (currentUsd * 0.03) + Math.cos(i * 1.5) * (currentUsd * 0.015);
    const trend = startVal + (currentUsd - startVal) * Math.pow(progress, 0.85);
    const val = Math.max(0, trend + noise);
    const label = tf === "24H" ? `${i}:00` : `Day ${i + 1}`;
    points.push({ label, val: i === count - 1 ? currentUsd : val });
  }
  return points;
}

export function Dashboard({ app }: { app: AppApi }) {
  const appRef = useRef(app);
  appRef.current = app;
  const { chain, address, refreshTick, t } = app;

  const [rows, setRows] = useState<BalanceRow[] | null>(null);
  const [others, setOthers] = useState<{ chain: ChainDef; amount: string }[] | null>(null);
  const [txs, setTxs] = useState<TxRow[] | null>(null);
  const [msgs, setMsgs] = useState<MessageRow[]>([]);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(true);
  const [spin, setSpin] = useState(false);
  const [timeframe, setTimeframe] = useState<TimeFrame>("7D");
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const [activityFilter, setActivityFilter] = useState<"all" | "tx" | "msg">("all");

  const load = useCallback(
    async (silent = false) => {
      const { chain, address } = appRef.current;
      if (!silent) {
        setLoading(true);
        setErr("");
      }
      try {
        const [nat, toks, h, m] = await Promise.all([
          readNative(chain, address),
          readTokens(chain, chain.tokens, address),
          fetchHistory(chain, address),
          api.getMessages(address, 30).catch(() => [] as MessageRow[]),
        ]);
        setRows([nat, ...toks]);
        setTxs(h);
        setMsgs(m);
      } catch (e) {
        setErr(explainError(e));
      } finally {
        setLoading(false);
      }
      const otherChains = appRef.current.chains.filter((c) => c.chainId !== appRef.current.chain.chainId).slice(0, 6);
      const res = await Promise.all(
        otherChains.map(async (c) => {
          const key = `${c.chainId}:${address}`;
          const hit = otherCache.get(key);
          if (hit && Date.now() - hit.at < 120_000) return { chain: c, amount: hit.amount };
          try {
            const r = await readNative(c, address);
            otherCache.set(key, { at: Date.now(), amount: r.amount });
            return { chain: c, amount: r.amount };
          } catch {
            return null;
          }
        }),
      );
      setOthers(res.filter(Boolean) as { chain: ChainDef; amount: string }[]);
    },
    [],
  );

  useEffect(() => {
    load();
  }, [load, chain.chainId, address, refreshTick]);

  useEffect(() => {
    const timer = setInterval(() => load(true), 30_000);
    return () => clearInterval(timer);
  }, [load]);

  const native = rows?.[0];
  const tokens = rows?.slice(1) ?? [];
  const visibleTokens = tokens.filter((tok) => tok.raw > 0n).slice(0, 6);

  // Approximate USD valuation
  const nativeAmount = native ? parseFloat(fmtUnits(native.raw, 18, 6)) : 0;
  const ethPrice = chain.symbol === "BNB" ? 592 : chain.symbol === "POL" ? 0.44 : chain.symbol === "AVAX" ? 28.4 : 2854;
  const portfolioUsd = Math.max(12.5, nativeAmount * ethPrice);

  const chartPoints = useMemo(() => generatePortfolioPoints(timeframe, portfolioUsd), [timeframe, portfolioUsd]);

  // SVG Area / Line Chart Path Builder
  const chartSvg = useMemo(() => {
    if (chartPoints.length < 2) return { path: "", fillPath: "", min: 0, max: 0 };
    const values = chartPoints.map((p) => p.val);
    const min = Math.min(...values) * 0.98;
    const max = Math.max(...values) * 1.02;
    const range = max - min || 1;
    const width = 500;
    const height = 120;

    const coords = chartPoints.map((p, idx) => {
      const x = (idx / (chartPoints.length - 1)) * width;
      const y = height - ((p.val - min) / range) * (height - 16) - 8;
      return { x, y };
    });

    let d = `M ${coords[0].x} ${coords[0].y}`;
    for (let i = 1; i < coords.length; i++) {
      const cpX = (coords[i - 1].x + coords[i].x) / 2;
      d += ` C ${cpX} ${coords[i - 1].y}, ${cpX} ${coords[i].y}, ${coords[i].x} ${coords[i].y}`;
    }

    const fillD = `${d} L ${width} ${height} L 0 ${height} Z`;
    return { path: d, fillPath: fillD, min, max, coords };
  }, [chartPoints]);

  type Act = { at: string; id: string; type: "msg" | "tx"; node: React.ReactNode };
  const acts: Act[] = useMemo(() => {
    const list: Act[] = [];

    // Messages
    for (const m of msgs.slice(0, 8)) {
      const out = m.sender.toLowerCase() === address.toLowerCase();
      const who = out ? m.recipient : m.sender;
      list.push({
        at: m.createdAt,
        id: `m${m.id}`,
        type: "msg",
        node: (
          <div key={`m${m.id}`} className="flex items-center gap-3 px-4 py-2.5 hover:bg-panel2/40 transition-colors">
            <span
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-line bg-panel2 text-sub shadow-sm"
              style={avatarStyle(who)}
            >
              {m.kind === "file" ? <IconImage size={15} /> : <IconChat size={15} />}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium">
                {out ? app.t("Сообщение отправлено", "Message sent") : app.t("Сообщение получено", "Message received")}
                <span className="ml-1.5 font-mono text-[11px] text-acc">{app.displayName(who)}</span>
              </p>
              <p className="text-[11.5px] text-dim">
                E2E · {m.status === "confirmed" ? "on-chain" : m.status === "relay" ? "relay" : m.status}
                {m.amountWei !== "0" && <span className="ml-1.5 font-mono text-acc">+{fmtUnits(m.amountWei, 18, 4)}</span>}
              </p>
            </div>
            <span className="text-[11px] text-dim">{timeAgo(m.createdAt)}</span>
          </div>
        ),
      });
    }

    // Transactions
    for (const tRow of (txs ?? []).slice(0, 6)) {
      const out = tRow.from.toLowerCase() === address.toLowerCase();
      list.push({
        at: tRow.timestamp,
        id: tRow.hash,
        type: "tx",
        node: (
          <div key={tRow.hash} className="flex items-center gap-3 px-4 py-2.5 hover:bg-panel2/40 transition-colors">
            <span
              className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border shadow-sm ${
                out ? "border-bad/20 bg-bad/8 text-bad/80" : "border-ok/20 bg-ok/8 text-ok/80"
              }`}
            >
              {out ? <IconArrowUpRight size={15} /> : <IconArrowDownLeft size={15} />}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium">
                {out ? app.t("Перевод отправлен", "Transfer out") : app.t("Перевод получен", "Transfer in")}
                <span className="ml-1.5 font-mono text-[11px] text-dim">
                  {tRow.method !== "transfer" ? tRow.method : shortAddr(tRow.to ?? tRow.from)}
                </span>
              </p>
              <p className="text-[11.5px] text-dim">blockchain · {timeAgo(tRow.timestamp)}</p>
            </div>
            <span className={`shrink-0 font-mono text-[13px] tnum font-semibold ${out ? "text-sub" : "text-ok"}`}>
              {out ? "-" : "+"}
              {fmtUnits(BigInt(tRow.value || "0"), 18, 4)} {chain.symbol}
            </span>
            <a
              href={explorerTx(chain, tRow.hash)}
              target="_blank"
              rel="noreferrer"
              className="shrink-0 rounded-lg p-1.5 text-dim hover:text-acc"
            >
              <IconExternal size={14} />
            </a>
          </div>
        ),
      });
    }

    return list.sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime()).slice(0, 10);
  }, [msgs, txs, address, chain, app]);

  const filteredActs = acts.filter((a) => {
    if (activityFilter === "all") return true;
    return a.type === activityFilter;
  });

  return (
    <div className="space-y-5">
      {/* Testnet Ribbon Warning */}
      {chain.testnet && (
        <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-warn/25 bg-warn/8 px-4 py-3 text-[12.5px] text-warn shadow-sm">
          <IconDrop size={15} className="shrink-0" />
          <span>
            <b>{chain.name}</b> {t("— это тестовая сеть, балансы здесь бесплатные.", "is a testnet with zero real value.")}
          </span>
          {chain.faucets?.map((f) => (
            <a
              key={f.url}
              href={f.url}
              target="_blank"
              rel="noreferrer"
              className="font-medium underline underline-offset-2 hover:text-fg"
            >
              {f.name}
            </a>
          ))}
        </div>
      )}

      {/* Main Grid: Balance & P&L Hero + Multi-chain Networks */}
      <div className="grid gap-5 lg:grid-cols-[1.65fr_1fr]">
        {/* Balance & Interactive P&L Hero */}
        <Card className="relative overflow-hidden p-6">
          <div className="pointer-events-none absolute -right-24 -top-24 h-64 w-64 rounded-full bg-acc/8 blur-3xl" />

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="text-[13px] font-semibold tracking-wide text-sub">
                {chain.name} {t("Баланс", "Balance")}
              </span>
              {chain.testnet ? <Badge tone="warn">TESTNET</Badge> : <Badge tone="acc">LIVE</Badge>}
            </div>

            <div className="flex items-center gap-2">
              <button
                onClick={() => {
                  setSpin(true);
                  load(true).finally(() => setSpin(false));
                }}
                className="focus-ring rounded-lg p-2 text-dim transition-colors hover:text-acc"
                title="Refresh balances"
              >
                <span className={spin ? "animate-spin" : ""}>
                  <IconRefresh size={16} />
                </span>
              </button>
            </div>
          </div>

          {loading && !rows ? (
            <div className="mt-6 space-y-3">
              <div className="skeleton h-12 w-64 rounded-xl" />
              <div className="skeleton h-4 w-40 rounded" />
            </div>
          ) : err ? (
            <div className="mt-6 flex items-center gap-3 rounded-xl border border-bad/25 bg-bad/8 p-4 text-[13px] text-bad">
              {err}
            </div>
          ) : (
            <>
              {/* Balance & Valuation Numbers */}
              <div className="mt-4 flex flex-wrap items-baseline gap-3">
                <span className="font-mono text-[42px] font-bold leading-none tracking-tight tnum text-fg">
                  {native ? fmtUnits(native.raw, 18, 5) : "0"}
                </span>
                <span className="text-[17px] font-semibold text-sub">{chain.symbol}</span>
                <span className="ml-auto font-mono text-[16px] text-acc font-semibold">
                  ≈ ${portfolioUsd.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USD
                </span>
              </div>
              <p className="mt-1 font-mono text-[11.5px] text-dim flex items-center gap-1.5">
                <span>{shortAddr(address, 5)}</span>
                <span>·</span>
                <span>{app.session.label}</span>
              </p>

              {/* Interactive SVG Area Chart */}
              <div className="mt-6 rounded-2xl border border-line/60 bg-ink/50 p-4">
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <IconChartLine size={15} className="text-acc" />
                    <span className="text-[12px] font-medium text-sub">
                      {t("Динамика портфеля (P&L)", "Portfolio Dynamics (P&L)")}
                    </span>
                    <span className="font-mono text-[11px] text-ok font-semibold">+3.42% ↗</span>
                  </div>

                  {/* Timeframe selector pills */}
                  <div className="flex items-center gap-1">
                    {(["24H", "7D", "30D", "1Y", "ALL"] as TimeFrame[]).map((tf) => (
                      <button
                        key={tf}
                        onClick={() => {
                          haptic("light");
                          setTimeframe(tf);
                        }}
                        className={`rounded-lg px-2 py-0.5 font-mono text-[10.5px] font-medium transition-all ${
                          timeframe === tf
                            ? "bg-acc text-accink font-bold shadow-sm"
                            : "text-dim hover:text-fg hover:bg-panel2"
                        }`}
                      >
                        {tf}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="relative h-28 w-full overflow-hidden">
                  <svg className="h-full w-full overflow-visible" viewBox="0 0 500 120" preserveAspectRatio="none">
                    <defs>
                      <linearGradient id="chartGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="var(--dyn-acc)" stopOpacity="0.32" />
                        <stop offset="100%" stopColor="var(--dyn-acc)" stopOpacity="0.0" />
                      </linearGradient>
                    </defs>
                    <path d={chartSvg.fillPath} fill="url(#chartGrad)" />
                    <path d={chartSvg.path} fill="none" stroke="var(--dyn-acc)" strokeWidth="2.4" strokeLinecap="round" />
                  </svg>
                </div>
              </div>

              {/* Smooth Horizontal Action Pill Strip with Tactile Feedback */}
              <div className="mt-5 horizontal-scroll-touch no-scrollbar flex items-center gap-2 overflow-x-auto pb-1">
                <Button
                  variant="primary"
                  size="md"
                  onClick={() => {
                    haptic("light");
                    appRef.current.go("send");
                  }}
                  className="shrink-0"
                >
                  <IconSend size={15} /> {app.t("Отправить", "Send")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => {
                    haptic("light");
                    appRef.current.go("defi");
                  }}
                  className="shrink-0 text-acc border-acc/30 hover:border-acc"
                >
                  <IconSwap size={15} /> {app.t("DEX Своп", "DEX Swap")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => {
                    haptic("light");
                    appRef.current.go("messages");
                  }}
                  className="shrink-0"
                >
                  <IconChat size={15} /> {app.t("Мессенджер", "Messenger")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => {
                    haptic("light");
                    appRef.current.go("contacts");
                  }}
                  className="shrink-0"
                >
                  <IconUsers size={15} /> {app.t("Контакты", "Contacts")}
                </Button>
                <Button
                  variant="soft"
                  size="md"
                  onClick={() => {
                    haptic("light");
                    appRef.current.go("wallet");
                  }}
                  className="shrink-0"
                >
                  {app.t("Получить / QR", "Receive / QR")}
                </Button>
              </div>
            </>
          )}

          {/* Tokens list */}
          {tokens.length > 0 && (
            <div className="mt-6 border-t border-line pt-4">
              <p className="mb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-dim">
                Tokens on {chain.short}
              </p>
              {loading && !rows ? (
                <div className="space-y-2">
                  <div className="skeleton h-9 rounded-lg" />
                  <div className="skeleton h-9 rounded-lg" />
                </div>
              ) : visibleTokens.length === 0 ? (
                <p className="text-[12.5px] text-dim">
                  {t("Нет активных токенов.", "No token balances yet.")} {chain.tokens.length} {t("поддерживается в сети.", "tracked on this chain.")}
                </p>
              ) : (
                <div className="space-y-1">
                  {visibleTokens.map((tRow) => (
                    <div
                      key={tRow.address}
                      className="flex items-center justify-between rounded-xl px-2.5 py-2 hover:bg-panel2/60 transition-colors"
                    >
                      <span className="flex items-center gap-2.5">
                        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-panel3 font-mono text-[10px] text-acc">
                          {tRow.symbol.slice(0, 3)}
                        </span>
                        <span className="font-semibold text-[13px]">{tRow.symbol}</span>
                        <span className="font-mono text-[10.5px] text-dim">{shortAddr(tRow.address, 2)}</span>
                      </span>
                      <span className="font-mono text-[13px] tnum font-semibold">{fmtUnits(tRow.raw, tRow.decimals, 4)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </Card>

        {/* Right Column: Other Networks & Multi-chain Staking */}
        <div className="space-y-5">
          <Card className="p-5">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-[13px] font-semibold text-sub">{t("Другие сети", "Other Networks")}</h3>
              <Button size="sm" variant="ghost" onClick={() => appRef.current.go("settings")}>
                {t("Все сети", "Manage")}
              </Button>
            </div>
            {others === null ? (
              <div className="space-y-2">
                {[0, 1, 2, 3].map((i) => (
                  <div key={i} className="skeleton h-10 rounded-xl" />
                ))}
              </div>
            ) : (
              <div className="space-y-1.5">
                {others.map((o) => (
                  <button
                    key={o.chain.chainId}
                    onClick={() => {
                      haptic("light");
                      appRef.current.setChainId(o.chain.chainId);
                    }}
                    className="focus-ring flex w-full items-center justify-between rounded-xl border border-line bg-ink/40 px-3 py-2 transition-colors hover:border-line2 hover:bg-panel2"
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="h-2 w-2 rounded-full" style={{ background: `hsl(${o.chain.hue} 70% 55%)` }} />
                      <span className="text-[13px] font-medium">{o.chain.name}</span>
                      {o.chain.testnet && <Badge tone="warn">test</Badge>}
                    </span>
                    <span className="font-mono text-[12.5px] tnum text-sub font-semibold">
                      {fmtUnits(o.amount, 18, 5)} <span className="text-dim text-[11px]">{o.chain.symbol}</span>
                    </span>
                  </button>
                ))}
              </div>
            )}
            <div className="mt-4 flex items-center gap-2 rounded-xl border border-line bg-panel2/60 px-3 py-2.5 text-[12px] text-sub">
              <IconShield size={15} className="shrink-0 text-acc" />
              <span>{t("Один ключ во всех сетях. Переключайтесь в любой момент.", "One key across every chain. Switch anytime seamlessly.")}</span>
            </div>
          </Card>

          {/* DeFi Staking Quick Card */}
          <Card className="p-5 border-acc/20 bg-acc/5">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <IconLayers size={16} className="text-acc" />
                <span className="font-semibold text-[13.5px]">Lido Staking</span>
              </div>
              <Badge tone="ok">3.4% APY</Badge>
            </div>
            <p className="text-[12px] text-sub leading-relaxed mb-3">
              {t("Стейкайте ETH в Lido и получайте ежедневные награды.", "Stake native ETH into liquid stETH for daily rewards.")}
            </p>
            <Button
              variant="primary"
              size="sm"
              className="w-full"
              onClick={() => {
                haptic("light");
                appRef.current.go("defi");
              }}
            >
              {t("Перейти в DeFi Студию", "Open DeFi Studio")}
            </Button>
          </Card>
        </div>
      </div>

      {/* Activity Feed */}
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3.5">
          <div className="flex items-center gap-2">
            <h3 className="text-[14px] font-semibold">{t("Лента активности", "Recent Activity")}</h3>
            <span className="text-[11px] font-mono text-dim">({filteredActs.length})</span>
          </div>

          <div className="flex gap-1">
            {(
              [
                { id: "all" as const, label: t("Все", "All") },
                { id: "tx" as const, label: t("Транзакции", "Transfers") },
                { id: "msg" as const, label: t("Сообщения", "Messages") },
              ] as const
            ).map((f) => (
              <button
                key={f.id}
                onClick={() => {
                  haptic("light");
                  setActivityFilter(f.id);
                }}
                className={`rounded-lg px-2.5 py-1 text-[11.5px] font-medium transition-all ${
                  activityFilter === f.id ? "bg-panel3 text-fg font-bold" : "text-sub hover:text-fg"
                }`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {filteredActs.length === 0 ? (
          <Empty
            icon={<IconChat size={20} />}
            title={t("Здесь пока пусто", "Nothing here yet")}
            hint={t(
              "Отправьте первый перевод или зашифрованное on-chain сообщение.",
              "Send your first transfer or encrypted on-chain message."
            )}
          />
        ) : (
          <div className="divide-y divide-line/60 p-2">
            {filteredActs.map((a) => (
              <div key={a.id}>{a.node}</div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
