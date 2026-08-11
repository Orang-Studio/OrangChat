import { shell } from "electron";

export const APP_URL = process.env.ORANGCHAT_URL ?? "https://chat.oranges.lt";
export const APP_ORIGIN = new URL(APP_URL).origin;
export const PROTOCOL = "orangchat";
export const APP_USER_MODEL_ID = "lt.oranges.orangchat";

export function isAppUrl(target: string): boolean {
  try {
    return new URL(target).origin === APP_ORIGIN;
  } catch {
    return false;
  }
}

export function openExternalIfWeb(url: string): void {
  try {
    if (/^https?:$/.test(new URL(url).protocol)) void shell.openExternal(url);
  } catch {
  }
}

export function deepLinkToAppUrl(link: string): string | null {
  try {
    const url = new URL(link);
    if (url.protocol !== `${PROTOCOL}:`) return null;
    const path = `${url.hostname}${url.pathname}`.replace(/^\/+/, "");
    return new URL(`/${path}${url.search}`, APP_URL).toString();
  } catch {
    return null;
  }
}
