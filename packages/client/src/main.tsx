import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { registerRealtime } from "./features/chat/realtime";
import { initPrefs } from "./lib/prefs";
import { initTheme } from "./lib/theme";
import { initViewport } from "./lib/viewport";
import { initPlugins } from "./features/plugins/store";
import { initInstalledTheme } from "./features/plugins/themes";
import "./styles/index.css";

initTheme();
initPrefs();
initViewport();
// Installed theme first (recolours variables), then enabled plugins, so a
// plugin that reads a colour sees the theme's value, not the stock one.
initInstalledTheme();
initPlugins();

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

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
