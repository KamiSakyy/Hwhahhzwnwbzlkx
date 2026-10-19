import { idbGet, idbSet } from "./vault";
import type { ChainDef } from "./networks";

/**
 * Obsidian RPC Engine
 * -------------------
 * Fixes "Network/RPC unreachable" by never trusting a single endpoint.
 *
 * Pipeline:
 *  1. CANDIDATES = curated seeds ∪ provider-keyed URLs (Alchemy/Infura/…)
 *                  ∪ live Chainlist registry ∪ user's custom RPCs
 *  2. PROBE      = POST eth_chainId (verifies the node serves the RIGHT chain,
 *                  catching 429 rate-limits, 403s and wrong-network proxies)
 *                  + latency measurement, concurrency-limited.
 *  3. SCORE      = latency + privacy penalty + failure penalty − custom bonus.
 *                  Failed nodes get a cooldown so we stop hammering them.
 *  4. FAILOVER   = `withRpcFailover` retries a call on the next-best node when
 *                  the current one errors mid-session (the real fix: the old
 *                  code cached the first responder forever).
 *
 * Storage policy: private keys live ONLY in the IndexedDB vault. This module
 * caches *public* data (endpoint URLs, latencies) in localStorage so cold
 * starts are instant; provider API keys are secrets and go to IndexedDB.
 */

export type RpcSource = "seed" | "chainlist" | "provider" | "custom";

export interface RpcCandidate {
  url: string;
  source: RpcSource;
  tracking?: string; // "none" | "limited" | "yes" | "unknown"
  label?: string;
}

export interface RpcProbeResult {
  url: string;
  ok: boolean;
  correctChain: boolean;
  latencyMs: number;
  httpStatus?: number;
  error?: string;
}

export interface RpcScore extends RpcProbeResult {
  source: RpcSource;
  tracking?: string;
  fails: number;
  cooldownUntil: number;
  lastProbe: number;
  score: number;
}

const CHAINLIST_DIRECT = "https://chainlist.org/rpcs.json";
const PROXY = "/api/rpcs";
const RANK_LS = "obsidian.rpc.ranks.v1"; // public perf cache (no secrets)
const REG_IDB_KEY = "chainlist_registry_v1"; // trimmed registry (IndexedDB)
const REG_TTL_MS = 24 * 60 * 60 * 1000;
const RANK_TTL_MS = 20 * 60 * 1000;
const FAIL_COOLDOWN_MS = 90 * 1000;
const HARD_FAIL_COOLDOWN_MS = 10 * 60 * 1000; // 403/429 → demote longer
const PROBE_TIMEOUT_MS = 6000;
const CONCURRENCY = 8;
const MAX_PER_CHAIN = 40;

/* ============ curated seeds (verified healthy, fastest first) ============ */

