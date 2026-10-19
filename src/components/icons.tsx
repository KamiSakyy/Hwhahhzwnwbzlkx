import type { SVGProps } from "react";

export interface IconProps {
  size?: number;
  className?: string;
  strokeWidth?: number;
}

function svgProps(p: IconProps, props?: SVGProps<SVGSVGElement>) {
  const { size = 18, className, strokeWidth = 1.8, ...rest } = { ...p, ...props };
  return {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    className,
    ...rest,
  };
}

export const LogoMark = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 2 4 8.5 12 22 20 8.5 12 2Z" fill="currentColor" stroke="none" opacity="0.92" />
    <path d="M12 2 8.2 8.5 12 22l3.8-13.5L12 2Z" fill="var(--color-ink)" stroke="none" opacity="0.55" />
  </svg>
);

export const IconGrid = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
  </svg>
);
export const IconWallet = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M19 7V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h13a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1H6" />
    <circle cx="16" cy="13.5" r="1" fill="currentColor" stroke="none" />
  </svg>
);
export const IconSend = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M20.5 3.5 3.8 9.9c-.9.35-.85 1.65.08 1.93l6.4 1.9 1.9 6.4c.28.93 1.58.98 1.93.08L20.5 3.5Z" />
    <path d="m10.3 13.7 4.8-4.8" />
  </svg>
);
export const IconChat = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M21 12a8 8 0 0 1-8 8H4l1.7-3.4A8 8 0 1 1 21 12Z" />
    <path d="M8.5 10.5h7M8.5 13.5h4.5" />
  </svg>
);
export const IconGear = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="3.2" />
    <path d="M19 12c0-.5-.05-.98-.15-1.44l2-1.55-2-3.46-2.35.95a7 7 0 0 0-2.5-1.45L13.6 2.7h-4l-.4 2.35a7 7 0 0 0-2.5 1.45l-2.35-.95-2 3.46 2 1.55a7.6 7.6 0 0 0 0 2.88l-2 1.55 2 3.46 2.35-.95a7 7 0 0 0 2.5 1.45l.4 2.35h4l.4-2.35a7 7 0 0 0 2.5-1.45l2.35.95 2-3.46-2-1.55c.1-.46.15-.94.15-1.44Z" />
  </svg>
);
export const IconLock = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="4.5" y="10.5" width="15" height="10" rx="2" />
    <path d="M8 10.5V7.8a4 4 0 0 1 8 0v2.7" />
    <circle cx="12" cy="15.5" r="1.1" fill="currentColor" stroke="none" />
  </svg>
);
export const IconCopy = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="9" y="9" width="11" height="11" rx="2" />
    <path d="M5.5 15H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v.5" />
  </svg>
);
export const IconCheck = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m4.5 12.5 5 5 10-11" />
  </svg>
);
export const IconX = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m6 6 12 12M18 6 6 18" />
  </svg>
);
export const IconExternal = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M14 4h6v6M20 4l-9 9" />
    <path d="M19 13.5V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4.5" />
  </svg>
);
export const IconQr = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="1" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="1" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1" />
    <path d="M13.5 13.5h3v3h-3zM17.5 17.5h3v3h-3z" fill="currentColor" stroke="none" />
  </svg>
);
export const IconImage = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
    <circle cx="9" cy="10" r="1.6" />
    <path d="m5 18.5 5-5 3 3 3.5-3.5 4 4" />
  </svg>
);
export const IconVideo = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="3" y="6" width="13" height="12" rx="2" />
    <path d="m16 10.5 5-3v9l-5-3" />
  </svg>
);
export const IconFile = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5L13.5 3Z" />
    <path d="M13.5 3v5.5H19" />
  </svg>
);
export const IconPlus = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);
export const IconChevronDown = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m6 9 6 6 6-6" />
  </svg>
);
export const IconSearch = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="11" cy="11" r="6.5" />
    <path d="m20 20-4.35-4.35" />
  </svg>
);
export const IconRefresh = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M20 11.5A8 8 0 0 0 5.6 7M4 12.5A8 8 0 0 0 18.4 17" />
    <path d="M20 4v4h-4M4 20v-4h4" />
  </svg>
);
export const IconEye = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);
export const IconEyeOff = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M4 4l16 16" />
    <path d="M9.9 5.9A9.5 9.5 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17.6 17.6 0 0 1-3.2 3.8M6 8.2A16.9 16.9 0 0 0 2.5 12S6 18.5 12 18.5c1.2 0 2.3-.25 3.3-.66" />
  </svg>
);
export const IconShield = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 2.5 4.5 5.5v6c0 4.8 3.2 8.3 7.5 9.9 4.3-1.6 7.5-5.1 7.5-9.9v-6L12 2.5Z" />
    <path d="m8.8 11.8 2.3 2.3 4.2-4.4" />
  </svg>
);
export const IconBolt = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M13 2.5 4.5 13.5H11l-1 8 8.5-11H12l1-8Z" />
  </svg>
);
export const IconClock = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 7.5V12l3 2" />
  </svg>
);
export const IconAlert = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 3.5 1.9 20.1h20.2L12 3.5Z" />
    <path d="M12 10v4.5" />
    <circle cx="12" cy="17.3" r="0.9" fill="currentColor" stroke="none" />
  </svg>
);
export const IconKey = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="8" cy="14" r="4.5" />
    <path d="m11.5 10.5 8-8M17 5l2.5 2.5M14 8l2 2" />
  </svg>
);
export const IconTrash = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M4 6.5h16M9.5 6V4.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V6M6.5 6.5 7.5 20a1.5 1.5 0 0 0 1.5 1.4h6a1.5 1.5 0 0 0 1.5-1.4l1-13.5" />
  </svg>
);
export const IconDownload = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 3.5V15M7.5 10.5 12 15l4.5-4.5" />
    <path d="M4.5 17.5v1a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-1" />
  </svg>
);
export const IconDrop = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 3s6.5 6.8 6.5 11a6.5 6.5 0 0 1-13 0C5.5 9.8 12 3 12 3Z" />
  </svg>
);
export const IconArrowUpRight = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M7 17 17 7M9 7h8v8" />
  </svg>
);
export const IconArrowDownLeft = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M17 7 7 17M15 17H7V9" />
  </svg>
);
export const IconClip = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m20 11.5-7.8 7.8a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.4a1.7 1.7 0 0 1-2.4-2.4l7.8-7.7" />
  </svg>
);
export const IconUsers = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M3 20a6 6 0 0 1 12 0" />
    <path d="M16.5 4.9a3.5 3.5 0 0 1 0 6.2M21 20a6 6 0 0 0-4.5-5.8" />
  </svg>
);
export const IconInfo = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 11v5" />
    <circle cx="12" cy="8" r="0.9" fill="currentColor" stroke="none" />
  </svg>
);
export const IconGlobe = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M3.5 12h17M12 3.5c2.5 2.3 3.8 5.2 3.8 8.5s-1.3 6.2-3.8 8.5c-2.5-2.3-3.8-5.2-3.8-8.5s1.3-6.2 3.8-8.5Z" />
  </svg>
);
export const IconLogout = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M14 4H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h7" />
    <path d="M17 8.5 20.5 12 17 15.5M9.5 12h11" />
  </svg>
);
export const IconMic = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="9" y="3" width="6" height="11" rx="3" />
    <path d="M5 11a7 7 0 0 0 14 0M12 18v3M8.5 21h7" />
  </svg>
);
export const IconVideoCircle = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="9" strokeDasharray="3 2" />
    <circle cx="12" cy="12" r="6.2" />
    <polygon points="10.6,9.5 15,12 10.6,14.5" fill="currentColor" stroke="none" />
  </svg>
);
export const IconPlay = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <polygon points="6.5,4.5 19,12 6.5,19.5" fill="currentColor" stroke="none" />
  </svg>
);
export const IconPause = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect x="6.5" y="5" width="3.8" height="14" rx="1" fill="currentColor" stroke="none" />
    <rect x="13.7" y="5" width="3.8" height="14" rx="1" fill="currentColor" stroke="none" />
  </svg>
);
export const IconFingerprint = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 3a8 8 0 0 0-7 4M19 7a8 8 0 0 0-3-3.1" />
    <path d="M7.2 18A9 9 0 0 1 6 12a6 6 0 0 1 12 0c0 2.2-.5 4.3-1.4 6" />
    <path d="M10 19.5A10 10 0 0 1 9 12a3 3 0 0 1 6 0c0 2.8-.6 5.4-1.8 7.6" />
    <path d="M12 12v6" />
  </svg>
);
export const IconStar = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <polygon points="12,3 14.8,8.7 21,9.6 16.5,14 17.6,20.2 12,17.3 6.4,20.2 7.5,14 3,9.6 9.2,8.7" />
  </svg>
);
export const IconUserPlus = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M3 20a6 6 0 0 1 12 0" />
    <path d="M19 8v6M16 11h6" />
  </svg>
);
export const IconReply = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M9 14 4 9l5-5" />
    <path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5V19" />
  </svg>
);
export const IconPalette = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M12 3a9 9 0 1 0 0 18c1.5 0 2.5-1 2.5-2.3 0-.6-.2-1.1-.6-1.5-.3-.4-.5-.8-.5-1.3 0-1.1.9-1.9 2-1.9H17a4 4 0 0 0 4-4c0-3.9-4-7-9-7Z" />
    <circle cx="7.5" cy="10.5" r="1" fill="currentColor" stroke="none" />
    <circle cx="10.5" cy="7.5" r="1" fill="currentColor" stroke="none" />
    <circle cx="14.5" cy="7.5" r="1" fill="currentColor" stroke="none" />
  </svg>
);
export const IconCamera = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M4 8h3l1.8-2.4A1.5 1.5 0 0 1 10 5h4a1.5 1.5 0 0 1 1.2.6L17 8h3a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z" />
    <circle cx="12" cy="13.5" r="3.2" />
  </svg>
);
export const IconSmilePlus = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M8 10h.01M16 10h.01" />
    <path d="M9.5 14.5c.8 1 2 1.5 2.5 1.5s1.7-.5 2.5-1.5" />
    <path d="m16.5 8.5 1.5 1.5" />
  </svg>
);
export const IconSparkles = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m12 3 1.8 4.6L18.5 9l-4.7 1.4L12 15l-1.8-4.6L5.5 9l4.7-1.4L12 3Z" />
    <path d="m18.5 15 .9 2.2 2.1.8-2.1.8-.9 2.2-.9-2.2-2.1-.8 2.1-.8.9-2.2Z" />
  </svg>
);

