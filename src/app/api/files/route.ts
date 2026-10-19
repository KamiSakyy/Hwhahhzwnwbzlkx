import { NextRequest, NextResponse } from "next/server";
import { db } from "@/db";
import { files } from "@/db/schema";

const MAX = 40 * 1024 * 1024; // 40 MB of ciphertext

/**
 * Uploads hold *client-side encrypted* blobs only — the server stores
 * opaque bytes and can never decrypt them (the key derives from ECDH
 * between the two wallet keypairs).
 */
export async function POST(req: NextRequest) {
  try {
    const form = await req.formData();
    const id = String(form.get("id") ?? "");
    const file = form.get("file");
    const name = String(form.get("name") ?? "file");
    const mimeType = String(form.get("mimeType") ?? "application/octet-stream");
    if (!/^[0-9a-f]{16,64}$/.test(id)) {
      return NextResponse.json({ error: "bad file id" }, { status: 400 });
    }
    if (!(file instanceof Blob) || file.size === 0 || file.size > MAX) {
      return NextResponse.json({ error: "file too large or missing" }, { status: 413 });
    }
    const buf = Buffer.from(await file.arrayBuffer());
    const b64 = buf.toString("base64");
    const messageId = form.get("messageId") ? String(form.get("messageId")) : null;
    try {
      await db.insert(files).values({
        id,
        messageId,
        name: name.slice(0, 200),
        mimeType: mimeType.slice(0, 80),
        size: file.size,
        data: b64,
      });
    } catch {
      return NextResponse.json({ error: "storage error" }, { status: 500 });
    }
    return NextResponse.json({ id }, { status: 201 });
  } catch (e) {
    console.error("files POST", e);
    return NextResponse.json({ error: "internal error" }, { status: 500 });
  }
}
