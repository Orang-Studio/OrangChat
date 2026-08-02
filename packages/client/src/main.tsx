import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { registerRealtime } from "./features/chat/realtime";
import { registerE2eeBootstrap } from "./features/e2ee/bootstrap";
import { initPrefs } from "./lib/prefs";
import { initTheme } from "./lib/theme";
import { initViewport } from "./lib/viewport";
import { initPlugins } from "./features/plugins/store";
import { initInstalledTheme } from "./features/plugins/themes";
import { registerServiceWorker } from "./lib/serviceWorker";
import "./styles/index.css";

initTheme();
initPrefs();
initViewport();
// Installed theme first (recolours variables), then enabled plugins, so a
// plugin that reads a colour sees the theme's value, not the stock one.
initInstalledTheme();
initPlugins();

// Serves avatars, emoji and proxied images from disk on later loads, and is
// what notifications are delivered through once they're enabled.
registerServiceWorker();

// Right-click interactions belong to the app's context-menu components. Keep
// the browser (and Electron's embedded Chromium) from opening a competing menu.
document.addEventListener("contextmenu", (event) => event.preventDefault());

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

// Socket events → query cache sync (messages, presence, channels, members).
registerRealtime(queryClient);

// Enrol this device's encryption identity if the account has none, and audit
// the account's own device log on every start.
registerE2eeBootstrap();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