export const IconPhone = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
  </svg>
);

export const IconPhoneOff = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m2 2 20 20M10.6 6.6c.5-.9 1.5-1.6 2.6-1.6h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L17.2 13M14 19.9a19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72" />
  </svg>
);

export const IconSwap = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="m16 3 4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16" />
  </svg>
);

export const IconLayers = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <polygon points="12 2 2 7 12 12 22 7 12 2" />
    <polyline points="2 17 12 22 22 17" />
    <polyline points="2 12 12 17 22 12" />
  </svg>
);

export const IconBridge = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M4 19V9a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10M2 19h20M7 7v12M17 7v12" />
    <path d="M4 11c4-2 12-2 16 0" />
  </svg>
);

export const IconChartLine = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M3 3v18h18" />
    <path d="m19 9-5 5-4-4-3 3" />
  </svg>
);

export const IconPhoto = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <rect width="18" height="18" x="3" y="3" rx="2" ry="2" />
    <circle cx="9" cy="9" r="2" />
    <path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" />
  </svg>
);

export const IconForward = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <polyline points="15 14 20 9 15 4" />
    <path d="M4 20v-7a4 4 0 0 1 4-4h12" />
  </svg>
);

export const IconCheckCheck = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M18 6 7 17l-5-5" />
    <path d="m22 10-7.5 7.5L13 16" />
  </svg>
);

export const IconCommand = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3 3 3 0 0 0 3-3 3 3 0 0 0-3-3H6a3 3 0 0 0-3 3 3 3 0 0 0 3 3 3 3 0 0 0 3-3V6a3 3 0 0 0-3-3 3 3 0 0 0-3 3 3 3 0 0 0 3 3h12a3 3 0 0 0 3-3 3 3 0 0 0-3-3z" />
  </svg>
);

export const IconFlame = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z" />
  </svg>
);

export const IconSliders2 = (p: IconProps) => (
  <svg {...svgProps(p)}>
    <line x1="4" x2="4" y1="21" y2="14" />
    <line x1="4" x2="4" y1="10" y2="3" />
    <line x1="12" x2="12" y1="21" y2="12" />
    <line x1="12" x2="12" y1="8" y2="3" />
    <line x1="20" x2="20" y1="21" y2="16" />
    <line x1="20" x2="20" y1="12" y2="3" />
    <line x1="1" x2="7" y1="14" y2="14" />
    <line x1="9" x2="15" y1="8" y2="8" />
    <line x1="17" x2="23" y1="16" y2="16" />
  </svg>
);
