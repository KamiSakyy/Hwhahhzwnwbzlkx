import {
  pgTable,
  text,
  integer,
  bigint,
  timestamp,
  index,
} from "drizzle-orm/pg-core";

/**
 * Obsidian — self-custody wallet + on-chain E2E messenger.
 *
 * Security model:
 *  - Private keys NEVER touch the server. They live in an AES-256-GCM
 *    keystore (PBKDF2, 310k iters) in the user's browser only.
 *  - `pubkey` is the wallet's compressed secp256k1 public key — safe to
 *    expose, needed for ECDH-based end-to-end encryption.
 *  - Message payloads are stored ONLY as ciphertext (AES-256-GCM, key from
 *    ECDH). The server cannot read messages.
 *  - File attachments are client-side encrypted before upload; `data` holds
 *    opaque base64 ciphertext (iv‖ct, AES-256-GCM).
 */

export const wallets = pgTable("wallets", {
  address: text("address").primaryKey(),
  label: text("label"),
  pubkey: text("pubkey"),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
  lastSeenAt: timestamp("last_seen_at", { withTimezone: true }).defaultNow(),
});

export const messages = pgTable(
  "messages",
  {
    id: bigint("id", { mode: "number" })
      .primaryKey()
      .generatedAlwaysAsIdentity(),
    sender: text("sender").notNull(),
    recipient: text("recipient").notNull(),
    chainId: integer("chain_id").notNull().default(1),
    txHash: text("tx_hash"),
    amountWei: bigint("amount_wei", { mode: "bigint" }).notNull(),
    tokenSymbol: text("token_symbol").notNull().default("ETH"),
    kind: text("kind").notNull().default("text"), // 'text' | 'file'
    // E2E envelope (AES-256-GCM)
    nonce: text("nonce").notNull(), // base64(12-byte IV)
    ciphertext: text("ciphertext").notNull(), // base64(ct+tag)
    senderPub: text("sender_pub").notNull(), // compressed secp256k1 pubkey (hex)
    mode: integer("mode").notNull().default(1), // 1 = ECDH E2E, 2 = address-bound
    fileId: text("file_id"),
    // 'pending' (tx broadcast, awaiting receipt) | 'confirmed' | 'failed' | 'relay' (chain tx unavailable, delivered via Obsidian relay index)
    status: text("status").notNull().default("pending"),
    createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
  },
  (t) => [
    index("idx_messages_recipient").on(t.recipient, t.createdAt),
    index("idx_messages_sender").on(t.sender, t.createdAt),
  ],
);

export const files = pgTable("files", {
  id: text("id").primaryKey(), // client-generated 16-byte hex, doubles as E2E file key label
  messageId: text("message_id"),
  name: text("name"),
  mimeType: text("mime_type"),
  size: integer("size"),
  data: text("data"), // base64 of client-side encrypted blob
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
});
