import { secp256k1 } from "@noble/curves/secp256k1";
import { keccak_256 } from "@noble/hashes/sha3";
import { Wallet, verifyMessage, hexlify, getBytes } from "ethers";
import { idbGet, idbSet, idbDelete, clearBiometricUnlock } from "./vault";

/**
 * Obsidian E2E Cryptographic Engine v2 (2026 Audit-Hardened)
 * ----------------------------------------------------------
 * Key modes:
 *  - MODE_E2E (1): Classic ECDH on secp256k1 using the PEER's compressed
 *    public key (from directory, contact card, or recovered directly from
 *    any on-chain transaction signature via ecrecover!):
 *      dk = keccak256( compressed( privMine · PubPeer ) )
 *  - MODE_PPSK (3): Pre-Shared Passphrase E2E (quantum-resistant symmetric
 *    mixing for 0-tx cold wallets before they publish a public key on-chain).
 *  - MODE_AB (2): Introductory Handshake envelope (carries sender's 33B
 *    compressed pubkey on-chain so the recipient immediately has `senderPub`
 *    to reply in full MODE_E2E).
 *
 * On-chain message envelope (tx `data`), self-describing:
 *   magic 4B "nxMS" | version 1B | mode 1B | senderPub 33B | iv 12B | ct+tag
 */

export const MSG_MAGIC = [0x6e, 0x78, 0x4d, 0x53]; // 'n' 'x' 'M' 'S'
export const MODE_E2E = 1;
export const MODE_AB = 2; // Introductory handshake
export const MODE_PPSK = 3; // Pre-shared passphrase E2E
const HDR = 4 + 1 + 1 + 33 + 12; // 51 bytes

const enc = new TextEncoder();
const dec = new TextDecoder();

// ---------- base64 (chunk-safe) ----------
export function toB64(u8: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u8.length; i += 0x8000) {
    s += String.fromCharCode(...u8.subarray(i, i + 0x8000));
  }
  return btoa(s);
}
export function fromB64(s: string): Uint8Array {
  const bin = atob(s);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}

function toBuf(u8: Uint8Array): ArrayBuffer {
  const ab = new ArrayBuffer(u8.byteLength);
  new Uint8Array(ab).set(u8);
  return ab;
}

// ---------- keys ----------
export function normHex(h: string): string {
  return h.startsWith("0x") ? h.toLowerCase() : `0x${h.toLowerCase()}`;
}

export function privBytes(privHex: string): Uint8Array {
  return getBytes(normHex(privHex));
}

export function compressedPubFromPriv(privHex: string): string {
  const priv = privBytes(privHex);
  try {
    return hexlify(secp256k1.getPublicKey(priv, true));
  } finally {
    priv.fill(0);
  }
}

export function isValidCompressedPub(pubHex: string | null | undefined): boolean {
  if (!pubHex) return false;
  try {
    const b = getBytes(normHex(pubHex));
    return b.length === 33 && (b[0] === 0x02 || b[0] === 0x03);
  } catch {
    return false;
  }
}

/** ECDH: keccak256 of the compressed shared point (32-byte AES key). */
export function ecdhKey(privHex: string, peerPubCompressedHex: string): Uint8Array {
  const priv = privBytes(privHex);
  try {
    const point = secp256k1.getSharedSecret(
      priv,
      getBytes(normHex(peerPubCompressedHex)),
      true,
    );
    return keccak_256(point);
  } finally {
    priv.fill(0);
  }
}

/** Pre-shared passphrase key (MODE_PPSK = 3) for 0-tx wallets. */
export function ppskKeyFor(sender: string, recipient: string, passphrase: string): Uint8Array {
  const [a, b] = [sender.toLowerCase(), recipient.toLowerCase()].sort();
  return keccak_256(enc.encode(`obsidian:ppsk:v2:${a}:${b}:${passphrase.trim()}`));
}

export function fileKeyFor(dk: Uint8Array, fileId: string): Uint8Array {
  return keccak_256(concat(dk, enc.encode(`obsidian-file:${fileId}`)));
}

