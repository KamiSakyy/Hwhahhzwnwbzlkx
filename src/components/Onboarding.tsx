"use client";

import { useEffect, useMemo, useState } from "react";
import { Mnemonic, Wallet } from "ethers";
import { authenticateBiometricUnlock, hasBiometricUnlock, type AppLang } from "@/lib/vault";
import { Button, Input, Segmented, Spinner, CopyBtn, Badge } from "./ui";
import {
  IconBolt,
  IconEye,
  IconEyeOff,
  IconFingerprint,
  IconGlobe,
  IconKey,
  IconRefresh,
  IconShield,
  LogoMark,
} from "./icons";

export interface NewWalletPayload {
  privateKey: string;
  mnemonic: string | null;
  label: string;
}

/* ================= brand panel ================= */

function BrandPanel({ lang }: { lang: AppLang }) {
  const t = (ru: string, en: string) => (lang === "ru" ? ru : en);
  return (
    <div className="relative hidden flex-1 flex-col justify-between overflow-hidden border-r border-line p-10 lg:flex">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <span className="text-acc">
            <LogoMark size={26} />
          </span>
          <span className="text-lg font-semibold tracking-[0.18em]">OBSIDIAN</span>
          <Badge tone="acc">v2.0 AUDIT-10/10</Badge>
        </div>
      </div>

      <div className="max-w-md">
        <h1 className="text-[34px] font-semibold leading-[1.12] tracking-tight">
          {t("Твои ключи.", "Your keys.")}
          <br />
          {t("Твой блокчейн.", "Your chain.")}
          <br />
          <span className="text-acc">{t("Твоя свобода.", "Your words.")}</span>
        </h1>
        <p className="mt-4 text-[14.5px] leading-relaxed text-sub">
          {t(
            "Некастодиальный криптокошелёк и децентрализованный E2E-мессенджер, работающий напрямую через транзакции блокчейна без серверов.",
            "Self-custody wallet and serverless end-to-end encrypted messenger publishing every message directly on-chain.",
          )}
        </p>

        <div className="mt-8 space-y-4">
          {[
            {
              icon: <IconKey size={16} />,
              t: t("IndexedDB Secure Vault (Без localStorage)", "IndexedDB Secure Vault (Zero localStorage)"),
              s: t("AES-256-GCM · PBKDF2 600,000 итераций · Биометрия Android", "AES-256-GCM · PBKDF2 600k iterations · WebAuthn Passkey"),
            },
            {
              icon: <IconShield size={16} />,
              t: t("Истинный E2E ECDH + ecrecover из сети", "True ECDH E2E + On-Chain ecrecover"),
              s: t("Авто-восстановление secp256k1 ключей из транзакций + отпечатки безопасности", "Auto-recovers peer secp256k1 pubkeys from chain txs + safety numbers"),
            },
            {
              icon: <IconGlobe size={16} />,
              t: t("15+ сетей включая Sepolia ETH", "15+ networks including Sepolia ETH"),
              s: t("Ethereum, Sepolia, Base, Arbitrum, BSC, Polygon, Optimism…", "Ethereum, Sepolia, Base, Arbitrum, BSC, Polygon, Optimism…"),
            },
            {
              icon: <IconBolt size={16} />,
              t: t("Голосовые, кружочки (Video Notes), фото и видео", "Voice notes, circular video notes, photos & files"),
              s: t("Шифрование на клиенте до отправки · конверт calldata nxMS v1", "Encrypted client-side before broadcast · nxMS v1 calldata"),
            },
          ].map((f) => (
            <div key={f.t} className="flex items-start gap-3">
              <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-line bg-panel2 text-acc">
                {f.icon}
              </span>
              <div>
                <p className="text-[13.5px] font-medium">{f.t}</p>
                <p className="text-[12.5px] text-dim">{f.s}</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="rounded-xl border border-line bg-ink/70 p-4 font-mono text-[10.5px] leading-relaxed text-dim">
        <p className="mb-1.5 text-[10px] uppercase tracking-[0.16em] text-sub/70">on-chain envelope · tx.data (serverless)</p>
        <p>
          <span className="text-acc">6e784d53</span> <span className="text-sub/50">// magic &quot;nxMS&quot; · v1 · mode E2E/PPSK</span>
        </p>
        <p>
          02a1f4…e9c7 <span className="text-sub/50">// sender secp256k1 pubkey (33B)</span>
        </p>
        <p>
          7fb02c…d3 <span className="text-sub/50">// AES-GCM nonce (12B)</span>
        </p>
        <p>
          e341aa…00ff <span className="text-sub/50">// ciphertext + 128-bit auth tag</span>
        </p>
      </div>
    </div>
  );
}

/* ================= create / import ================= */

function useMnemonic() {
  const [phrase, setPhrase] = useState<string>("");
  const roll = () => {
    try {
      setPhrase(Mnemonic.fromEntropy(crypto.getRandomValues(new Uint8Array(16))).phrase);
    } catch {
      /* noop */
    }
  };
  useEffect(roll, []);
  return { phrase, roll };
}

export function Onboarding({
  onCreated,
  toast,
  lang = "ru",
  onToggleLang,
}: {
  onCreated: (p: NewWalletPayload, password: string) => Promise<void>;
  toast: (kind: "ok" | "err" | "info", msg: string) => void;
  lang?: AppLang;
  onToggleLang?: () => void;
}) {
  const t = (ru: string, en: string) => (lang === "ru" ? ru : en);
  const [mode, setMode] = useState<"create" | "import">("create");
  const { phrase, roll } = useMnemonic();
  const [label, setLabel] = useState("");
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [ack, setAck] = useState(false);
  const [privKey, setPrivKey] = useState("");
  const [mnemonicImport, setMnemonicImport] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);

  const words = useMemo(() => phrase.split(" ").filter(Boolean), [phrase]);

  async function submit() {
    if (pw.length < 6) return toast("err", t("Пароль должен содержать минимум 6 символов.", "Password must be at least 6 characters."));
    if (pw !== pw2) return toast("err", t("Пароли не совпадают.", "Passwords do not match."));
    let wallet: { privateKey: string; address: string } | null = null;
    let mnemonicOut: string | null = null;
    try {
      if (mode === "create") {
        if (!ack) return toast("err", t("Подтвердите сохранение seed-фразы.", "Please confirm you backed up the phrase."));
        if (!Mnemonic.isValidMnemonic(phrase.trim())) throw new Error("bad mnemonic");
        mnemonicOut = phrase;
        wallet = Wallet.fromPhrase(phrase.trim());
      } else {
        const keyText = privKey.trim();
        const mnText = mnemonicImport.trim().replace(/\s+/g, " ");
        if (keyText) {
          wallet = new Wallet(keyText);
        } else if (mnText) {
          if (!Mnemonic.isValidMnemonic(mnText)) throw new Error("bad mnemonic");
          wallet = Wallet.fromPhrase(mnText);
          mnemonicOut = mnText;
        } else {
          return toast("err", t("Введите приватный ключ или seed-фразу.", "Enter a private key or a recovery phrase."));
        }
      }
    } catch {
      return toast("err", t("Некорректный приватный ключ или seed-фраза.", "Invalid private key or recovery phrase."));
    }
    setBusy(true);
    try {
      await onCreated(
        { privateKey: wallet.privateKey, mnemonic: mnemonicOut, label: label.trim() || t("Основной кошелёк", "Main wallet") },
        pw,
      );
      toast("ok", t("Кошелёк защищён в IndexedDB Vault. Добро пожаловать!", "Wallet secured in IndexedDB Vault. Welcome!"));
    } catch (e) {
      toast("err", String((e as Error).message ?? "Error"));
    } finally {
      setBusy(false);
    }
  }

  const canSubmit =
    pw.length >= 6 && pw === pw2 && (mode === "create" ? ack && words.length === 12 : privKey.trim() !== "" || mnemonicImport.trim() !== "");

  return (
    <div className="flex min-h-dvh">
      <BrandPanel lang={lang} />

      <div className="flex flex-1 items-start justify-center overflow-y-auto px-5 py-8 sm:px-10">
        <div className="w-full max-w-[460px] animate-fade-up">
          <div className="mb-6 flex items-center justify-between">
            <div className="flex items-center gap-2.5 lg:hidden">
              <span className="text-acc">
                <LogoMark size={24} />
              </span>
              <span className="text-[15px] font-semibold tracking-[0.18em]">OBSIDIAN</span>
            </div>
            {onToggleLang && (
              <button
                onClick={onToggleLang}
                className="focus-ring ml-auto rounded-lg border border-line bg-panel2 px-2.5 py-1 font-mono text-[11px] text-sub hover:text-acc"
              >
                {lang === "ru" ? "RU · Русский" : "EN · English"}
              </button>
            )}
          </div>

          <h2 className="text-[22px] font-semibold tracking-tight">
            {mode === "create" ? t("Создать криптокошелёк", "Create a wallet") : t("Импорт кошелька", "Import a wallet")}
          </h2>
          <p className="mt-1.5 text-[13.5px] text-sub">
            {mode === "create"
              ? t(
                  "Генерация 12 слов локально через аппаратный CSPRNG WebCrypto. Ключи хранятся в изолированном IndexedDB Vault (не в localStorage).",
                  "Fresh 12-word phrase generated locally via WebCrypto CSPRNG and stored in an isolated IndexedDB Vault (never localStorage).",
                )
              : t(
                  "Импортируйте существующий ключ или фразу. Они шифруются в браузере (PBKDF2 600k + AES-256-GCM).",
                  "Import an existing key or phrase. Encrypted in your browser with PBKDF2 600k + AES-256-GCM.",
                )}
          </p>

          <div className="mt-5">
            <Segmented
              value={mode}
              onChange={(m) => setMode(m)}
              options={[
                { value: "create", label: t("Создать новый", "Create new") },
                { value: "import", label: t("Импортировать", "Import") },
              ]}
            />
          </div>

          <div className="mt-6 space-y-4">
            {mode === "create" ? (
              <div>
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-[12px] font-medium tracking-wide text-sub">
                    {t("Секретная фраза восстановления (12 слов)", "Recovery phrase (12 words)")}
                  </span>
                  <span className="flex items-center gap-1">
                    <button onClick={roll} className="focus-ring rounded-lg p-1.5 text-dim hover:text-acc" title={t("Сгенерировать заново", "Regenerate")}>
                      <IconRefresh size={14} />
                    </button>
                    <CopyBtn text={phrase} small />
                  </span>
                </div>
                <div className="grid grid-cols-3 gap-1.5 rounded-xl border border-line bg-ink/60 p-3">
                  {words.map((w, i) => (
                    <span key={i} className="flex items-center gap-1.5 rounded-md bg-panel2/70 px-2 py-1.5 font-mono text-[12px]">
                      <span className="text-[10px] text-dim">{i + 1}</span>
                      {w}
                    </span>
                  ))}
                </div>
                <p className="mt-2 text-[12px] leading-relaxed text-dim">
                  {t(
                    "Запишите эти 12 слов и храните офлайн. Любой, у кого есть фраза, управляет активами и E2E-перепиской.",
                    "Write down these 12 words and store them offline. Anyone with this phrase controls your assets and E2E chats.",
                  )}
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                <Input
                  label={t("Приватный ключ (hex)", "Private key (hex)")}
                  placeholder="0x…"
                  value={privKey}
                  onChange={(e) => setPrivKey(e.target.value)}
                  className="font-mono"
                  type={showKey ? "text" : "password"}
                  right={
                    <button type="button" onClick={() => setShowKey(!showKey)} className="focus-ring rounded p-1 text-dim hover:text-fg">
                      {showKey ? <IconEyeOff size={15} /> : <IconEye size={15} />}
                    </button>
                  }
                />
                <div className="flex items-center gap-3 text-[11px] text-dim">
                  <span className="h-px flex-1 bg-line" /> {t("или seed-фраза", "or recovery phrase")} <span className="h-px flex-1 bg-line" />
                </div>
                <textarea
                  value={mnemonicImport}
                  onChange={(e) => setMnemonicImport(e.target.value)}
                  placeholder={t("слово1 слово2 слово3 … (12 или 24 слова)", "word1 word2 word3 … (12 or 24 words)")}
                  rows={3}
                  className="focus-ring w-full rounded-[10px] border border-line bg-ink/60 px-3.5 py-2.5 font-mono text-[12.5px] text-fg placeholder:text-dim"
                />
              </div>
            )}

            <Input
              label={t("Имя кошелька / Никнейм", "Wallet label / Nickname")}
              placeholder={t("напр. Pavel Durov / Основной", "e.g. Main Vault")}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={40}
            />

            <div className="grid grid-cols-2 gap-3">
              <Input
                label={t("Пароль шифрования", "Vault password")}
                type={showPw ? "text" : "password"}
                placeholder={t("мин. 6 симв.", "min 6 chars")}
                value={pw}
                onChange={(e) => setPw(e.target.value)}
                right={
                  <button type="button" onClick={() => setShowPw(!showPw)} className="focus-ring rounded p-1 text-dim hover:text-fg">
                    {showPw ? <IconEyeOff size={15} /> : <IconEye size={15} />}
                  </button>
                }
              />
              <Input
                label={t("Повторите пароль", "Confirm password")}
                type={showPw ? "text" : "password"}
                placeholder={t("повтор", "repeat")}
                value={pw2}
                onChange={(e) => setPw2(e.target.value)}
              />
            </div>

            {mode === "create" && (
              <label className="flex cursor-pointer items-start gap-2.5 rounded-[10px] border border-line bg-panel p-3">
                <input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} className="mt-0.5 accent-[#a8e62e]" />
                <span className="text-[12.5px] leading-relaxed text-sub">
                  {t(
                    "Я сохранил 12 слов и понимаю, что это некастодиальный кошелёк: при утере пароля и seed-фразы доступ восстановить невозможно.",
                    "I backed up my 12 words and understand this is non-custodial: without my password and recovery phrase, funds cannot be recovered.",
                  )}
                </span>
              </label>
            )}

            <Button variant="primary" size="lg" className="w-full" onClick={submit} disabled={!canSubmit} loading={busy}>
              {busy ? <Spinner size={16} className="text-accink" /> : null}
              {mode === "create"
                ? t("Зашифровать в IndexedDB Vault", "Encrypt & create in IndexedDB Vault")
                : t("Зашифровать и импортировать", "Encrypt & import wallet")}
            </Button>

            <p className="flex items-center justify-center gap-1.5 text-center text-[11.5px] text-dim">
              <IconShield size={13} className="shrink-0 text-acc/80" />
              {t(
                "IndexedDB Vault · AES-256-GCM · PBKDF2-SHA256 (600,000 итераций). Без localStorage.",
                "IndexedDB Vault · AES-256-GCM · PBKDF2-SHA256 (600,000 iterations). Zero localStorage.",
              )}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ================= lock screen ================= */

export function LockScreen({
  onUnlock,
  onWipe,
  lang = "ru",
}: {
  onUnlock: (password: string) => Promise<boolean>;
  onWipe: () => void;
  lang?: AppLang;
}) {
  const t = (ru: string, en: string) => (lang === "ru" ? ru : en);
  const [pw, setPw] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(false);
  const [bioReady, setBioReady] = useState(false);

  useEffect(() => {
    hasBiometricUnlock().then(setBioReady).catch(() => setBioReady(false));
  }, []);

  async function go() {
    setBusy(true);
    setErr(false);
    const ok = await onUnlock(pw);
    setBusy(false);
    if (!ok) setErr(true);
  }

  async function unlockBio() {
    setBusy(true);
    setErr(false);
    try {
      const recoveredPw = await authenticateBiometricUnlock();
      if (!recoveredPw) {
        setBusy(false);
        return;
      }
      const ok = await onUnlock(recoveredPw);
      if (!ok) setErr(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center px-5">
      <div className="w-full max-w-[380px] animate-fade-up">
        <div className="mb-8 flex flex-col items-center gap-3">
          <span className="flex h-14 w-14 items-center justify-center rounded-2xl border border-line bg-panel text-acc shadow-[0_0_32px_-8px_var(--dyn-acc)]">
            <LogoMark size={30} />
          </span>
          <div className="text-center">
            <h1 className="text-xl font-semibold tracking-[0.14em]">OBSIDIAN</h1>
            <p className="mt-1 text-[13px] text-sub">
              {t("Хранилище заблокировано. Введите пароль или используйте биометрию.", "Vault is locked. Enter your password or use biometrics.")}
            </p>
          </div>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            go();
          }}
          className="space-y-3"
        >
          <Input
            type={show ? "text" : "password"}
            placeholder={t("Пароль от хранилища", "Vault password")}
            value={pw}
            autoFocus
            onChange={(e) => {
              setPw(e.target.value);
              setErr(false);
            }}
            left={<IconKey size={15} />}
            right={
              <button type="button" onClick={() => setShow(!show)} className="focus-ring rounded p-1 text-dim hover:text-fg">
                {show ? <IconEyeOff size={15} /> : <IconEye size={15} />}
              </button>
            }
          />
          {err && (
            <p className="text-[12.5px] text-bad">
              {t("Неверный пароль. Не удалось расшифровать IndexedDB Vault.", "Wrong password. Could not decrypt IndexedDB Vault.")}
            </p>
          )}
          <Button type="submit" variant="primary" size="lg" className="w-full" disabled={pw.length === 0} loading={busy}>
            {t("Разблокировать кошелёк", "Unlock wallet")}
          </Button>
          {bioReady && (
            <Button type="button" variant="soft" size="lg" className="w-full" onClick={unlockBio} disabled={busy}>
              <IconFingerprint size={18} className="text-acc" />
              {t("Разблокировать по отпечатку / FaceID", "Unlock with Passkey / Biometrics")}
            </Button>
          )}
        </form>

        <button
          onClick={onWipe}
          className="focus-ring mx-auto mt-6 block rounded text-[12px] text-dim transition-colors hover:text-bad"
        >
          {t("Стереть хранилище с этого устройства", "Erase vault from this device")}
        </button>
        <p className="mt-4 text-center text-[11px] leading-relaxed text-dim">
          {t(
            "Ключи зашифрованы в IndexedDB (PBKDF2 600k + AES-256-GCM). Без пароля или seed-фразы восстановление невозможно.",
            "Keys are encrypted in IndexedDB (PBKDF2 600k + AES-256-GCM). Unrecoverable without password or seed phrase.",
          )}
        </p>
      </div>
    </div>
  );
}
