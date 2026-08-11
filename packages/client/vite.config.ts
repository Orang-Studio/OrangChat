import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const BACKEND_URL = process.env.ORANGCHAT_BACKEND_URL ?? "http://localhost:3001";

const ORANGMOVE_URL = process.env.ORANGCHAT_ORANGMOVE_URL ?? "http://localhost:8080";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as {
  version: string;
};


function serviceWorker(): Plugin {
  const entry = fileURLToPath(new URL("./src/sw/sw.ts", import.meta.url));
  let cached: string | null = null;

  const bundle = async (minify: boolean): Promise<string> => {
    if (cached) return cached;
    const { build } = await import("vite");
    const result = await build({
      configFile: false,
      logLevel: "warn",
      define: { "process.env.NODE_ENV": JSON.stringify("production") },
      build: {
        write: false,
        minify,
        target: "es2020",
        lib: { entry, formats: ["iife"], name: "OrangChatServiceWorker" },
        rollupOptions: { output: { inlineDynamicImports: true } },
      },
    });

    const outputs = (Array.isArray(result) ? result[0] : result) as {
      output: { type: string; code?: string }[];
    };
    const code = outputs.output.find((item) => item.type === "chunk")?.code;
    if (!code) throw new Error("service worker produced no chunk");
    cached = code;
    return code;
  };

  return {
    name: "orangchat-service-worker",
    apply: () => true,
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if ((req.url ?? "").split("?")[0] !== "/sw.js") return next();
        cached = null;
        void bundle(false).then(
          (code) => {
            res.setHeader("Content-Type", "text/javascript");
            res.setHeader("Cache-Control", "no-store");
            res.end(code);
          },
          () => next(),
        );
      });
    },
    async generateBundle() {
      this.emitFile({ type: "asset", fileName: "sw.js", source: await bundle(true) });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), serviceWorker()],
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
    },
  },
});
