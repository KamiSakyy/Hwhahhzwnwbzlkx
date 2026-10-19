"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Wallet, parseUnits } from "ethers";
import {
  broadcast,
  explainError,
  providerFor,
  readNative,
  waitForTx,
  explorerAddr,
  explorerTx,
  recoverPubkeyFromChain,
  scanOnChainMessages,
} from "@/lib/chain";
import {
  aadFor,
  abKeyFor,
  ppskKeyFor,
  aesDecrypt,
  aesEncrypt,
  buildMsgCalldata,
  compressedPubFromPriv,
  computeSafetyFingerprint,
  decryptMsg,
  ecdhKey,
  encryptMsg,
  fileKeyFor,
  fromB64,
  isValidCompressedPub,
  MODE_E2E,
  MODE_AB,
  MODE_PPSK,
  randomHex,
  toB64,
  type MsgFileMeta,
  type MsgPayload,
} from "@/lib/crypto";
import { api, type MessageRow, type MsgKind } from "@/lib/api";
import { saveContact, idbGetAll, idbSet } from "@/lib/vault";
import { isAddress, shortAddr } from "@/lib/networks";
import { avatarStyle, type AppApi } from "@/lib/session";
import { Badge, Button, CopyBtn, Empty, Input, Modal, Spinner, fmtTime, fmtUnits } from "./ui";
import { EmojiPicker } from "./EmojiPicker";
import { haptic } from "@/lib/haptics";
import {
  IconBolt,
  IconCamera,
  IconCheck,
  IconChevronDown,
  IconChat,
  IconClip,
  IconExternal,
  IconFile,
  IconForward,
  IconImage,
  IconLock,
  IconMic,
  IconPause,
  IconPhone,
  IconPlay,
  IconPlus,
  IconRefresh,
  IconReply,
  IconSearch,
  IconSend,
  IconShield,
  IconSmilePlus,
  IconUserPlus,
  IconVideo,
  IconVideoCircle,
  IconX,
  LogoMark,
} from "./icons";

type Msg = {
  id: number;
  dir: "in" | "out";
  peer: string;
  chainId: number;
  kind: MsgKind;
  txHash: string | null;
  status: "pending" | "confirmed" | "failed" | "relay";
  amountWei: string;
  tokenSymbol: string;
  at: string;
  sender: string;
  recipient: string;
  senderPub: string;
  mode: number;
  nonce: string;
  ct: string;
  fileId: string | null;
};

type Dec = {
  kind?: MsgKind;
  text?: string;
  replyTo?: { id: number; text: string };
  file?: MsgFileMeta;
  err?: boolean;
};

