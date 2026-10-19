import { NextRequest, NextResponse } from "next/server";
import { computeAddress } from "ethers";
import { db } from "@/db";
import { messages } from "@/db/schema";
import { verifyApiAuth, isValidCompressedPub } from "@/lib/crypto";

interface Body {
  sender?: string;
  recipient?: string;
  chainId?: number;
  txHash?: string | null;
  amountWei?: string;
  tokenSymbol?: string;
  kind?: string;
  nonce?: string;
  ciphertext?: string;
  senderPub?: string;
  mode?: number;
  fileId?: string | null;
  status?: string;
}

const VALID_KINDS = new Set(["text", "file", "voice", "videonote"]);

export async function POST(req: NextRequest) {
  try {
    const b = (await req.json()) as Body;
    const sender = (b.sender ?? "").toLowerCase();
    const recipient = (b.recipient ?? "").toLowerCase();
    if (!/^0x[0-9a-f]{40}$/.test(sender) || !/^0x[0-9a-f]{40}$/.test(recipient)) {
      return NextResponse.json({ error: "invalid addresses" }, { status: 400 });
    }
    if (!b.ciphertext || !b.nonce || !b.senderPub) {
      return NextResponse.json({ error: "missing envelope" }, { status: 400 });
    }
    if (b.ciphertext.length > 250_000 || b.nonce.length > 64) {
      return NextResponse.json({ error: "envelope too large" }, { status: 400 });
    }

    // Verify EIP-191 auth header if present
    const sigHeader = req.headers.get("x-obsidian-signature");
    if (sigHeader) {
      const auth = verifyApiAuth(
        req.headers.get("x-obsidian-address"),
        req.headers.get("x-obsidian-timestamp"),
        sigHeader,
        "message:send",
      );
      if (!auth.valid || auth.address !== sender) {
        return NextResponse.json({ error: auth.error ?? "unauthorized sender" }, { status: 401 });
      }
    }

    // Mathematical check: if senderPub is a full 33-byte compressed key, verify it derives `sender`
    if (isValidCompressedPub(b.senderPub)) {
      try {
        if (computeAddress(b.senderPub).toLowerCase() !== sender) {
          return NextResponse.json({ error: "senderPub does not match sender address" }, { status: 403 });
        }
      } catch {
        return NextResponse.json({ error: "invalid senderPub" }, { status: 400 });
      }
    }

    const status = ["pending", "confirmed", "failed", "relay"].includes(b.status ?? "") ? b.status! : "pending";
    const kind = VALID_KINDS.has(b.kind ?? "") ? b.kind! : "text";
    const mode = b.mode === 2 ? 2 : b.mode === 3 ? 3 : 1;

    const inserted = await db
      .insert(messages)
      .values({
        sender,
        recipient,
        chainId: Number.isInteger(b.chainId) ? b.chainId! : 1,
        txHash: b.txHash ?? null,
        amountWei: /^\d+$/.test(b.amountWei ?? "0") ? BigInt(b.amountWei!) : 0n,
        tokenSymbol: (b.tokenSymbol ?? "ETH").slice(0, 12),
        kind,
        nonce: b.nonce!,
        ciphertext: b.ciphertext!,
        senderPub: b.senderPub!.slice(0, 70),
        mode,
        fileId: b.fileId ?? null,
        status,
      })
      .returning({ id: messages.id });
    return NextResponse.json({ id: inserted[0].id }, { status: 201 });
  } catch (e) {
    console.error("messages POST", e);
    return NextResponse.json({ error: "internal error" }, { status: 500 });
  }
}
