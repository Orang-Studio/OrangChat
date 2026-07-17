import { cn } from "../lib/cn";

/** The OrangChat brand mark - Oranges.LT orange-slice + chat bubble. */
export function LogoMark({ className }: { className?: string }) {
  return <img src="/icon.svg" alt="" aria-hidden className={cn("select-none", className)} />;
}
