import {
  JsonRpcProvider,
  Wallet,
  Transaction,
  SigningKey,
  computeAddress,
  getAddress,
  formatUnits,
  getBigInt,
} from "ethers";
import type { ChainDef, TokenDef } from "./networks";
import { parseMsgCalldata, toB64, isValidCompressedPub } from "./crypto";
import type { MessageRow } from "./api";
import {
  getBestRpc,
  getCachedRanks,
  getRpcHealth,
  markRpcFailure,
  markRpcSuccess,
  seedUrls,
  type RpcScore,
} from "./rpc";

/**
 * Client-side chain engine with resilient multi-RPC failover.
 *
 * Every read/write runs through `withRpc`, which:
 *  - resolves the best-ranked endpoint (latency + correct-chain verified),
 *  - retries on the next-best endpoint when one errors mid-session,
 *  - records failures so rate-limited (429/403) nodes are demoted,
 *  - surfaces ONE actionable message only when every candidate fails.
 *
 * No backend sits in the hot path — all traffic goes browser → public RPC.
 */

const providerCache = new Map<string, JsonRpcProvider>();

function providerForUrl(url: string, chainId: number): JsonRpcProvider {
  const key = `${chainId}:${url}`;
  let p = providerCache.get(key);
  if (!p) {
    p = new JsonRpcProvider(url, chainId, {
      batchMaxCount: 1,
      staticNetwork: true,
      cacheTimeout: -1,
    });
    providerCache.set(key, p);
  }
  return p;
}

/** Evict a provider whose endpoint proved unhealthy. */
function dropProvider(url: string, chainId: number) {
  const key = `${chainId}:${url}`;
  const p = providerCache.get(key);
  if (p) {
    try {
      p.destroy();
    } catch {
      /* noop */
    }
    providerCache.delete(key);
  }
}

const MAX_ATTEMPTS = 4;

interface WithRpcOptions {
  attempts?: number;
  /** Writes should try more endpoints before giving up. */
  critical?: boolean;
  force?: boolean;
}

async function withRpc<T>(
  chain: ChainDef,
  fn: (provider: JsonRpcProvider, url: string) => Promise<T>,
  opts: WithRpcOptions = {},
): Promise<T> {
  const attempts = opts.attempts ?? (opts.critical ? MAX_ATTEMPTS + 2 : MAX_ATTEMPTS);

  // NON-BLOCKING endpoint selection: use cached rankings when available,
  // otherwise the curated seeds. A probe sweep must never delay first paint;
  // `getBestRpc` refines the cache in the background, and the retry loop below
  // handles any dead node immediately.
  const cached: RpcScore[] | null = opts.force ? null : getCachedRanks(chain.chainId);
  const ordered = cached ? cached.filter((s) => s.ok && s.cooldownUntil < Date.now()).map((s) => s.url) : [];
  if (!cached) {
    // Warm the ranking cache without awaiting it.
    void getBestRpc(chain, { force: opts.force }).catch(() => undefined);
  }

  const urls: string[] = [];
  const seen = new Set<string>();
  for (const u of [...ordered, ...seedUrls(chain)]) {
    if (!u) continue;
    const k = u.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    urls.push(u);
  }

  if (urls.length === 0) {
    throw new Error(`No RPC endpoint is known for ${chain.name} (chain ${chain.chainId}).`);
  }

  const errors: string[] = [];
  for (let i = 0; i < Math.min(urls.length, attempts); i++) {
    const url = urls[i];
    const provider = providerForUrl(url, chain.chainId);
    try {
      const out = await fn(provider, url);
      markRpcSuccess(url, chain.chainId);
      return out;
    } catch (e) {
      const msg = String((e as Error)?.message ?? e);
      const hard = /429|403|401|rate.?limit|forbidden|too many/i.test(msg);
      const net = /failed to fetch|network|timeout|ECONN|ENOTFOUND|socket|bad response|could not detect/i.test(msg);
      markRpcFailure(url, chain.chainId, hard);
      if (hard || net) {
        dropProvider(url, chain.chainId);
        errors.push(`${hostOf(url)}: ${hard ? "rate-limited" : "unreachable"}`);
        continue; // try the next-best endpoint
      }
      // Genuine on-chain/RPC-level rejection (invalid nonce, insufficient
      // funds, revert) — retrying elsewhere would just repeat it.
      throw e;
    }
  }

  const healthy = cached?.filter((s) => s.ok).length ?? 0;
  throw new Error(
    `All ${urls.length} RPC endpoint(s) failed for ${chain.name}${
      healthy === 0 ? " — the network may be temporarily down or blocked" : ""
    }. Tried: ${errors.slice(0, 3).join("; ") || "unreachable"}. Tap the network pill or open Settings → RPC Engine to re-scan, add a custom node, or paste an Alchemy/Infura key.`,
  );
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url.slice(0, 24);
  }
}

