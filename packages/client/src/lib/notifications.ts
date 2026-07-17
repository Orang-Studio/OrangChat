import { api } from "./api";

const PREF_KEY = "oc-notifications";

export function notificationsPreferred(): boolean {
  return localStorage.getItem(PREF_KEY) !== "off";
}
export function setNotificationsPreferred(on: boolean): void {
  localStorage.setItem(PREF_KEY, on ? "on" : "off");
}
export function notificationsSupported(): boolean {
  return typeof window !== "undefined" && "Notification" in window && "serviceWorker" in navigator && "PushManager" in window;
}
export function notificationPermission(): NotificationPermission {
  return notificationsSupported() ? Notification.permission : "denied";
}
export async function requestNotificationPermission(): Promise<boolean> {
  if (!notificationsSupported()) return false;
  if (Notification.permission === "granted") return true;
  if (Notification.permission === "denied") return false;
  return (await Notification.requestPermission()) === "granted";
}

let navigatorFn: ((href: string) => void) | null = null;
export function setNotificationNavigator(fn: (href: string) => void): void {
  navigatorFn = fn;
}

function decodeKey(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.padEnd(Math.ceil(value.length / 4) * 4, "=").replace(/-/g, "+").replace(/_/g, "/");
  const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  return bytes as Uint8Array<ArrayBuffer>;
}

export async function enablePushNotifications(): Promise<boolean> {
  if (!(await requestNotificationPermission())) return false;
  const config = await api<{ webPushPublicKey: string | null }>("/push/config");
  if (!config.webPushPublicKey) return false;
  const registration = await navigator.serviceWorker.register("/sw.js");
  await navigator.serviceWorker.ready;
  let subscription = await registration.pushManager.getSubscription();
  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: decodeKey(config.webPushPublicKey),
    });
  }
  const json = subscription.toJSON();
  await api<void>("/push/subscriptions", {
    method: "PUT",
    json: {
      kind: "webpush",
      endpoint: subscription.endpoint,
      p256dh: json.keys?.p256dh,
      auth: json.keys?.auth,
      label: navigator.userAgent.slice(0, 200),
    },
  });
  setNotificationsPreferred(true);
  return true;
}

export async function disablePushNotifications(): Promise<void> {
  setNotificationsPreferred(false);
  if (!notificationsSupported()) return;
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (!subscription) return;
  await api<void>("/push/subscriptions", { method: "DELETE", json: { endpoint: subscription.endpoint } }).catch(() => {});
  await subscription.unsubscribe();
}

export async function restorePushNotifications(): Promise<void> {
  if (notificationsPreferred() && notificationPermission() === "granted") {
    await enablePushNotifications();
  }
}

interface NotifyOptions { title: string; body: string; icon?: string; href?: string; tag?: string }
export function canNotify(): boolean {
  return notificationPermission() === "granted" && notificationsPreferred();
}
export function notify({ title, body, icon, href, tag }: NotifyOptions): void {
  if (!canNotify()) return;
  void navigator.serviceWorker.ready.then((registration) =>
    registration.showNotification(title, { body, icon, tag, data: { href } }),
  );
}

if (notificationsSupported()) {
  navigator.serviceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "notification:navigate" && navigatorFn) navigatorFn(event.data.href);
  });
}
