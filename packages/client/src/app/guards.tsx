import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "../stores/auth";
import { SplashScreen } from "../components/SplashScreen";


export function RequireAuth() {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();

  if (status === "loading") return <SplashScreen />;
  if (status === "guest") {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}


export function GuestOnly() {
  const status = useAuthStore((s) => s.status);

  if (status === "loading") return <SplashScreen />;
  if (status === "authenticated") return <Navigate to="/app" replace />;
  return <Outlet />;
}