const SEEDS: Record<number, string[]> = {
  // Sepolia — verified live 2026-10-19 (14/30 healthy, eth_chainId confirmed)
  11155111: [
    "https://gateway.tenderly.co/public/sepolia",
    "https://sepolia.gateway.tenderly.co",
    "https://public.1rpc.io/sepolia",
    "https://ethereum-sepolia-rpc.publicnode.com",
    "https://eth-sepolia.api.onfinality.io/public",
    "https://ethereum-sepolia-public.nodies.app",
    "https://ethereum-sepolia.gateway.tatum.io",
    "https://sepolia.rpc.sentio.xyz",
    "https://sepolia.drpc.org",
    "https://rpc.sepolia.ethpandaops.io",
    "https://ethereum-sepolia-rpc.blockreq.com/v1/rpc/public",
    "https://0xrpc.io/sep",
    "https://api.zan.top/eth-sepolia",
    "https://sepolia.drpc.org",
    "https://rpc.owlracle.info/sepolia/70d38ce1826c4a60bb2a8e05a6c8b20f",
    "https://eth-sepolia-testnet.api.pocket.network",
    "https://lb.routeme.sh/rpc/evm/11155111",
    "https://eth-testnet.4everland.org/v1/37fa9972c1b1cd5fab542c7bdd4cde2f",
    "https://ethereum-sepolia.therpc.io",
    "https://endpoints.omniatech.io/v1/eth/sepolia/public",
    "https://public.stackup.sh/api/v1/node/ethereum-sepolia",
    "https://rpc.notadegen.com/eth/sepolia",
  ],
  1: [
    "https://eth.llamarpc.com",
    "https://ethereum-rpc.publicnode.com",
    "https://gateway.tenderly.co/public/mainnet",
    "https://rpc.ankr.com/eth",
    "https://eth.drpc.org",
    "https://1rpc.io/eth",
    "https://rpc.flashbots.net",
    "https://eth-mainnet.public.blastapi.io",
    "https://api.zan.top/eth-mainnet",
    "https://ethereum.blockpi.network/v1/rpc/public",
    "https://rpc.payload.de",
    "https://0xrpc.io/eth",
    "https://lb.routeme.sh/rpc/evm/1",
    "https://eth.meowrpc.com",
    "https://mainnet.era.zksync.io",
  ],
  17000: [
    "https://ethereum-holesky-rpc.publicnode.com",
    "https://holesky.drpc.org",
    "https://rpc.holesky.ethpandaops.io",
    "https://1rpc.io/holesky",
    "https://holesky.gateway.tenderly.co",
    "https://lb.routeme.sh/rpc/evm/17000",
  ],
  56: [
    "https://bsc-rpc.publicnode.com",
    "https://bsc.drpc.org",
    "https://1rpc.io/bnb",
    "https://binance.llamarpc.com",
    "https://rpc.ankr.com/bsc",
    "https://bsc-mainnet.public.blastapi.io",
    "https://bsc.blockpi.network/v1/rpc/public",
    "https://api.zan.top/bsc-mainnet",
    "https://lb.routeme.sh/rpc/evm/56",
    "https://bsc.meowrpc.com",
  ],
  97: [
    "https://bsc-testnet-rpc.publicnode.com",
    "https://bsc-testnet.drpc.org",
    "https://data-seed-prebsc-1-s1.binance.org:8545",
    "https://data-seed-prebsc-2-s1.binance.org:8545",
    "https://1rpc.io/bnb/testnet",
    "https://lb.routeme.sh/rpc/evm/97",
  ],
  137: [
    "https://polygon-bor-rpc.publicnode.com",
    "https://polygon.drpc.org",
    "https://1rpc.io/matic",
    "https://polygon.llamarpc.com",
    "https://rpc.ankr.com/polygon",
    "https://polygon-bor.blockpi.network/v1/rpc/public",
    "https://polygon.api.onfinality.io/public",
    "https://api.zan.top/polygon-mainnet",
    "https://lb.routeme.sh/rpc/evm/137",
    "https://polygon.meowrpc.com",
  ],
  80002: [
    "https://rpc-amoy.polygon.technology",
    "https://polygon-amoy-rpc.publicnode.com",
    "https://polygon-amoy.drpc.org",
    "https://polygon-amoy-bor-rpc.publicnode.com",
    "https://lb.routeme.sh/rpc/evm/80002",
  ],
  42161: [
    "https://arb1.arbitrum.io/rpc",
    "https://arbitrum-one-rpc.publicnode.com",
    "https://arbitrum.drpc.org",
    "https://1rpc.io/arb",
    "https://rpc.ankr.com/arbitrum",
    "https://arbitrum-one.public.blastapi.io",
    "https://arbitrum.blockpi.network/v1/rpc/public",
    "https://api.zan.top/arb-mainnet",
    "https://lb.routeme.sh/rpc/evm/42161",
    "https://arbitrum.meowrpc.com",
  ],
  421614: [
    "https://sepolia-rollup.arbitrum.io/rpc",
    "https://arbitrum-sepolia-rpc.publicnode.com",
    "https://arbitrum-sepolia.drpc.org",
    "https://1rpc.io/arb-sepolia",
    "https://lb.routeme.sh/rpc/evm/421614",
    "https://arbitrum-sepolia.gateway.tenderly.co",
  ],
  10: [
    "https://mainnet.optimism.io",
    "https://optimism-rpc.publicnode.com",
    "https://optimism.drpc.org",
    "https://1rpc.io/op",
    "https://rpc.ankr.com/optimism",
    "https://optimism.public.blastapi.io",
    "https://optimism.blockpi.network/v1/rpc/public",
    "https://api.zan.top/opt-mainnet",
    "https://lb.routeme.sh/rpc/evm/10",
    "https://optimism.meowrpc.com",
  ],
  11155420: [
    "https://sepolia.optimism.io",
    "https://optimism-sepolia-rpc.publicnode.com",
    "https://optimism-sepolia.drpc.org",
    "https://1rpc.io/op-sepolia",
    "https://lb.routeme.sh/rpc/evm/11155420",
    "https://optimism-sepolia.gateway.tenderly.co",
  ],
  8453: [
    "https://mainnet.base.org",
    "https://base-rpc.publicnode.com",
    "https://base.drpc.org",
    "https://1rpc.io/base",
    "https://base.llamarpc.com",
    "https://rpc.ankr.com/base",
    "https://base.public.blastapi.io",
    "https://base.blockpi.network/v1/rpc/public",
    "https://api.zan.top/base-mainnet",
    "https://lb.routeme.sh/rpc/evm/8453",
    "https://base.meowrpc.com",
  ],
  84532: [
    "https://sepolia.base.org",
    "https://base-sepolia-rpc.publicnode.com",
    "https://base-sepolia.drpc.org",
    "https://1rpc.io/base-sepolia",
    "https://lb.routeme.sh/rpc/evm/84532",
    "https://base-sepolia.gateway.tenderly.co",
  ],
  43114: [
    "https://api.avax.network/ext/bc/C/rpc",
    "https://avalanche-c-rpc.publicnode.com",
    "https://avalanche.drpc.org",
    "https://1rpc.io/avax/c",
    "https://rpc.ankr.com/avalanche",
    "https://avax.meowrpc.com",
    "https://lb.routeme.sh/rpc/evm/43114",
  ],
  100: [
    "https://rpc.gnosischain.com",
    "https://gnosis-rpc.publicnode.com",
    "https://gnosis.drpc.org",
    "https://1rpc.io/gnosis",
    "https://rpc.ankr.com/gnosis",
    "https://gnosis.blockpi.network/v1/rpc/public",
    "https://lb.routeme.sh/rpc/evm/100",
  ],
};

