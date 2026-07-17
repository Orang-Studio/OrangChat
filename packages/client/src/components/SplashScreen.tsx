import { LogoMark } from "./LogoMark";

export function SplashScreen() {
  return (
    <div
      role="status"
      aria-label="Loading OrangChat"
      className="flex min-h-dvh items-center justify-center"
    >
      <LogoMark className="size-16 animate-pulse" />
    </div>
  );
}
