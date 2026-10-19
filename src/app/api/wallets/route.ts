import { NextRequest, NextResponse } from "next/server";
import { eq, ilike, and, sql, desc } from "drizzle-orm";
import { computeAddress } from "ethers";
import { db } from "@/db";
import { wallets } from "@/db/schema";
import { verifyApiAuth, isValidCompressedPub } from "@/lib/crypto";

const norm = (a: string) => a.trim().toLowerCase();
const isAddr = (a: string) => /^0x[0-9a-f]{40}$/.test(a);

export async function POST(req: NextRequest) {
  try {
    const body = (await req.json()) as { address?: string; label?: string | null; pubkey?: string | null };
    const address = norm(body.address ?? "");
    if (!isAddr(address)) return NextResponse.json({ error: "invalid address" }, { status: 400 });

    // Verify EIP-191 signature if provided
    const sigHeader = req.headers.get("x-obsidian-signature");
    if (sigHeader) {
      const auth = verifyApiAuth(
        req.headers.get("x-obsidian-address"),
        req.headers.get("x-obsidian-timestamp"),
        sigHeader,
        "wallet:upsert",
      );
      if (!auth.valid || auth.address !== address) {
        return NextResponse.json({ error: auth.error ?? "unauthorized" }, { status: 401 });
      }
    }

    const label = typeof body.label === "string" ? body.label.replace(/[<>]/g, "").slice(0, 60) : null;
    let pubkey: string | null = null;
    if (typeof body.pubkey === "string" && body.pubkey.startsWith("0x")) {
      const candidate = body.pubkey.slice(0, 70);
      // Mathematical verification: if a full 33-byte compressed secp256k1 pubkey is given,
      // verify that its derived Ethereum address matches `address` (prevents MITM key spoofing!)
      if (isValidCompressedPub(candidate)) {
        try {
          if (computeAddress(candidate).toLowerCase() !== address) {
            return NextResponse.json({ error: "pubkey does not match wallet address" }, { status: 403 });
          }
          pubkey = candidate.toLowerCase();
        } catch {
          return NextResponse.json({ error: "invalid secp256k1 pubkey" }, { status: 400 });
        }
      } else {
        pubkey = candidate;
      }
    }

    const existing = await db.select().from(wallets).where(eq(wallets.address, address));
    const row = existing[0];
    if (row) {
      await db
        .update(wallets)
        .set({
          label: label ?? row.label,
          pubkey: pubkey ?? row.pubkey,
          updatedAt: new Date(),
          lastSeenAt: new Date(),
        })
        .where(eq(wallets.address, address));
      const fresh = await db.select().from(wallets).where(eq(wallets.address, address));
      return NextResponse.json(fresh[0]);
    }
    const inserted = await db
      .insert(wallets)
      .values({ address, label, pubkey })
      .returning();
    return NextResponse.json(inserted[0], { status: 201 });
  } catch (e) {
    console.error("wallets POST", e);
    return NextResponse.json({ error: "internal error" }, { status: 500 });
  }
}

export async function GET(req: NextRequest) {
  try {
    const q = (req.nextUrl.searchParams.get("search") ?? "").trim();
    let rows;
    if (!q) {
      rows = await db.select().from(wallets).orderBy(desc(wallets.lastSeenAt)).limit(25);
    } else if (isAddr(q.toLowerCase())) {
      rows = await db.select().from(wallets).where(eq(wallets.address, q.toLowerCase())).limit(20);
    } else {
      rows = await db
        .select()
        .from(wallets)
        .where(and(ilike(wallets.label, `%${q}%`), sql`label IS NOT NULL`))
        .limit(20);
    }
    return NextResponse.json(rows);
  } catch (e) {
    console.error("wallets GET", e);
    return NextResponse.json({ error: "internal error" }, { status: 500 });
  }
}