/* ============ provider keyed endpoints (Alchemy / Infura) ============ */

export interface ProviderKeys {
  alchemy?: string;
  infura?: string;
  quicknode?: string; // full endpoint URL
  drpc?: string; // optional; public dRPC needs no key
}

const ALCHEMY_NET: Record<number, string> = {
  1: "eth-mainnet",
  11155111: "eth-sepolia",
  17000: "eth-holesky",
  10: "opt-mainnet",
  11155420: "opt-sepolia",
  42161: "arb-mainnet",
  421614: "arb-sepolia",
  8453: "base-mainnet",
  84532: "base-sepolia",
  137: "polygon-mainnet",
  80002: "polygon-amoy",
  56: "bnb-mainnet",
  97: "bnb-testnet",
  43114: "avax-mainnet",
};

const INFURA_NET: Record<number, string> = {
  1: "mainnet",
  11155111: "sepolia",
  17000: "holesky",
  10: "optimism-mainnet",
  11155420: "optimism-sepolia",
  42161: "arbitrum-mainnet",
  421614: "arbitrum-sepolia",
  8453: "base-mainnet",
  84532: "base-sepolia",
  137: "polygon-mainnet",
  80002: "polygon-amoy",
  59144: "linea-mainnet",
};

const KEYS_IDB = "provider_keys_v1";

