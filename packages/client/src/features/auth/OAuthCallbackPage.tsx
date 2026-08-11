import { useEffect } from "react";
import { Navigate } from "react-router-dom";
import { useAuthStore } from "../../stores/auth";
import { SplashScreen } from "../../components/SplashScreen";
import { refreshSession } from "./session";


export function OAuthCallbackPage() {
  const status = useAuthStore((s) => s.status);

  useEffect(() => {
    void refreshSession();
  }, []);

  if (status === "authenticated") return <Navigate to="/app" replace />;
  if (status === "guest") return <Navigate to="/login?error=oauth_failed" replace />;
  return <SplashScreen />;
}
