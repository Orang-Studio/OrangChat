import { api } from "./api";
import { syncEpochKeys } from "../features/e2ee/conversation";
import { NOTIFICATION_PREVIEWS, getSetting, setSetting } from "../features/e2ee/keystore";

const PREF_KEY = "oc-notifications";


let previews = true;

export function messagePreviewsEnabled(): boolean {
  return previews;
}


export async function loadMessagePreviews(): Promise<void> {
  previews = await getSetting(NOTIFICATION_PREVIEWS, true).catch(() => true);
}

export async function setMessagePreviews(on: boolean): Promise<void> {
  previews = on;
  await setSetting(NOTIFICATION_PREVIEWS, on);
}

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
const notificationGenerations = new Map<string, number>();

function bumpNotificationGeneration(tag: string): number {
  const generation = (notificationGenerations.get(tag) ?? 0) + 1;
  notificationGenerations.set(tag, generation);
  return generation;
}

export function canNotify(): boolean {
  return notificationPermission() === "granted" && notificationsPreferred();
}
export function notify({ title, body, icon, href, tag }: NotifyOptions): void {
  if (!canNotify()) return;
  const generation = tag ? bumpNotificationGeneration(tag) : null;
  void navigator.serviceWorker.ready.then(async (registration) => {
    if (tag && notificationGenerations.get(tag) !== generation) return;
    await registration.showNotification(title, { body, icon, tag, data: { href } });
    if (tag && notificationGenerations.get(tag) !== generation) {
      const stale = await registration.getNotifications({ tag });
      stale.forEach((notification) => notification.close());
    }
  });
}


export async function clearConversationNotifications(channelId: string): Promise<void> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  bumpNotificationGeneration(channelId);
  const registration = await navigator.serviceWorker.getRegistration();
  const notifications = await registration?.getNotifications({ tag: channelId });
  notifications?.forEach((notification) => notification.close());
}


async function registerRenewedSubscription(subscription: PushSubscriptionJSON): Promise<void> {
  if (!subscription.endpoint) return;
  await api<void>("/push/subscriptions", {
    method: "PUT",
    json: {
      kind: "webpush",
      endpoint: subscription.endpoint,
      p256dh: subscription.keys?.p256dh,
      auth: subscription.keys?.auth,
      label: navigator.userAgent.slice(0, 200),
    },
  });
}

if (notificationsSupported()) {
  navigator.serviceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "notification:navigate" && navigatorFn) navigatorFn(event.data.href);
    if (event.data?.type === "push:resubscribed" && event.data.subscription) {
      void registerRenewedSubscription(event.data.subscription).catch(() => {});
    }
    if (event.data?.type === "e2ee:sync" && typeof event.data.channelId === "string") {
      void syncEpochKeys(event.data.channelId).catch(() => {});
    }
  });
}