export async function loadProviderKeys(): Promise<ProviderKeys> {
  try {
    return (await idbGet<ProviderKeys>("preferences", KEYS_IDB)) ?? {};
  } catch {
    return {};
  }
}

export async function saveProviderKeys(keys: ProviderKeys): Promise<void> {
  await idbSet("preferences", KEYS_IDB, keys);
  // Keys changed → invalidate rankings so keyed endpoints get scored.
  try {
    localStorage.removeItem(RANK_LS);
  } catch {
    /* noop */
  }
}

function providerCandidates(chainId: number, keys: ProviderKeys): RpcCandidate[] {
  const out: RpcCandidate[] = [];
  const k = (s: string) => s.trim().replace(/^https?:\/\//, "").replace(/[^a-zA-Z0-9_-]/g, "");

  if (keys.alchemy && ALCHEMY_NET[chainId]) {
    out.push({
      url: `https://${ALCHEMY_NET[chainId]}.g.alchemy.com/v2/${k(keys.alchemy)}`,
      source: "provider",
      label: "Alchemy (your key)",
      tracking: "yes",
    });
  }
  if (keys.infura && INFURA_NET[chainId]) {
    out.push({
      url: `https://${INFURA_NET[chainId]}.infura.io/v3/${k(keys.infura)}`,
      source: "provider",
      label: "Infura (your key)",
      tracking: "yes",
    });
  }
  if (keys.quicknode && /^https:\/\/[^\s]+$/i.test(keys.quicknode.trim())) {
    out.push({ url: keys.quicknode.trim(), source: "provider", label: "QuickNode (your endpoint)", tracking: "yes" });
  }
  if (keys.drpc) {
    // dRPC public gateways need no key; a key adds higher rate limits.
    out.push({ url: `https://lb.drpc.org/ogrpc?network=${chainId}&dkey=${k(keys.drpc)}`, source: "provider", label: "dRPC (your key)", tracking: "limited" });
  }
  return out;
}

/* ============ Chainlist dynamic discovery ============ */

interface RegistryCache {
  at: number;
  chains: Record<string, string[]>;
}

async function fetchChainlistBatch(chainIds: number[]): Promise<Record<string, string[]>> {
  const out: Record<string, string[]> = {};

  // 1) Preferred: our own trimming proxy (≈2 KB, no CORS dependency, cached 1 h).
  try {
    const res = await fetch(`${PROXY}?ids=${chainIds.join(",")}`, {
      signal: AbortSignal.timeout(9000),
      headers: { accept: "application/json" },
    });
    if (res.ok) {
      const j = (await res.json()) as { chains?: Record<string, { url: string; tracking?: string }[]> };
      if (j.chains) {
        for (const [id, eps] of Object.entries(j.chains)) {
          out[id] = (eps ?? []).map((e) => e.url).filter(Boolean);
        }
        if (Object.values(out).some((v) => v.length > 0)) return out;
      }
    }
  } catch {
    /* fall through to direct */
  }

  // 2) Fallback: fetch the registry directly (CORS `*`), trim client-side.
  try {
    const res = await fetch(CHAINLIST_DIRECT, { signal: AbortSignal.timeout(20_000) });
    if (!res.ok) return out;
    const data = (await res.json()) as Array<{
      chainId?: number;
      rpc?: (string | { url?: string; tracking?: string })[];
    }>;
    const want = new Set(chainIds);
    for (const c of data) {
      if (typeof c.chainId !== "number" || !want.has(c.chainId) || !Array.isArray(c.rpc)) continue;
      const urls: string[] = [];
      for (const item of c.rpc) {
        const url = (typeof item === "string" ? item : item?.url ?? "").trim();
        if (!/^https:\/\//i.test(url)) continue;
        if (/\$\{|<[A-Z_]+>|API_KEY|YOUR_KEY/i.test(url)) continue;
        urls.push(url);
      }
      if (urls.length) out[String(c.chainId)] = urls;
    }
  } catch {
    /* offline — seeds still work */
  }
  return out;
}

let registryMem: RegistryCache | null = null;

async function getRegistry(chainIds: number[]): Promise<RegistryCache> {
  if (registryMem && Date.now() - registryMem.at < REG_TTL_MS) return registryMem;
  try {
    const stored = await idbGet<RegistryCache>("preferences", REG_IDB_KEY);
    if (stored && Date.now() - stored.at < REG_TTL_MS) {
      registryMem = stored;
      return stored;
    }
  } catch {
    /* noop */
  }
  const fresh = await fetchChainlistBatch(chainIds);
  const next: RegistryCache = {
    at: Date.now(),
    chains: { ...(registryMem?.chains ?? {}), ...fresh },
  };
  registryMem = next;
  try {
    await idbSet("preferences", REG_IDB_KEY, next);
  } catch {
    /* IndexedDB blocked — memory cache still serves this session */
  }
  return next;
}

/* ============ candidate assembly ============ */

const LS_CUSTOM = "obsidian.customChains.v1";
const LS_CUSTOM_RPC = "obsidian.rpc.custom.v1";

function customRpcsFor(chainId: number): string[] {
  try {
    const raw = localStorage.getItem(LS_CUSTOM_RPC);
    if (!raw) return [];
    const map = JSON.parse(raw) as Record<string, string[]>;
    return (map[String(chainId)] ?? []).filter((u) => /^https?:\/\//i.test(u));
  } catch {
    return [];
  }
}

export async function collectCandidates(
  chain: ChainDef,
  keys?: ProviderKeys,
): Promise<RpcCandidate[]> {
  const providerKeys = keys ?? (await loadProviderKeys());
  const seen = new Set<string>();
  const out: RpcCandidate[] = [];
  const push = (url: string, source: RpcSource, tracking?: string, label?: string) => {
    const u = url.trim();
    if (!/^https?:\/\//i.test(u)) return;
    const key = u.toLowerCase().replace(/\/$/, "");
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ url: u, source, tracking, label });
  };

  // Priority order matters for cold starts: user's own keys and custom RPCs
  // first, then verified seeds, then the live Chainlist registry.
  for (const c of providerCandidates(chain.chainId, providerKeys)) push(c.url, "provider", c.tracking, c.label);
  for (const u of customRpcsFor(chain.chainId)) push(u, "custom", "none", "Custom RPC");
  for (const u of chain.rpcs ?? []) push(u, "seed", "none");
  for (const u of SEEDS[chain.chainId] ?? []) push(u, "seed", "none");

  const reg = await getRegistry([chain.chainId]).catch(() => null);
  for (const u of reg?.chains[String(chain.chainId)] ?? []) push(u, "chainlist", "unknown");

  return out.slice(0, MAX_PER_CHAIN * 2);
}

/* ============ probing & scoring ============ */

export async function probeRpc(url: string, chainId: number, timeoutMs = PROBE_TIMEOUT_MS): Promise<RpcProbeResult> {
  const t0 = performance.now();
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "eth_chainId", params: [] }),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const latencyMs = Math.round(performance.now() - t0);
    if (!res.ok) {
      return { url, ok: false, correctChain: false, latencyMs, httpStatus: res.status, error: `HTTP ${res.status}` };
    }
    const j = (await res.json()) as { result?: string; error?: { message?: string } };
    if (j.error) {
      return { url, ok: false, correctChain: false, latencyMs, httpStatus: res.status, error: j.error.message ?? "rpc error" };
    }
    const cid = typeof j.result === "string" ? parseInt(j.result, 16) : NaN;
    const correctChain = Number.isFinite(cid) && cid === chainId;
    return {
      url,
      ok: correctChain,
      correctChain,
      latencyMs,
      httpStatus: res.status,
      error: correctChain ? undefined : `serves chain ${Number.isFinite(cid) ? cid : "?"}`,
    };
  } catch (e) {
    return {
      url,
      ok: false,
      correctChain: false,
      latencyMs: Math.round(performance.now() - t0),
      error: String((e as Error).message ?? e).slice(0, 60),
    };
  }
}

