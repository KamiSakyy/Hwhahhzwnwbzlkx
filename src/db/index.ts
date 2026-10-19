import { drizzle, type NodePgDatabase } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as schema from "./schema";

/**
 * Lazy database client.
 *
 * The pool is created on FIRST QUERY, never at module import time.
 * This is critical for deployability:
 *  - `next build` imports every route module while "Collecting page data".
 *    A throw at import time (missing DATABASE_URL in a build sandbox) aborts
 *    the whole production build, leaving no `.next` output and a dead preview.
 *  - A missing/unreachable database must degrade the relay features only,
 *    never the wallet itself (all chain + crypto work is client-side).
 */

const globalForDb = globalThis as typeof globalThis & {
  __arenaNextJsPostgresqlPool?: Pool;
  __obsidianDb?: NodePgDatabase<typeof schema>;
};

function resolveConnectionString(): string {
  return (
    process.env.DATABASE_URL ||
    process.env.POSTGRES_URL ||
    "postgresql://postgres:postgres@127.0.0.1:5432/app_db"
  );
}

export function getPool(): Pool {
  if (!globalForDb.__arenaNextJsPostgresqlPool) {
    globalForDb.__arenaNextJsPostgresqlPool = new Pool({
      connectionString: resolveConnectionString(),
      max: 8,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 8_000,
    });
    // Never let an idle-client error crash the Node process (that would take
    // the entire wallet offline instead of degrading the relay index).
    globalForDb.__arenaNextJsPostgresqlPool.on("error", () => {
      /* swallowed: transient pool error, next query reconnects */
    });
  }
  return globalForDb.__arenaNextJsPostgresqlPool;
}

export function getDb(): NodePgDatabase<typeof schema> {
  if (!globalForDb.__obsidianDb) {
    globalForDb.__obsidianDb = drizzle(getPool(), { schema });
  }
  return globalForDb.__obsidianDb;
}

export const pool = new Proxy({} as Pool, {
  get(_target, prop) {
    const real = getPool() as unknown as Record<string | symbol, unknown>;
    const value = real[prop];
    return typeof value === "function" ? value.bind(real) : value;
  },
});

export const db = new Proxy({} as NodePgDatabase<typeof schema>, {
  get(_target, prop) {
    const real = getDb() as unknown as Record<string | symbol, unknown>;
    const value = real[prop];
    return typeof value === "function" ? value.bind(real) : value;
  },
});
