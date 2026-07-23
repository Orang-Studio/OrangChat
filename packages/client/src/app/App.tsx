import { useEffect } from "react";
import { Outlet, Route, Routes } from "react-router-dom";
import { bootstrapSession } from "../features/auth/session";
import { LoginPage } from "../features/auth/LoginPage";
import { SignupPage } from "../features/auth/SignupPage";
import { HomePane } from "../features/chat/HomePane";
import { FriendsPage } from "../features/friends/FriendsPage";
import { DmSidebar } from "../features/dms/DmSidebar";
import { DmView } from "../features/dms/DmView";
import { InvitePage } from "../features/servers/InvitePage";
import { ServerView } from "../features/servers/ServerView";
import { AppShell } from "./AppShell";
import { PanelShell } from "./PanelShell";
import { GuestOnly, RequireAuth } from "./guards";
import { AndroidAppBanner } from "../components/AndroidAppBanner";

/** Home area: DM conversation list beside whatever the route renders. */
function HomeLayout() {
  return (
    <PanelShell sidebar={<DmSidebar />}>
      <Outlet />
    </PanelShell>
  );
}

export function App() {
  useEffect(() => {
    bootstrapSession();
  }, []);

  return (
    <>
      <AndroidAppBanner />
      <Routes>
        <Route element={<GuestOnly />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
        </Route>
        {/* Outside both guards: an invite is the one link that has to mean
            something to a stranger, and a member following it shouldn't be
            bounced to the home page either. */}
        <Route path="/invite/:code" element={<InvitePage />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route element={<HomeLayout />}>
              <Route path="/" element={<HomePane />} />
              <Route path="/friends" element={<FriendsPage />} />
              <Route path="/dms/:channelId" element={<DmView />} />
            </Route>
            <Route path="/servers/:serverId" element={<ServerView />} />
            <Route
              path="/servers/:serverId/channels/:channelId"
              element={<ServerView />}
            />
          </Route>
        </Route>
      </Routes>
    </>
  );
}
