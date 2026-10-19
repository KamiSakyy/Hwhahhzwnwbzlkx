import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,

  /**
   * Security headers — preview-safe.
   *
   * `X-Frame-Options: DENY` was removed deliberately: hosted preview
   * environments (arena preview, dashboards, docs, mobile shells) embed the
   * app inside an iframe, and DENY makes the browser refuse to render it,
   * which looks exactly like "the site does not launch".
   *
   * `Permissions-Policy` grants camera/microphone to the app itself AND to
   * embedding origins, otherwise voice messages and circular video notes
   * silently fail with NotAllowedError inside an embedded preview.
   */
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          {
            key: "Permissions-Policy",
            value: "camera=*, microphone=*, clipboard-write=*, fullscreen=*, geolocation=()",
          },
          { key: "X-DNS-Prefetch-Control", value: "on" },
        ],
      },
      {
        // Never cache the app shell aggressively: a stale HTML document
        // pointing at purged JS chunks renders a blank screen after deploys.
        source: "/",
        headers: [{ key: "Cache-Control", value: "public, max-age=0, must-revalidate" }],
      },
    ];
  },
};

export default nextConfig;
