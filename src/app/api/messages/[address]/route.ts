import { NextRequest, NextResponse } from "next/server";
import { eq, or, desc } from "drizzle-orm";
import { db } from "@/db";
import { messages } from "@/db/schema";

export async function GET(req: NextRequest, ctx: { params: Promise<{ address: string }> }) {
  const { address } = await ctx.params;
  const addr = address.trim().toLowerCase();
  if (!/^0x[0-9a-f]{40}$/.test(addr)) {
    return NextResponse.json({ error: "invalid address" }, { status: 400 });
  }
  const limit = Math.min(Number(req.nextUrl.searchParams.get("limit") ?? 500) || 500, 1000);
  const rows = await db
    .select()
    .from(messages)
    .where(or(eq(messages.sender, addr), eq(messages.recipient, addr)))
    .orderBy(desc(messages.createdAt), desc(messages.id))
    .limit(limit);
  return NextResponse.json(rows.map((r) => ({ ...r, amountWei: r.amountWei.toString() })));
}
