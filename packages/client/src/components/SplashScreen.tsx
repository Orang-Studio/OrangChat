import { LogoMark } from "./LogoMark";
import { t } from "../lib/i18n";

export function SplashScreen() {
  return (
    <div
      role="status"
      aria-label={t("splashScreen.loadingOrangchat")}
      className="flex min-h-dvh items-center justify-center bg-surface-0"
    >
      <LogoMark className="size-16 animate-pulse" />
    </div>
  );
}