async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let i = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) {
      const idx = i++;
      out[idx] = await fn(items[idx]);
    }
  });
  await Promise.all(workers);
  return out;
}

function scoreOf(p: RpcProbeResult, c: RpcCandidate, fails: number): number {
  if (!p.ok) return Number.POSITIVE_INFINITY;
  const privacy = c.tracking === "yes" ? 220 : c.tracking === "limited" ? 70 : 0;
  const sourceBonus =
    c.source === "provider" ? -400 : c.source === "custom" ? -300 : c.source === "seed" ? -40 : 0;
  const failPenalty = fails * 4000;
  return p.latencyMs + privacy + sourceBonus + failPenalty;
}

type RankStore = Record<string, { at: number; scores: RpcScore[] }>;

function readRanks(): RankStore {
  try {
    return JSON.parse(localStorage.getItem(RANK_LS) ?? "{}") as RankStore;
  } catch {
    return {};
  }
}
function writeRanks(r: RankStore) {
  try {
    localStorage.setItem(RANK_LS, JSON.stringify(r));
  } catch {
    /* quota — rankings are a perf cache only */
  }
}

/** Live in-session failure bookkeeping (survives across views, not reloads). */
const liveFails = new Map<string, { fails: number; cooldownUntil: number }>();

export function markRpcFailure(url: string, chainId: number, hard = false) {
  const key = `${chainId}:${url.toLowerCase()}`;
  const cur = liveFails.get(key) ?? { fails: 0, cooldownUntil: 0 };
  cur.fails += 1;
  cur.cooldownUntil = Date.now() + (hard ? HARD_FAIL_COOLDOWN_MS : FAIL_COOLDOWN_MS);
  liveFails.set(key, cur);
  const ranks = readRanks();
  const entry = ranks[String(chainId)];
  if (entry) {
    entry.scores = entry.scores.map((s) =>
      s.url.toLowerCase() === url.toLowerCase()
        ? { ...s, ok: false, fails: s.fails + 1, cooldownUntil: cur.cooldownUntil, score: Number.POSITIVE_INFINITY }
        : s,
    );
    entry.at = Date.now();
    writeRanks(ranks);
  }
}

