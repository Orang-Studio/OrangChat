import { readFileSync } from "node:fs";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// API + Socket.IO are same-origin in production; in dev, Vite proxies both
// to the backend (planned port 3001 - confirm with backend scaffold).
const BACKEND_URL = process.env.ORANGCHAT_BACKEND_URL ?? "http://localhost:3001";

// Attachments over 10MB are uploaded straight to OrangMove. Production serves it
// from this app's own origin under /orangmove/ (see deploy/nginx) because
// OrangMove pins CORS to a single origin; dev mirrors that so the upload path is
// identical in both. Needs OrangMove running locally on :8080.
const ORANGMOVE_URL = process.env.ORANGCHAT_ORANGMOVE_URL ?? "http://localhost:8080";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as {
  version: string;
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  server: {
    host: true, // expose on LAN/Tailscale, not just localhost
    port: 5173,
    proxy: {
      "/api": {
        target: BACKEND_URL,
        changeOrigin: false,
      },
      "/socket.io": {
        target: BACKEND_URL,
        ws: true,
      },
      "/orangmove": {
        target: ORANGMOVE_URL,
        changeOrigin: false,
        rewrite: (path) => path.replace(/^\/orangmove/, "/api"),
      },
      // No /attachments entry: like /uploads, stored bytes are served by nginx,
      // not the backend, so previews of local attachments need it in front.
    },
  },
});
