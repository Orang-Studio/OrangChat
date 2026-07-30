import { useEffect } from "react";
import { useAuthStore } from "../stores/auth";
import { desktop } from "./desktop";

/** The mark shipped with the app, used whenever the user has not chosen one. */
export const DEFAULT_APP_ICON = "/icon.svg";

/**
 * Swap the browser tab favicon. The tag has to be replaced rather than mutated:
 * some browsers ignore an `href` change on an already-resolved <link rel=icon>,
 * and the shipped tag declares `type="image/svg+xml"`, which is wrong for the
 * PNG/JPEG an upload produces.
 */
function setFavicon(url: string): void {
  document.querySelectorAll<HTMLLinkElement>("link[rel~='icon']").forEach((el) => el.remove());
  const link = document.createElement("link");
  link.rel = "icon";
  if (url.endsWith(".svg")) link.type = "image/svg+xml";
  link.href = url;
  document.head.appendChild(link);
}

/**
 * Hand the icon to the Electron shell, which owns the window frame, the tray and
 * the Windows taskbar - none of which the page can touch. Fetched here rather
 * than in main because only the renderer carries the session cookie, and the
 * url may be a same-origin route that main cannot resolve.
 */
async function setDesktopIcon(url: string | null): Promise<void> {
  const setIcon = desktop?.setAppIcon;
  if (!setIcon) return;
  if (!url) {
    setIcon(null);
    return;
  }
  try {
    const res = await fetch(url, { credentials: "include" });
    if (!res.ok) return;
    const blob = await res.blob();
    const reader = new FileReader();
    const dataUrl = await new Promise<string>((resolve, reject) => {
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(blob);
    });
    setIcon(dataUrl);
  } catch {
    // Leave the bundled icon in place; a failed swap is not worth surfacing.
  }
}

/** Point every client-side surface at `url`, or back at the shipped mark. */
export function applyAppIcon(url: string | null | undefined): void {
  setFavicon(url || DEFAULT_APP_ICON);
  void setDesktopIcon(url || null);
}

/** Keep the app icon in sync with the signed-in user's choice. */
export function useAppIcon(): void {
  const url = useAuthStore((s) => s.user?.appIconUrl);
  useEffect(() => {
    applyAppIcon(url ?? null);
  }, [url]);
}
