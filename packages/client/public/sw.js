self.addEventListener("push", (event) => {
  event.waitUntil((async () => {
    const payload = event.data?.json();
    if (!payload) return;
    // A read on another device: take back this channel's notification rather
    // than showing anything new.
    if (payload.kind === "read") {
      const existing = await self.registration.getNotifications({ tag: payload.tag });
      existing.forEach((n) => n.close());
      return;
    }
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    if (windows.some((client) => client.focused)) return;
    await self.registration.showNotification(payload.title, {
      body: payload.body,
      icon: payload.icon || "/icon.svg",
      badge: "/icon.svg",
      tag: payload.tag,
      renotify: payload.kind === "call",
      requireInteraction: payload.kind === "call",
      data: { href: payload.href },
    });
  })());
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const href = event.notification.data?.href || "/";
  event.waitUntil((async () => {
    const url = new URL(href, self.location.origin).href;
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      if (new URL(client.url).origin === self.location.origin) {
        await client.focus();
        client.postMessage({ type: "notification:navigate", href });
        return;
      }
    }
    await self.clients.openWindow(url);
  })());
});
