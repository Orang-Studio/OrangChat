import { useEffect } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { TooltipProvider } from "../components/ui/Tooltip";
import { useCustomCss } from "../lib/customCss";
import { useAppIcon } from "../lib/appIcon";
import { getUnreads } from "../features/unread/api";
import { loadStrictOverrides } from "../features/e2ee/strict";
import { unreadActions } from "../stores/unread";
import {
  loadMessagePreviews,
  restorePushNotifications,
  setNotificationNavigator,
} from "../lib/notifications";
import { takePendingInvite } from "../features/servers/invite-url";
import { CallStage } from "../features/voice/CallStage";
import { CallErrorToast } from "../features/voice/CallErrorToast";
import { IncomingCallDialog } from "../features/voice/IncomingCallDialog";
import { getActiveChannel } from "../features/unread/active";
import { WhatsNewDialog } from "../features/updates/WhatsNewDialog";
import { UpdateGate } from "../features/updates/UpdateGate";
import { SecurityAlertOverlay } from "../features/e2ee/SecurityAlertOverlay";

/** Authenticated frame: each layout renders its own PanelShell inside. */
export function AppShell() {
  useCustomCss();
  useAppIcon();
  const navigate = useNavigate();

  // Let notification clicks deep-link into the app.
  useEffect(() => {
    setNotificationNavigator((href) => navigate(href));
  }, [navigate]);

  // Seed unread + mention badges from the server on load.
  useEffect(() => {
    getUnreads()
      .then((items) => {
        const active = getActiveChannel();
        unreadActions.hydrate(items.filter((item) => item.channelId !== active));
      })
      .catch(() => {});
    void restorePushNotifications().catch(() => {});
    void loadMessagePreviews();
    // Which conversations this account has marked "verify before messaging".
    // Cached locally too, so the gate holds before this lands.
    void loadStrictOverrides();
  }, []);

  // Someone who signed in to accept an invite lands here first - OAuth in
  // particular reloads the page and drops the router's own return-to. Take them
  // the last step to the invite they were actually after.
  useEffect(() => {
    const code = takePendingInvite();
    if (code) navigate(`/invite/${code}`, { replace: true });
  }, [navigate]);

  return (
    <TooltipProvider>
      {/* Height comes from the visual viewport (see lib/viewport) so the
          composer sits above the on-screen keyboard, not behind it. */}
      <div className="flex h-[var(--oc-vvh,100dvh)] overflow-hidden pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)]">
        <Outlet />
      </div>
      {/* Calls arrive whatever route the user is on, so these live above it. */}
      <IncomingCallDialog />
      <CallStage />
      <CallErrorToast />
      <WhatsNewDialog />
      <UpdateGate />
      {/* Above everything, including calls: an equivocating server is the one
          thing that outranks whatever the user is currently doing. */}
      <SecurityAlertOverlay />
    </TooltipProvider>
  );
}