/** Introductory Handshake key (MODE_AB = 2). */
export function abKeyFor(sender: string, recipient: string, chainId: number): Uint8Array {
  const [a, b] = [sender.toLowerCase(), recipient.toLowerCase()].sort();
  return keccak_256(enc.encode(`obsidian:ab:${a}:${b}:${chainId}`));
}

/**
 * Safety Number & Emoji Fingerprint (Telegram Secret Chat / Signal style)
 * Allows two peers to verify their ECDH public keys out-of-band.
 */
const SAFETY_EMOJIS = [
  "🛡️", "🔑", "⚡", "💎", "🛰️", "🔮", "🧬", "🪐",
  "🦅", "🐉", "🔥", "🌊", "🏔️", "🌙", "☀️", "⭐",
  "🎯", "🧭", "⚓", "👑", "🗡️", "🏛️", "🚀", "🛸",
  "🦊", "🐺", "🦁", "🦈", "🍀", "🌿", "❄️", "☄️",
];

export function computeSafetyFingerprint(
  addrA: string,
  pubA: string | null | undefined,
  addrB: string,
  pubB: string | null | undefined,
): { digits: string; emojis: string[]; verifiedE2E: boolean } {
  const pA = isValidCompressedPub(pubA) ? normHex(pubA!) : addrA.toLowerCase();
  const pB = isValidCompressedPub(pubB) ? normHex(pubB!) : addrB.toLowerCase();
  const [first, second] = [ `${addrA.toLowerCase()}:${pA}`, `${addrB.toLowerCase()}:${pB}` ].sort();
  const digest = keccak_256(enc.encode(`obsidian:safety:v1:${first}|${second}`));
  const groups: string[] = [];
  for (let i = 0; i < 4; i++) {
    const val = ((digest[i * 2] << 8) | digest[i * 2 + 1]) % 10000;
    groups.push(String(val).padStart(4, "0"));
  }
  const emojis = [
    SAFETY_EMOJIS[digest[8] % SAFETY_EMOJIS.length],
    SAFETY_EMOJIS[digest[9] % SAFETY_EMOJIS.length],
    SAFETY_EMOJIS[digest[10] % SAFETY_EMOJIS.length],
    SAFETY_EMOJIS[digest[11] % SAFETY_EMOJIS.length],
  ];
  return {
    digits: groups.join(" "),
    emojis,
    verifiedE2E: isValidCompressedPub(pubA) && isValidCompressedPub(pubB),
  };
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const n = parts.reduce((s, p) => s + p.length, 0);
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

// ---------- AES-256-GCM ----------
async function aesKey(dk: Uint8Array, usage: "encrypt" | "decrypt"): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", toBuf(dk), "AES-GCM", false, [usage]);
}

export function aadFor(sender: string, recipient: string, chainId: number): Uint8Array {
  return enc.encode(`${sender.toLowerCase()}:${recipient.toLowerCase()}:${chainId}`);
}

export interface Envelope {
  iv: Uint8Array;
  ct: Uint8Array; // ciphertext + 16-byte tag
}

export async function aesEncrypt(dk: Uint8Array, aad: Uint8Array, data: Uint8Array): Promise<Envelope> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await aesKey(dk, "encrypt");
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv: toBuf(iv), additionalData: toBuf(aad) }, key, toBuf(data)),
  );
  return { iv, ct };
}

export async function aesDecrypt(dk: Uint8Array, aad: Uint8Array, iv: Uint8Array, ct: Uint8Array): Promise<Uint8Array> {
  const key = await aesKey(dk, "decrypt");
  const pt = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: toBuf(iv), additionalData: toBuf(aad) },
    key,
    toBuf(ct),
  );
  return new Uint8Array(pt);
}

// ---------- on-chain envelope ----------
export function buildMsgCalldata(mode: number, senderPubHex: string, iv: Uint8Array, ct: Uint8Array): string {
  const pub = getBytes(normHex(senderPubHex));
  if (pub.length !== 33) throw new Error("bad sender pubkey");
  if (iv.length !== 12) throw new Error("bad iv");
  const out = new Uint8Array(HDR + ct.length);
  out.set(MSG_MAGIC, 0);
  out[4] = 1; // version
  out[5] = mode;
  out.set(pub, 6);
  out.set(iv, 39);
  out.set(ct, HDR);
  return hexlify(out);
}

