import { lazy, Suspense, useEffect } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { bootstrapSession } from "../features/auth/session";
import { GuestOnly, RequireAuth } from "./guards";
import { AndroidAppBanner } from "../components/AndroidAppBanner";
import { Toaster } from "../components/ui/Toaster";
import { SplashScreen } from "../components/SplashScreen";

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

const AppShell = lazy(() =>
  import("./AppShell").then(({ AppShell }) => ({ default: AppShell })),
);
const MainLayout = lazy(() =>
  import("./MainLayout").then(({ MainLayout }) => ({ default: MainLayout })),
);
const ServerChannelContent = lazy(() =>
  import("./MainLayout").then(({ ServerChannelContent }) => ({ default: ServerChannelContent })),
);
const HomePane = lazy(() =>
  import("../features/chat/HomePane").then(({ HomePane }) => ({ default: HomePane })),
);
const FriendsPage = lazy(() =>
  import("../features/friends/FriendsPage").then(({ FriendsPage }) => ({ default: FriendsPage })),
);
const DevelopersPage = lazy(() =>
  import("../features/developers/DevelopersPage").then(({ DevelopersPage }) => ({
    default: DevelopersPage,
  })),
);
const DmView = lazy(() =>
  import("../features/dms/DmView").then(({ DmView }) => ({ default: DmView })),
);

export function App() {
  useEffect(() => {
    bootstrapSession();
  }, []);

  return (
    <>
      <AndroidAppBanner />
      <Toaster />
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
              {/* Home and server routes share one MainLayout/PanelShell instance -
                  see MainLayout.tsx for why that matters. */}
              <Route element={<MainLayout />}>
                <Route path="/app" element={<HomePane />} />
                <Route path="/friends" element={<FriendsPage />} />
                <Route path="/developers" element={<DevelopersPage />} />
                <Route path="/dms/:channelId" element={<DmView />} />
                <Route path="/servers/:serverId" element={<ServerChannelContent />} />
                <Route
                  path="/servers/:serverId/channels/:channelId"
                  element={<ServerChannelContent />}
                />
              </Route>
            </Route>
          </Route>
          {/* Never leave a stray URL rendering an empty page. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </>
  );
}