/** Best endpoint for a chain (used for UI status + one-off calls). */
export async function ensureRpc(chain: ChainDef, customRpc?: string): Promise<string> {
  if (customRpc) return customRpc;
  const best = await getBestRpc(chain);
  return best.url;
}

export async function providerFor(chain: ChainDef, customRpc?: string): Promise<JsonRpcProvider> {
  const url = await ensureRpc(chain, customRpc);
  return providerForUrl(url, chain.chainId);
}

/** Live network status for the header indicator / Settings panel. */
export async function getNetworkStatus(chain: ChainDef) {
  const best = await getBestRpc(chain).catch(() => null);
  const scores = await getRpcHealth(chain).catch(() => []);
  return {
    best: best?.url ?? null,
    latencyMs: best?.latencyMs ?? -1,
    healthy: best?.healthyCount ?? 0,
    total: Math.max(scores.length, best?.totalProbed ?? 0),
    source: best?.source ?? null,
    label: best?.label ?? null,
  };
}

export interface BalanceRow {
  token: string;
  symbol: string;
  address: string;
  decimals: number;
  raw: bigint;
  amount: string;
}

export async function readNative(chain: ChainDef, address: string): Promise<BalanceRow> {
  const raw = await withRpc(chain, (p) => p.getBalance(address));
  return {
    token: "NATIVE",
    symbol: chain.symbol,
    address: "0x0000000000000000000000000000000000000000",
    decimals: 18,
    raw,
    amount: formatUnits(raw, 18),
  };
}

export async function readTokens(chain: ChainDef, tokens: TokenDef[], address: string): Promise<BalanceRow[]> {
  if (tokens.length === 0) return [];
  const pad32 = (v: string) => v.slice(2).padStart(64, "0");
  // One provider, one failover boundary for the whole batch (fewer probes).
  return withRpc(
    chain,
    async (p) =>
      Promise.all(
        tokens.map(async (t) => {
          const decimals = t.decimals ?? 18;
          try {
            const hex = await p.call({ to: t.address, data: "0x70a08231" + pad32(address) });
            const raw = getBigInt(hex);
            return { token: t.address, symbol: t.symbol, address: t.address, decimals, raw, amount: formatUnits(raw, decimals) };
          } catch {
            return { token: t.address, symbol: t.symbol, address: t.address, decimals, raw: 0n, amount: formatUnits(0n, decimals) };
          }
        }),
      ),
  );
}

const pad = (h: string) => h.slice(2).toLowerCase().padStart(64, "0");

export function erc20TransferData(to: string, amountRaw: bigint): string {
  return "0xa9059cbb" + pad(to) + amountRaw.toString(16).padStart(64, "0");
}
export function erc20ApproveData(spender: string, amountRaw: bigint): string {
  return "0x095ea7b3" + pad(spender) + amountRaw.toString(16).padStart(64, "0");
}

export async function readAllowance(chain: ChainDef, token: string, owner: string, spender: string): Promise<bigint> {
  try {
    return await withRpc(chain, async (p) => {
      const hex = await p.call({ to: token, data: "0xdd62ed3e" + pad(owner) + pad(spender) });
      return getBigInt(hex);
    });
  } catch {
    return 0n;
  }
}

