import { signApiAuth } from "./crypto";

/** JSON API client with optional EIP-191 cryptographic signature headers. */

async function req<T>(path: string, init?: RequestInit, auth?: { privateKey: string; action: string }): Promise<T> {
  const authHeaders = auth ? await signApiAuth(auth.privateKey, auth.action) : {};
  const res = await fetch(path, {
    ...init,
    headers: {
      "content-type": "application/json",
      ...authHeaders,
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = (await res.json()) as { error?: string };
      if (j.error) msg = j.error;
    } catch {
      /* noop */
    }
    throw new Error(msg);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export interface WalletRow {
  address: string;
  label: string | null;
  pubkey: string | null;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string;
}

export type MsgKind = "text" | "file" | "voice" | "videonote";

export interface MessageRow {
  id: number;
  sender: string;
  recipient: string;
  chainId: number;
  txHash: string | null;
  amountWei: string;
  tokenSymbol: string;
  kind: MsgKind;
  nonce: string;
  ciphertext: string;
  senderPub: string;
  mode: number;
  fileId: string | null;
  status: "pending" | "confirmed" | "failed" | "relay";
  createdAt: string;
}

export interface NewMessageInput {
  sender: string;
  recipient: string;
  chainId: number;
  txHash?: string | null;
  amountWei?: string;
  tokenSymbol?: string;
  kind: MsgKind;
  nonce: string;
  ciphertext: string;
  senderPub: string;
  mode?: number;
  fileId?: string | null;
  status: "pending" | "confirmed" | "failed" | "relay";
}

export const api = {
  upsertWallet: (
    w: { address: string; label?: string | null; pubkey?: string | null },
    privateKey?: string,
  ) =>
    req<WalletRow>(
      "/api/wallets",
      { method: "POST", body: JSON.stringify(w) },
      privateKey ? { privateKey, action: "wallet:upsert" } : undefined,
    ),
  getWallet: (address: string) => req<WalletRow>(`/api/wallets/${encodeURIComponent(address)}`),
  searchWallets: (q: string) => req<WalletRow[]>(`/api/wallets?search=${encodeURIComponent(q)}`),
  sendMessage: (m: NewMessageInput, privateKey?: string) =>
    req<{ id: number }>(
      "/api/messages",
      { method: "POST", body: JSON.stringify(m) },
      privateKey ? { privateKey, action: "message:send" } : undefined,
    ),
  getMessages: (address: string, limit = 500) =>
    req<MessageRow[]>(`/api/messages/${encodeURIComponent(address)}?limit=${limit}`),
  setMessageStatus: (
    id: number,
    status: "confirmed" | "failed" | "relay" | "pending",
    privateKey?: string,
  ) =>
    req<{ ok: boolean }>(
      "/api/messages/status",
      { method: "POST", body: JSON.stringify({ id, status }) },
      privateKey ? { privateKey, action: `message:status:${id}` } : undefined,
    ),
  uploadFile: async (
    id: string,
    blob: Blob,
    meta: { name: string; mimeType: string; size: number; messageId?: string },
    privateKey?: string,
  ) => {
    const form = new FormData();
    form.append("id", id);
    form.append("file", blob, meta.name);
    form.append("name", meta.name);
    form.append("mimeType", meta.mimeType);
    form.append("size", String(meta.size));
    if (meta.messageId) form.append("messageId", meta.messageId);
    const authHeaders = privateKey ? await signApiAuth(privateKey, `file:upload:${id}`) : {};
    const res = await fetch("/api/files", {
      method: "POST",
      headers: authHeaders,
      body: form,
    });
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try {
        const j = (await res.json()) as { error?: string };
        if (j.error) msg = j.error;
      } catch {
        /* noop */
      }
      throw new Error(msg);
    }
    return (await res.json()) as { id: string };
  },
  fetchFile: async (id: string) => {
    const res = await fetch(`/api/files/${encodeURIComponent(id)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.arrayBuffer();
  },
};
