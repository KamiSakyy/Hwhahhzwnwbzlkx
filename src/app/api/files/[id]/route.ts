import { NextRequest, NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { files } from "@/db/schema";

export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  if (!/^[0-9a-f]{16,64}$/.test(id)) {
    return NextResponse.json({ error: "bad id" }, { status: 400 });
  }
  const rows = await db.select().from(files).where(eq(files.id, id));
  const row = rows[0];
  if (!row || !row.data) return NextResponse.json({ error: "not found" }, { status: 404 });
  const buf = Buffer.from(row.data, "base64");
  return new NextResponse(new Uint8Array(buf), {
    headers: {
      "content-type": "application/octet-stream",
      "content-length": String(buf.length),
      "content-disposition": `inline; filename="${(row.name ?? "file").replace(/"/g, "")}"`,
    },
  });
}