export async function probeToken(chain: ChainDef, address: string): Promise<TokenDef> {
  const a = getAddress(address);
  return withRpc(chain, async (p) => {
    const symHex = await p.call({ to: a, data: "0x95d89b41" });
    let symbol = "???";
    try {
      if (symHex.length === 66) {
        const raw = symHex.slice(2).replace(/0+$/, "");
        symbol = raw ? Buffer.from(raw, "hex").toString("ascii").replace(/[^\x20-\x7e]/g, "") : "";
      } else if (symHex.length > 130) {
        const lenHex = symHex.slice(66, 130);
        const len = parseInt(lenHex, 16);
        symbol = Buffer.from(symHex.slice(130, 130 + len * 2), "hex")
          .toString("ascii")
          .replace(/[^\x20-\x7e]/g, "");
      }
    } catch {
      symbol = "???";
    }
    let decimals = 18;
    try {
      const d = await p.call({ to: a, data: "0x313ce567" });
      const parsed = parseInt(d.slice(-2), 16);
      decimals = Number.isNaN(parsed) ? 18 : parsed;
    } catch {
      /* keep 18 */
    }
    return { address: a, symbol: symbol || "TOKEN", name: symbol || "Custom token", decimals };
  });
}

export interface BroadcastOpts {
  chain: ChainDef;
  wallet: Wallet;
  to: string;
  value?: bigint;
  data?: string;
  gasFallback?: number;
  customRpc?: string;
}

export interface BroadcastResult {
  hash: string;
  provider: JsonRpcProvider;
  rpcUrl: string;
}

function calldataGas(data: string | undefined): number {
  if (!data || data === "0x") return 0;
  const hex = data.slice(2);
  let g = 0;
  for (const ch of hex) g += ch === "0" ? 4 : 16;
  return g;
}

export function explainError(e: unknown): string {
  const raw = (e as { shortMessage?: string; reason?: string; message?: string }) ?? {};
  const m = String(raw.shortMessage || raw.reason || raw.message || e);
  if (/insufficient funds|balance/i.test(m)) return "Insufficient funds to cover the transfer + gas.";
  if (/nonce/i.test(m)) return "Nonce conflict — another transaction is pending. Wait a few seconds and retry.";
  if (/replacement transaction|already known|underpriced/i.test(m))
    return "A conflicting transaction is already in the mempool. Wait for it to confirm.";
  if (/all \d+ rpc endpoint/i.test(m)) return m;
  if (/no rpc endpoint is known/i.test(m)) return m;
  if (/failed to fetch|network|timeout|ECONN|ENOTFOUND|bad response/i.test(m))
    return "Network/RPC unreachable. The engine tried every known endpoint — check your connection or add a custom RPC in Settings.";
  if (/execution reverted/i.test(m)) return "Transaction reverted on-chain (contract rejected it).";
  return m.length > 180 ? m.slice(0, 180) + "…" : m;
}

export async function broadcast(opts: BroadcastOpts): Promise<BroadcastResult> {
  const { chain, wallet } = opts;
  const base = {
    to: getAddress(opts.to),
    value: opts.value ?? 0n,
    data: opts.data ?? "0x",
    chainId: chain.chainId,
  };
  const gasHint = BigInt((opts.gasFallback ?? 21_000) + calldataGas(base.data));

  return withRpc(
    chain,
    async (p, url) => {
      const nonce = (await p.getTransactionCount(wallet.address, "latest")) as number;
      const fee = await p.getFeeData();
      const feeCfg =
        fee.maxFeePerGas != null && fee.maxPriorityFeePerGas != null
          ? { maxFeePerGas: fee.maxFeePerGas, maxPriorityFeePerGas: fee.maxPriorityFeePerGas }
          : { gasPrice: fee.gasPrice ?? 10n ** 9n };

      let gasLimit: bigint;
      try {
        gasLimit = await p.estimateGas({ ...base, nonce });
      } catch {
        // Zero-balance accounts on fresh testnets cannot estimate; use the
        // deterministic 21k + calldata formula instead of failing the send.
        gasLimit = gasHint;
      }

      const signed = await wallet.signTransaction({ ...base, nonce, gasLimit, ...feeCfg });
      const resp = await p.broadcastTransaction(signed);
      return { hash: resp.hash, provider: p, rpcUrl: url };
    },
    { critical: true },
  );
}

