"use client";

import { useCallback, useEffect, useState } from "react";
import {
  addCustomRpc,
  clearRpcCache,
  getRpcHealth,
  listCustomRpcs,
  loadProviderKeys,
  removeCustomRpc,
  saveProviderKeys,
  type ProviderKeys,
  type RpcScore,
} from "@/lib/rpc";
import { Badge, Button, Card, Input, Spinner } from "./ui";
import { IconBolt, IconCheck, IconExternal, IconGlobe, IconKey, IconPlus, IconRefresh, IconShield, IconX } from "./icons";
import type { AppApi } from "@/lib/session";

function scoreTone(s: RpcScore): "ok" | "warn" | "bad" {
  if (!s.ok) return "bad";
  if (s.latencyMs <= 700) return "ok";
  if (s.latencyMs <= 1600) return "warn";
  return "bad";
}

function sourceLabel(s: RpcScore): string {
  switch (s.source) {
    case "provider":
      return "YOUR KEY";
    case "custom":
      return "CUSTOM";
    case "seed":
      return "CURATED";
    default:
      return "CHAINLIST";
  }
}

export function RpcPanel({ app, compact = false }: { app: AppApi; compact?: boolean }) {
  const { chain, t } = app;
  const [rows, setRows] = useState<RpcScore[] | null>(null);
  const [scanning, setScanning] = useState(false);
  const [progress, setProgress] = useState({ done: 0, total: 0 });
  const [customUrl, setCustomUrl] = useState("");
  const [showAdd, setShowAdd] = useState(false);
  const [keys, setKeys] = useState<ProviderKeys>({});
  const [keysLoaded, setKeysLoaded] = useState(false);
  const [showKeys, setShowKeys] = useState(false);

  useEffect(() => {
    let alive = true;
    loadProviderKeys().then((k) => {
      if (alive) {
        setKeys(k);
        setKeysLoaded(true);
      }
    });
    return () => {
      alive = false;
    };
  }, []);

  const customs = listCustomRpcs(chain.chainId);

  const scan = useCallback(
    async (force = true) => {
      setScanning(true);
      setProgress({ done: 0, total: 0 });
      try {
        const out = await getRpcHealth(chain, force);
        setRows(out);
        if (out.length === 0) {
          app.toast(
            "err",
            t(
              "Ни один RPC не ответил. Проверьте интернет или добавьте свой RPC / ключ Alchemy.",
              "No RPC endpoint responded. Check your connection or add a custom RPC / Alchemy key.",
            ),
          );
        } else {
          app.toast(
            "ok",
            t(
              `RPC-движок: ${out.length} рабочих узлов, лучший ${out[0].latencyMs} мс.`,
              `RPC engine: ${out.length} healthy nodes, best ${out[0].latencyMs} ms.`,
            ),
          );
        }
      } catch (e) {
        app.toast("err", String((e as Error).message ?? "scan failed"));
      } finally {
        setScanning(false);
      }
    },
    [chain, app, t],
  );

  useEffect(() => {
    setRows(null);
    scan(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chain.chainId]);

  async function handleAddCustom() {
    const ok = addCustomRpc(chain.chainId, customUrl);
    if (!ok) return app.toast("err", t("Некорректный URL (нужно http/https).", "Invalid URL (must be http/https)."));
    setCustomUrl("");
    setShowAdd(false);
    app.toast("ok", t("Свой RPC добавлен и будет приоритетным.", "Custom RPC added with priority."));
    scan(true);
  }

  async function handleSaveKeys() {
    await saveProviderKeys(keys);
    setShowKeys(false);
    app.toast("ok", t("Ключи провайдеров сохранены в зашифрованный IndexedDB Vault.", "Provider keys saved to the encrypted IndexedDB vault."));
    scan(true);
  }

  const healthy = rows?.length ?? 0;
  const best = rows?.[0];

  return (
    <Card className="p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <IconBolt size={16} className="text-acc" />
          <h3 className="text-[14px] font-semibold">
            {t("RPC-движок · авто-поиск рабочих узлов", "RPC Engine · Auto Node Discovery")}
          </h3>
          {healthy > 0 ? (
            <Badge tone="ok">
              {healthy} {t("рабочих", "healthy")}
            </Badge>
          ) : rows ? (
            <Badge tone="bad">0</Badge>
          ) : (
            <Badge tone="sub">…</Badge>
          )}
        </div>
        <div className="flex gap-1.5">
          <Button size="sm" variant="soft" onClick={() => setShowKeys(!showKeys)}>
            <IconKey size={13} /> {t("Ключи API", "API keys")}
          </Button>
          <Button size="sm" variant="primary" onClick={() => scan(true)} loading={scanning}>
            <IconRefresh size={13} /> {t("Пересканировать", "Re-scan")}
          </Button>
        </div>
      </div>

      <p className="mt-2 text-[12px] leading-relaxed text-sub">
        {t(
          "Узлы берутся из реестра Chainlist (rpcs.json) + курируемого списка, проверяются запросом eth_chainId (узлы с чужой сетью и лимитом 429 отсеиваются) и ранжируются по задержке. При сбое узла запрос автоматически уходит на следующий — ошибка «Network/RPC unreachable» больше не блокирует работу.",
          "Nodes come from the Chainlist registry (rpcs.json) plus a curated list, are verified with eth_chainId (wrong-chain and 429-limited nodes are rejected) and ranked by latency. On failure the call automatically moves to the next node.",
        )}
      </p>

      {/* Provider keys (secrets → IndexedDB vault only) */}
      {showKeys && (
        <div className="mt-3 animate-fade-up space-y-2.5 rounded-xl border border-line bg-ink/50 p-3.5">
          <p className="flex items-start gap-2 text-[11.5px] leading-relaxed text-dim">
            <IconShield size={13} className="mt-0.5 shrink-0 text-acc" />
            <span>
              {t(
                "Ключи хранятся только в зашифрованном IndexedDB Vault на этом устройстве и никогда не отправляются на сервер. dRPC и публичные шлюзы (Tenderly, PublicNode, 1RPC, LlamaRPC, Ankr) работают без ключа.",
                "Keys stay in the encrypted IndexedDB vault on this device and are never sent to any server. dRPC and public gateways work key-free.",
              )}
            </span>
          </p>
          <div className="grid gap-2.5 sm:grid-cols-2">
            <Input
              label="Alchemy API key"
              placeholder={t("бесплатно на dashboard.alchemy.com", "free at dashboard.alchemy.com")}
              value={keys.alchemy ?? ""}
              onChange={(e) => setKeys({ ...keys, alchemy: e.target.value })}
              className="font-mono text-[12px]"
            />
            <Input
              label="Infura Project ID"
              placeholder={t("бесплатно на infura.io", "free at infura.io")}
              value={keys.infura ?? ""}
              onChange={(e) => setKeys({ ...keys, infura: e.target.value })}
              className="font-mono text-[12px]"
            />
            <Input
              label="QuickNode endpoint URL"
              placeholder="https://xxxx.quiknode.pro/yyyy/"
              value={keys.quicknode ?? ""}
              onChange={(e) => setKeys({ ...keys, quicknode: e.target.value })}
              className="font-mono text-[12px]"
            />
            <Input
              label="dRPC key (опционально)"
              placeholder={t("публичные dRPC работают без ключа", "public dRPC needs no key")}
              value={keys.drpc ?? ""}
              onChange={(e) => setKeys({ ...keys, drpc: e.target.value })}
              className="font-mono text-[12px]"
            />
          </div>
          <div className="flex gap-2">
            <Button size="sm" variant="primary" onClick={handleSaveKeys} disabled={!keysLoaded}>
              {t("Сохранить и пересканировать", "Save & re-scan")}
            </Button>
            {(keys.alchemy || keys.infura || keys.quicknode || keys.drpc) && (
              <Button
                size="sm"
                variant="danger"
                onClick={async () => {
                  await saveProviderKeys({});
                  setKeys({});
                  app.toast("info", t("Ключи провайдеров удалены.", "Provider keys cleared."));
                }}
              >
                <IconX size={13} /> {t("Очистить", "Clear")}
              </Button>
            )}
          </div>
        </div>
      )}

      {/* Summary */}
      {best && !compact && (
        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl border border-ok/25 bg-ok/8 px-3.5 py-2.5 text-[12.5px]">
          <IconCheck size={14} className="text-ok" />
          <span className="text-sub">
            {t("Активный узел:", "Active node:")} <b className="font-mono text-fg">{hostOf(best.url)}</b>
          </span>
          <Badge tone="ok">{best.latencyMs} ms</Badge>
          <Badge tone="sub">{sourceLabel(best)}</Badge>
          <span className="text-dim">
            {healthy} / {rows?.length ?? healthy} {t("прошли проверку eth_chainId", "passed eth_chainId check")}
          </span>
        </div>
      )}

      {/* Health table */}
      <div className="mt-3 overflow-hidden rounded-xl border border-line">
        <div className="grid grid-cols-[1fr_64px_78px_70px] items-center gap-2 border-b border-line bg-panel2/60 px-3 py-2 text-[10.5px] uppercase tracking-[0.1em] text-dim">
          <span>RPC {t("сервер", "server")}</span>
          <span className="text-right">{t("Задержка", "Latency")}</span>
          <span className="text-right">Score</span>
          <span className="text-right">Privacy</span>
        </div>

        <div className={`max-h-[320px] overflow-y-auto ${compact ? "max-h-[220px]" : ""}`}>
          {rows === null ? (
            <div className="flex items-center justify-center gap-2 py-8 text-[12px] text-dim">
              <Spinner size={14} /> {t("Сканирование узлов…", "Probing nodes…")}
            </div>
          ) : rows.length === 0 ? (
            <div className="py-8 text-center text-[12.5px] text-bad">
              {t("Нет доступных узлов для этой сети.", "No reachable nodes for this network.")}
              <div className="mt-2">
                <Button size="sm" variant="soft" onClick={() => scan(true)}>
                  {t("Повторить", "Retry")}
                </Button>
              </div>
            </div>
          ) : (
            rows.map((s, i) => (
              <div
                key={s.url}
                className={`grid grid-cols-[1fr_64px_78px_70px] items-center gap-2 border-b border-line/60 px-3 py-2 text-[11.5px] last:border-0 ${
                  i === 0 ? "bg-acc/6" : ""
                }`}
              >
                <span className="flex min-w-0 items-center gap-2">
                  {i === 0 && <span className="shrink-0 text-[9px] font-bold text-acc">BEST</span>}
                  <span className="truncate font-mono">{hostOf(s.url)}</span>
                  {s.source === "provider" && <Badge tone="acc">key</Badge>}
                  {s.source === "custom" && <Badge tone="info">custom</Badge>}
                  {customs.includes(s.url) && (
                    <button
                      onClick={() => {
                        removeCustomRpc(chain.chainId, s.url);
                        scan(true);
                      }}
                      className="shrink-0 text-dim hover:text-bad"
                      title={t("Удалить свой RPC", "Remove custom RPC")}
                    >
                      <IconX size={11} />
                    </button>
                  )}
                </span>
                <span className={`text-right font-mono tnum ${scoreTone(s) === "ok" ? "text-ok" : scoreTone(s) === "warn" ? "text-warn" : "text-sub"}`}>
                  {s.latencyMs} ms
                </span>
                <span className="text-right font-mono tnum text-sub">{Math.round(s.score)}</span>
                <span className="text-right">
                  <span className={s.tracking === "none" ? "text-ok" : s.tracking === "yes" ? "text-warn" : "text-dim"}>
                    {s.tracking === "none" ? "none" : s.tracking === "yes" ? "tracks" : s.tracking === "limited" ? "limited" : "—"}
                  </span>
                </span>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Custom RPC */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {showAdd ? (
          <>
            <input
              value={customUrl}
              onChange={(e) => setCustomUrl(e.target.value)}
              placeholder="https://my-node.example.com/rpc"
              className="focus-ring h-9 min-w-[220px] flex-1 rounded-lg border border-line bg-ink/60 px-3 font-mono text-[12px]"
            />
            <Button size="sm" variant="primary" onClick={handleAddCustom}>
              <IconPlus size={13} /> {t("Добавить", "Add")}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setShowAdd(false)}>
              {t("Отмена", "Cancel")}
            </Button>
          </>
        ) : (
          <>
            <Button size="sm" variant="soft" onClick={() => setShowAdd(true)}>
              <IconPlus size={13} /> {t("Свой RPC", "Custom RPC")}
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                clearRpcCache();
                app.toast("info", t("Кэш ранжирования очищен.", "Ranking cache cleared."));
                scan(true);
              }}
            >
              <IconRefresh size={13} /> {t("Сбросить кэш", "Reset cache")}
            </Button>
            <a
              href="https://chainlist.org"
              target="_blank"
              rel="noreferrer"
              className="focus-ring ml-auto flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-[11.5px] text-dim hover:text-acc"
            >
              <IconGlobe size={12} /> chainlist.org <IconExternal size={11} />
            </a>
          </>
        )}
      </div>

      {progress.total > 0 && progress.done < progress.total && (
        <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-panel2">
          <div className="h-full bg-acc transition-all" style={{ width: `${(progress.done / progress.total) * 100}%` }} />
        </div>
      )}
    </Card>
  );
}

function hostOf(url: string): string {
  try {
    const u = new URL(url);
    return u.host + (u.pathname !== "/" ? u.pathname.slice(0, 18) : "");
  } catch {
    return url.slice(0, 40);
  }
}