export interface MsgEnvelopeParsed {
  version: number;
  mode: number;
  senderPub: string;
  iv: Uint8Array;
  ct: Uint8Array;
}

export function parseMsgCalldata(dataHex: string | null | undefined): MsgEnvelopeParsed | null {
  if (!dataHex || dataHex.length < HDR * 2 + 2) return null;
  let b: Uint8Array;
  try {
    b = getBytes(dataHex);
  } catch {
    return null;
  }
  for (let i = 0; i < 4; i++) if (b[i] !== MSG_MAGIC[i]) return null;
  return {
    version: b[4],
    mode: b[5],
    senderPub: hexlify(b.slice(6, 39)),
    iv: b.slice(39, 51),
    ct: b.slice(HDR),
  };
}

// ---------- payload JSON ----------
export interface MsgFileMeta {
  id: string;
  name: string;
  mime: string;
  size: number;
  duration?: number; // seconds for voice / videonote
  waveform?: number[]; // 32 normalized bars (0..100) for voice messages
  inlineB64?: string; // optional inline encrypted/raw media for serverless small voice/circle notes
}

export interface MsgPayload {
  v: 1;
  t: "text" | "file" | "voice" | "videonote";
  s?: string; // text or caption
  replyTo?: { id: number; text: string };
  f?: MsgFileMeta;
}

export async function encryptMsg(dk: Uint8Array, aad: Uint8Array, payload: MsgPayload): Promise<Envelope> {
  return aesEncrypt(dk, aad, enc.encode(JSON.stringify(payload)));
}

export async function decryptMsg(
  dk: Uint8Array,
  aad: Uint8Array,
  iv: Uint8Array,
  ct: Uint8Array,
): Promise<MsgPayload> {
  const pt = await aesDecrypt(dk, aad, iv, ct);
  const obj = JSON.parse(dec.decode(pt)) as MsgPayload;
  if (!obj || !["text", "file", "voice", "videonote"].includes(obj.t)) throw new Error("bad payload");
  return obj;
}

// ---------- IndexedDB Keystore Vault (PBKDF2 600k → AES-256-GCM) ----------
export interface KeystoreBlob {
  v: 2;
  storage: "indexeddb-vault";
  kdf: "pbkdf2-sha256";
  iter: number;
  salt: string; // b64
  iv: string; // b64
  ct: string; // b64
  createdAt: string;
}

const KS_ITER_V2 = 600_000; // OWASP 2026 hardened iteration count
export const KS_KEY = "obsidian.keystore.v1";
const IDB_KS_KEY = "primary_vault_v2";

// In-memory fallback for Node.js test environments where IndexedDB is not present
let memoryVaultFallback: KeystoreBlob | null = null;

async function deriveKey(password: string, salt: Uint8Array, iterations = KS_ITER_V2): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey("raw", toBuf(enc.encode(password)), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: toBuf(salt), iterations, hash: "SHA-256" },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

/**
 * Migrate any legacy localStorage keystore into IndexedDB and wipe localStorage.
 */
export async function migrateLegacyKeystore(): Promise<KeystoreBlob | null> {
  try {
    const existing = await idbGet<KeystoreBlob>("keystore", IDB_KS_KEY);
    if (existing) {
      if (typeof localStorage !== "undefined" && localStorage.getItem(KS_KEY)) {
        localStorage.removeItem(KS_KEY);
      }
      return existing;
    }
    if (typeof localStorage !== "undefined") {
      const raw = localStorage.getItem(KS_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as KeystoreBlob;
        const upgraded: KeystoreBlob = {
          ...parsed,
          v: 2,
          storage: "indexeddb-vault",
          createdAt: parsed.createdAt || new Date().toISOString(),
        };
        await idbSet("keystore", IDB_KS_KEY, upgraded);
        localStorage.removeItem(KS_KEY);
        return upgraded;
      }
    }
  } catch {
    /* ignore */
  }
  return memoryVaultFallback;
}

