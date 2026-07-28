import { useEffect, useState } from "react";
import {
  notificationPermission,
  notificationsPreferred,
  notificationsSupported,
  disablePushNotifications,
  enablePushNotifications,
} from "../../lib/notifications";
import { useConnectionStore } from "../../stores/connection";
import { Button } from "../../components/ui/Button";
import { SectionTitle, Toggle } from "./controls";
import { desktop, type UpdateCheckResult } from "../../lib/desktop";

function ConnectionRow() {
  const status = useConnectionStore((s) => s.status);
  const socketId = useConnectionStore((s) => s.socketId);
  const dot =
    status === "connected" ? "bg-success" : status === "connecting" ? "bg-warning" : "bg-danger";
  const label =
    status === "connected" ? "Connected" : status === "connecting" ? "Connecting…" : "Disconnected";
  return (
    <div className="flex items-center justify-between rounded-lg border border-border bg-surface-1 px-3 py-2.5 text-sm">
      <span className="flex items-center gap-2">
        <span className={`size-2.5 rounded-full ${dot}`} />
        Realtime connection
      </span>
      <span className="text-ink-muted">
        {label}
        {socketId && status === "connected" ? ` · ${socketId.slice(0, 6)}` : ""}
      </span>
    </div>
  );
}

function NotificationsRow() {
  const supported = notificationsSupported();
  const [pref, setPref] = useState(notificationsPreferred());
  const [perm, setPerm] = useState(supported ? notificationPermission() : "denied");

  const enable = async () => {
    const enabled = await enablePushNotifications().catch(() => false);
    setPerm(notificationPermission());
    setPref(enabled);
  };

  if (!supported) {
    return <p className="text-sm text-ink-muted">This browser doesn't support notifications.</p>;
  }
  if (perm === "denied") {
    return (
      <p className="text-sm text-ink-muted">
        Notifications are blocked. Enable them for this site in your browser settings.
      </p>
    );
  }
  if (perm === "default") {
    return (
      <Button type="button" variant="secondary" size="sm" onClick={() => void enable()}>
        Enable notifications
      </Button>
    );
  }
  return (
    <Toggle
      checked={pref}
      onChange={(next) => {
        setPref(next);
        void (async () => {
          if (next) setPref(await enablePushNotifications());
          else await disablePushNotifications();
        })().catch(() => setPref(false));
      }}
      label="Desktop notifications"
      hint="Push alerts for direct messages and @mentions, even when OrangChat is closed."
    />
  );
}

function StorageRow() {
  const [usage, setUsage] = useState<string | null>(null);

  useEffect(() => {
    navigator.storage?.estimate?.().then((est) => {
      const used = est.usage ?? 0;
      setUsage(`${(used / 1024 / 1024).toFixed(1)} MB`);
    });
  }, []);

  const clearCaches = async () => {
    if ("caches" in window) {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    }
    location.reload();
  };

  return (
    <div className="space-y-2">
      <p className="text-sm text-ink-secondary">
        Cached data on this device{usage ? `: ~${usage}` : ""}.
      </p>
      <Button type="button" variant="secondary" size="sm" onClick={() => void clearCaches()}>
        Clear cache & reload
      </Button>
    </div>
  );
}

function UpdatesRow() {
  const [state, setState] = useState<UpdateCheckResult | "checking" | null>(null);
  // Shells older than the updater IPC expose no checkForUpdates at all; calling
  // it would just throw and report a failure that isn't one.
  const supported = typeof desktop?.checkForUpdates === "function";

  const check = async () => {
    setState("checking");
    try {
      setState((await desktop!.checkForUpdates!()) ?? { status: "error" });
    } catch (error) {
      setState({
        status: "error",
        message: error instanceof Error ? error.message : undefined,
      });
    }
  };

  const message = (() => {
    if (state === "checking") return "Checking for updates…";
    if (!state || typeof state === "string") return null;
    switch (state.status) {
      case "available":
      case "downloading":
        return `Update ${state.version ?? ""} found - downloading. You'll be asked to restart.`;
      case "current":
        return `You're on the latest version${state.version ? ` (${state.version})` : ""}.`;
      case "dev":
        return state.message ?? "Update checks only run in the installed app.";
      case "error":
        return `Couldn't check right now. Try again later.${
          state.message ? ` (${state.message})` : ""
        }`;
      default:
        return null;
    }
  })();

  return (
    <div className="space-y-2">
      <p className="text-sm text-ink-secondary">
        OrangChat {desktop?.version ? `${desktop.version} ` : ""}for {desktop?.platform}.
      </p>
      {supported ? (
        <>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            loading={state === "checking"}
            onClick={() => void check()}
          >
            Check for updates
          </Button>
          {message && <p className="text-sm text-ink-muted">{message}</p>}
        </>
      ) : (
        <p className="text-sm text-ink-muted">
          This app build can't check from here - use Help → Check for Updates… in the window menu,
          or the tray icon.
        </p>
      )}
    </div>
  );
}

export function SystemTab() {
  return (
    <div className="space-y-6">
      {desktop && (
        <div>
          <SectionTitle>App updates</SectionTitle>
          <UpdatesRow />
        </div>
      )}

      <div className={desktop ? "border-t border-border pt-5" : undefined}>
        <SectionTitle>Notifications</SectionTitle>
        <NotificationsRow />
      </div>

      <div className="space-y-2 border-t border-border pt-5">
        <SectionTitle>Status</SectionTitle>
        <ConnectionRow />
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>Storage</SectionTitle>
        <StorageRow />
      </div>
    </div>
  );
}
