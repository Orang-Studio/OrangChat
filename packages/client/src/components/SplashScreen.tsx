import { LogoMark } from "./LogoMark";

export function SplashScreen() {
  return (
    <div
      role="status"
      aria-label="Loading OrangChat"
      className="flex min-h-dvh items-center justify-center bg-surface-0"
    >
      <LogoMark className="size-16 animate-pulse" />
    </div>
  );
}
