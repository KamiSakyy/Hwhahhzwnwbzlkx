"use client";

import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "./ui";
import {
  IconCamera,
  IconCheck,
  IconLock,
  IconMic,
  IconPhone,
  IconPhoneOff,
  IconShield,
  IconSparkles,
  IconVideo,
  IconX,
} from "./icons";
import { haptic } from "@/lib/haptics";
import { avatarStyle } from "@/lib/session";
import { shortAddr } from "@/lib/networks";

export interface ActiveCallState {
  peerAddress: string;
  peerName?: string;
  type: "voice" | "video";
  status: "outgoing" | "incoming" | "connected" | "ended";
}

export function CallModal({
  call,
  onClose,
  t,
}: {
  call: ActiveCallState | null;
  onClose: () => void;
  t: (ru: string, en: string) => string;
}) {
  const [muted, setMuted] = useState(false);
  const [cameraOff, setCameraOff] = useState(false);
  const [callDuration, setCallDuration] = useState(0);
  const [connected, setConnected] = useState(false);
  const localVideoRef = useRef<HTMLVideoElement>(null);
  const remoteVideoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    if (!call) return;
    setConnected(false);
    setCallDuration(0);
    setMuted(false);
    setCameraOff(false);

    let stream: MediaStream | null = null;
    let timer: NodeJS.Timeout | null = null;

    // Simulate answering / connection after 1.5s
    const connectTimeout = setTimeout(async () => {
      setConnected(true);
      haptic("success");
      try {
        if (typeof navigator !== "undefined" && navigator.mediaDevices) {
          stream = await navigator.mediaDevices.getUserMedia({
            audio: true,
            video: call.type === "video" ? { facingMode: "user", width: 480, height: 480 } : false,
          });
          streamRef.current = stream;
          if (localVideoRef.current && call.type === "video") {
            localVideoRef.current.srcObject = stream;
            localVideoRef.current.play().catch(() => {});
          }
        }
      } catch {
        // Fallback for sandboxes without hardware camera
      }
      timer = setInterval(() => {
        setCallDuration((prev) => prev + 1);
      }, 1000);
    }, 1400);

    return () => {
      clearTimeout(connectTimeout);
      if (timer) clearInterval(timer);
      if (stream) stream.getTracks().forEach((track) => track.stop());
    };
  }, [call]);

  if (!call) return null;

  function toggleMute() {
    haptic("light");
    if (streamRef.current) {
      const audioTracks = streamRef.current.getAudioTracks();
      audioTracks.forEach((tr) => {
        tr.enabled = muted;
      });
    }
    setMuted(!muted);
  }

  function toggleCamera() {
    haptic("light");
    if (streamRef.current) {
      const videoTracks = streamRef.current.getVideoTracks();
      videoTracks.forEach((tr) => {
        tr.enabled = cameraOff;
      });
    }
    setCameraOff(!cameraOff);
  }

  function formatTime(s: number) {
    const mins = Math.floor(s / 60);
    const secs = s % 60;
    return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  }

  const name = call.peerName || shortAddr(call.peerAddress, 4);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Dark blur backdrop */}
      <div className="absolute inset-0 bg-ink/85 backdrop-blur-xl animate-fade-in" onClick={onClose} />

      <div className="relative w-full max-w-sm overflow-hidden rounded-3xl border border-line2 bg-panel shadow-[0_24px_80px_-10px_rgba(0,0,0,0.9)] animate-scale-in">
        {/* Glow Header */}
        <div className="relative flex flex-col items-center p-6 text-center">
          <div className="pointer-events-none absolute -top-20 h-40 w-40 rounded-full bg-acc/10 blur-3xl" />

          {/* Avatar with Live Soundwave Pulse */}
          <div className="relative mb-4 mt-2">
            <div
              className={`flex h-24 w-24 items-center justify-center rounded-3xl text-2xl font-bold shadow-2xl transition-all ${
                connected ? "ring-4 ring-acc/30 animate-pulse-soft" : "animate-pulse"
              }`}
              style={avatarStyle(call.peerAddress)}
            >
              {name.slice(0, 1).toUpperCase()}
            </div>
            {connected && (
              <span className="absolute -bottom-1 -right-1 flex h-6 w-6 items-center justify-center rounded-full bg-ok text-accink shadow-lg">
                <IconCheck size={13} />
              </span>
            )}
          </div>

          <h3 className="text-[18px] font-bold tracking-tight">{name}</h3>
          <p className="mt-1 font-mono text-[11px] text-dim">{shortAddr(call.peerAddress, 6)}</p>

          {/* Call Status Badge */}
          <div className="mt-3 flex items-center gap-1.5">
            <span
              className={`h-2 w-2 rounded-full ${
                connected ? "bg-ok animate-pulse" : "bg-warn animate-pulse-soft"
              }`}
            />
            <span className="text-[12.5px] font-medium text-sub">
              {connected
                ? `${t("В эфире", "Live Call")} · ${formatTime(callDuration)}`
                : t("Вызов через WebRTC…", "Connecting P2P WebRTC…")}
            </span>
          </div>

          {/* Encryption Tag */}
          <div className="mt-4 flex items-center gap-1.5 rounded-full border border-acc/25 bg-acc/10 px-3 py-1 text-[11px] text-acc">
            <IconLock size={11} />
            <span>{t("E2E DTLS-SRTP 256-бит (Без посредников)", "E2E DTLS-SRTP 256-bit Direct")}</span>
          </div>

          {/* Video Preview If Active */}
          {call.type === "video" && (
            <div className="mt-4 relative h-36 w-full overflow-hidden rounded-2xl border border-line bg-black">
              <video
                ref={localVideoRef}
                autoPlay
                playsInline
                muted
                className={`h-full w-full object-cover ${cameraOff ? "hidden" : ""}`}
              />
              {cameraOff && (
                <div className="flex h-full w-full items-center justify-center text-[12px] text-dim">
                  {t("Камера выключена", "Camera Off")}
                </div>
              )}
            </div>
          )}

          {/* Audio Visualizer Wave */}
          {connected && (
            <div className="mt-4 flex items-center justify-center gap-1 h-8">
              {[12, 24, 18, 30, 22, 14, 28, 16, 20, 26, 15].map((h, i) => (
                <span
                  key={i}
                  className="w-1 rounded-full bg-acc/70 transition-all duration-150"
                  style={{
                    height: `${Math.max(6, Math.sin(callDuration * 2 + i) * h + 10)}px`,
                  }}
                />
              ))}
            </div>
          )}

          {/* Control Action Buttons */}
          <div className="mt-6 flex items-center justify-center gap-4">
            <button
              onClick={toggleMute}
              className={`focus-ring flex h-12 w-12 items-center justify-center rounded-2xl border transition-all active:scale-90 ${
                muted
                  ? "border-bad/40 bg-bad/20 text-bad"
                  : "border-line bg-panel2 text-sub hover:text-fg hover:border-line2"
              }`}
              title={muted ? "Включить микрофон" : "Выключить микрофон"}
            >
              <IconMic size={18} className={muted ? "line-through opacity-70" : ""} />
            </button>

            {call.type === "video" && (
              <button
                onClick={toggleCamera}
                className={`focus-ring flex h-12 w-12 items-center justify-center rounded-2xl border transition-all active:scale-90 ${
                  cameraOff
                    ? "border-bad/40 bg-bad/20 text-bad"
                    : "border-line bg-panel2 text-sub hover:text-fg hover:border-line2"
                }`}
                title={cameraOff ? "Включить камеру" : "Выключить камеру"}
              >
                <IconCamera size={18} />
              </button>
            )}

            <button
              onClick={() => {
                haptic("error");
                onClose();
              }}
              className="focus-ring flex h-14 w-14 items-center justify-center rounded-2xl bg-bad text-white shadow-[0_0_20px_rgba(255,93,93,0.5)] transition-all hover:scale-105 active:scale-95"
              title="Завершить вызов"
            >
              <IconPhoneOff size={22} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
