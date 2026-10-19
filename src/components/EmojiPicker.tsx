import { useState } from "react";
import { IconSmilePlus } from "./icons";

const EMOJI_LIST = [
  "👍", "👎", "❤️", "🔥", "🎉", "😂", "😱", "🤩", "💎", "⚡",
  "🛡️", "🔮", "🚀", "🦾", "💰", "👑", "🎊", "👏", "💪", "🌙",
];

export function EmojiPicker({
  onSelect,
}: {
  onSelect: (emoji: string) => void;
}) {
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="focus-ring rounded-lg border border-line bg-panel2 p-1.5 text-dim hover:text-acc"
        title="Add emoji reaction"
      >
        <IconSmilePlus size={15} />
      </button>
    );
  }
  return (
    <div className="relative z-30">
      <div className="absolute bottom-10 left-0 rounded-xl border border-line2 bg-panel2 p-2 shadow-2xl">
        <div className="grid grid-cols-5 gap-1">
          {EMOJI_LIST.map((e) => (
            <button
              key={e}
              onClick={() => {
                onSelect(e);
                setOpen(false);
              }}
              className="focus-ring flex h-8 w-8 items-center justify-center rounded-lg text-base hover:bg-panel3"
            >
              {e}
            </button>
          ))}
        </div>
      </div>
      <button
        onClick={() => setOpen(false)}
        className="focus-ring rounded-lg border border-line bg-panel2 p-1.5 text-acc"
      >
        <span className="text-[10px] font-bold">✕</span>
      </button>
    </div>
  );
}

/** Quick message templates for one-tap sending */
export const QUICK_TEMPLATES = [
  { label: "Send to friend", ruLabel: "Отправить другу", text: "💎 Sending crypto — instant, secure, on-chain.", amount: "0.0001" },
  { label: "Get paid / Invoice", ruLabel: "Получить / Счет", text: "📄 Invoice / Get paid request — please confirm and send.", amount: "0" },
  { label: "Buy NFT / Mint", ruLabel: "Купить NFT / Mint", text: "🎨 NFT mint / purchase request — please send transaction.", amount: "0.001" },
];