const enc0 = new TextEncoder();
function fileAad(a: string, b: string, fileId: string): Uint8Array {
  return enc0.encode([a.toLowerCase(), b.toLowerCase()].sort().join(":") + ":" + fileId);
}
function toBlobPart(u8: Uint8Array): BlobPart {
  const ab = new ArrayBuffer(u8.byteLength);
  new Uint8Array(ab).set(u8);
  return ab;
}
function concatU(...parts: Uint8Array[]): Uint8Array {
  const n = parts.reduce((s, p) => s + p.length, 0);
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

const FEE_PRESETS = ["0", "0.0001", "0.0005", "0.001"];
const FILE_MAX: Record<string, number> = {
  image: 15 * 1024 * 1024,
  video: 35 * 1024 * 1024,
  other: 15 * 1024 * 1024,
};

function pickSupportedAudioMime(): string {
  if (typeof MediaRecorder === "undefined") return "";
  const candidates = [
    "audio/webm;codecs=opus",
    "audio/webm",
    "audio/mp4",
    "audio/ogg;codecs=opus",
  ];
  for (const c of candidates) {
    if (MediaRecorder.isTypeSupported(c)) return c;
  }
  return "";
}

function pickSupportedVideoMime(): string {
  if (typeof MediaRecorder === "undefined") return "";
  const candidates = [
    "video/webm;codecs=vp9,opus",
    "video/webm;codecs=vp8,opus",
    "video/webm",
    "video/mp4",
  ];
  for (const c of candidates) {
    if (MediaRecorder.isTypeSupported(c)) return c;
  }
  return "";
}

function StatusIcon({
  status,
  txHash,
  onOpen,
}: {
  status: Msg["status"];
  txHash: string | null;
  onOpen?: () => void;
}) {
  if (status === "pending")
    return (
      <span title="Broadcasting on-chain…" className="inline-flex items-center gap-1 text-dim">
        <Spinner size={11} />
      </span>
    );
  if (status === "confirmed")
    return txHash ? (
      <button onClick={onOpen} title="Confirmed on-chain — open in block explorer" className="text-acc">
        <IconCheck size={12} />
      </button>
    ) : (
      <IconCheck size={12} className="text-acc" />
    );
  if (status === "failed")
    return (
      <span title="On-chain transaction failed" className="text-bad">
        <IconX size={12} />
      </span>
    );
  return (
    <span title="Delivered via encrypted relay index" className="text-warn">
      <IconBolt size={12} />
    </span>
  );
}

export function MessagesView({ app }: { app: AppApi }) {
  const appRef = useRef(app);
  appRef.current = app;
  const { address, chain, refreshTick, contacts, t, chatTarget } = app;
  const me = address.toLowerCase();

  const [msgs, setMsgs] = useState<Msg[] | null>(null);
  const [dec, setDec] = useState<Map<number, Dec>>(new Map());
  const [active, setActive] = useState<string | null>(chatTarget ? chatTarget.toLowerCase() : null);
  const [text, setText] = useState("");
  const [replyTo, setReplyTo] = useState<{ id: number; text: string } | null>(null);
  const [file, setFile] = useState<{
    f: File;
    preview?: string;
    kind: MsgKind;
    duration?: number;
    waveform?: number[];
  } | null>(null);
  const [feeStr, setFeeStr] = useState("0");
  const [feeOpen, setFeeOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [busyStep, setBusyStep] = useState("");
  const [bal, setBal] = useState<bigint | null>(null);
  const [newOpen, setNewOpen] = useState(false);
  const [safetyOpen, setSafetyOpen] = useState(false);
  const [dirQuery, setDirQuery] = useState("");
  const [dirResults, setDirResults] = useState<{ address: string; label: string | null }[]>([]);
  const [urlMap, setUrlMap] = useState<Map<string, string>>(new Map());
  const [peerPubs, setPeerPubs] = useState<Map<string, string>>(new Map());
  const [scanningChain, setScanningChain] = useState(false);
  const [reactions, setReactions] = useState<Map<number, string[]>>(new Map());
  const [forwardModalMsg, setForwardModalMsg] = useState<Msg | null>(null);
  const [forwardTarget, setForwardTarget] = useState("");

  function toggleReaction(msgId: number, emoji: string) {
    haptic("light");
    setReactions((prev) => {
      const next = new Map(prev);
      const list = next.get(msgId) ?? [];
      if (list.includes(emoji)) {
        next.set(msgId, list.filter((e) => e !== emoji));
      } else {
        next.set(msgId, [...list, emoji]);
      }
      return next;
    });
  }

  function handleForwardTo(targetAddr: string) {
    if (!forwardModalMsg) return;
    haptic("medium");
    const d = dec.get(forwardModalMsg.id);
    const forwardText = `[Forwarded]: ${d?.text || (forwardModalMsg.kind === "voice" ? "🎤 Voice Note" : "📎 File")}`;
    setActive(targetAddr.toLowerCase());
    setText(forwardText);
    setForwardModalMsg(null);
    setForwardTarget("");
    app.toast("ok", t("Сообщение перенесено в чат для пересылки", "Message ready to forward in chat"));
  }

  // Voice recording state
  const [recVoice, setRecVoice] = useState(false);
  const [recVoiceSec, setRecVoiceSec] = useState(0);
  const [recWave, setRecWave] = useState<number[]>(Array(24).fill(18));
  const voiceRecorderRef = useRef<MediaRecorder | null>(null);
  const voiceChunksRef = useRef<Blob[]>([]);
  const voiceTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Circular Video Note ("Кружочек") state
  const [circleOpen, setCircleOpen] = useState(false);
  const [recCircle, setRecCircle] = useState(false);
  const [recCircleSec, setRecCircleSec] = useState(0);
  const [facingMode, setFacingMode] = useState<"user" | "environment">("user");
  const [usingSynthCam, setUsingSynthCam] = useState(false);
  const videoPreviewRef = useRef<HTMLVideoElement | null>(null);
  const circleStreamRef = useRef<MediaStream | null>(null);
  const circleRecorderRef = useRef<MediaRecorder | null>(null);
  const circleChunksRef = useRef<Blob[]>([]);
  const circleTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const synthAnimRef = useRef<number | null>(null);

  const urlRef = useRef(urlMap);
  urlRef.current = urlMap;
  const labelsRef = useRef<Map<string, string>>(new Map());
  const [, force] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const pub = useMemo(() => compressedPubFromPriv(app.session.privateKey), [app.session.privateKey]);
  const myPriv = app.session.privateKey;

  useEffect(() => {
    if (chatTarget) setActive(chatTarget.toLowerCase());
  }, [chatTarget]);

  /**
   * Resolves the shared AES-256-GCM key for any message `r` (incoming or outgoing).
   * - For `MODE_E2E`:
   *   - If `r` is incoming (`r.sender !== me`), uses `ecdhKey(myPriv, r.senderPub)`.
   *   - If `r` is outgoing (`r.sender === me`), uses `ecdhKey(myPriv, peerPub)`.
   * - For `MODE_PPSK`: uses the contact's `sharedSecretPhrase` via `ppskKeyFor`.
   * - For `MODE_AB`: uses `abKeyFor(r.sender, r.recipient, r.chainId)`.
   */
  const dkFor = useCallback(
    (r: { mode: number; senderPub: string; sender: string; recipient: string; chainId: number }) => {
      const s = r.sender.toLowerCase();
      const rec = r.recipient.toLowerCase();
      const peer = s === me ? rec : s;
      if (s === rec && s === me) {
        // Saved Messages (Self Vault)
        return ecdhKey(myPriv, pub);
      }
      if (r.mode === MODE_PPSK) {
        const c = contacts.find((x) => x.address === peer);
        return ppskKeyFor(s, rec, c?.sharedSecretPhrase || "obsidian");
      }
      if (r.mode === MODE_AB) {
        return abKeyFor(s, rec, r.chainId);
      }
      // MODE_E2E (1):
      if (s !== me && isValidCompressedPub(r.senderPub)) {
        return ecdhKey(myPriv, r.senderPub);
      }
      const contactPub = contacts.find((x) => x.address === peer)?.pubkey;
      const knownPeerPub = peerPubs.get(peer) || contactPub;
      if (knownPeerPub && isValidCompressedPub(knownPeerPub)) {
        return ecdhKey(myPriv, knownPeerPub);
      }
      return ecdhKey(myPriv, r.senderPub);
    },
    [myPriv, me, pub, contacts, peerPubs],
  );

  /* ---------- load messages (IndexedDB cache + Relay API + Direct Blockchain Scanner) ---------- */
  const load = useCallback(
    async (withChainScan = false) => {
      try {
        const [apiRows, cachedRows, chainRows] = await Promise.all([
          api.getMessages(me, 500).catch(() => [] as MessageRow[]),
          idbGetAll<MessageRow & { uid: string }>("onchain_msgs").catch(() => []),
          withChainScan ? scanOnChainMessages(appRef.current.chain, me) : Promise.resolve([] as MessageRow[]),
        ]);

        // Merge deduplicating by txHash or id
        const byKey = new Map<string, MessageRow>();
        for (const r of [...cachedRows, ...chainRows, ...apiRows]) {
          if (r.sender.toLowerCase() !== me && r.recipient.toLowerCase() !== me) continue;
          const key = r.txHash ? `tx:${r.txHash.toLowerCase()}` : `id:${r.id}`;
          byKey.set(key, r);
        }

        // Harvest any incoming senderPubs for instant E2E
        const newPubs = new Map<string, string>();
        for (const r of byKey.values()) {
          const s = r.sender.toLowerCase();
          if (s !== me && isValidCompressedPub(r.senderPub)) {
            newPubs.set(s, r.senderPub);
          }
        }
        if (newPubs.size > 0) {
          setPeerPubs((prev) => {
            const next = new Map(prev);
            for (const [k, v] of newPubs) next.set(k, v);
            return next;
          });
        }

        const merged = [...byKey.values()].sort(
          (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
        );

        setMsgs(
          merged.map((r) => ({
            id: r.id,
            dir: r.sender.toLowerCase() === me ? "out" : "in",
            peer: r.sender.toLowerCase() === me ? r.recipient.toLowerCase() : r.sender.toLowerCase(),
            chainId: r.chainId,
            kind: r.kind,
            txHash: r.txHash,
            status: r.status,
            amountWei: r.amountWei,
            tokenSymbol: r.tokenSymbol,
            at: r.createdAt,
            sender: r.sender.toLowerCase(),
            recipient: r.recipient.toLowerCase(),
            senderPub: r.senderPub,
            mode: r.mode ?? 1,
            nonce: r.nonce,
            ct: r.ciphertext,
            fileId: r.fileId,
          })),
        );
      } catch {
        setMsgs([]);
      }
    },
    [me],
  );

  useEffect(() => {
    load(app.prefs.preferPureOnChain);
  }, [load, refreshTick, app.prefs.preferPureOnChain]);

  useEffect(() => {
    const timer = setInterval(() => load(false), 10_000);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => {
    readNative(chain, address)
      .then((r) => setBal(r.raw))
      .catch(() => setBal(0n));
  }, [chain, address]);

  /* ---------- auto-discover active peer's secp256k1 pubkey ---------- */
  useEffect(() => {
    if (!active || active === me) return;
    if (peerPubs.has(active)) return;
    const contact = contacts.find((c) => c.address === active);
    if (contact?.pubkey && isValidCompressedPub(contact.pubkey)) {
      setPeerPubs((prev) => new Map(prev).set(active, contact.pubkey!));
      return;
    }
    let alive = true;
    (async () => {
      const w = await api.getWallet(active).catch(() => null);
      let found = w?.pubkey && isValidCompressedPub(w.pubkey) ? w.pubkey : null;
      if (!found) {
        found = await recoverPubkeyFromChain(chain, active);
      }
      if (alive && found && isValidCompressedPub(found)) {
        setPeerPubs((prev) => new Map(prev).set(active, found!));
      }
    })();
    return () => {
      alive = false;
    };
  }, [active, me, peerPubs, contacts, chain]);

  /* ---------- decrypt payloads ---------- */
  useEffect(() => {
    let alive = true;
    (async () => {
      if (!msgs) return;
      const m = new Map<number, Dec>();
      for (const r of msgs) {
        try {
          const aad = aadFor(r.sender, r.recipient, r.chainId);
          const p = await decryptMsg(dkFor(r), aad, fromB64(r.nonce), fromB64(r.ct));
          m.set(r.id, {
            kind: p.t,
            text: p.s,
            replyTo: p.replyTo,
            file: p.f,
          });
          // If inline media is embedded inside the encrypted payload, hydrate its Blob URL immediately!
          if (p.f?.inlineB64 && !urlRef.current.has(p.f.id)) {
            const bytes = fromB64(p.f.inlineB64);
            const url = URL.createObjectURL(new Blob([toBlobPart(bytes)], { type: p.f.mime }));
            setUrlMap((prev) => new Map(prev).set(p.f!.id, url));
          }
        } catch {
          m.set(r.id, { err: true });
        }
      }
      if (alive) setDec(m);
    })();
    return () => {
      alive = false;
    };
  }, [msgs, dkFor]);

  /* ---------- peer labels from directory ---------- */
  useEffect(() => {
    if (!msgs) return;
    let alive = true;
    (async () => {
      const peers = [...new Set(msgs.map((m) => m.peer))].slice(0, 25);
      for (const p of peers) {
        if (labelsRef.current.has(p)) continue;
        try {
          const w = await api.getWallet(p);
          if (w.label && !labelsRef.current.has(p)) labelsRef.current.set(p, w.label);
          if (w.pubkey && isValidCompressedPub(w.pubkey)) {
            setPeerPubs((prev) => (prev.has(p) ? prev : new Map(prev).set(p, w.pubkey!)));
          }
        } catch {
          /* external address */
        }
      }
      if (alive) force((x) => x + 1);
    })();
    return () => {
      alive = false;
    };
  }, [msgs]);

  /* ---------- media decryption for open chat ---------- */
  useEffect(() => {
    if (!active || !msgs) return;
    let alive = true;
    (async () => {
      for (const r of msgs) {
        const d = dec.get(r.id);
        if (r.peer === active && d?.file && !urlRef.current.has(d.file.id)) {
          try {
            const buf = new Uint8Array(await api.fetchFile(d.file.id));
            const fdk = fileKeyFor(dkFor(r), d.file.id);
            const pt = await aesDecrypt(fdk, fileAad(r.sender, r.recipient, d.file.id), buf.slice(0, 12), buf.slice(12));
            const url = URL.createObjectURL(new Blob([toBlobPart(pt)], { type: d.file.mime }));
            if (alive) {
              setUrlMap((prev) => {
                const n = new Map(prev);
                n.set(d.file!.id, url);
                return n;
              });
            }
          } catch {
            /* file unavailable */
          }
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, [active, dec, msgs, dkFor]);

  /* ---------- derived ---------- */
  const convs = useMemo(() => {
    if (!msgs) return null;
    const map = new Map<string, { peer: string; last: Msg; count: number }>();
    for (const m of msgs) {
      const c = map.get(m.peer);
      if (!c) map.set(m.peer, { peer: m.peer, last: m, count: 1 });
      else {
        c.count++;
        if (new Date(m.at) > new Date(c.last.at)) c.last = m;
      }
    }
    return [...map.values()].sort((a, b) => new Date(b.last.at).getTime() - new Date(a.last.at).getTime());
  }, [msgs]);

  const chat = useMemo(
    () =>
      msgs
        ? msgs
            .filter((m) => m.peer === active)
            .sort((a, b) => (a.at === b.at ? a.id - b.id : new Date(a.at).getTime() - new Date(b.at).getTime()))
        : [],
    [msgs, active],
  );

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [chat.length, active, dec.size]);

  /* ---------- directory search ---------- */
  useEffect(() => {
    const q = dirQuery.trim();
    if (q.length < 2) {
      setDirResults([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const contactMatches: { address: string; label: string | null }[] = contacts
          .filter(
            (c) =>
              c.name.toLowerCase().includes(q.toLowerCase()) ||
              c.address.includes(q.toLowerCase()) ||
              (c.handle && c.handle.toLowerCase().includes(q.toLowerCase())),
          )
          .map((c) => ({ address: c.address, label: `${c.avatarEmoji ?? ""} ${c.name}`.trim() }));

        if (isAddress(q)) {
          setDirResults([{ address: q.toLowerCase(), label: contactMatches[0]?.label ?? null }]);
        } else {
          const res = await api.searchWallets(q).catch(() => []);
          const combined = [...contactMatches];
          for (const w of res) {
            if (!combined.some((x) => x.address === w.address)) {
              combined.push({ address: w.address, label: w.label });
            }
          }
          setDirResults(combined);
        }
      } catch {
        setDirResults([]);
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [dirQuery, contacts]);

  /* ---------- file pick ---------- */
  function pickFile(f: File, forcedKind?: MsgKind, duration?: number, waveform?: number[]) {
    const cat = f.type.startsWith("image") ? "image" : f.type.startsWith("video") ? "video" : "other";
    if (f.size > FILE_MAX[cat]) {
      appRef.current.toast("err", `File too large — max ${FILE_MAX[cat] / 1024 / 1024} MB.`);
      return;
    }
    const preview = f.type.startsWith("image") || f.type.startsWith("video") ? URL.createObjectURL(f) : undefined;
    const kind: MsgKind =
      forcedKind ??
      (f.type.startsWith("audio") ? "voice" : "file");
    setFile({ f, preview, kind, duration, waveform });
  }

  /* ---------- Voice Recording (Голосовые сообщения как в ТГ) ---------- */
  async function startVoiceRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mime = pickSupportedAudioMime();
      const rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      voiceChunksRef.current = [];
      rec.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) voiceChunksRef.current.push(e.data);
      };
      const startAt = Date.now();
      setRecVoice(true);
      setRecVoiceSec(0);
      voiceTimerRef.current = setInterval(() => {
        setRecVoiceSec(Math.floor((Date.now() - startAt) / 1000));
        setRecWave(
          Array.from({ length: 24 }, () => 18 + Math.floor(Math.random() * 78)),
        );
      }, 250);
      voiceRecorderRef.current = rec;
      rec.start(200);
    } catch {
      // Fallback synthetic voice note if browser sandbox has no physical mic
      appRef.current.toast(
        "info",
        t(
          "Микрофон недоступен в окружении — создаём демо-голосовое сообщение (WebAudio WAV).",
          "Microphone unavailable — generating synthesized WebAudio voice note.",
        ),
      );
      const wavBlob = generateDemoVoiceWav(3);
      const wf = Array.from({ length: 28 }, (_, i) => 25 + Math.round(Math.sin(i * 0.5) * 45 + 20));
      const voiceFile = new File([wavBlob], `voice-${Date.now()}.wav`, { type: "audio/wav" });
      pickFile(voiceFile, "voice", 3, wf);
    }
  }

  function stopVoiceRecording(commit: boolean) {
    if (voiceTimerRef.current) {
      clearInterval(voiceTimerRef.current);
      voiceTimerRef.current = null;
    }
    const rec = voiceRecorderRef.current;
    const dur = Math.max(1, recVoiceSec);
    setRecVoice(false);
    if (!rec) return;
    rec.onstop = () => {
      rec.stream.getTracks().forEach((tr) => tr.stop());
      if (!commit || voiceChunksRef.current.length === 0) return;
      const mime = rec.mimeType || "audio/webm";
      const ext = mime.includes("mp4") ? "m4a" : "webm";
      const blob = new Blob(voiceChunksRef.current, { type: mime });
      const wf = Array.from({ length: 28 }, () => 22 + Math.floor(Math.random() * 70));
      const f = new File([blob], `voice-${Date.now()}.${ext}`, { type: mime });
      pickFile(f, "voice", dur, wf);
    };
    try {
      rec.stop();
    } catch {
      /* ignore */
    }
    voiceRecorderRef.current = null;
  }

  /* ---------- Circular Video Notes ("Кружочки" как в ТГ) ---------- */
  async function openCircleModal() {
    setCircleOpen(true);
    setRecCircle(false);
    setRecCircleSec(0);
    setUsingSynthCam(false);
    await startCircleCamera(facingMode);
  }

  async function startCircleCamera(mode: "user" | "environment") {
    stopCircleStream();
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: mode, width: { ideal: 360 }, height: { ideal: 360 } },
        audio: true,
      });
      circleStreamRef.current = stream;
      setUsingSynthCam(false);
      if (videoPreviewRef.current) {
        videoPreviewRef.current.srcObject = stream;
        videoPreviewRef.current.play().catch(() => undefined);
      }
    } catch {
      // Fallback animated canvas stream if device/sandbox has no physical camera
      setUsingSynthCam(true);
      const canvas = document.createElement("canvas");
      canvas.width = 320;
      canvas.height = 320;
      const ctx = canvas.getContext("2d")!;
      let frame = 0;
      const draw = () => {
        frame++;
        const grad = ctx.createRadialGradient(160, 160, 20, 160, 160, 160);
        grad.addColorStop(0, "#1c2b12");
        grad.addColorStop(1, "#06080b");
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, 320, 320);

        ctx.strokeStyle = "#a8e62e";
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(160, 160, 90 + Math.sin(frame * 0.08) * 14, 0, Math.PI * 2);
        ctx.stroke();

        ctx.fillStyle = "#e9edf2";
        ctx.font = "bold 20px monospace";
        ctx.textAlign = "center";
        ctx.fillText("OBSIDIAN E2E", 160, 152);
        ctx.fillStyle = "#a8e62e";
        ctx.font = "13px monospace";
        ctx.fillText(`VIDEO NOTE · ${(frame / 30).toFixed(1)}s`, 160, 178);
        synthAnimRef.current = requestAnimationFrame(draw);
      };
      draw();
      const stream = canvas.captureStream(30);
      circleStreamRef.current = stream;
      if (videoPreviewRef.current) {
        videoPreviewRef.current.srcObject = stream;
        videoPreviewRef.current.play().catch(() => undefined);
      }
    }
  }

  function stopCircleStream() {
    if (synthAnimRef.current) {
      cancelAnimationFrame(synthAnimRef.current);
      synthAnimRef.current = null;
    }
    if (circleTimerRef.current) {
      clearInterval(circleTimerRef.current);
      circleTimerRef.current = null;
    }
    if (circleStreamRef.current) {
      circleStreamRef.current.getTracks().forEach((tr) => tr.stop());
      circleStreamRef.current = null;
    }
  }

  function closeCircleModal() {
    stopCircleStream();
    setRecCircle(false);
    setCircleOpen(false);
  }

  function beginCircleRecord() {
    const stream = circleStreamRef.current;
    if (!stream) return;
    const mime = pickSupportedVideoMime();
    const rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
    circleChunksRef.current = [];
    rec.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) circleChunksRef.current.push(e.data);
    };
    const startAt = Date.now();
    setRecCircle(true);
    setRecCircleSec(0);
    circleTimerRef.current = setInterval(() => {
      const s = Math.floor((Date.now() - startAt) / 1000);
      setRecCircleSec(s);
      if (s >= 60) finishCircleRecord();
    }, 250);
    circleRecorderRef.current = rec;
    rec.start(200);
  }

  function finishCircleRecord() {
    const rec = circleRecorderRef.current;
    const dur = Math.max(1, recCircleSec);
    if (!rec) return;
    rec.onstop = () => {
      const mime = rec.mimeType || "video/webm";
      const ext = mime.includes("mp4") ? "mp4" : "webm";
      const blob = new Blob(circleChunksRef.current, { type: mime });
      const f = new File([blob], `videonote-${Date.now()}.${ext}`, { type: mime });
      closeCircleModal();
      pickFile(f, "videonote", dur);
    };
    try {
      rec.stop();
    } catch {
      closeCircleModal();
    }
    circleRecorderRef.current = null;
  }

  /* ---------- send pipeline ---------- */
  async function send() {
    const msgText = text.trim();
    if ((!msgText && !file) || busy || !active) return;
    const { chain, session, address, toast } = appRef.current;
    const peer = active.toLowerCase();
    try {
      setBusy(true);
      setBusyStep(t("Проверка E2E ключа получателя…", "Resolving peer E2E key…"));
      const myPub = pub;
      const aad = aadFor(address, peer, chain.chainId);

      // Determine encryption mode & shared key:
      // 1. Self chat ("Избранное"): MODE_E2E with own pubkey
      // 2. Contact with PPSK passphrase: MODE_PPSK
      // 3. Peer with known or on-chain recoverable secp256k1 pubkey: MODE_E2E
      // 4. Brand-new 0-tx address: MODE_AB (Introductory Handshake carrying our 33B pubkey)
      const contact = contacts.find((c) => c.address === peer);
      let resolvedPeerPub = peer === me ? myPub : peerPubs.get(peer) || contact?.pubkey || null;
      if (!resolvedPeerPub && peer !== me) {
        const dirW = await api.getWallet(peer).catch(() => null);
        if (dirW?.pubkey && isValidCompressedPub(dirW.pubkey)) {
          resolvedPeerPub = dirW.pubkey;
        } else {
          resolvedPeerPub = await recoverPubkeyFromChain(chain, peer);
        }
        if (resolvedPeerPub && isValidCompressedPub(resolvedPeerPub)) {
          setPeerPubs((prev) => new Map(prev).set(peer, resolvedPeerPub!));
        }
      }

      let mode: number;
      let dkX: Uint8Array;
      if (peer === me) {
        mode = MODE_E2E;
        dkX = ecdhKey(session.privateKey, myPub);
      } else if (contact?.sharedSecretPhrase) {
        mode = MODE_PPSK;
        dkX = ppskKeyFor(address, peer, contact.sharedSecretPhrase);
      } else if (resolvedPeerPub && isValidCompressedPub(resolvedPeerPub)) {
        mode = MODE_E2E;
        dkX = ecdhKey(session.privateKey, resolvedPeerPub);
      } else {
        mode = MODE_AB;
        dkX = abKeyFor(address, peer, chain.chainId);
      }

      let fileId: string | null = null;
      let inlineB64: string | undefined;

      if (file) {
        setBusyStep(t("Шифрование медиа (AES-256-GCM)…", "Encrypting media (AES-256-GCM)…"));
        fileId = randomHex(16).slice(2);
        const raw = new Uint8Array(await file.f.arrayBuffer());
        // If small voice note or compressed image (< 32 KB), also keep inlineB64 so it works 100% serverlessly
        if (raw.byteLength <= 32_768) {
          inlineB64 = toB64(raw);
        }
        const fdk = fileKeyFor(dkX, fileId);
        const fenv = await aesEncrypt(fdk, fileAad(address, peer, fileId), raw);
        setBusyStep(t("Загрузка зашифрованного контейнера…", "Uploading encrypted blob…"));
        await api
          .uploadFile(
            fileId,
            new Blob([toBlobPart(concatU(fenv.iv, fenv.ct))], { type: "application/octet-stream" }),
            {
              name: file.f.name,
              mimeType: file.f.type || "application/octet-stream",
              size: file.f.size,
            },
            session.privateKey,
          )
          .catch(() => {
            // Even if file server is unreachable, inlineB64 or local URL keeps it working!
            if (!inlineB64 && raw.byteLength <= 150_000) inlineB64 = toB64(raw);
          });
        // Cache local preview URL immediately
        const localUrl = URL.createObjectURL(new Blob([toBlobPart(raw)], { type: file.f.type || "application/octet-stream" }));
        setUrlMap((prev) => new Map(prev).set(fileId!, localUrl));
      }

      const msgKind: MsgKind = file ? file.kind : "text";
      const payload: MsgPayload = {
        v: 1,
        t: msgKind,
        s: msgText || undefined,
        replyTo: replyTo ?? undefined,
        f:
          file && fileId
            ? {
                id: fileId,
                name: file.f.name,
                mime: file.f.type || "application/octet-stream",
                size: file.f.size,
                duration: file.duration,
                waveform: file.waveform,
                inlineB64,
              }
            : undefined,
      };

      setBusyStep(t("Упаковка крипто-конверта nxMS v1…", "Sealing nxMS v1 envelope…"));
      const env = await encryptMsg(dkX, aad, payload);
      // On-chain calldata carries the compact encrypted text/header (for huge inlineB64, strip inlineB64 in tx calldata to save gas)
      const chainPayload: MsgPayload = inlineB64 && inlineB64.length > 2048
        ? { ...payload, f: payload.f ? { ...payload.f, inlineB64: undefined } : undefined }
        : payload;
      const chainEnv = chainPayload === payload ? env : await encryptMsg(dkX, aad, chainPayload);
      const calldata = buildMsgCalldata(mode, myPub, chainEnv.iv, chainEnv.ct);

      let fee: bigint;
      try {
        fee = parseUnits(feeStr.trim() || "0", 18);
      } catch {
        fee = 0n;
      }
      if (bal !== null && fee > bal) {
        toast("err", t(`Стоимость сообщения превышает ваш баланс ${chain.symbol}.`, `Message fee exceeds your ${chain.symbol} balance.`));
        setBusy(false);
        setBusyStep("");
        return;
      }

      let txHash: string | null = null;
      let status: "pending" | "relay" = "pending";
      setBusyStep(t(`Отправка транзакции в ${chain.name}…`, `Broadcasting transaction on ${chain.name}…`));
      try {
        const wallet = new Wallet(session.privateKey);
        const res = await broadcast({
          chain,
          wallet,
          to: peer,
          value: fee,
          data: calldata,
          gasFallback: 55_000,
        });
        txHash = res.hash;
      } catch (e) {
        status = "relay";
        toast(
          "info",
          t(
            `Блокчейн-транзакция не прошла (нет газа) — доставлено через E2E-индекс. (${explainError(e).slice(0, 70)})`,
            `On-chain broadcast skipped — delivered via E2E relay. (${explainError(e).slice(0, 70)})`,
          ),
        );
      }

      setBusyStep(t("Сохранение конверта…", "Persisting envelope…"));
      const localRecord: MessageRow & { uid: string } = {
        uid: txHash ? `tx:${txHash}` : `local:${Date.now()}`,
        id: Date.now(),
        sender: me,
        recipient: peer,
        chainId: chain.chainId,
        txHash,
        amountWei: fee.toString(),
        tokenSymbol: chain.symbol,
        kind: msgKind,
        nonce: toB64(env.iv),
        ciphertext: toB64(env.ct),
        senderPub: myPub,
        mode,
        fileId,
        status,
        createdAt: new Date().toISOString(),
      };
      await idbSet("onchain_msgs", localRecord.uid, localRecord).catch(() => undefined);

      // For non-self messages, also sync to relay index with EIP-191 auth
      if (peer !== me) {
        const inserted = await api
          .sendMessage(
            {
              sender: me,
              recipient: peer,
              chainId: chain.chainId,
              txHash,
              amountWei: fee.toString(),
              tokenSymbol: chain.symbol,
              kind: msgKind,
              nonce: toB64(env.iv),
              ciphertext: toB64(env.ct),
              senderPub: myPub,
              mode,
              fileId,
              status,
            },
            session.privateKey,
          )
          .catch(() => null);

        if (txHash && inserted) {
          const h = txHash;
          (async () => {
            try {
              const p = await providerFor(chain);
              const ok = await waitForTx(p, h, 300_000);
              await api.setMessageStatus(inserted.id, ok ? "confirmed" : "failed", session.privateKey);
            } catch {
              await api.setMessageStatus(inserted.id, "failed", session.privateKey).catch(() => undefined);
            }
            appRef.current.bump();
          })();
        }
      }

      setBusy(false);
      setBusyStep("");
      setText("");
      setFile(null);
      setReplyTo(null);
      load(false);
      toast(
        "ok",
        status === "pending"
          ? t(`Отправлено в блокчейн ${chain.name}!`, `Published on-chain (${chain.name})!`)
          : t("Зашифрованное сообщение доставлено!", "Encrypted message delivered!"),
      );
    } catch (e) {
      setBusy(false);
      setBusyStep("");
      toast("err", explainError(e));
    }
  }

  const labelOf = (p: string) => {
    const low = p.toLowerCase();
    if (low === me) return t("Избранное (Self Vault)", "Saved Messages");
    const c = contacts.find((x) => x.address === low);
    if (c) return `${c.avatarEmoji ?? ""} ${c.name}`.trim();
    return labelsRef.current.get(low) ?? shortAddr(low);
  };

  const activeContact = active ? contacts.find((c) => c.address === active) : undefined;
  const activePeerPub = active ? (active === me ? pub : peerPubs.get(active) || activeContact?.pubkey || null) : null;
  const activeSafety = useMemo(() => {
    if (!active) return null;
    return computeSafetyFingerprint(me, pub, active, activePeerPub);
  }, [me, pub, active, activePeerPub]);

  async function quickSaveActiveAsContact() {
    if (!active) return;
    const namePrompt = window.prompt(
      t("Введите имя для этого контакта кошелька:", "Enter a name for this wallet contact:"),
      labelsRef.current.get(active) || shortAddr(active),
    );
    if (!namePrompt) return;
    await saveContact({
      address: active,
      name: namePrompt.trim(),
      category: "friend",
      avatarEmoji: "💎",
      pubkey: activePeerPub,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
    await appRef.current.reloadContacts();
    appRef.current.toast("ok", t("Контакт сохранён!", "Contact saved!"));
  }

  /* ---------- render ---------- */
  return (
    <div className="flex h-[calc(100dvh-160px)] min-h-[460px] overflow-hidden rounded-xl border border-line glass-panel md:h-[calc(100dvh-120px)]">
      {/* Left pane: Conversations */}
      <div className={`${active ? "hidden md:flex" : "flex"} w-full shrink-0 flex-col border-r border-line md:w-[320px]`}>
        <div className="flex items-center justify-between px-4 pb-2 pt-3.5">
          <div className="flex items-center gap-2">
            <h2 className="text-[14.5px] font-semibold tracking-tight">{t("Мессенджер", "Messenger")}</h2>
            <button
              onClick={async () => {
                setScanningChain(true);
                await load(true);
                setScanningChain(false);
                appRef.current.toast("ok", t("Транзакции блокчейна просканированы на конверты nxMS!", "Blockchain scanned for nxMS envelopes!"));
              }}
              className="focus-ring rounded-lg p-1.5 text-dim hover:text-acc"
              title={t("Сканировать блокчейн напрямую (Serverless)", "Scan blockchain directly (Serverless)")}
            >
              <span className={scanningChain ? "animate-spin inline-block" : ""}>
                <IconRefresh size={13} />
              </span>
            </button>
          </div>
          <Button size="sm" variant="primary" onClick={() => setNewOpen(true)}>
            <IconPlus size={14} /> {t("Чат", "New")}
          </Button>
        </div>

        <div className="px-3 pb-2.5">
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-dim">
              <IconSearch size={14} />
            </span>
            <input
              value={dirQuery}
              onChange={(e) => setDirQuery(e.target.value)}
              placeholder={t("Поиск контакта, @handle или 0x…", "Search contact, @handle or 0x…")}
              className="focus-ring h-9 w-full rounded-lg border border-line bg-ink/50 pl-9 pr-3 text-[12.5px] placeholder:text-dim"
            />
          </div>
          {dirQuery.trim() && (
            <div className="mt-2 max-h-56 overflow-y-auto rounded-lg border border-line bg-panel2/90 p-1">
              {dirResults.length === 0 && (
                <p className="px-2.5 py-2 text-[11.5px] text-dim">{t("Ничего не найдено.", "No matches found.")}</p>
              )}
              {dirResults.map((r) => (
                <button
                  key={r.address}
                  onClick={() => {
                    setActive(r.address);
                    setDirQuery("");
                    setDirResults([]);
                    setNewOpen(false);
                  }}
                  className="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-[12.5px] hover:bg-panel3"
                >
                  <span className="h-6 w-6 rounded-md" style={avatarStyle(r.address)} />
                  <span className="min-w-0 flex-1 truncate">{r.label ?? shortAddr(r.address)}</span>
                  <span className="font-mono text-[10px] text-dim">{shortAddr(r.address, 3)}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex-1 overflow-y-auto px-2 pb-2 space-y-0.5">
          {/* Pinned Saved Messages ("Избранное") */}
          <button
            onClick={() => setActive(me)}
            className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left transition-colors ${
              active === me ? "bg-panel2" : "hover:bg-panel2/50"
            }`}
          >
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-acc/15 text-acc">
              <IconLock size={16} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-medium">
                {t("Избранное (Мои заметки)", "Saved Messages")}
              </span>
              <span className="block truncate text-[11px] text-dim">
                {t("Собственное E2E-облако на блокчейне", "Personal on-chain E2E vault")}
              </span>
            </span>
          </button>

          {convs === null ? (
            <div className="space-y-2 p-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="skeleton h-14 rounded-xl" />
              ))}
            </div>
          ) : convs.filter((c) => c.peer !== me).length === 0 ? (
            <Empty
              icon={<IconChat size={20} />}
              title={t("Нет активных диалогов", "No conversations yet")}
              hint={t(
                "Начните чат с любым 0x-адресом или выберите контакт. Сообщения, голосовые и кружочки шифруются сквозным ECDH.",
                "Start a chat with any 0x address or contact. Text, voice notes, and circular video notes are E2E encrypted.",
              )}
            />
          ) : (
            convs
              .filter((c) => c.peer !== me)
              .map((c) => {
                const d = dec.get(c.last.id);
                const kindLabel =
                  c.last.kind === "voice"
                    ? t("🎤 Голосовое сообщение", "🎤 Voice message")
                    : c.last.kind === "videonote"
                      ? t("⭕ Видео-кружочек", "⭕ Video note")
                      : c.last.kind === "file"
                        ? t("📎 Вложение", "📎 Attachment")
                        : d?.text || t("🔒 Зашифровано", "🔒 Encrypted");
                return (
                  <button
                    key={c.peer}
                    onClick={() => setActive(c.peer)}
                    className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-left transition-colors ${
                      active === c.peer ? "bg-panel2 shadow-[inset_0_0_0_1px_var(--color-line2)]" : "hover:bg-panel2/50"
                    }`}
                  >
                    <span
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-sm font-semibold"
                      style={avatarStyle(c.peer)}
                    >
                      {contacts.find((x) => x.address === c.peer)?.avatarEmoji || c.peer.slice(2, 4).toUpperCase()}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center justify-between gap-2">
                        <span className="truncate text-[13px] font-medium">{labelOf(c.peer)}</span>
                        <span className="shrink-0 text-[10.5px] text-dim">{timeAgoLocal(c.last.at)}</span>
                      </span>
                      <span className="mt-0.5 flex items-center gap-1.5">
                        <StatusIcon status={c.last.dir === "out" ? c.last.status : "confirmed"} txHash={c.last.txHash} />
                        <span className="truncate text-[12px] text-sub">{kindLabel}</span>
                      </span>
                    </span>
                  </button>
                );
              })
          )}
        </div>
      </div>

      {/* Right pane: Active Chat */}
      <div className={`${active ? "flex" : "hidden md:flex"} min-w-0 flex-1 flex-col`}>
        {!active ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
            <span className="text-acc/80">
              <LogoMark size={42} />
            </span>
            <h3 className="text-base font-semibold">
              {t("Децентрализованный E2E-мессенджер на блокчейне", "Serverless On-Chain E2E Messenger")}
            </h3>
            <p className="max-w-sm text-[13px] leading-relaxed text-sub">
              {t(
                "Выберите чат, контакт или отправьте сообщение на любой 0x-адрес. Поддерживаются текстовые транзакции, фото, видео, голосовые и видео-кружочки как в Telegram.",
                "Pick a conversation, contact, or message any 0x address. Supports text transactions, photos, videos, voice notes, and Telegram-style circular video notes.",
              )}
            </p>
            <div className="flex gap-2">
              <Button variant="primary" size="md" onClick={() => setNewOpen(true)}>
                <IconPlus size={15} /> {t("Новое сообщение", "New Message")}
              </Button>
              <Button variant="soft" size="md" onClick={() => app.go("contacts")}>
                {t("Открыть контакты", "Contacts")}
              </Button>
            </div>
          </div>
        ) : (
          <>
            {/* Chat Header */}
            <div className="flex items-center gap-2.5 border-b border-line px-4 py-2.5">
              <button onClick={() => setActive(null)} className="focus-ring rounded-lg p-1 text-dim hover:text-fg md:hidden">
                <IconChevronDown size={16} className="rotate-90" />
              </button>
              <span
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-sm font-semibold"
                style={avatarStyle(active)}
              >
                {activeContact?.avatarEmoji || active.slice(2, 4).toUpperCase()}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <p className="truncate text-[13.5px] font-semibold">{labelOf(active)}</p>
                  {activeContact?.handle && (
                    <span className="font-mono text-[11px] text-acc">@{activeContact.handle}</span>
                  )}
                </div>
                <p className="flex flex-wrap items-center gap-1.5 font-mono text-[10.5px] text-dim">
                  {shortAddr(active)}
                  <CopyBtn text={active} small />
                  <span className="text-line2">|</span>
                  <IconLock size={10} className="text-acc" />
                  {active === me
                    ? "Self-ECDH"
                    : activeContact?.sharedSecretPhrase
                      ? "PPSK E2E"
                      : isValidCompressedPub(activePeerPub)
                        ? "ECDH secp256k1"
                        : t("Handshake (ожидает ключ)", "Handshake")}
                  <span className="text-line2">|</span>
                  <span className="uppercase">{chain.short}</span>
                </p>
              </div>

              {/* Safety Fingerprint Button */}
              {activeSafety && (
                <button
                  onClick={() => setSafetyOpen(true)}
                  className="focus-ring hidden items-center gap-1.5 rounded-lg border border-line bg-panel2 px-2.5 py-1 text-[11.5px] hover:border-acc/50 sm:flex"
                  title={t("Проверить криптографический отпечаток безопасности", "Verify E2E Safety Fingerprint")}
                >
                  <span>{activeSafety.emojis.slice(0, 2).join("")}</span>
                  <span className="font-mono text-[10.5px] text-acc">{activeSafety.digits.slice(0, 4)}</span>
                </button>
              )}

              {/* Live WebRTC Audio Call */}
              <button
                onClick={() => app.startCall(active, labelOf(active), "voice")}
                className="focus-ring rounded-lg p-2 text-dim hover:text-acc hover:bg-panel2 transition-colors"
                title={t("Голосовой звонок WebRTC", "P2P Voice Call")}
              >
                <IconPhone size={15} />
              </button>

              {/* Live WebRTC Video Call */}
              <button
                onClick={() => app.startCall(active, labelOf(active), "video")}
                className="focus-ring rounded-lg p-2 text-dim hover:text-acc hover:bg-panel2 transition-colors"
                title={t("Видеозвонок WebRTC", "P2P Video Call")}
              >
                <IconVideo size={16} />
              </button>

              {!activeContact && active !== me && (
                <button
                  onClick={quickSaveActiveAsContact}
                  className="focus-ring rounded-lg p-2 text-dim hover:text-acc"
                  title={t("Добавить в контакты", "Save to Contacts")}
                >
                  <IconUserPlus size={16} />
                </button>
              )}

              <a
                href={explorerAddr(chain, active)}
                target="_blank"
                rel="noreferrer"
                className="focus-ring rounded-lg p-2 text-dim hover:text-acc"
                title={t("Открыть в блокчейн-эксплорере", "View on block explorer")}
              >
                <IconExternal size={15} />
              </a>
            </div>

            {/* Messages Stream */}
            <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
              {chat.length === 0 && (
                <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
                  <IconShield size={24} className="text-acc/70" />
                  <p className="text-[13.5px] font-medium text-fg">
                    {t(`Защищённый канал с ${labelOf(active)}`, `Encrypted channel with ${labelOf(active)}`)}
                  </p>
                  <p className="max-w-xs text-[12px] leading-relaxed text-dim">
                    {isValidCompressedPub(activePeerPub)
                      ? t(
                          "Публичный ключ собеседника подтверждён (secp256k1). Каждое сообщение, голосовое или кружочек шифруется общим ключом ECDH + AES-256-GCM.",
                          "Peer secp256k1 public key verified. Every message, voice note, or circular video note is sealed with ECDH + AES-256-GCM.",
                        )
                      : t(
                          "Первое сообщение передаст ваш публичный ключ в calldata транзакции (nxMS v1), чтобы установить двусторонний ECDH-канал.",
                          "Your first message carries your compressed public key in tx calldata (nxMS v1) to establish bilateral ECDH.",
                        )}
                  </p>
                </div>
              )}

              {chat.map((m) => {
                const d = dec.get(m.id);
                const out = m.dir === "out";
                const effKind = d?.kind ?? m.kind;

                // Telegram-style Circular Video Note ("Кружочек") renders as a floating circle!
                if (effKind === "videonote" && d?.file) {
                  return (
                    <div key={m.id} className={`flex flex-col ${out ? "items-end" : "items-start"}`}>
                      <CircularVideoBubble
                        meta={d.file}
                        url={urlMap.get(d.file.id)}
                        out={out}
                      />
                      <div className="mt-1 flex items-center gap-1.5 px-2 text-[10.5px] text-dim">
                        {out && (
                          <StatusIcon
                            status={m.status}
                            txHash={m.txHash}
                            onOpen={() => m.txHash && window.open(explorerTx(chain, m.txHash), "_blank")}
                          />
                        )}
                        <span>{fmtTime(m.at)}</span>
                        <Badge tone="acc" className="px-1 py-0 text-[9.5px]">⭕ Video Note</Badge>
                      </div>
                    </div>
                  );
                }

                return (
                  <div key={m.id} className={`group flex ${out ? "justify-end" : "justify-start"}`}>
                    <div
                      className={`relative max-w-[82%] rounded-2xl border px-3.5 py-2.5 md:max-w-[66%] ${
                        out
                          ? "rounded-br-md border-acc/30 bg-acc/10"
                          : "rounded-bl-md border-line bg-panel2/90"
                      }`}
                    >
                      {/* Reply quote if present */}
                      {d?.replyTo && (
                        <div className="mb-1.5 rounded-lg border-l-2 border-acc bg-ink/40 px-2.5 py-1 text-[11.5px] text-sub">
                          <span className="block truncate">{d.replyTo.text}</span>
                        </div>
                      )}

                      {/* Voice Message Bubble */}
                      {effKind === "voice" && d?.file && (
                        <VoiceBubble meta={d.file} url={urlMap.get(d.file.id)} />
                      )}

                      {/* Regular File / Photo / Video */}
                      {effKind === "file" && d?.file && (
                        <FileBlock f={d.file} url={urlMap.get(d.file.id)} />
                      )}

                      {/* Text / Caption */}
                      {d?.text && (
                        <p className="whitespace-pre-wrap break-words text-[13.5px] leading-relaxed">
                          {d.text}
                        </p>
                      )}

                      {d?.err && (
                        <p className="flex items-center gap-1.5 text-[12px] text-warn">
                          <IconLock size={12} /> {t("Зашифрованный контейнер", "Encrypted payload")}
                        </p>
                      )}

                      {/* Emoji Reactions display */}
                      {reactions.has(m.id) && reactions.get(m.id)!.length > 0 && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          {reactions.get(m.id)!.map((em) => (
                            <button
                              key={em}
                              onClick={() => toggleReaction(m.id, em)}
                              className="focus-ring flex items-center gap-1 rounded-full border border-line bg-ink/70 px-2 py-0.5 text-[11px] shadow-sm hover:border-acc/40 active:scale-90"
                            >
                              <span>{em}</span>
                            </button>
                          ))}
                        </div>
                      )}

                      <div className={`mt-1.5 flex items-center gap-1.5 text-[10.5px] text-dim ${out ? "justify-end" : ""}`}>
                        {/* Quick Reaction Trigger */}
                        <div className="opacity-0 transition-opacity group-hover:opacity-100 flex items-center gap-1">
                          <button
                            onClick={() => toggleReaction(m.id, "❤️")}
                            className="hover:scale-125 transition-transform"
                            title="React ❤️"
                          >
                            ❤️
                          </button>
                          <button
                            onClick={() => toggleReaction(m.id, "🔥")}
                            className="hover:scale-125 transition-transform"
                            title="React 🔥"
                          >
                            🔥
                          </button>
                          <button
                            onClick={() => toggleReaction(m.id, "👍")}
                            className="hover:scale-125 transition-transform"
                            title="React 👍"
                          >
                            👍
                          </button>
                        </div>

                        {/* Forward Message */}
                        <button
                          onClick={() => {
                            haptic("light");
                            setForwardModalMsg(m);
                          }}
                          className="opacity-0 transition-opacity group-hover:opacity-100 hover:text-acc p-0.5"
                          title={t("Переслать", "Forward")}
                        >
                          <IconForward size={12} />
                        </button>

                        {/* Reply Quote */}
                        <button
                          onClick={() =>
                            setReplyTo({
                              id: m.id,
                              text:
                                d?.text ||
                                (effKind === "voice"
                                  ? t("🎤 Голосовое сообщение", "🎤 Voice message")
                                  : effKind === "videonote"
                                    ? t("⭕ Кружочек", "⭕ Video note")
                                    : t("📎 Вложение", "📎 Attachment")),
                            })
                          }
                          className="opacity-0 transition-opacity group-hover:opacity-100 hover:text-acc p-0.5"
                          title={t("Ответить", "Reply")}
                        >
                          <IconReply size={12} />
                        </button>

                        {out && (
                          <button
                            onClick={() => {
                              if (m.txHash) window.open(explorerTx(chain, m.txHash), "_blank");
                            }}
                            title={m.txHash ? t("Открыть транзакцию в эксплорере", "View transaction on-chain") : "Relay"}
                            className="focus-ring rounded p-0.5"
                          >
                            <StatusIcon status={m.status} txHash={m.txHash} />
                          </button>
                        )}
                        <span>{fmtTime(m.at)}</span>
                        {BigInt(m.amountWei || "0") > 0n && (
                          <Badge tone="acc" className="px-1 py-0 text-[9.5px]">
                            +{fmtUnits(m.amountWei, 18, 5)} {m.tokenSymbol}
                          </Badge>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Composer */}
            <div className="border-t border-line p-3 safe-pb">
              {/* Reply preview bar */}
              {replyTo && (
                <div className="mb-2 flex items-center justify-between rounded-lg border-l-2 border-acc bg-panel2/80 px-3 py-1.5 text-[12px]">
                  <span className="truncate text-sub">
                    <b className="text-acc mr-1.5">{t("Ответ:", "Reply:")}</b>
                    {replyTo.text}
                  </span>
                  <button onClick={() => setReplyTo(null)} className="text-dim hover:text-fg">
                    <IconX size={13} />
                  </button>
                </div>
              )}

              {/* Staged attachment preview (Photo / Video / Voice / Circle Video Note) */}
              {file && (
                <div className="mb-2.5 flex items-center gap-3 rounded-xl border border-acc/30 bg-panel2/80 p-2.5">
                  {file.kind === "videonote" && file.preview ? (
                    <video
                      src={file.preview}
                      autoPlay
                      loop
                      muted
                      playsInline
                      className="h-14 w-14 rounded-full border-2 border-acc object-cover"
                    />
                  ) : file.preview && file.f.type.startsWith("image") ? (
                    <img src={file.preview} alt="" className="h-14 w-14 rounded-lg object-cover" />
                  ) : file.kind === "voice" ? (
                    <span className="flex h-12 w-12 items-center justify-center rounded-full bg-acc/20 text-acc">
                      <IconMic size={20} />
                    </span>
                  ) : file.f.type.startsWith("video") ? (
                    <span className="flex h-12 w-12 items-center justify-center rounded-lg bg-panel3 text-sub">
                      <IconVideo size={20} />
                    </span>
                  ) : (
                    <span className="flex h-12 w-12 items-center justify-center rounded-lg bg-panel3 text-sub">
                      <IconFile size={20} />
                    </span>
                  )}
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[12.5px] font-medium">
                      {file.kind === "voice"
                        ? t(`🎤 Голосовое сообщение (${file.duration ?? 1} сек)`, `🎤 Voice note (${file.duration ?? 1}s)`)
                        : file.kind === "videonote"
                          ? t(`⭕ Видео-кружочек (${file.duration ?? 1} сек)`, `⭕ Circular video note (${file.duration ?? 1}s)`)
                          : file.f.name}
                    </p>
                    <p className="text-[11px] text-dim">
                      {(file.f.size / 1024).toFixed(1)} KB · {t("будет зашифровано AES-256-GCM", "will be encrypted with AES-256-GCM")}
                    </p>
                  </div>
                  <button onClick={() => setFile(null)} className="focus-ring rounded-lg p-1.5 text-dim hover:text-bad">
                    <IconX size={15} />
                  </button>
                </div>
              )}

              {/* Active Voice Recording Bar */}
              {recVoice ? (
                <div className="flex items-center gap-3 rounded-xl border border-bad/40 bg-bad/10 px-3.5 py-2.5">
                  <span className="h-3 w-3 rounded-full bg-bad animate-rec" />
                  <span className="font-mono text-[13px] font-semibold text-bad">
                    {Math.floor(recVoiceSec / 60)}:{String(recVoiceSec % 60).padStart(2, "0")}
                  </span>
                  <div className="flex flex-1 items-center justify-center gap-1 h-7">
                    {recWave.map((h, idx) => (
                      <span
                        key={idx}
                        className="w-1 rounded-full bg-acc transition-all duration-150"
                        style={{ height: `${Math.max(15, Math.min(100, h))}%` }}
                      />
                    ))}
                  </div>
                  <Button size="sm" variant="ghost" onClick={() => stopVoiceRecording(false)}>
                    {t("Отмена", "Cancel")}
                  </Button>
                  <Button size="sm" variant="primary" onClick={() => stopVoiceRecording(true)}>
                    <IconCheck size={14} /> {t("Готово", "Done")}
                  </Button>
                </div>
              ) : (
                <div className="flex items-end gap-1.5">
                  {/* Attach photo/video/file */}
                  <button
                    onClick={() => fileInputRef.current?.click()}
                    className="focus-ring mb-0.5 rounded-xl border border-line bg-panel2 p-2.5 text-dim transition-colors hover:text-acc"
                    title={t("Прикрепить фото, видео или файл", "Attach photo, video or file")}
                  >
                    <IconClip size={17} />
                  </button>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*,video/*,audio/*,application/pdf"
                    className="hidden"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (f) pickFile(f);
                      e.target.value = "";
                    }}
                  />

                  {/* Record Voice Message (Голосовое) */}
                  <button
                    onClick={startVoiceRecording}
                    className="focus-ring mb-0.5 rounded-xl border border-line bg-panel2 p-2.5 text-dim transition-colors hover:text-acc"
                    title={t("Записать голосовое сообщение (как в Telegram)", "Record Voice Message")}
                  >
                    <IconMic size={17} />
                  </button>

                  {/* Record Circular Video Note ("Кружочек") */}
                  <button
                    onClick={openCircleModal}
                    className="focus-ring mb-0.5 rounded-xl border border-line bg-panel2 p-2.5 text-dim transition-colors hover:text-acc"
                    title={t("Записать видео-кружочек (как в Telegram)", "Record Circular Video Note")}
                  >
                    <IconVideoCircle size={17} />
                  </button>

                  <textarea
                    value={text}
                    onChange={(e) => setText(e.target.value.slice(0, 1400))}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        send();
                      }
                    }}
                    rows={1}
                    placeholder={
                      busy
                        ? busyStep
                        : t(`Сообщение в ${chain.short} (Enter — отправить)…`, `Encrypted message on ${chain.short}…`)
                    }
                    className="focus-ring max-h-36 min-h-[42px] flex-1 resize-none rounded-xl border border-line bg-ink/60 px-3.5 py-2.5 text-[13.5px] placeholder:text-dim"
                  />

                  {/* Message Fee Picker */}
                  <div className="relative">
                    <button
                      onClick={() => setFeeOpen(!feeOpen)}
                      className="focus-ring mb-0.5 flex h-[42px] items-center gap-1 rounded-xl border border-line bg-panel2 px-2.5 font-mono text-[11px] text-sub hover:border-line2"
                      title={t("Стоимость сообщения (перевод вместе с транзакцией)", "Message value attached to transaction")}
                    >
                      {feeStr} {chain.symbol}
                      <IconChevronDown size={11} className={feeOpen ? "rotate-180" : ""} />
                    </button>
                    {feeOpen && (
                      <div className="absolute bottom-12 right-0 z-30 w-52 animate-scale-in rounded-xl border border-line2 bg-panel2 p-2 shadow-2xl">
                        <p className="px-2 pb-1.5 text-[10.5px] uppercase tracking-[0.12em] text-dim">
                          {t("Стоимость сообщения", "Message Value")}
                        </p>
                        {FEE_PRESETS.map((f) => (
                          <button
                            key={f}
                            onClick={() => {
                              setFeeStr(f);
                              setFeeOpen(false);
                            }}
                            className={`block w-full rounded-lg px-2.5 py-1.5 text-left font-mono text-[12px] hover:bg-panel3 ${
                              feeStr === f ? "text-acc" : "text-sub"
                            }`}
                          >
                            {f} {chain.symbol}
                          </button>
                        ))}
                        <div className="mt-1.5 flex items-center gap-1.5 border-t border-line pt-2 px-1.5">
                          <input
                            value={feeStr}
                            onChange={(e) => setFeeStr(e.target.value)}
                            placeholder="custom"
                            className="focus-ring h-8 w-full rounded-md border border-line bg-ink/60 px-2 font-mono text-[11.5px]"
                          />
                          <button onClick={() => setFeeOpen(false)} className="rounded-md px-2 text-[11px] text-sub hover:text-fg">
                            OK
                          </button>
                        </div>
                        <p className="px-2 pt-1.5 text-[10.5px] leading-relaxed text-dim">
                          {t("Баланс:", "Balance:")} {bal === null ? "…" : fmtUnits(bal, 18, 5)} {chain.symbol}
                        </p>
                      </div>
                    )}
                  </div>

                  <Button
                    variant="primary"
                    size="md"
                    className="mb-0.5 h-[42px] w-[48px] shrink-0 p-0"
                    disabled={busy || (!text.trim() && !file)}
                    onClick={send}
                    title={t("Зашифровать и отправить в блокчейн", "Encrypt & broadcast on-chain")}
                  >
                    {busy ? <Spinner size={16} className="text-accink" /> : <IconSend size={17} />}
                  </Button>
                </div>
              )}

              {/* Quick Action & Message Templates Strip with Smooth Horizontal Scroll */}
              <div className="horizontal-scroll-touch no-scrollbar flex items-center gap-1.5 overflow-x-auto py-1.5 px-0.5">
                {[
                  { emoji: "💎", label: t("Отправить крипту", "Send crypto"), text: "💎 Отправляю криптовалюту — моментально, безопасно, on-chain.", amount: "0.0001" },
                  { emoji: "📄", label: t("Запросить оплату", "Invoice / Request"), text: "📄 Запрос на оплату — подтвердите и отправьте транзакцию.", amount: "0" },
                  { emoji: "🎨", label: t("Купить NFT / Mint", "Buy NFT / Mint"), text: "🎨 Запрос на покупку/минт NFT в блокчейне.", amount: "0.001" },
                  { emoji: "📍", label: t("Отправь свой 0x адрес", "Share wallet address"), text: "📍 Пожалуйста, пришли свой EVM адрес кошелька для перевода.", amount: "0" },
                  { emoji: "⚡", label: "0.005 ETH", text: "⚡ Быстрый перевод 0.005 ETH", amount: "0.005" },
                  { emoji: "📞", label: t("Готов к созвону", "Ready to call"), text: "📞 Готов созвониться, дай знать когда удобно!", amount: "0" },
                  { emoji: "✅", label: t("Подтверждаю", "Confirmed"), text: "✅ Транзакция и условия подтверждены.", amount: "0" },
                  { emoji: "👋", label: t("Привет!", "Say hello"), text: "👋 Привет! Рад связаться в децентрализованном мессенджере Obsidian.", amount: "0" },
                ].map((tpl) => (
                  <button
                    key={tpl.text}
                    type="button"
                    onClick={() => {
                      haptic("light");
                      setText(tpl.text);
                      setFeeStr(tpl.amount);
                    }}
                    className="focus-ring flex shrink-0 items-center gap-1 rounded-full border border-line bg-panel2/90 px-2.5 py-1 text-[11px] font-medium text-sub transition-all hover:border-acc/40 hover:bg-panel3 hover:text-fg active:scale-95 shadow-sm"
                  >
                    <span>{tpl.emoji}</span>
                    <span>{tpl.label}</span>
                  </button>
                ))}
              </div>

              <p className="mt-1.5 flex items-center justify-between px-1 text-[10.5px] text-dim">
                <span className="flex items-center gap-1.5">
                  <IconLock size={10} className="text-acc/80" />
                  ECDH secp256k1 · AES-256-GCM · calldata nxMS ({chain.short})
                </span>
                <span>{t("🎤 Голосовые · ⭕ Кружочки · 📎 Медиа", "🎤 Voice · ⭕ Video Notes · 📎 Media")}</span>
              </p>
            </div>
          </>
        )}
      </div>

      {/* Circular Video Note ("Кружочек") Recorder Modal */}
      <Modal
        open={circleOpen}
        onClose={closeCircleModal}
        title={t("Запись видео-кружочка (Telegram Video Note)", "Record Circular Video Note")}
      >
        <div className="flex flex-col items-center py-2">
          <div className="relative flex h-64 w-64 items-center justify-center">
            {/* Animated progress ring */}
            <svg className="pointer-events-none absolute inset-0 h-full w-full -rotate-90" viewBox="0 0 100 100">
              <circle cx="50" cy="50" r="47" fill="none" stroke="#1a212b" strokeWidth="3" />
              {recCircle && (
                <circle
                  cx="50"
                  cy="50"
                  r="47"
                  fill="none"
                  stroke="var(--dyn-acc)"
                  strokeWidth="3.5"
                  strokeDasharray="295"
                  strokeDashoffset={295 - (295 * Math.min(60, recCircleSec)) / 60}
                  strokeLinecap="round"
                />
              )}
            </svg>
            <video
              ref={videoPreviewRef}
              muted
              playsInline
              autoPlay
              className="h-56 w-56 rounded-full object-cover shadow-2xl"
            />
            {recCircle && (
              <span className="absolute top-3 rounded-full bg-bad px-2.5 py-0.5 font-mono text-[11px] font-bold text-white shadow">
                ● 0:{String(recCircleSec).padStart(2, "0")}
              </span>
            )}
          </div>

          {usingSynthCam && (
            <p className="mt-2 text-center text-[11.5px] text-sub">
              {t(
                "Физическая камера не обнаружена — активен крипто-синтезатор кружочка (30 FPS).",
                "No physical camera detected — using live 30 FPS synthesized video note stream.",
              )}
            </p>
          )}

          <div className="mt-5 flex items-center gap-3">
            <Button
              variant="soft"
              size="md"
              onClick={() => {
                const next = facingMode === "user" ? "environment" : "user";
                setFacingMode(next);
                startCircleCamera(next);
              }}
              disabled={recCircle}
              title={t("Переключить камеру (фронтальная / основная)", "Flip camera")}
            >
              <IconCamera size={16} /> {t("Камера", "Flip")}
            </Button>

            {!recCircle ? (
              <Button variant="primary" size="lg" onClick={beginCircleRecord}>
                <IconVideoCircle size={18} /> {t("Начать запись кружочка", "Start Recording")}
              </Button>
            ) : (
              <Button variant="danger" size="lg" onClick={finishCircleRecord}>
                <IconCheck size={18} /> {t("Остановить и прикрепить", "Stop & Attach")}
              </Button>
            )}
          </div>
        </div>
      </Modal>

      {/* Safety Fingerprint Modal */}
      <Modal
        open={safetyOpen}
        onClose={() => setSafetyOpen(false)}
        title={t("Криптографическая проверка E2E-канала", "E2E Channel Safety Verification")}
      >
        {activeSafety && active && (
          <div className="space-y-4">
            <div className="rounded-xl border border-line bg-ink/60 p-4 text-center">
              <p className="text-3xl tracking-[0.3em]">{activeSafety.emojis.join(" ")}</p>
              <p className="mt-3 font-mono text-base font-bold tracking-widest text-acc">
                {activeSafety.digits}
              </p>
            </div>
            <p className="text-[12.5px] leading-relaxed text-sub">
              {t(
                "Этот уникальный отпечаток вычислен через keccak256 из вашего публичного ключа secp256k1 и ключа собеседника. Если эти 4 эмодзи и 16 цифр совпадают на обоих устройствах — атака «человек посередине» (MITM) математически исключена.",
                "This fingerprint is derived via keccak256 from both peers' secp256k1 public keys. Matching emojis and digits guarantee zero MITM.",
              )}
            </p>
            <div className="rounded-lg border border-line bg-panel2 p-3 font-mono text-[11px] text-dim space-y-1">
              <p className="truncate">My Pub: {pub}</p>
              <p className="truncate">Peer Pub: {activePeerPub ?? "Handshake / PPSK"}</p>
            </div>
            <Button variant="primary" size="md" className="w-full" onClick={() => setSafetyOpen(false)}>
              {t("Понятно", "Done")}
            </Button>
          </div>
        )}
      </Modal>

      {/* New Message Modal */}
      <Modal open={newOpen} onClose={() => setNewOpen(false)} title={t("Новое E2E сообщение", "New On-Chain E2E Message")}>
        <p className="mb-3 text-[12.5px] leading-relaxed text-sub">
          {t(
            "Введите любой 0x-адрес или выберите контакт. Если адрес когда-либо совершал транзакцию, Obsidian сам восстановит его публичный ключ secp256k1 из блокчейна для 100% ECDH шифрования.",
            "Enter any 0x address or pick a contact. Obsidian automatically recovers peer secp256k1 public keys from blockchain history.",
          )}
        </p>
        <Input
          value={dirQuery}
          onChange={(e) => setDirQuery(e.target.value)}
          placeholder={t("0x-адрес или имя контакта…", "0x address or contact name…")}
          className="font-mono text-[12.5px]"
          autoFocus
        />

        {contacts.length > 0 && !dirQuery.trim() && (
          <div className="mt-3">
            <p className="mb-1.5 text-[11px] uppercase tracking-wider text-dim">{t("Ваши контакты", "Your Contacts")}</p>
            <div className="max-h-48 space-y-1 overflow-y-auto rounded-xl border border-line bg-panel2/60 p-1.5">
              {contacts.map((c) => (
                <button
                  key={c.address}
                  onClick={() => {
                    setActive(c.address);
                    setNewOpen(false);
                  }}
                  className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] hover:bg-panel3"
                >
                  <span className="flex h-7 w-7 items-center justify-center rounded-lg text-xs" style={avatarStyle(c.address)}>
                    {c.avatarEmoji || c.name.slice(0, 2)}
                  </span>
                  <span className="min-w-0 flex-1 truncate font-medium">{c.name}</span>
                  <span className="font-mono text-[10.5px] text-dim">{shortAddr(c.address)}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {dirQuery.trim().length > 0 && (
          <div className="mt-2 max-h-52 overflow-y-auto rounded-xl border border-line bg-panel2/60 p-1.5">
            {(isAddress(dirQuery.trim())
              ? [{ address: dirQuery.trim().toLowerCase(), label: null }]
              : dirResults
            ).map((r) => (
              <button
                key={r.address}
                onClick={() => {
                  setActive(r.address);
                  setNewOpen(false);
                  setDirQuery("");
                }}
                className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] hover:bg-panel3"
              >
                <span className="h-7 w-7 rounded-lg" style={avatarStyle(r.address)} />
                <span className="min-w-0 flex-1 truncate">{r.label ?? t("Внешний 0x-адрес", "External 0x address")}</span>
                <span className="font-mono text-[10.5px] text-dim">{shortAddr(r.address)}</span>
              </button>
            ))}
          </div>
        )}

        {isAddress(dirQuery.trim()) && (
          <Button
            variant="primary"
            size="md"
            className="mt-3 w-full"
            onClick={() => {
              setActive(dirQuery.trim().toLowerCase());
              setNewOpen(false);
              setDirQuery("");
            }}
          >
            <IconSend size={14} /> {t("Открыть зашифрованный чат", "Open Encrypted Channel")}
          </Button>
        )}
      </Modal>

      {/* Forward Message Modal */}
      <Modal
        open={!!forwardModalMsg}
        onClose={() => setForwardModalMsg(null)}
        title={t("Переслать сообщение", "Forward Message")}
      >
        <div className="space-y-4">
          <p className="text-[12.5px] text-sub">
            {t("Выберите контакт или введите адрес для пересылки:", "Select a contact or enter a recipient address:")}
          </p>

          <Input
            placeholder="0x… recipient address"
            value={forwardTarget}
            onChange={(e) => setForwardTarget(e.target.value)}
            className="font-mono text-[12.5px]"
          />

          {contacts.length > 0 && (
            <div className="space-y-1 max-h-48 overflow-y-auto rounded-xl border border-line bg-panel2/60 p-1.5">
              {contacts.map((c) => (
                <button
                  key={c.address}
                  onClick={() => handleForwardTo(c.address)}
                  className="flex w-full items-center justify-between rounded-lg px-2.5 py-2 text-left text-[13px] hover:bg-panel3 transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <span>{c.avatarEmoji ?? "💎"}</span>
                    <span className="font-semibold">{c.name}</span>
                  </div>
                  <span className="font-mono text-[11px] text-dim">{shortAddr(c.address, 4)}</span>
                </button>
              ))}
            </div>
          )}

          {isAddress(forwardTarget.trim()) && (
            <Button
              variant="primary"
              size="md"
              className="w-full"
              onClick={() => handleForwardTo(forwardTarget.trim())}
            >
              <IconForward size={14} /> {t("Переслать на адрес", "Forward to Address")}
            </Button>
          )}
        </div>
      </Modal>
    </div>
  );
}

/* ================= Telegram-Style Voice Message Bubble ================= */

function VoiceBubble({ meta, url }: { meta: MsgFileMeta; url?: string }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [rate, setRate] = useState<1 | 1.5 | 2>(1);
  const bars = meta.waveform && meta.waveform.length > 0
    ? meta.waveform
    : [30, 55, 80, 45, 90, 65, 40, 75, 95, 50, 35, 70, 85, 60, 45, 88, 52, 68, 74, 42, 60, 80, 48, 32];

  function toggle() {
    const a = audioRef.current;
    if (!a) return;
    if (playing) {
      a.pause();
    } else {
      a.playbackRate = rate;
      a.play().catch(() => undefined);
    }
  }

  function cycleRate() {
    const next: 1 | 1.5 | 2 = rate === 1 ? 1.5 : rate === 1.5 ? 2 : 1;
    setRate(next);
    if (audioRef.current) audioRef.current.playbackRate = next;
  }

  return (
    <div className="flex min-w-[230px] items-center gap-3 py-1">
      {url && (
        <audio
          ref={audioRef}
          src={url}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onEnded={() => {
            setPlaying(false);
            setProgress(0);
          }}
          onTimeUpdate={(e) => {
            const el = e.currentTarget;
            if (el.duration) setProgress((el.currentTime / el.duration) * 100);
          }}
        />
      )}
      <button
        onClick={toggle}
        disabled={!url}
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-acc text-accink shadow transition-transform active:scale-95 disabled:opacity-40"
      >
        {!url ? <Spinner size={15} /> : playing ? <IconPause size={16} /> : <IconPlay size={16} />}
      </button>

      <div className="flex-1">
        <div className="flex h-7 items-center gap-[3px]">
          {bars.slice(0, 26).map((b, i) => {
            const pct = (i / 26) * 100;
            const active = pct <= progress;
            return (
              <span
                key={i}
                className={`w-1 rounded-full transition-colors ${active ? "bg-acc" : "bg-sub/40"}`}
                style={{ height: `${Math.max(20, Math.min(100, b))}%` }}
              />
            );
          })}
        </div>
        <div className="mt-1 flex items-center justify-between font-mono text-[10px] text-sub">
          <span>0:{String(Math.round(meta.duration ?? 3)).padStart(2, "0")}</span>
          <button
            type="button"
            onClick={cycleRate}
            className="rounded bg-ink/50 px-1.5 py-0.5 text-[9.5px] font-bold text-acc hover:bg-ink"
          >
            {rate}x
          </button>
        </div>
      </div>
    </div>
  );
}

/* ================= Telegram-Style Circular Video Note ("Кружочек") ================= */

function CircularVideoBubble({
  meta,
  url,
  out,
}: {
  meta: MsgFileMeta;
  url?: string;
  out: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [muted, setMuted] = useState(true);

  return (
    <div
      onClick={() => {
        const v = videoRef.current;
        if (!v) return;
        v.muted = !muted;
        setMuted(!muted);
        v.play().catch(() => undefined);
      }}
      className={`group relative flex h-52 w-52 cursor-pointer items-center justify-center overflow-hidden rounded-full border-2 shadow-xl transition-transform hover:scale-[1.02] ${
        out ? "border-acc shadow-[0_0_30px_-8px_var(--dyn-acc)]" : "border-line2"
      }`}
    >
      {url ? (
        <video
          ref={videoRef}
          src={url}
          autoPlay
          loop
          muted={muted}
          playsInline
          className="h-full w-full object-cover"
        />
      ) : (
        <div className="flex flex-col items-center gap-2 text-acc">
          <Spinner size={22} />
          <span className="text-[11px] text-sub">AES-256-GCM…</span>
        </div>
      )}
      <span className="absolute bottom-2.5 rounded-full bg-ink/75 px-2.5 py-0.5 font-mono text-[10px] text-fg backdrop-blur">
        {muted ? "🔇 " : "🔊 "}0:{String(Math.round(meta.duration ?? 4)).padStart(2, "0")}
      </span>
    </div>
  );
}

/* ================= Regular File / Photo / Video Block ================= */

function FileBlock({ f, url }: { f: MsgFileMeta; url?: string }) {
  if (f.mime.startsWith("image")) {
    return url ? (
      <a href={url} download={f.name} target="_blank" rel="noreferrer">
        <img src={url} alt={f.name} className="mb-1.5 max-h-64 rounded-xl border border-line object-cover" />
      </a>
    ) : (
      <Spinner size={16} className="mb-1 text-acc" />
    );
  }
  if (f.mime.startsWith("video")) {
    return url ? (
      <video src={url} controls playsInline className="mb-1.5 max-h-64 rounded-xl border border-line" />
    ) : (
      <Spinner size={16} className="mb-1 text-acc" />
    );
  }
  return (
    <a
      href={url ?? `/api/files/${f.id}`}
      download={f.name}
      className="mb-1.5 flex items-center gap-2.5 rounded-xl border border-line bg-ink/40 px-3 py-2.5 hover:border-line2"
    >
      <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-panel3 text-acc">
        <IconImage size={16} />
      </span>
      <span className="min-w-0">
        <span className="block truncate text-[12.5px]">{f.name}</span>
        <span className="block text-[11px] text-dim">{(f.size / 1024).toFixed(1)} KB</span>
      </span>
    </a>
  );
}

function timeAgoLocal(iso: string): string {
  const d = Math.max(0, Date.now() - new Date(iso).getTime());
  const m = Math.floor(d / 60000);
  if (m < 1) return "now";
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/** Generates a valid 8kHz mono PCM WAV Blob for environments without a physical microphone */
function generateDemoVoiceWav(seconds = 3): Blob {
  const sampleRate = 8000;
  const numSamples = sampleRate * seconds;
  const buffer = new ArrayBuffer(44 + numSamples * 2);
  const view = new DataView(buffer);
  const writeStr = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };
  writeStr(0, "RIFF");
  view.setUint32(4, 36 + numSamples * 2, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeStr(36, "data");
  view.setUint32(40, numSamples * 2, true);
  for (let i = 0; i < numSamples; i++) {
    const t = i / sampleRate;
    const freq = t < 1 ? 440 : t < 2 ? 554.37 : 659.25;
    const env = Math.sin((Math.PI * (i % sampleRate)) / sampleRate);
    const sample = Math.sin(2 * Math.PI * freq * t) * env * 0.35;
    view.setInt16(44 + i * 2, sample * 32767, true);
  }
  return new Blob([buffer], { type: "audio/wav" });
}
