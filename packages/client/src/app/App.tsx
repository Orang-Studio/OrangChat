import { lazy, Suspense, useEffect } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { bootstrapSession } from "../features/auth/session";
import { GuestOnly, RequireAuth } from "./guards";
import { AndroidAppBanner } from "../components/AndroidAppBanner";
import { SplashScreen } from "../components/SplashScreen";
import { AppShell } from "./AppShell";
import { HomeLayout } from "./HomeLayout";
import { HomePane } from "../features/chat/HomePane";
import { FriendsPage } from "../features/friends/FriendsPage";
import { DevelopersPage } from "../features/developers/DevelopersPage";
import { DmView } from "../features/dms/DmView";
import { ServerView } from "../features/servers/ServerView";

// Standalone entry points only: a first visit has no in-app UI behind them to
// preserve, so their chunks load behind the splash instead of in the critical
// path. Every route inside the app shell is eager - navigating home ⇄ server
// ⇄ DM must never unmount the shell for a chunk fetch.
const LoginPage = lazy(() =>
  import("../features/auth/LoginPage").then(({ LoginPage }) => ({ default: LoginPage })),
);
const SignupPage = lazy(() =>
  import("../features/auth/SignupPage").then(({ SignupPage }) => ({ default: SignupPage })),
);
const OAuthCallbackPage = lazy(() =>
  import("../features/auth/OAuthCallbackPage").then(({ OAuthCallbackPage }) => ({
    default: OAuthCallbackPage,
  })),
);
const InvitePage = lazy(() =>
  import("../features/servers/InvitePage").then(({ InvitePage }) => ({ default: InvitePage })),
);
const LegalPage = lazy(() =>
  import("../features/legal/LegalPage").then(({ LegalPage }) => ({ default: LegalPage })),
);

export function App() {
  useEffect(() => {
    bootstrapSession();
  }, []);

  return (
    <>
      <AndroidAppBanner />
      <Suspense fallback={<SplashScreen />}>
        <Routes>
          <Route path="/" element={<Navigate to="/login" replace />} />
          <Route element={<GuestOnly />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
          </Route>
          {/* Outside GuestOnly: the session cookie is already set when the OAuth
              callback lands here, but the store still says "loading", and the
              guard would bounce the redirect before it can be claimed. */}
          <Route path="/auth/callback" element={<OAuthCallbackPage />} />
          {/* Outside both guards: an invite is the one link that has to mean
              something to a stranger, and a member following it shouldn't be
              bounced to the home page either. */}
          <Route path="/invite/:code" element={<InvitePage />} />
          <Route path="/terms" element={<LegalPage document="terms" />} />
          <Route path="/privacy" element={<LegalPage document="privacy" />} />
          <Route path="/cookies" element={<LegalPage document="cookies" />} />
          <Route path="/guidelines" element={<LegalPage document="guidelines" />} />
          <Route path="/legal-notice" element={<LegalPage document="notice" />} />
          <Route element={<RequireAuth />}>
            <Route element={<AppShell />}>
              <Route element={<HomeLayout />}>
                <Route path="/app" element={<HomePane />} />
                <Route path="/friends" element={<FriendsPage />} />
                <Route path="/developers" element={<DevelopersPage />} />
                <Route path="/dms/:channelId" element={<DmView />} />
              </Route>
              <Route path="/servers/:serverId" element={<ServerView />} />
              <Route
                path="/servers/:serverId/channels/:channelId"
                element={<ServerView />}
              />
            </Route>
          </Route>
          {/* Never leave a stray URL rendering an empty page. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </>
  );
}