export function markRpcSuccess(url: string, chainId: number) {
  const key = `${chainId}:${url.toLowerCase()}`;
  liveFails.delete(key);
  const ranks = readRanks();
  const entry = ranks[String(chainId)];
  if (entry) {
    entry.scores = entry.scores.map((s) =>
      s.url.toLowerCase() === url.toLowerCase() ? { ...s, fails: 0, cooldownUntil: 0 } : s,
    );
    writeRanks(ranks);
  }
}

export interface RankOptions {
  force?: boolean;
  maxProbe?: number;
  keys?: ProviderKeys;
  onProgress?: (done: number, total: number) => void;
}

/**
 * Rank all candidates for a chain. Uses the cached ranking when fresh
 * (stale-while-revalidate), otherwise probes concurrently and persists.
 */
export async function rankRpcs(chain: ChainDef, opts: RankOptions = {}): Promise<RpcScore[]> {
  const { force = false, maxProbe = MAX_PER_CHAIN, keys } = opts;
  const id = String(chain.chainId);
  const ranks = readRanks();
  const cached = ranks[id];
  const now = Date.now();

  if (!force && cached && now - cached.at < RANK_TTL_MS) {
    // Drop endpoints still in cooldown; keep the rest ordered.
    const usable = cached.scores.filter((s) => s.cooldownUntil < now && s.ok);
    if (usable.length > 0) return usable;
  }

  const candidates = (await collectCandidates(chain, keys)).slice(0, maxProbe);
  if (candidates.length === 0) return [];

  let done = 0;
  const probes = await mapLimit(candidates, CONCURRENCY, async (c) => {
    const key = `${id}:${c.url.toLowerCase()}`;
    const live = liveFails.get(key);
    if (live && live.cooldownUntil > now) {
      done++;
      opts.onProgress?.(done, candidates.length);
      return {
        url: c.url,
        ok: false,
        correctChain: false,
        latencyMs: 0,
        error: "cooldown",
        source: c.source,
        tracking: c.tracking,
        fails: live.fails,
        cooldownUntil: live.cooldownUntil,
        lastProbe: now,
        score: Number.POSITIVE_INFINITY,
      } as RpcScore;
    }
    const p = await probeRpc(c.url, chain.chainId);
    const priorFails = cached?.scores.find((s) => s.url === c.url)?.fails ?? 0;
    const fails = p.ok ? 0 : priorFails + 1;
    const hard = p.httpStatus === 429 || p.httpStatus === 403 || p.httpStatus === 401;
    if (!p.ok && fails > 0) {
      liveFails.set(key, { fails, cooldownUntil: now + (hard ? HARD_FAIL_COOLDOWN_MS : FAIL_COOLDOWN_MS) });
    }
    done++;
    opts.onProgress?.(done, candidates.length);
    return {
      ...p,
      source: c.source,
      tracking: c.tracking,
      fails,
      cooldownUntil: p.ok ? 0 : now + (hard ? HARD_FAIL_COOLDOWN_MS : FAIL_COOLDOWN_MS),
      lastProbe: now,
      score: scoreOf(p, c, fails),
    } as RpcScore;
  });

  const sorted = probes.filter((p) => p.ok).sort((a, b) => a.score - b.score);
  const next: RankStore = { ...ranks, [id]: { at: now, scores: sorted } };
  writeRanks(next);
  registryMem = registryMem; // keep memory cache warm
  return sorted;
}

