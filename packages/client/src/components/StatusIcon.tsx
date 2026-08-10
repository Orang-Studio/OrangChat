import { useId } from "react";
import type { PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";

export const STATUS_TEXT: Record<PresenceStatus, string> = {
  online: "text-success",
  idle: "text-warning",
  dnd: "text-danger",
  offline: "text-ink-muted",
};

/**
 * Presence as a shape, not just a colour: a full disc for online, a crescent for
 * idle, a barred disc for do-not-disturb, a hollow ring for offline. Colour
 * alone fails for the ~8% of men with red/green deficiency, and at 12px the
 * green and the amber dot are the same dot.
 *
 * The cut-outs are punched through with a mask rather than painted, so whatever
 * sits behind the badge shows through them and the shape reads on any surface.
 */
export function StatusIcon({
  status,
  className,
  label,
}: {
  status: PresenceStatus;
  className?: string;
  /** Accessible name; pass null when a parent already labels the badge. */
  label?: string | null;
}) {
  const maskId = useId();
  return (
    <svg
      viewBox="0 0 12 12"
      className={cn("size-3 shrink-0", STATUS_TEXT[status], className)}
      {...(label === null ? { "aria-hidden": true } : { role: "img", "aria-label": label })}
    >
      <mask id={maskId}>
        <circle cx="6" cy="6" r="6" fill="white" />
        {status === "idle" && <circle cx="4.6" cy="4.6" r="5.2" fill="black" />}
        {status === "dnd" && <rect x="1.4" y="4.9" width="9.2" height="2.2" rx="1.1" fill="black" />}
        {status === "offline" && <circle cx="6" cy="6" r="2.8" fill="black" />}
      </mask>
      <rect width="12" height="12" fill="currentColor" mask={`url(#${maskId})`} />
    </svg>
  );
}
