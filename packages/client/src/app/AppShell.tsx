import { useEffect } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { TooltipProvider } from "../components/ui/Tooltip";
import { useCustomCss } from "../lib/customCss";
import { getUnreads } from "../features/unread/api";
import { unreadActions } from "../stores/unread";
import { restorePushNotifications, setNotificationNavigator } from "../lib/notifications";
import { takePendingInvite } from "../features/servers/invite-url";
import { CallStage } from "../features/voice/CallStage";
import { CallErrorToast } from "../features/voice/CallErrorToast";
import { IncomingCallDialog } from "../features/voice/IncomingCallDialog";
import { getActiveChannel } from "../features/unread/active";

/** Authenticated frame: each layout renders its own PanelShell inside. */
export function AppShell() {
  useCustomCss();
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
    </TooltipProvider>
  );
}
