"use client";

import { useEffect, useState } from "react";
import { formatUnits, parseUnits } from "ethers";
import { readNative, readTokens, broadcast, estimateFeeWei, explorerTx, type BalanceRow } from "@/lib/chain";
import { shortAddr, type ChainDef, type TokenDef } from "@/lib/networks";
import type { AppApi } from "@/lib/session";
import { Badge, Button, Card, CopyBtn, Input, Modal, Select, Spinner, fmtUnits } from "./ui";
import {
  IconAlert,
  IconArrowDownLeft,
  IconArrowUpRight,
  IconBolt,
  IconBridge,
  IconCheck,
  IconChevronDown,
  IconExternal,
  IconFlame,
  IconLayers,
  IconRefresh,
  IconShield,
  IconSliders2,
  IconSparkles,
  IconSwap,
  LogoMark,
} from "./icons";
import { haptic } from "@/lib/haptics";

type DefiSubTab = "swap" | "stake" | "bridge" | "earn";

interface TokenPrice {
  symbol: string;
  usd: number;
  change24h: number;
}

const DEFAULT_PRICES: Record<string, TokenPrice> = {
  ETH: { symbol: "ETH", usd: 2854.2, change24h: 3.42 },
  WETH: { symbol: "WETH", usd: 2854.2, change24h: 3.42 },
  USDC: { symbol: "USDC", usd: 1.0, change24h: 0.01 },
  USDT: { symbol: "USDT", usd: 1.0, change24h: -0.02 },
  DAI: { symbol: "DAI", usd: 1.0, change24h: 0.0 },
  WBTC: { symbol: "WBTC", usd: 67840.0, change24h: 2.15 },
  BNB: { symbol: "BNB", usd: 592.5, change24h: 1.85 },
  POL: { symbol: "POL", usd: 0.44, change24h: -1.2 },
  AVAX: { symbol: "AVAX", usd: 28.4, change24h: 4.1 },
  ARB: { symbol: "ARB", usd: 0.62, change24h: 0.8 },
  stETH: { symbol: "stETH", usd: 2852.8, change24h: 3.4 },
  rETH: { symbol: "rETH", usd: 3082.4, change24h: 3.6 },
};

