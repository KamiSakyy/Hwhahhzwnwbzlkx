import { NextRequest, NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { wallets } from "@/db/schema";

export async function GET(_req: NextRequest, ctx: { params: Promise<{ address: string }> }) {
  const { address } = await ctx.params;
  const addr = address.trim().toLowerCase();
  if (!/^0x[0-9a-f]{40}$/.test(addr)) {
    return NextResponse.json({ error: "invalid address" }, { status: 400 });
  }
  const rows = await db.select().from(wallets).where(eq(wallets.address, addr));
  if (!rows[0]) return NextResponse.json({ error: "not found" }, { status: 404 });
  return NextResponse.json(rows[0]);
}
