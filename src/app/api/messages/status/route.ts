import { NextRequest, NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { messages } from "@/db/schema";
import { verifyApiAuth } from "@/lib/crypto";

export async function POST(req: NextRequest) {
  try {
    const b = (await req.json()) as { id?: number; status?: string; txHash?: string };
    if (!Number.isInteger(b.id) || !["pending", "confirmed", "failed", "relay"].includes(b.status ?? "")) {
      return NextResponse.json({ error: "bad request" }, { status: 400 });
    }

    const existing = await db.select().from(messages).where(eq(messages.id, b.id!));
    if (!existing[0]) {
      return NextResponse.json({ error: "message not found" }, { status: 404 });
    }

    const sigHeader = req.headers.get("x-obsidian-signature");
    if (sigHeader) {
      const auth = verifyApiAuth(
        req.headers.get("x-obsidian-address"),
        req.headers.get("x-obsidian-timestamp"),
        sigHeader,
        `message:status:${b.id}`,
      );
      if (
        !auth.valid ||
        (auth.address !== existing[0].sender.toLowerCase() &&
          auth.address !== existing[0].recipient.toLowerCase())
      ) {
        return NextResponse.json({ error: "unauthorized status update" }, { status: 401 });
      }
    }

    await db
      .update(messages)
      .set({
        status: b.status!,
        ...(b.txHash ? { txHash: b.txHash } : {}),
      })
      .where(eq(messages.id, b.id!));
    return NextResponse.json({ ok: true });
  } catch (e) {
    console.error("messages status", e);
    return NextResponse.json({ error: "internal error" }, { status: 500 });
  }
}