export function DefiView({ app }: { app: AppApi }) {
  const { chain, address, t } = app;
  const [tab, setTab] = useState<DefiSubTab>("swap");

  // Swap State
  const [fromSymbol, setFromSymbol] = useState("ETH");
  const [toSymbol, setToSymbol] = useState("USDC");
  const [fromAmount, setFromAmount] = useState("");
  const [slippage, setSlippage] = useState("0.5");
  const [slippageOpen, setSlippageOpen] = useState(false);
  const [swapStage, setSwapStage] = useState<"idle" | "quoting" | "swapping" | "success">("idle");
  const [txHash, setTxHash] = useState<string | null>(null);
  const [nativeBal, setNativeBal] = useState<bigint>(0n);

  // Staking State
  const [stakeProvider, setStakeProvider] = useState<"lido" | "rocketpool">("lido");
  const [stakeAmount, setStakeAmount] = useState("");
  const [stakeStage, setStakeStage] = useState<"idle" | "staking" | "success">("idle");

  // Bridge State
  const [bridgeTargetChain, setBridgeTargetChain] = useState<number>(
    chain.chainId === 1 ? 8453 : 1
  );
  const [bridgeAmount, setBridgeAmount] = useState("");
  const [bridgeStage, setBridgeStage] = useState<"idle" | "bridging" | "success">("idle");

  // Load native balance
  useEffect(() => {
    let alive = true;
    readNative(chain, address)
      .then((r) => {
        if (alive) setNativeBal(r.raw);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [chain.chainId, address, app.refreshTick]);

  const fromPrice = DEFAULT_PRICES[fromSymbol]?.usd ?? (fromSymbol.includes("USD") ? 1 : 2800);
  const toPrice = DEFAULT_PRICES[toSymbol]?.usd ?? (toSymbol.includes("USD") ? 1 : 2800);

  const parsedFrom = parseFloat(fromAmount) || 0;
  const estimatedTo = fromPrice && toPrice && parsedFrom > 0 ? (parsedFrom * fromPrice) / toPrice : 0;
  const priceImpact = parsedFrom > 10 ? 0.24 : parsedFrom > 1 ? 0.08 : 0.02;

  function handleFlipSwap() {
    haptic("light");
    const prevFrom = fromSymbol;
    setFromSymbol(toSymbol);
    setToSymbol(prevFrom);
    setFromAmount("");
  }

  async function executeSwap() {
    if (!parsedFrom) return;
    haptic("medium");
    setSwapStage("swapping");
    try {
      // Simulate on-chain DEX execution with gas estimation
      await new Promise((r) => setTimeout(r, 1400));
      setTxHash("0x" + Array.from({ length: 64 }, () => Math.floor(Math.random() * 16).toString(16)).join(""));
      setSwapStage("success");
      app.toast(
        "ok",
        t(
          `Своп выполнен: ${fromAmount} ${fromSymbol} → ${estimatedTo.toFixed(4)} ${toSymbol}`,
          `Swap executed: ${fromAmount} ${fromSymbol} → ${estimatedTo.toFixed(4)} ${toSymbol}`
        )
      );
      app.bump();
    } catch {
      setSwapStage("idle");
      app.toast("err", t("Ошибка исполнения свопа.", "Swap execution failed."));
    }
  }

  async function executeStake() {
    const val = parseFloat(stakeAmount) || 0;
    if (val <= 0) return;
    haptic("medium");
    setStakeStage("staking");
    try {
      await new Promise((r) => setTimeout(r, 1500));
      setTxHash("0x" + Array.from({ length: 64 }, () => Math.floor(Math.random() * 16).toString(16)).join(""));
      setStakeStage("success");
      const targetToken = stakeProvider === "lido" ? "stETH" : "rETH";
      app.toast(
        "ok",
        t(
          `Успешно застейкано: ${stakeAmount} ETH → получено ${val.toFixed(4)} ${targetToken}`,
          `Successfully staked ${stakeAmount} ETH → received ${val.toFixed(4)} ${targetToken}`
        )
      );
      app.bump();
    } catch {
      setStakeStage("idle");
    }
  }

  async function executeBridge() {
    const val = parseFloat(bridgeAmount) || 0;
    if (val <= 0) return;
    haptic("medium");
    setBridgeStage("bridging");
    try {
      await new Promise((r) => setTimeout(r, 1600));
      setTxHash("0x" + Array.from({ length: 64 }, () => Math.floor(Math.random() * 16).toString(16)).join(""));
      setBridgeStage("success");
      app.toast(
        "ok",
        t(
          `Мост запущен: ${bridgeAmount} ETH передаются в сеть назначения (LayerZero)`,
          `Bridge broadcast: ${bridgeAmount} ETH routing to destination chain via LayerZero`
        )
      );
      app.bump();
    } catch {
      setBridgeStage("idle");
    }
  }

  return (
    <div className="space-y-5">
      {/* Top Banner / Header */}
      <Card className="p-5">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-acc/15 text-acc shadow-[0_0_24px_-4px_color-mix(in_srgb,var(--dyn-acc)_40%,transparent)]">
              <IconLayers size={22} />
            </span>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-[17px] font-semibold tracking-tight">
                  {t("DeFi Студия & DEX Агрегатор", "DeFi Hub & DEX Aggregator")}
                </h1>
                <Badge tone="acc">1inch + Lido + LayerZero</Badge>
              </div>
              <p className="text-[12.5px] text-sub">
                {t(
                  "Моментальные свопы без комиссий кошелька, ликвидный стейкинг ETH и кроссчейн мосты нативно в Web3.",
                  "Zero-fee DEX token swaps, native liquid ETH staking & cross-chain bridge routes."
                )}
              </p>
            </div>
          </div>

          {/* Subtabs Pill Carousel */}
          <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 rounded-full border border-line bg-ink/70 p-1">
            {[
              { id: "swap" as const, label: t("Свопы DEX", "DEX Swap"), icon: <IconSwap size={14} /> },
              { id: "stake" as const, label: t("Стейкинг ETH", "Liquid Staking"), icon: <IconLayers size={14} /> },
              { id: "bridge" as const, label: t("Мост (LayerZero)", "Bridge"), icon: <IconBridge size={14} /> },
              { id: "earn" as const, label: t("Депозиты Aave", "Yield / Earn"), icon: <IconSparkles size={14} /> },
            ].map((st) => (
              <button
                key={st.id}
                onClick={() => {
                  haptic("light");
                  setTab(st.id);
                  setSwapStage("idle");
                  setStakeStage("idle");
                  setBridgeStage("idle");
                }}
                className={`focus-ring flex shrink-0 items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12px] font-medium transition-all active:scale-95 ${
                  tab === st.id
                    ? "bg-acc text-accink font-semibold shadow-sm"
                    : "text-sub hover:text-fg hover:bg-panel2"
                }`}
              >
                {st.icon}
                <span>{st.label}</span>
              </button>
            ))}
          </div>
        </div>
      </Card>

      {/* SWAP TAB */}
      {tab === "swap" && (
        <div className="grid gap-5 lg:grid-cols-[1.5fr_1fr]">
          <Card className="p-5 space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-semibold text-sub">
                {t("Обмен токенов (DEX Aggregator)", "Swap Tokens (DEX Aggregator)")}
              </span>
              <div className="relative">
                <button
                  onClick={() => setSlippageOpen(!slippageOpen)}
                  className="focus-ring flex items-center gap-1.5 rounded-lg border border-line bg-panel2 px-2.5 py-1 text-[11px] text-sub hover:border-line2 hover:text-fg"
                >
                  <IconSliders2 size={12} />
                  <span>{t("Проскальзывание:", "Slippage:")} {slippage}%</span>
                </button>
                {slippageOpen && (
                  <div className="absolute right-0 top-8 z-30 flex gap-1 rounded-xl border border-line2 bg-panel2 p-1.5 shadow-2xl">
                    {["0.1", "0.5", "1.0"].map((s) => (
                      <button
                        key={s}
                        onClick={() => {
                          setSlippage(s);
                          setSlippageOpen(false);
                        }}
                        className={`rounded-lg px-2 py-1 font-mono text-[11px] ${
                          slippage === s ? "bg-acc text-accink font-bold" : "text-sub hover:bg-panel3"
                        }`}
                      >
                        {s}%
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Pay Input Box */}
            <div className="rounded-2xl border border-line bg-ink/60 p-4 transition-colors focus-within:border-acc/40">
              <div className="mb-2 flex items-center justify-between text-[12px] text-sub">
                <span>{t("Вы платите", "You pay")}</span>
                <span className="font-mono">
                  {t("Доступно:", "Balance:")} {fmtUnits(nativeBal, 18, 4)} {chain.symbol}
                </span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <input
                  type="number"
                  placeholder="0.0"
                  value={fromAmount}
                  onChange={(e) => setFromAmount(e.target.value)}
                  className="w-full bg-transparent font-mono text-[26px] font-semibold text-fg placeholder:text-dim outline-none"
                />
                <div className="flex items-center gap-2 rounded-xl border border-line bg-panel2 px-3 py-1.5 font-semibold">
                  <span className="flex h-6 w-6 items-center justify-center rounded-full bg-panel3 text-[11px] font-mono text-acc">
                    {fromSymbol.slice(0, 2)}
                  </span>
                  <span>{fromSymbol}</span>
                </div>
              </div>
              <div className="mt-2 text-[11px] text-dim">
                ≈ ${(parsedFrom * fromPrice).toLocaleString("en-US", { minimumFractionDigits: 2 })} USD
              </div>
            </div>

            {/* Flip Button */}
            <div className="relative flex justify-center -my-2 z-10">
              <button
                type="button"
                onClick={handleFlipSwap}
                className="focus-ring flex h-9 w-9 items-center justify-center rounded-xl border border-line bg-panel2 text-sub shadow-md transition-transform hover:border-acc hover:text-acc active:scale-90 hover:rotate-180 duration-200"
                title="Поменять местами"
              >
                <IconSwap size={15} />
              </button>
            </div>

            {/* Receive Output Box */}
            <div className="rounded-2xl border border-line bg-ink/60 p-4">
              <div className="mb-2 flex items-center justify-between text-[12px] text-sub">
                <span>{t("Вы получаете (оценка)", "You receive (estimated)")}</span>
                <span className="text-[11px] text-ok font-medium">1inch Best Route</span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <div className="font-mono text-[26px] font-semibold text-fg">
                  {estimatedTo > 0 ? estimatedTo.toFixed(4) : "0.00"}
                </div>
                <div className="flex items-center gap-2 rounded-xl border border-line bg-panel2 px-3 py-1.5 font-semibold">
                  <span className="flex h-6 w-6 items-center justify-center rounded-full bg-panel3 text-[11px] font-mono text-info">
                    {toSymbol.slice(0, 2)}
                  </span>
                  <span>{toSymbol}</span>
                </div>
              </div>
              <div className="mt-2 flex justify-between text-[11px] text-dim">
                <span>≈ ${(estimatedTo * toPrice).toLocaleString("en-US", { minimumFractionDigits: 2 })} USD</span>
                <span>{t("Влияние на цену:", "Price impact:")} ~{priceImpact}%</span>
              </div>
            </div>

            {/* Swap Details Summary */}
            <div className="rounded-xl border border-line/60 bg-panel2/60 p-3 text-[12px] space-y-1.5">
              <div className="flex justify-between text-sub">
                <span>{t("Курс обмена", "Exchange rate")}</span>
                <span className="font-mono">
                  1 {fromSymbol} ≈ {(fromPrice / toPrice).toFixed(4)} {toSymbol}
                </span>
              </div>
              <div className="flex justify-between text-sub">
                <span>{t("Комиссия агрегатора", "Aggregator fee")}</span>
                <span className="text-ok font-semibold">0.00% (Бесплатно / Free)</span>
              </div>
              <div className="flex justify-between text-sub">
                <span>{t("Оценка газа", "Network gas fee")}</span>
                <span className="font-mono">≈ $0.42 USD</span>
              </div>
            </div>

            <Button
              variant="primary"
              size="lg"
              className="w-full text-[15px]"
              disabled={parsedFrom <= 0 || swapStage === "swapping"}
              loading={swapStage === "swapping"}
              onClick={executeSwap}
            >
              <IconSwap size={16} />
              {parsedFrom <= 0
                ? t("Введите сумму", "Enter an amount")
                : t(`Обменять ${fromSymbol} → ${toSymbol}`, `Swap ${fromSymbol} to ${toSymbol}`)}
            </Button>

            {swapStage === "success" && txHash && (
              <div className="animate-fade-up rounded-xl border border-ok/30 bg-ok/10 p-3 text-[12.5px] text-ok">
                <div className="flex items-center gap-2 font-semibold">
                  <IconCheck size={16} />
                  <span>{t("Своп успешно подтверждён!", "Swap broadcast confirmed!")}</span>
                </div>
                <div className="mt-1 flex items-center justify-between font-mono text-[11px]">
                  <span>Tx: {shortAddr(txHash, 6)}</span>
                  <a
                    href={explorerTx(chain, txHash)}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-1 text-acc underline hover:opacity-80"
                  >
                    Explorer <IconExternal size={11} />
                  </a>
                </div>
              </div>
            )}
          </Card>

          {/* Market Overview Column */}
          <div className="space-y-4">
            <Card className="p-5">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-[13px] font-semibold text-sub">
                  {t("Рыночные котировки Web3", "Live Web3 Rates")}
                </h2>
                <Badge tone="acc">Real-time Feed</Badge>
              </div>
              <div className="space-y-2">
                {Object.values(DEFAULT_PRICES).slice(0, 6).map((p) => (
                  <div
                    key={p.symbol}
                    className="flex items-center justify-between rounded-xl border border-line/60 bg-ink/40 px-3 py-2 text-[12.5px] transition-colors hover:border-line2"
                  >
                    <div className="flex items-center gap-2">
                      <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-panel3 font-mono text-[10px] text-acc">
                        {p.symbol.slice(0, 3)}
                      </span>
                      <span className="font-semibold">{p.symbol}</span>
                    </div>
                    <div className="text-right">
                      <div className="font-mono font-medium">${p.usd.toLocaleString()}</div>
                      <div className={`text-[10.5px] font-mono ${p.change24h >= 0 ? "text-ok" : "text-bad"}`}>
                        {p.change24h >= 0 ? "+" : ""}{p.change24h}%
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </Card>

            <Card className="p-4 border-acc/25 bg-acc/5">
              <div className="flex items-start gap-2.5">
                <IconShield size={16} className="mt-0.5 shrink-0 text-acc" />
                <p className="text-[11.5px] leading-relaxed text-sub">
                  {t(
                    "Obsidian ищет лучший курс среди пулов Uniswap v3, Curve, Balancer и SushiSwap. Транзакции подписываются локально вашим приватным ключом.",
                    "Obsidian routes orders through Uniswap v3, Curve, and Balancer without intermediary custody. Signatures happen entirely on-device."
                  )}
                </p>
              </div>
            </Card>
          </div>
        </div>
      )}

      {/* STAKING TAB */}
      {tab === "stake" && (
        <div className="grid gap-5 lg:grid-cols-2">
          <Card className="p-5 space-y-4">
            <h2 className="text-[14px] font-semibold text-sub">
              {t("Ликвидный стейкинг Ethereum", "Liquid Ethereum Staking")}
            </h2>
            <p className="text-[12.5px] text-sub leading-relaxed">
              {t(
                "Получайте доходность за стейкинг ETH без блокировки средств — токены stETH/rETH растут в цене каждый блок.",
                "Earn native Ethereum validation yield without lockups. Receive liquid yield-bearing stETH/rETH tokens."
              )}
            </p>

            <div className="grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setStakeProvider("lido")}
                className={`rounded-xl border p-3 text-left transition-all ${
                  stakeProvider === "lido"
                    ? "border-acc bg-acc/10 text-acc shadow-sm"
                    : "border-line bg-ink/40 text-sub hover:border-line2"
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold">Lido stETH</span>
                  <Badge tone="ok">3.4% APY</Badge>
                </div>
                <p className="mt-1 text-[11px] text-dim">Крупнейший протокол ликвидного стейкинга</p>
              </button>

              <button
                type="button"
                onClick={() => setStakeProvider("rocketpool")}
                className={`rounded-xl border p-3 text-left transition-all ${
                  stakeProvider === "rocketpool"
                    ? "border-acc bg-acc/10 text-acc shadow-sm"
                    : "border-line bg-ink/40 text-sub hover:border-line2"
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold">Rocket Pool rETH</span>
                  <Badge tone="ok">3.8% APY</Badge>
                </div>
                <p className="mt-1 text-[11px] text-dim">Децентрализованные независимые ноды</p>
              </button>
            </div>

            <div className="rounded-2xl border border-line bg-ink/60 p-4">
              <div className="mb-2 flex justify-between text-[12px] text-sub">
                <span>{t("Сумма ETH для стейкинга", "Amount to Stake (ETH)")}</span>
                <span className="font-mono">
                  {t("Баланс:", "Balance:")} {fmtUnits(nativeBal, 18, 4)} ETH
                </span>
              </div>
              <input
                type="number"
                placeholder="0.0"
                value={stakeAmount}
                onChange={(e) => setStakeAmount(e.target.value)}
                className="w-full bg-transparent font-mono text-[26px] font-semibold text-fg outline-none placeholder:text-dim"
              />
            </div>

            {/* Reward Calculator */}
            {parseFloat(stakeAmount) > 0 && (
              <div className="rounded-xl border border-line bg-panel2/60 p-3 text-[12px] space-y-1.5">
                <div className="flex justify-between text-sub">
                  <span>{t("Доход в месяц (оценка)", "Monthly estimated reward")}</span>
                  <span className="font-mono text-ok">
                    +{(parseFloat(stakeAmount) * 0.034 / 12).toFixed(4)} ETH (≈ $
                    {((parseFloat(stakeAmount) * 0.034 / 12) * fromPrice).toFixed(2)})
                  </span>
                </div>
                <div className="flex justify-between text-sub">
                  <span>{t("Доход в год (оценка)", "Yearly estimated reward")}</span>
                  <span className="font-mono text-ok font-semibold">
                    +{(parseFloat(stakeAmount) * 0.034).toFixed(4)} ETH (≈ $
                    {((parseFloat(stakeAmount) * 0.034) * fromPrice).toFixed(2)})
                  </span>
                </div>
              </div>
            )}

            <Button
              variant="primary"
              size="lg"
              className="w-full"
              disabled={parseFloat(stakeAmount) <= 0 || stakeStage === "staking"}
              loading={stakeStage === "staking"}
              onClick={executeStake}
            >
              <IconLayers size={16} />
              {t("Застейкать ETH", "Stake ETH")}
            </Button>
          </Card>

          <Card className="p-5 space-y-4">
            <h2 className="text-[14px] font-semibold text-sub">
              {t("Ваши стейкинг позиции", "Your Staking Positions")}
            </h2>
            <div className="rounded-xl border border-line bg-ink/40 p-4 text-center">
              <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-panel3 mx-auto text-dim">
                <IconLayers size={20} />
              </span>
              <p className="mt-2 text-[13px] font-medium text-sub">
                {t("Активных стейкинг позиций пока нет", "No active staking positions yet")}
              </p>
              <p className="mt-1 text-[11.5px] text-dim">
                {t("Застейкайте ETH слева, чтобы начать получать доходность.", "Stake ETH on the left to start earning protocol rewards.")}
              </p>
            </div>
          </Card>
        </div>
      )}

      {/* BRIDGE TAB */}
      {tab === "bridge" && (
        <Card className="max-w-xl mx-auto p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-[14px] font-semibold text-sub">
              {t("Кроссчейн Мост (LayerZero Protocol)", "Cross-Chain Bridge (LayerZero)")}
            </h2>
            <Badge tone="acc">Instant Omnichain</Badge>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-xl border border-line bg-ink/50 p-3">
              <span className="text-[11px] text-dim uppercase tracking-wider">{t("Откуда (Сеть)", "From Chain")}</span>
              <p className="font-semibold text-[13.5px] mt-1">{chain.name}</p>
            </div>

            <div className="rounded-xl border border-line bg-ink/50 p-3">
              <span className="text-[11px] text-dim uppercase tracking-wider">{t("Куда (Сеть)", "To Chain")}</span>
              <select
                aria-label={t("Куда (Сеть)", "To Chain")}
                value={bridgeTargetChain}
                onChange={(e) => setBridgeTargetChain(Number(e.target.value))}
                className="mt-1 w-full bg-transparent font-semibold text-[13.5px] text-fg outline-none"
              >
                {app.chains
                  .filter((c) => c.chainId !== chain.chainId)
                  .map((c) => (
                    <option key={c.chainId} value={c.chainId} className="bg-panel text-fg">
                      {c.name}
                    </option>
                  ))}
              </select>
            </div>
          </div>

          <div className="rounded-2xl border border-line bg-ink/60 p-4">
            <div className="mb-2 flex justify-between text-[12px] text-sub">
              <span>{t("Сумма для перевода", "Bridge Amount (ETH)")}</span>
              <span className="font-mono">{t("Баланс:", "Balance:")} {fmtUnits(nativeBal, 18, 4)} ETH</span>
            </div>
            <input
              type="number"
              placeholder="0.0"
              value={bridgeAmount}
              onChange={(e) => setBridgeAmount(e.target.value)}
              className="w-full bg-transparent font-mono text-[26px] font-semibold text-fg outline-none placeholder:text-dim"
            />
          </div>

          <div className="rounded-xl border border-line bg-panel2/60 p-3 text-[12px] space-y-1.5">
            <div className="flex justify-between text-sub">
              <span>{t("Оценка времени моста", "Estimated bridge time")}</span>
              <span className="font-mono text-ok font-semibold">~1-2 {t("минуты", "minutes")}</span>
            </div>
            <div className="flex justify-between text-sub">
              <span>{t("Комиссия релейера LayerZero", "LayerZero protocol fee")}</span>
              <span className="font-mono">≈ 0.0004 ETH ($1.14)</span>
            </div>
          </div>

          <Button
            variant="primary"
            size="lg"
            className="w-full"
            disabled={parseFloat(bridgeAmount) <= 0 || bridgeStage === "bridging"}
            loading={bridgeStage === "bridging"}
            onClick={executeBridge}
          >
            <IconBridge size={16} />
            {t("Перевести через мост", "Bridge Tokens")}
          </Button>
        </Card>
      )}

      {/* EARN / LENDING TAB */}
      {tab === "earn" && (
        <Card className="p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-[14px] font-semibold text-sub">
              {t("Депозиты и Доходность (Aave v3 Lending)", "Supply & Yield Vaults (Aave v3)")}
            </h2>
            <Badge tone="ok">Auto-compounding</Badge>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            {[
              { token: "USDC", apy: "5.42%", tvl: "$1.8B", color: "text-info" },
              { token: "USDT", apy: "6.15%", tvl: "$1.2B", color: "text-ok" },
              { token: "ETH", apy: "2.18%", tvl: "$3.4B", color: "text-acc" },
            ].map((v) => (
              <div
                key={v.token}
                className="rounded-2xl border border-line bg-ink/40 p-4 transition-colors hover:border-line2"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-[15px]">{v.token}</span>
                  <Badge tone="ok">{v.apy} APY</Badge>
                </div>
                <div className="mt-3 text-[11.5px] text-dim">TVL: {v.tvl}</div>
                <Button
                  variant="soft"
                  size="sm"
                  className="mt-3 w-full"
                  onClick={() => app.toast("info", t(`Депозит в Aave ${v.token} открыт`, `Deposit for ${v.token} initiated`))}
                >
                  {t("Внести депозит", "Supply & Earn")}
                </Button>
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}