export async function waitForTx(p: JsonRpcProvider, hash: string, timeoutMs = 120_000): Promise<boolean> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const rcpt = await p.getTransactionReceipt(hash);
      if (rcpt) return (rcpt.status ?? 0) === 1;
    } catch {
      /* transient RPC hiccup while polling — keep waiting */
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error("Timed out waiting for transaction confirmation.");
}

/** Wait for a tx using the resilient multi-RPC path. */
export async function waitForTxResilient(chain: ChainDef, hash: string, timeoutMs = 180_000): Promise<boolean> {
  const start = Date.now();
  let lastErr = "";
  while (Date.now() - start < timeoutMs) {
    try {
      return await withRpc(chain, async (p) => {
        const rcpt = await p.getTransactionReceipt(hash);
        if (!rcpt) throw new Error("__pending__");
        return (rcpt.status ?? 0) === 1;
      });
    } catch (e) {
      const m = String((e as Error).message ?? e);
      if (m.includes("__pending__")) {
        await new Promise((r) => setTimeout(r, 3000));
        continue;
      }
      lastErr = m;
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
  throw new Error(lastErr || "Timed out waiting for transaction confirmation.");
}

export function estimateFeeWei(chain: ChainDef, data: string, gasLimit: number): { gasLimit: number; wei: bigint } {
  const total = gasLimit + calldataGas(data);
  const gweiGuess = chain.testnet ? 10n : chain.chainId === 1 ? 12n * 10n ** 9n : 1n * 10n ** 9n;
  return { gasLimit: total, wei: BigInt(total) * gweiGuess };
}

/* ============ history (Blockscout v2) with endpoint failover ============ */

export interface TxRow {
  hash: string;
  from: string;
  to: string | null;
  value: string;
  method: string;
  blockNumber: number;
  timestamp: string;
  rawInput?: string;
}

async function tryJson(url: string, timeoutMs = 10_000): Promise<unknown | null> {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

export async function fetchHistory(chain: ChainDef, address: string): Promise<TxRow[] | null> {
  const bases: string[] = [];
  if (chain.blockscout) bases.push(chain.blockscout);
  // Public fallbacks that work without an API key
  bases.push(`https://eth.blockscout.com`.replace("eth", chain.blockscout ? "" : "eth"));
  const uniq = [...new Set(bases.filter(Boolean))];

  for (const base of uniq) {
    const j = (await tryJson(`${base}/api/v2/addresses/${address}/transactions?limit=35`)) as {
      items?: Array<Record<string, unknown>>;
    } | null;
    if (!j || !Array.isArray(j.items)) continue;
    return j.items.map((t) => ({
      hash: String(t.hash),
      from: String((t.from as { hash: string })?.hash ?? ""),
      to: (t.to as { hash: string } | null)?.hash ?? null,
      value: String(t.value ?? "0"),
      method: String(t.method ?? "transfer"),
      blockNumber: Number(t.block_number ?? t.blockNumber ?? 0),
      timestamp: String(t.timestamp ?? new Date().toISOString()),
      rawInput: typeof t.raw_input === "string" ? t.raw_input : undefined,
    }));
  }
  return null;
}

/**
 * Serverless on-chain public key recovery (ecrecover from a real tx).
 * Lets true ECDH E2E work for any address that ever sent a transaction,
 * with no backend involved.
 */
export async function recoverPubkeyFromChain(chain: ChainDef, targetAddress: string): Promise<string | null> {
  const norm = targetAddress.trim().toLowerCase();
  if (!/^0x[0-9a-f]{40}$/.test(norm)) return null;
  if (!chain.blockscout) return null;

  const j = (await tryJson(`${chain.blockscout}/api/v2/addresses/${norm}/transactions?limit=20`, 9000)) as {
    items?: Array<Record<string, unknown>>;
  } | null;
  if (!j || !Array.isArray(j.items)) return null;

  // Fast path: a previous nxMS envelope already carries the 33B pubkey.
  for (const item of j.items) {
    const from = String((item.from as { hash?: string })?.hash ?? "").toLowerCase();
    const rawInput = typeof item.raw_input === "string" ? item.raw_input : "";
    if (from === norm && rawInput.startsWith("0x6e784d53")) {
      const parsed = parseMsgCalldata(rawInput);
      if (parsed && isValidCompressedPub(parsed.senderPub)) return parsed.senderPub;
    }
  }

  // Universal path: recover secp256k1 pubkey from any outgoing signature.
  const outgoing = j.items.find(
    (item) => String((item.from as { hash?: string })?.hash ?? "").toLowerCase() === norm && typeof item.hash === "string",
  );
  if (!outgoing) return null;

  try {
    const txObj = await withRpc(chain, async (p) => {
      const t = await p.send("eth_getTransactionByHash", [String(outgoing.hash)]);
      return t as Record<string, string> | null;
    });
    if (!txObj || !txObj.r || !txObj.s) return null;

    const txType = txObj.type ? parseInt(txObj.type, 16) : 0;
    const tx = Transaction.from({
      type: txType,
      to: txObj.to || null,
      nonce: parseInt(txObj.nonce, 16),
      gasLimit: BigInt(txObj.gas),
      data: txObj.input || "0x",
      value: BigInt(txObj.value || "0x0"),
      chainId: txObj.chainId ? BigInt(txObj.chainId) : BigInt(chain.chainId),
      ...(txType === 2
        ? {
            maxPriorityFeePerGas: BigInt(txObj.maxPriorityFeePerGas || "0x0"),
            maxFeePerGas: BigInt(txObj.maxFeePerGas || "0x0"),
          }
        : { gasPrice: BigInt(txObj.gasPrice || "0x0") }),
      signature: { r: txObj.r, s: txObj.s, v: BigInt(txObj.v || "0x0") },
    });

    const uncompressed = SigningKey.recoverPublicKey(tx.unsignedHash, tx.signature!);
    if (computeAddress(uncompressed).toLowerCase() === norm) {
      return SigningKey.computePublicKey(uncompressed, true);
    }
  } catch {
    /* endpoint unavailable — caller falls back to PPSK/handshake */
  }
  return null;
}

/** Scans recent chain transactions for `nxMS` calldata envelopes (serverless). */
export async function scanOnChainMessages(chain: ChainDef, address: string): Promise<MessageRow[]> {
  try {
    const history = await fetchHistory(chain, address);
    if (!history) return [];
    const out: MessageRow[] = [];
    for (const t of history) {
      if (!t.rawInput || !t.rawInput.startsWith("0x6e784d53") || !t.to) continue;
      const parsed = parseMsgCalldata(t.rawInput);
      if (!parsed) continue;
      const syntheticId = parseInt(t.hash.slice(2, 10), 16) || Date.now();
      out.push({
        id: syntheticId,
        sender: t.from.toLowerCase(),
        recipient: t.to.toLowerCase(),
        chainId: chain.chainId,
        txHash: t.hash,
        amountWei: t.value || "0",
        tokenSymbol: chain.symbol,
        kind: "text",
        nonce: toB64(parsed.iv),
        ciphertext: toB64(parsed.ct),
        senderPub: parsed.senderPub,
        mode: parsed.mode,
        fileId: null,
        status: "confirmed",
        createdAt: t.timestamp || new Date().toISOString(),
      });
    }
    return out;
  } catch {
    return [];
  }
}

export function explorerTx(chain: ChainDef, hash: string): string {
  if (chain.blockscout) return `${chain.blockscout}/tx/${hash}`;
  if (chain.explorer) return `${chain.explorer}/tx/${hash}`;
  return hash;
}

export function explorerAddr(chain: ChainDef, addr: string): string {
  if (chain.blockscout) return `${chain.blockscout}/address/${addr}`;
  if (chain.explorer) return `${chain.explorer}/address/${addr}`;
  return "";
}
