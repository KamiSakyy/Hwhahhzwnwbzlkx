import { db } from "@/db";
import { sql } from "drizzle-orm";

export const dynamic = "force-dynamic";

/**
 * Liveness probe.
 *
 * IMPORTANT: this endpoint must report the health of the *web application*,
 * not of the database. If it returned 500 whenever Postgres was unreachable
 * (cold start, preview sandbox without a DB, transient outage), the platform
 * healthcheck would conclude the app failed to start and the preview would
 * never come up — even though the wallet itself is fully functional because
 * every chain operation runs client-side.
 *
 * Database reachability is reported as informational metadata only.
 */
export async function GET() {
  let database: "up" | "down" = "down";

  try {
    await Promise.race([
      db.execute(sql`select 1`),
      new Promise((_, reject) => setTimeout(() => reject(new Error("db timeout")), 2500)),
    ]);
    database = "up";
  } catch {
    database = "down";
  }

  return Response.json(
    {
      ok: true,
      service: "obsidian",
      version: "2.0",
      database,
      timestamp: new Date().toISOString(),
    },
    {
      status: 200,
      headers: { "cache-control": "no-store" },
    },
  );
}
