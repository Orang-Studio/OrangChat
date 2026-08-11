import { useEffect } from "react";
import { useAuthStore } from "../stores/auth";
import { desktop } from "./desktop";


export const DEFAULT_APP_ICON = "/icon.svg";


function setFavicon(url: string): void {
  document.querySelectorAll<HTMLLinkElement>("link[rel~='icon']").forEach((el) => el.remove());
  const link = document.createElement("link");
  link.rel = "icon";
  if (url.endsWith(".svg")) link.type = "image/svg+xml";
  link.href = url;
  document.head.appendChild(link);
}


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
  }
}


export function applyAppIcon(url: string | null | undefined): void {
  setFavicon(url || DEFAULT_APP_ICON);
  void setDesktopIcon(url || null);
}


export function useAppIcon(): void {
  const url = useAuthStore((s) => s.user?.appIconUrl);
  useEffect(() => {
    applyAppIcon(url ?? null);
  }, [url]);
}
