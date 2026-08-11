import { useEffect, useState } from "react";
import {
  notificationPermission,
  notificationsPreferred,
  notificationsSupported,
  disablePushNotifications,
  enablePushNotifications,
  loadMessagePreviews,
  messagePreviewsEnabled,
  setMessagePreviews,
} from "../../lib/notifications";
import { useConnectionStore } from "../../stores/connection";
import { Button } from "../../components/ui/Button";
import { SectionTitle, Toggle } from "./controls";
import { desktop, type UpdateCheckResult } from "../../lib/desktop";
import { t } from "../../lib/i18n";

function ConnectionRow() {
  const status = useConnectionStore((s) => s.status);
  const socketId = useConnectionStore((s) => s.socketId);
  const dot =
    status === "connected" ? "bg-success" : status === "connecting" ? "bg-warning" : "bg-danger";
  const label =
    status === "connected"
      ? t("systemTab.connected")
      : status === "connecting"
        ? t("systemTab.connecting")
        : t("systemTab.disconnected");
  return (
    <div className="flex items-center justify-between rounded-lg border border-border bg-surface-1 px-3 py-2.5 text-sm">
      <span className="flex items-center gap-2">
        <span className={`size-2.5 rounded-full ${dot}`} />
        {t("systemTab.realtimeConnection")}
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
    return <p className="text-sm text-ink-muted">{t("systemTab.thisBrowserDoesntSupportNotifications")}</p>;
  }
  if (perm === "denied") {
    return (
      <p className="text-sm text-ink-muted">
        {t("systemTab.notificationsAreBlockedEnableThemFor")}
      </p>
    );
  }
  if (perm === "default") {
    return (
      <Button type="button" variant="secondary" size="sm" onClick={() => void enable()}>
        {t("systemTab.enableNotifications")}
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
      label={t("systemTab.desktopNotifications")}
      hint={t("systemTab.pushAlertsForDirectMessagesAnd")}
    />
  );
}

function MessagePreviewsRow() {
  const [previews, setPreviews] = useState(messagePreviewsEnabled());

  // The in-memory mirror is seeded once at app start; re-read on mount so this
  // screen is right even in a tab that opened straight into settings.
  useEffect(() => {
    void loadMessagePreviews().then(() => setPreviews(messagePreviewsEnabled()));
  }, []);

  if (!notificationsSupported()) return null;
  return (
    <Toggle
      checked={previews}
      onChange={(next) => {
        setPreviews(next);
        void setMessagePreviews(next).catch(() => setPreviews(!next));
      }}
      label={t("systemTab.showMessageText")}
      hint={t("systemTab.offNotificationsShowWhoWroteAnd")}
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
        {usage
          ? t("systemTab.cachedDataOnThisDeviceUsage", { usage })
          : t("systemTab.cachedDataOnThisDevice")}
      </p>
      <Button type="button" variant="secondary" size="sm" onClick={() => void clearCaches()}>
        {t("systemTab.clearCacheReload")}
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
    if (state === "checking") return t("systemTab.checkingForUpdates");
    if (!state || typeof state === "string") return null;
    switch (state.status) {
      case "available":
      case "downloading":
        return t("systemTab.updateFoundDownloading", { version: state.version ?? "" });
      case "current":
        return state.version
          ? t("systemTab.onLatestVersionNumbered", { version: state.version })
          : t("systemTab.onLatestVersion");
      case "dev":
        return state.message ?? t("systemTab.updateChecksOnlyRunInThe");
      case "error":
        return state.message
          ? t("systemTab.couldntCheckRightNowWithMessage", { message: state.message })
          : t("systemTab.couldntCheckRightNow");
      default:
        return null;
    }
  })();

  return (
    <div className="space-y-2">
      <p className="text-sm text-ink-secondary">
        {desktop?.version
          ? t("systemTab.orangchatVersionForPlatform", {
              version: desktop.version,
              platform: desktop?.platform ?? "",
            })
          : t("systemTab.orangchatForPlatform", { platform: desktop?.platform ?? "" })}
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
            {t("systemTab.checkForUpdates")}
          </Button>
          {message && <p className="text-sm text-ink-muted">{message}</p>}
        </>
      ) : (
        <p className="text-sm text-ink-muted">
          {t("systemTab.thisAppBuildCantCheckFrom")}
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
          <SectionTitle>{t("systemTab.appUpdates")}</SectionTitle>
          <UpdatesRow />
        </div>
      )}

      <div className={`space-y-3 ${desktop ? "border-t border-border pt-5" : ""}`}>
        <SectionTitle>{t("systemTab.notifications")}</SectionTitle>
        <NotificationsRow />
        <MessagePreviewsRow />
      </div>

      <div className="space-y-2 border-t border-border pt-5">
        <SectionTitle>{t("systemTab.status")}</SectionTitle>
        <ConnectionRow />
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>{t("systemTab.storage")}</SectionTitle>
        <StorageRow />
      </div>
    </div>
  );
}
