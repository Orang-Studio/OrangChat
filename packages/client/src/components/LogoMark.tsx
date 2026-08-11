import { cn } from "../lib/cn";
import { DEFAULT_APP_ICON } from "../lib/appIcon";
import { useAuthStore } from "../stores/auth";


export function LogoMark({ className }: { className?: string }) {
  const custom = useAuthStore((s) => s.user?.appIconUrl);
  return (
    <img
      src={custom || DEFAULT_APP_ICON}
      alt=""
      aria-hidden
      className={cn("select-none object-contain", className)}
    />
  );
}
