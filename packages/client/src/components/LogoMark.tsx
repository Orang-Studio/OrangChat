import { cn } from "../lib/cn";
import { DEFAULT_APP_ICON } from "../lib/appIcon";
import { useAuthStore } from "../stores/auth";

/**
 * The OrangChat brand mark - Oranges.LT orange-slice + chat bubble, or whatever
 * the signed-in user replaced it with. Falls back to the shipped mark on the
 * signed-out surfaces (auth, invite, legal) where there is no user to ask.
 */
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
