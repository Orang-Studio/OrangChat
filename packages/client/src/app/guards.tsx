import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "../stores/auth";
import { SplashScreen } from "../components/SplashScreen";

/** Wraps authenticated routes; bounces guests to /login (remembering where from). */
export function RequireAuth() {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();

  if (status === "loading") return <SplashScreen />;
  if (status === "guest") {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}

/** Wraps auth pages; already-authenticated users go straight to the app. */
export function GuestOnly() {
  const status = useAuthStore((s) => s.status);

  if (status === "loading") return <SplashScreen />;
  if (status === "authenticated") return <Navigate to="/" replace />;
  return <Outlet />;
}
