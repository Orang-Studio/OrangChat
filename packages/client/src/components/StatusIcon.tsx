import { useId } from "react";
import type { PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";

export const STATUS_TEXT: Record<PresenceStatus, string> = {
  online: "text-success",
  idle: "text-warning",
  dnd: "text-danger",
  offline: "text-ink-muted",
};


export function StatusIcon({
  status,
  mobile = false,
  className,
  label,
}: {
  status: PresenceStatus;

  mobile?: boolean;
  className?: string;

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
        {mobile ? (
          <>
            <rect x="3.1" y="0.2" width="5.8" height="11.6" rx="1.5" fill="white" />
            {/* The disc's crescent and ring cut-outs are sized for a circle and
                would swallow a shape this narrow, so the phone hollows out its
                own screen instead - still three shapes, still one glance. */}
            {status !== "online" && (
              <rect x="4.3" y="1.4" width="3.4" height="9.2" rx="0.7" fill="black" />
            )}
            {status === "dnd" && (
              <rect x="2.1" y="4.9" width="7.8" height="2.2" rx="1.1" fill="white" />
            )}
          </>
        ) : (
          <>
            <circle cx="6" cy="6" r="6" fill="white" />
            {status === "idle" && <circle cx="4.6" cy="4.6" r="4.4" fill="black" />}
            {status === "dnd" && (
              <rect x="1.4" y="4.9" width="9.2" height="2.2" rx="1.1" fill="black" />
            )}
            {status === "offline" && <circle cx="6" cy="6" r="2.8" fill="black" />}
          </>
        )}
      </mask>
      <rect width="12" height="12" fill="currentColor" mask={`url(#${maskId})`} />
    </svg>
  );
}