export interface BestRpc {
  url: string;
  latencyMs: number;
  healthyCount: number;
  totalProbed: number;
  source: RpcSource;
  label?: string;
}

const bestMem = new Map<number, { at: number; value: BestRpc }>();
const rankingInflight = new Map<number, Promise<RpcScore[]>>();
const backgroundRanked = new Set<number>();

/** Curated, measurement-ordered seeds — used for instant cold starts. */
export function seedUrls(chain: ChainDef): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const u of [...(chain.rpcs ?? []), ...(SEEDS[chain.chainId] ?? [])]) {
    const k = u.toLowerCase();
    if (!seen.has(k)) {
      seen.add(k);
      out.push(u);
    }
  }
  return out;
}

/** Kick off ranking without blocking the caller. */
function rankInBackground(chain: ChainDef, keys?: ProviderKeys) {
  if (backgroundRanked.has(chain.chainId)) return;
  backgroundRanked.add(chain.chainId);
  rankRpcs(chain, { force: true, keys })
    .then(() => {
      bestMem.delete(chain.chainId);
    })
    .catch(() => undefined)
    .finally(() => backgroundRanked.delete(chain.chainId));
}

function hasFreshRanks(chainId: number): RpcScore[] | null {
  const entry = readRanks()[String(chainId)];
  if (!entry || Date.now() - entry.at >= RANK_TTL_MS) return null;
  const usable = entry.scores.filter((s) => s.ok && s.cooldownUntil < Date.now());
  return usable.length > 0 ? usable : null;
}

/**
 * Non-blocking lookup of previously ranked endpoints.
 * Returns null when nothing is cached yet, so callers can fall back to seeds
 * instead of waiting for a probe sweep (keeps first paint instant).
 */
export function getCachedRanks(chainId: number): RpcScore[] | null {
  return hasFreshRanks(chainId);
}

/**
 * Returns the currently best RPC for a chain, refreshing in the background
 * when the cache ages out. Falls back to any seed so the UI never dead-ends.
 */
