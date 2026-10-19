import { NextRequest, NextResponse } from "next/server";

/**
 * Chainlist RPC registry proxy + trimmer.
 *
 * `https://chainlist.org/rpcs.json` is the canonical public RPC registry
 * (2992 chains, ~2.4 MB). Downloading that on a mobile client is wasteful, so
 * this route fetches it server-side, caches it in memory, and returns ONLY the
 * endpoints for the requested chain (~2 KB). It also filters out:
 *   - websocket URLs (ethers' JsonRpcProvider needs http(s))
 *   - placeholder URLs containing ${API_KEY} / <YOUR_KEY>
 * and de-duplicates.
 *
 * This is a pure convenience/robustness layer: the client can also fetch
 * chainlist directly (it sends `access-control-allow-origin: *`).
 */

const CHAINLIST_URL = "https://chainlist.org/rpcs.json";
const CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

interface ChainlistRpc {
  url?: string;
  tracking?: string;
}
interface ChainlistChain {
  chainId?: number;
  name?: string;
  rpc?: (string | ChainlistRpc)[];
  isTestnet?: boolean;
}

export interface RpcEndpoint {
  url: string;
  tracking: string; // "none" | "limited" | "yes" | "unknown"
  source: "chainlist";
}

let cache: { at: number; chains: Map<number, RpcEndpoint[]> } | null = null;
let inflight: Promise<Map<number, RpcEndpoint[]>> | null = null;

function normalize(raw: (string | ChainlistRpc)[]): RpcEndpoint[] {
  const seen = new Set<string>();
  const out: RpcEndpoint[] = [];
  for (const item of raw) {
    const url = (typeof item === "string" ? item : item?.url ?? "").trim();
    if (!url) continue;
    if (!/^https:\/\//i.test(url)) continue; // drop ws:// wss:// and odd schemes
    if (/\$\{|<[A-Z_]+>|API_KEY|YOUR_KEY|INSERT|REPLACE/i.test(url)) continue; // placeholders
    const key = url.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      url,
      tracking: typeof item === "object" && item?.tracking ? item.tracking : "unknown",
      source: "chainlist",
    });
  }
  return out;
}

async function loadRegistry(): Promise<Map<number, RpcEndpoint[]>> {
  if (cache && Date.now() - cache.at < CACHE_TTL_MS) return cache.chains;
  if (inflight) return inflight;

  inflight = (async () => {
    const res = await fetch(CHAINLIST_URL, {
      signal: AbortSignal.timeout(25_000),
      headers: { accept: "application/json" },
    });
    if (!res.ok) throw new Error(`chainlist HTTP ${res.status}`);
    const data = (await res.json()) as ChainlistChain[];
    if (!Array.isArray(data)) throw new Error("chainlist payload is not an array");

    const map = new Map<number, RpcEndpoint[]>();
    for (const c of data) {
      if (typeof c.chainId !== "number" || !Array.isArray(c.rpc)) continue;
      const eps = normalize(c.rpc);
      if (eps.length) map.set(c.chainId, eps);
    }
    cache = { at: Date.now(), chains: map };
    return map;
  })();

  try {
    return await inflight;
  } finally {
    inflight = null;
  }
}

export async function GET(req: NextRequest) {
  const chainIdRaw = req.nextUrl.searchParams.get("chainId");
  const allParam = req.nextUrl.searchParams.get("ids");

  try {
    const registry = await loadRegistry();

    // Single chain
    if (chainIdRaw) {
      const id = Number(chainIdRaw);
      if (!Number.isInteger(id) || id <= 0) {
        return NextResponse.json({ error: "invalid chainId" }, { status: 400 });
      }
      const eps = registry.get(id) ?? [];
      return NextResponse.json(
        { chainId: id, count: eps.length, endpoints: eps },
        { headers: { "cache-control": "public, max-age=1800, stale-while-revalidate=86400" } },
      );
    }

    // Multiple chains at once (batched to keep payload small)
    if (allParam) {
      const ids = allParam
        .split(",")
        .map((s) => Number(s.trim()))
        .filter((n) => Number.isInteger(n) && n > 0)
        .slice(0, 40);
      const out: Record<string, RpcEndpoint[]> = {};
      for (const id of ids) out[String(id)] = registry.get(id) ?? [];
      return NextResponse.json(
        { chains: out },
        { headers: { "cache-control": "public, max-age=1800, stale-while-revalidate=86400" } },
      );
    }

    return NextResponse.json({
      ok: true,
      registry: "chainlist.org/rpcs.json",
      chainsIndexed: registry.size,
      cachedAt: cache?.at ?? null,
    });
  } catch (e) {
    // Serve stale cache rather than failing outright
    if (cache && chainIdRaw) {
      const eps = cache.chains.get(Number(chainIdRaw)) ?? [];
      return NextResponse.json(
        { chainId: Number(chainIdRaw), count: eps.length, endpoints: eps, stale: true },
        { headers: { "cache-control": "public, max-age=60" } },
      );
    }
    console.error("rpcs proxy error:", e);
    return NextResponse.json(
      { error: "registry unavailable", endpoints: [] },
      { status: 503 },
    );
  }
}