export async function createKeystore(payload: Record<string, string>, password: string): Promise<void> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt, KS_ITER_V2);
  const plainBytes = enc.encode(JSON.stringify(payload));
  try {
    const ct = new Uint8Array(
      await crypto.subtle.encrypt({ name: "AES-GCM", iv: toBuf(iv) }, key, toBuf(plainBytes)),
    );
    const blob: KeystoreBlob = {
      v: 2,
      storage: "indexeddb-vault",
      kdf: "pbkdf2-sha256",
      iter: KS_ITER_V2,
      salt: toB64(salt),
      iv: toB64(iv),
      ct: toB64(ct),
      createdAt: new Date().toISOString(),
    };
    memoryVaultFallback = blob;
    try {
      await idbSet("keystore", IDB_KS_KEY, blob);
    } catch {
      /* fallback in non-browser tests */
    }
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(KS_KEY);
    }
  } finally {
    plainBytes.fill(0);
  }
}

export async function unlockKeystore(password: string): Promise<Record<string, string> | null> {
  try {
    const blob = await migrateLegacyKeystore();
    if (!blob) return null;
    const key = await deriveKey(password, fromB64(blob.salt), blob.iter || 310_000);
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: toBuf(fromB64(blob.iv)) },
      key,
      toBuf(fromB64(blob.ct)),
    );
    const u8 = new Uint8Array(pt);
    try {
      return JSON.parse(dec.decode(u8));
    } finally {
      u8.fill(0);
    }
  } catch {
    return null;
  }
}

export async function keystoreExistsAsync(): Promise<boolean> {
  const blob = await migrateLegacyKeystore();
  return !!blob;
}

export function keystoreExists(): boolean {
  if (memoryVaultFallback) return true;
  if (typeof window === "undefined") return false;
  return !!localStorage.getItem(KS_KEY);
}

export async function deleteKeystore(): Promise<void> {
  memoryVaultFallback = null;
  await idbDelete("keystore", IDB_KS_KEY);
  await clearBiometricUnlock();
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem(KS_KEY);
  }
}

export async function reEncryptKeystore(oldPw: string, newPw: string): Promise<boolean> {
  const payload = await unlockKeystore(oldPw);
  if (!payload) return false;
  await createKeystore(payload, newPw);
  return true;
}

export function randomHex(bytes: number): string {
  return hexlify(crypto.getRandomValues(new Uint8Array(bytes)));
}

// ---------- EIP-191 Cryptographic API Authentication ----------
export interface SignedAuthHeaders {
  "x-obsidian-address": string;
  "x-obsidian-timestamp": string;
  "x-obsidian-signature": string;
}

export async function signApiAuth(privateKey: string, action: string): Promise<SignedAuthHeaders> {
  const wallet = new Wallet(privateKey);
  const address = wallet.address.toLowerCase();
  const timestamp = String(Date.now());
  const message = `OBSIDIAN-AUTH-V1:${address}:${timestamp}:${action}`;
  const signature = await wallet.signMessage(message);
  return {
    "x-obsidian-address": address,
    "x-obsidian-timestamp": timestamp,
    "x-obsidian-signature": signature,
  };
}

export function verifyApiAuth(
  addressHeader: string | null,
  timestampHeader: string | null,
  signatureHeader: string | null,
  action: string,
  maxSkewMs = 5 * 60 * 1000,
): { valid: boolean; address?: string; error?: string } {
  if (!addressHeader || !timestampHeader || !signatureHeader) {
    return { valid: false, error: "missing auth headers" };
  }
  const addr = addressHeader.trim().toLowerCase();
  if (!/^0x[0-9a-f]{40}$/.test(addr)) {
    return { valid: false, error: "invalid auth address" };
  }
  const ts = Number(timestampHeader);
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > maxSkewMs) {
    return { valid: false, error: "expired auth timestamp" };
  }
  try {
    const message = `OBSIDIAN-AUTH-V1:${addr}:${timestampHeader}:${action}`;
    const recovered = verifyMessage(message, signatureHeader).toLowerCase();
    if (recovered !== addr) {
      return { valid: false, error: "signature mismatch" };
    }
    return { valid: true, address: recovered };
  } catch {
    return { valid: false, error: "invalid signature" };
  }
}