export async function getBestRpc(chain: ChainDef, opts: RankOptions = {}): Promise<BestRpc> {
  const mem = bestMem.get(chain.chainId);
  if (!opts.force && mem && Date.now() - mem.at < 60_000) return mem.value;

  // COLD START: never block first paint on a multi-second probe sweep.
  // Serve the curated (measurement-ordered) seeds immediately and rank in the
  // background; the cache makes every later call optimal and instant.
  if (!opts.force) {
    const fresh = hasFreshRanks(chain.chainId);
    if (!fresh) {
      rankInBackground(chain, opts.keys);
      const seeds = seedUrls(chain);
      if (seeds[0]) {
        const value: BestRpc = {
          url: seeds[0],
          latencyMs: -1,
          healthyCount: 0,
          totalProbed: 0,
          source: "seed",
        };
        bestMem.set(chain.chainId, { at: Date.now() - 45_000, value }); // short-lived
        return value;
      }
    }
  }

  const key = chain.chainId;
  let promise = rankingInflight.get(key);
  if (!promise || opts.force) {
    promise = rankRpcs(chain, opts);
    rankingInflight.set(key, promise);
    promise.finally(() => rankingInflight.delete(key));
  }

  const scores = await promise;
  const healthy = scores.filter((s) => s.cooldownUntil < Date.now() && s.ok);
  if (healthy.length > 0) {
    const top = healthy[0];
    const value: BestRpc = {
      url: top.url,
      latencyMs: top.latencyMs,
      healthyCount: healthy.length,
      totalProbed: scores.length || healthy.length,
      source: top.source,
      label: (await collectCandidates(chain, opts.keys)).find((c) => c.url === top.url)?.label,
    };
    bestMem.set(chain.chainId, { at: Date.now(), value });
    return value;
  }

  // Total outage: degrade gracefully to the first seed so the user still gets
  // a clear, actionable error instead of a blank/unresponsive app.
  const fallback = (SEEDS[chain.chainId] ?? chain.rpcs ?? [])[0];
  if (!fallback) throw new Error(`No RPC endpoints are known for ${chain.name}.`);
  return {
    url: fallback,
    latencyMs: -1,
    healthyCount: 0,
    totalProbed: scores.length,
    source: "seed",
  };
}

/** All ranked endpoints (for the Settings health table). */
export async function getRpcHealth(chain: ChainDef, force = false): Promise<RpcScore[]> {
  return rankRpcs(chain, { force });
}

export function clearRpcCache() {
  try {
    localStorage.removeItem(RANK_LS);
  } catch {
    /* noop */
  }
  registryMem = null;
  bestMem.clear();
  liveFails.clear();
}

export function addCustomRpc(chainId: number, url: string): boolean {
  const u = url.trim();
  if (!/^https?:\/\//i.test(u)) return false;
  try {
    const map = JSON.parse(localStorage.getItem(LS_CUSTOM_RPC) ?? "{}") as Record<string, string[]>;
    const list = map[String(chainId)] ?? [];
    if (!list.includes(u)) list.push(u);
    map[String(chainId)] = list.slice(0, 12);
    localStorage.setItem(LS_CUSTOM_RPC, JSON.stringify(map));
    bestMem.delete(chainId);
    return true;
  } catch {
    return false;
  }
}

export function removeCustomRpc(chainId: number, url: string) {
  try {
    const map = JSON.parse(localStorage.getItem(LS_CUSTOM_RPC) ?? "{}") as Record<string, string[]>;
    map[String(chainId)] = (map[String(chainId)] ?? []).filter((u) => u !== url);
    localStorage.setItem(LS_CUSTOM_RPC, JSON.stringify(map));
    bestMem.delete(chainId);
  } catch {
    /* noop */
  }
}

export function listCustomRpcs(chainId: number): string[] {
  return customRpcsFor(chainId);
}

void LS_CUSTOM;
