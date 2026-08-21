import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { registerRealtime } from "./features/chat/realtime";
import { registerE2eeBootstrap } from "./features/e2ee/bootstrap";
import { initLanguage, useCatalogReady, useLanguageKey } from "./lib/i18n";
import { SplashScreen } from "./components/SplashScreen";
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
const initialCssOverridesActive = allowsCssOverrides(window.location.pathname);
initInstalledTheme(initialCssOverridesActive);
initPlugins(initialCssOverridesActive);

registerServiceWorker();

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

registerRealtime(queryClient);

registerE2eeBootstrap();


function Localized() {
  const language = useLanguageKey();
  if (!useCatalogReady(language)) return <SplashScreen />;
  return <App key={language} />;
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
