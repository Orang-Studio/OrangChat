import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { registerRealtime } from "./features/chat/realtime";
import { registerE2eeBootstrap } from "./features/e2ee/bootstrap";
import { initLanguage, useLanguageKey } from "./lib/i18n";
import { initPrefs } from "./lib/prefs";
import { initTheme } from "./lib/theme";
import { initViewport } from "./lib/viewport";
import { initPlugins } from "./features/plugins/store";
import { initInstalledTheme } from "./features/plugins/themes";
import { registerServiceWorker } from "./lib/serviceWorker";
import { initOfflineQueryCache } from "./lib/offlineQueryCache";
import { CssOverrideScope, allowsCssOverrides } from "./app/CssOverrideScope";
import { ErrorBoundary } from "./app/ErrorBoundary";
import "./styles/index.css";

initTheme();
initLanguage();
initPrefs();
initViewport();
// Landing and legal pages always keep the shipped design. Everywhere else,
// installed theme first (recolours variables), then enabled plugins, so a
// plugin that reads a colour sees the theme's value, not the stock one.
const initialCssOverridesActive = allowsCssOverrides(window.location.pathname);
initInstalledTheme(initialCssOverridesActive);
initPlugins(initialCssOverridesActive);

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

initOfflineQueryCache(queryClient);

// Socket events → query cache sync (messages, presence, channels, members).
registerRealtime(queryClient);

// Enrol this device's encryption identity if the account has none, and audit
// the account's own device log on every start.
registerE2eeBootstrap();

/**
 * Rebuilds the app when the interface language changes.
 *
 * `t()` is a plain function rather than a hook, so nothing re-renders on its
 * own when the catalogue underneath it changes - this is what makes the new
 * language appear. See `lib/i18n`.
 */
function Localized() {
  return <App key={useLanguageKey()} />;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <CssOverrideScope />
          <Localized />
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);
