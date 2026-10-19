"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Wallet, getAddress, formatEther, getBigInt, hexlify, parseUnits, toUtf8Bytes } from "ethers";
import {
  broadcast,
  explainError,
  erc20ApproveData,
  erc20TransferData,
  estimateFeeWei,
  explorerTx,
  readAllowance,
  readNative,
  readTokens,
  waitForTx,
} from "@/lib/chain";
import { Badge, Button, Card, CopyBtn, Input, Spinner, fmtUnits } from "./ui";
import {
  IconAlert,
  IconArrowUpRight,
  IconCheck,
  IconChevronDown,
  IconExternal,
  IconPlus,
  IconSend,
  IconTrash,
  IconUsers,
} from "./icons";
import { haptic } from "@/lib/haptics";
import type { AppApi } from "@/lib/session";

type Phase = "form" | "working" | "done";

interface BatchItem {
  id: string;
  to: string;
  amount: string;
}

export function SendView({ app }: { app: AppApi }) {
  const appRef = useRef(app);
  appRef.current = app;
  const { chain, refreshTick, t } = app;

  const [mode, setMode] = useState<"single" | "batch">("single");
  const [tokenAddr, setTokenAddr] = useState(""); // "" = native
  const [to, setTo] = useState(app.sendTarget ?? "");
  useEffect(() => {
    if (app.sendTarget) setTo(app.sendTarget);
  }, [app.sendTarget]);

  const [amount, setAmount] = useState("");
  const [memo, setMemo] = useState("");
  const [bal, setBal] = useState<bigint>(0n);
  const [balLoading, setBalLoading] = useState(true);
  const [phase, setPhase] = useState<Phase>("form");
  const [steps, setSteps] = useState<string[]>([]);
  const [stepIdx, setStepIdx] = useState(0);
  const [hash, setHash] = useState<string | null>(null);
  const [failed, setFailed] = useState<string>("");
  const [toOpen, setToOpen] = useState(false);
  const [gasSpeed, setGasSpeed] = useState<"fast" | "standard" | "low">("fast");

  // Batch transfer rows
  const [batchRows, setBatchRows] = useState<BatchItem[]>([
    { id: "1", to: "", amount: "" },
    { id: "2", to: "", amount: "" },
  ]);

  const token = tokenAddr ? chain.tokens.find((tTok) => tTok.address === tokenAddr) ?? null : null;
  const decimals = token?.decimals ?? 18;

  const loadBal = useCallback(() => {
    const { chain: cChain, address: cAddress } = appRef.current;
    setBalLoading(true);
    (async () => {
      try {
        if (!token) {
          const n = await readNative(cChain, cAddress);
          setBal(n.raw);
        } else {
          const tRows = await readTokens(cChain, [token], cAddress);
          setBal(tRows[0].raw);
        }
      } catch {
        setBal(0n);
      } finally {
        setBalLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tokenAddr, chain.chainId]);

  useEffect(loadBal, [loadBal, refreshTick]);

  let toValid: string | null = null;
  let toErr = "";
  if (to.trim()) {
    try {
      toValid = getAddress(to.trim());
    } catch {
      toErr = t("Некорректный 0x адрес (42 hex символа).", "Not a valid 0x address (42 hex chars).");
    }
  }

  let amountRaw: bigint | null = null;
  let amountErr = "";
  if (amount.trim()) {
    try {
      amountRaw = parseUnits(amount.trim(), decimals);
      if (amountRaw <= 0n) amountErr = t("Сумма должна быть больше нуля.", "Amount must be greater than zero.");
      else if (amountRaw > bal) amountErr = `${t("Превышает баланс", "Exceeds balance")} (${formatEther(bal)} ${chain.symbol}).`;
    } catch {
      amountErr = t("Некорректная сумма.", "Invalid amount.");
    }
  }

  const memoOk = !token; // memos ride in calldata — native only
  const data = token
    ? amountRaw
      ? erc20TransferData(toValid ?? "0x0000000000000000000000000000000000000000", amountRaw)
      : undefined
    : memo.trim()
      ? hexlify(toUtf8Bytes(memo.trim().slice(0, 160)))
      : undefined;

  const gasBase = token ? 90_000 : 21_000;
  const fee = data ? estimateFeeWei(chain, data, gasBase) : estimateFeeWei(chain, "0x", gasBase);
  const canSend = !!toValid && !!amountRaw && !amountErr && phase === "form";

  function setFraction(pct: number) {
    haptic("light");
    if (bal <= 0n) return;
    if (!token) {
      const f = estimateFeeWei(chain, "0x", 21_000).wei;
      const avail = bal > f ? bal - f : 0n;
      const portion = (avail * BigInt(pct)) / 100n;
      setAmount(formatEther(portion > 0n ? portion : 0n));
    } else {
      const portion = (bal * BigInt(pct)) / 100n;
      setAmount(fmtUnits(portion, decimals, 8));
    }
  }

  async function maxFill() {
    setFraction(100);
  }

  function addBatchRow() {
    haptic("light");
    setBatchRows((prev) => [...prev, { id: String(Date.now()), to: "", amount: "" }]);
  }

  function removeBatchRow(id: string) {
    haptic("light");
    setBatchRows((prev) => prev.filter((r) => r.id !== id));
  }

  function updateBatchRow(id: string, field: "to" | "amount", val: string) {
    setBatchRows((prev) => prev.map((r) => (r.id === id ? { ...r, [field]: val } : r)));
  }

  async function run() {
    if (!toValid || amountRaw == null) return;
    const { chain: currentChain, session, address, toast } = appRef.current;
    const wallet = new Wallet(session.privateKey);
    setPhase("working");
    setFailed("");
    haptic("medium");
    try {
      const plan: string[] = [];
      if (token) {
        const al = await readAllowance(currentChain, token.address, address, address);
        if (al < amountRaw) plan.push("Approving token allowance");
        plan.push("Estimating gas");
        plan.push("Signing locally");
        plan.push("Broadcasting to " + currentChain.name);
        plan.push("Waiting for confirmation");
      } else {
        plan.push("Estimating gas");
        plan.push("Signing locally");
        plan.push("Broadcasting to " + currentChain.name);
        plan.push("Waiting for confirmation");
      }
      setSteps(plan);
      let idx = 0;
      const mark = (s: string) => {
        const i = plan.indexOf(s);
        if (i >= 0) {
          idx = i;
          setStepIdx(i);
        }
      };

      let dataX: string | undefined;
      let value = 0n;
      let gasFallback = 21_000;

      if (token) {
        const al = await readAllowance(currentChain, token.address, address, address);
        if (al < amountRaw) {
          mark("Approving token allowance");
          const appr = await broadcast({
            chain: currentChain,
            wallet,
            to: token.address,
            data: erc20ApproveData(address, amountRaw),
            gasFallback: 85_000,
          });
          const ok1 = await waitForTx(appr.provider, appr.hash, 180_000);
          if (!ok1) throw new Error("Token approval reverted on-chain.");
        }
        dataX = erc20TransferData(toValid, amountRaw);
        gasFallback = 90_000;
      } else {
        value = amountRaw;
        if (memo.trim()) dataX = hexlify(toUtf8Bytes(memo.trim().slice(0, 160)));
      }

      mark("Broadcasting to " + currentChain.name);
      const res = await broadcast({ chain: currentChain, wallet, to: toValid, value, data: dataX, gasFallback });
      mark("Waiting for confirmation");
      const ok = await waitForTx(res.provider, res.hash, 180_000);
      if (!ok) throw new Error("Transaction was broadcast but reverted on-chain.");
      setHash(res.hash);
      setPhase("done");
      appRef.current.bump();
      toast("ok", `Sent on ${currentChain.name}. Tx ${res.hash.slice(0, 10)}…`);
      haptic("success");
    } catch (e) {
      setFailed(explainError(e));
      setPhase("form");
      toast("err", explainError(e));
    }
  }

  return (
    <div className="grid gap-5 lg:grid-cols-[1.5fr_1fr]">
      <Card className="p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-[14px] font-semibold text-sub">
            {t("Параметры перевода", "Transfer Details")}
          </h2>

          <div className="flex rounded-full border border-line bg-ink/70 p-0.5">
            <button
              onClick={() => {
                haptic("light");
                setMode("single");
              }}
              className={`rounded-full px-3 py-1 text-[11.5px] font-medium transition-all ${
                mode === "single" ? "bg-acc text-accink font-bold shadow-sm" : "text-sub hover:text-fg"
              }`}
            >
              {t("Обычный перевод", "Single Send")}
            </button>
            <button
              onClick={() => {
                haptic("light");
                setMode("batch");
              }}
              className={`rounded-full px-3 py-1 text-[11.5px] font-medium transition-all ${
                mode === "batch" ? "bg-acc text-accink font-bold shadow-sm" : "text-sub hover:text-fg"
              }`}
            >
              {t("Пакетный (Batch)", "Batch Send")}
            </button>
          </div>
        </div>

        {/* Token Picker */}
        <div className="relative mb-4">
          <button
            onClick={() => setToOpen(!toOpen)}
            className="focus-ring flex h-11 w-full items-center justify-between rounded-xl border border-line bg-ink/60 px-3.5 text-sm hover:border-line2"
          >
            <span className="flex items-center gap-2.5">
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-panel3 font-mono text-[10px] text-sub">
                {token ? token.symbol.slice(0, 2) : chain.symbol.slice(0, 2)}
              </span>
              <span className="font-semibold">{token ? token.symbol : `${chain.symbol} (native)`}</span>
              {token && <span className="font-mono text-[10.5px] text-dim">{token.address.slice(0, 8)}…</span>}
            </span>
            <span className={toOpen ? "rotate-180 text-acc" : "text-dim"}>
              <IconChevronDown size={15} />
            </span>
          </button>
          {toOpen && (
            <div className="absolute z-20 mt-1.5 max-h-64 w-full overflow-y-auto rounded-xl border border-line2 bg-panel2 p-1.5 shadow-2xl animate-scale-in">
              <button
                onClick={() => {
                  setTokenAddr("");
                  setToOpen(false);
                }}
                className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-[13px] hover:bg-panel3 ${!token ? "text-acc font-semibold" : ""}`}
              >
                <span>{chain.symbol} (native)</span>
                <span className="font-mono text-[12px] tnum text-sub">{balLoading ? "…" : fmtUnits(bal, 18, 5)}</span>
              </button>
              {chain.tokens.map((tTok) => (
                <button
                  key={tTok.address}
                  onClick={() => {
                    setTokenAddr(tTok.address);
                    setToOpen(false);
                  }}
                  className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-[13px] hover:bg-panel3 ${tokenAddr === tTok.address ? "text-acc font-semibold" : ""}`}
                >
                  <span>
                    {tTok.symbol} <span className="text-dim">· {tTok.name}</span>
                  </span>
                </button>
              ))}
              {chain.tokens.length === 0 && <p className="px-3 py-2 text-[12px] text-dim">No ERC-20 presets on this chain.</p>}
            </div>
          )}
        </div>

        {/* Quick Action Templates Strip */}
        <div className="mb-4">
          <span className="mb-1.5 block text-[11.5px] font-medium text-sub">
            {app.t("Шаблоны быстрых действий:", "Quick action templates:")}
          </span>
          <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 overflow-x-auto pb-1">
            {[
              { emoji: "💎", label: app.t("Отправить другу", "Send to friend"), memo: "💎 Transfer to friend", amount: "0.01" },
              { emoji: "💼", label: app.t("Зарплата / Оплата", "Salary / Payment"), memo: "💼 Payment for services", amount: "0.1" },
              { emoji: "🎨", label: app.t("Купить NFT / Mint", "Buy NFT / Mint"), memo: "🎨 NFT Purchase / Mint", amount: "0.05" },
              { emoji: "☕", label: app.t("На кофе / Чаевые", "Coffee / Tip"), memo: "☕ Coffee tip / Thank you!", amount: "0.005" },
            ].map((tpl) => (
              <button
                key={tpl.label}
                type="button"
                onClick={() => {
                  haptic("light");
                  if (memoOk) setMemo(tpl.memo);
                  setAmount(tpl.amount);
                }}
                className="focus-ring flex shrink-0 items-center gap-1 rounded-full border border-line bg-panel2 px-2.5 py-1 text-[11px] font-medium text-sub transition-all hover:border-acc/40 hover:bg-panel3 hover:text-fg active:scale-95 shadow-sm"
              >
                <span>{tpl.emoji}</span>
                <span>{tpl.label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* SINGLE TRANSFER MODE */}
        {mode === "single" && (
          <div className="space-y-4">
            {app.contacts.length > 0 && (
              <div>
                <span className="mb-1.5 block text-[11.5px] font-medium text-sub">
                  {app.t("Быстрый выбор из контактов:", "Quick pick from Contacts:")}
                </span>
                <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 overflow-x-auto pb-1">
                  {app.contacts.slice(0, 15).map((c) => (
                    <button
                      key={c.address}
                      type="button"
                      onClick={() => {
                        haptic("light");
                        setTo(c.address);
                      }}
                      className={`focus-ring flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11.5px] transition-all active:scale-95 ${
                        to.toLowerCase() === c.address.toLowerCase()
                          ? "border-acc bg-acc/15 text-acc font-semibold shadow-sm"
                          : "border-line bg-panel2 text-sub hover:text-fg hover:bg-panel3"
                      }`}
                    >
                      <span>{c.avatarEmoji ?? "💎"}</span>
                      <span>{c.name}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            <Input
              label={app.t("Адрес получателя (0x… или .eth)", "Recipient address (0x… or .eth)")}
              placeholder="0x… or vitalik.eth"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="font-mono text-[13px]"
            />
            {toErr && <p className="-mt-3 text-[12px] text-bad">{toErr}</p>}

            <div>
              <div className="relative">
                <Input
                  label={t("Сумма перевода", "Transfer Amount")}
                  placeholder="0.00"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  className="font-mono pr-24 text-[16px] font-semibold"
                  inputMode="decimal"
                />
                <div className="absolute right-3 top-[30px] flex items-center gap-1.5">
                  <button
                    onClick={maxFill}
                    disabled={balLoading}
                    className="focus-ring rounded-md bg-panel3 px-2 py-1 font-mono text-[10.5px] text-acc hover:bg-panel3/60 disabled:opacity-40 font-bold"
                  >
                    MAX
                  </button>
                  <span className="font-mono text-[11px] text-dim">{token ? token.symbol : chain.symbol}</span>
                </div>
              </div>

              <div className="mt-1.5 flex flex-wrap items-center justify-between gap-1 text-[11.5px] text-dim">
                <span>
                  Available: <span className="font-mono">{balLoading ? "…" : fmtUnits(bal, decimals, 6)}</span>{" "}
                  {token ? token.symbol : chain.symbol}
                </span>
                {amountErr && <span className="text-bad font-medium">{amountErr}</span>}
              </div>

              {/* Percentage Presets */}
              <div className="mt-2 flex items-center gap-1.5 overflow-x-auto no-scrollbar">
                {[25, 50, 75, 100].map((pct) => (
                  <button
                    key={pct}
                    type="button"
                    disabled={balLoading || bal <= 0n}
                    onClick={() => setFraction(pct)}
                    className="focus-ring flex-1 rounded-lg border border-line bg-panel2/80 py-1 text-center font-mono text-[11px] font-medium text-sub transition-all hover:border-acc/40 hover:bg-panel3 hover:text-fg active:scale-95 disabled:opacity-40"
                  >
                    {pct === 100 ? "MAX" : `${pct}%`}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <Input
                label={memoOk ? t("Заметка / Memo в блокчейне (опционально)", "On-Chain Memo (optional)") : t("Memo (только для нативных переводов)", "Memo (native only)")}
                placeholder="e.g. rent for may / order #482"
                value={memo}
                onChange={(e) => setMemo(e.target.value.slice(0, 160))}
                disabled={!memoOk}
                className={!memoOk ? "opacity-45" : ""}
              />
              {!memoOk && <p className="mt-1.5 text-[11.5px] text-dim">ERC-20 calldata is fixed by contract.</p>}
            </div>
          </div>
        )}

        {/* BATCH TRANSFER MODE */}
        {mode === "batch" && (
          <div className="space-y-3">
            <p className="text-[12.5px] text-sub leading-relaxed">
              {t(
                "Отправка средств нескольким получателям в одной очереди транзакций.",
                "Send funds to multiple recipients in a batch transaction sequence."
              )}
            </p>

            <div className="space-y-2">
              {batchRows.map((r, i) => (
                <div key={r.id} className="flex items-center gap-2 rounded-xl border border-line bg-ink/40 p-2.5">
                  <span className="font-mono text-[11px] text-dim px-1">{i + 1}</span>
                  <input
                    placeholder="0x… address"
                    value={r.to}
                    onChange={(e) => updateBatchRow(r.id, "to", e.target.value)}
                    className="w-full bg-transparent font-mono text-[12.5px] outline-none placeholder:text-dim"
                  />
                  <input
                    placeholder="0.00"
                    type="number"
                    value={r.amount}
                    onChange={(e) => updateBatchRow(r.id, "amount", e.target.value)}
                    className="w-24 bg-transparent font-mono text-[12.5px] text-right outline-none placeholder:text-dim font-semibold"
                  />
                  <span className="font-mono text-[11px] text-dim">{chain.symbol}</span>
                  {batchRows.length > 1 && (
                    <button onClick={() => removeBatchRow(r.id)} className="rounded p-1 text-dim hover:text-bad">
                      <IconTrash size={14} />
                    </button>
                  )}
                </div>
              ))}
            </div>

            <Button variant="soft" size="sm" onClick={addBatchRow} className="w-full">
              <IconPlus size={13} /> {t("Добавить получателя", "Add Recipient")}
            </Button>
          </div>
        )}
      </Card>

      {/* Summary & Broadcast Column */}
      <div className="space-y-4">
        <Card className="p-5">
          <h2 className="mb-3 text-[13px] font-semibold text-sub">{t("Сводка и Газ", "Summary & Gas")}</h2>
          <dl className="space-y-2.5 text-[13px]">
            <div className="flex justify-between gap-3">
              <dt className="text-sub">{t("Сеть", "Network")}</dt>
              <dd className="flex items-center gap-2 font-medium">
                {chain.name} {chain.testnet && <Badge tone="warn">testnet</Badge>}
              </dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-sub">{t("Актив", "Asset")}</dt>
              <dd className="font-medium">{token ? token.symbol : `${chain.symbol} (native)`}</dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-sub">{t("Сумма", "Amount")}</dt>
              <dd className="font-mono tnum font-bold text-fg">
                {amount || "0"} {token ? token.symbol : chain.symbol}
              </dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-sub">{t("Получатель", "Recipient")}</dt>
              <dd className="flex items-center gap-1 font-mono text-[12px]">
                {toValid ? toValid.slice(0, 10) + "…" : "—"}
                {toValid && <CopyBtn text={toValid} small />}
              </dd>
            </div>
            {memo.trim() && memoOk && (
              <div className="flex justify-between gap-3">
                <dt className="text-sub">Memo</dt>
                <dd className="max-w-[180px] truncate font-mono text-[12px] text-sub">“{memo.trim()}”</dd>
              </div>
            )}
            <div className="flex justify-between gap-3 border-t border-line pt-2.5">
              <dt className="text-sub">{t("Макс. комиссия газа", "Max Gas Fee")}</dt>
              <dd className="font-mono tnum text-sub">
                ≈ {fmtUnits(fee.wei, 18, 6)} {chain.symbol}
                <span className="ml-1 text-[10.5px] text-dim">({fee.gasLimit.toLocaleString()} gas)</span>
              </dd>
            </div>
          </dl>

          <div className="mt-4 flex items-start gap-2 rounded-xl border border-line bg-panel2/60 p-2.5 text-[11.5px] leading-relaxed text-dim">
            <IconAlert size={14} className="mt-0.5 shrink-0 text-warn" />
            <span>
              {t(
                "Транзакция подписывается на устройстве вашим ключом. Блокчейн-транзакции необратимы.",
                "Transaction signed on-device. Blockchain transfers are irreversible."
              )}
            </span>
          </div>

          <Button variant="primary" size="lg" className="mt-4 w-full" disabled={!canSend} onClick={run}>
            <IconSend size={16} /> {t("Подписать и отправить", "Sign & Broadcast")}
          </Button>
        </Card>

        {phase === "working" && (
          <Card className="animate-fade-up p-5">
            <h3 className="mb-3 text-[13px] font-semibold text-sub">{t("Исполнение…", "Broadcasting…")}</h3>
            <ul className="space-y-2.5">
              {steps.map((s, i) => (
                <li key={s} className="flex items-center gap-2.5 text-[13px]">
                  {i < stepIdx ? (
                    <IconCheck size={15} className="text-ok" />
                  ) : i === stepIdx ? (
                    <Spinner size={15} className="text-acc" />
                  ) : (
                    <span className="h-3.5 w-3.5 rounded-full border border-line2" />
                  )}
                  <span className={i <= stepIdx ? "text-fg font-medium" : "text-dim"}>{s}</span>
                </li>
              ))}
            </ul>
          </Card>
        )}

        {phase === "done" && hash && (
          <Card className="animate-fade-up border-ok/30 p-5 space-y-3">
            <div className="flex items-center gap-2 text-ok">
              <IconCheck size={18} />
              <h3 className="text-[14.5px] font-bold">{t("Транзакция подтверждена!", "Transaction Confirmed!")}</h3>
            </div>
            <p className="text-[12.5px] text-sub">
              {amount} {token ? token.symbol : chain.symbol} on {chain.name}
            </p>
            <div className="flex items-center justify-between rounded-xl border border-line bg-ink/50 px-3 py-2.5">
              <span className="font-mono text-[11.5px] text-dim">{hash.slice(0, 20)}…</span>
              <div className="flex items-center gap-1">
                <CopyBtn text={hash} small />
                <a
                  href={explorerTx(chain, hash)}
                  target="_blank"
                  rel="noreferrer"
                  className="focus-ring rounded-lg p-1.5 text-dim hover:text-acc"
                >
                  <IconExternal size={14} />
                </a>
              </div>
            </div>
            <Button
              variant="soft"
              size="md"
              className="w-full"
              onClick={() => {
                setPhase("form");
                setHash(null);
                setAmount("");
                setMemo("");
              }}
            >
              {t("Новый перевод", "New transfer")}
            </Button>
          </Card>
        )}

        {failed && (
          <Card className="animate-fade-up border-bad/30 p-4 text-[13px] text-bad">
            {failed}
          </Card>
        )}
      </div>
    </div>
  );
}
