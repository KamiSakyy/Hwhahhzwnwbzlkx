import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
  themeColor: "#06080b",
};

export const metadata: Metadata = {
  title: "OBSIDIAN — Non-Custodial Web3 Wallet & On-Chain E2E Messenger",
  description:
    "Production-grade multi-chain crypto wallet (Ethereum, Sepolia, Base, Arbitrum, BSC, Polygon) and serverless on-chain E2E encrypted messenger with voice & circular video notes.",
  manifest: "/manifest.json",
  appleWebApp: {
    capable: true,
    statusBarStyle: "black-translucent",
    title: "OBSIDIAN",
  },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ru">
      <body className="antialiased selection:bg-acc/30">{children}</body>
    </html>
  );
}
