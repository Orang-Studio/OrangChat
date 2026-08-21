import { useId } from "react";
import type { PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";

export const STATUS_TEXT: Record<PresenceStatus, string> = {
  online: "text-success",
  idle: "text-warning",
  dnd: "text-danger",
  offline: "text-ink-muted",
};


const CIRCLE_CUTOUT: Record<PresenceStatus, { cx: number; cy: number; r: number }> = {
  online: { cx: 6, cy: 6, r: 0 },
  idle: { cx: 4.6, cy: 4.6, r: 4.4 },
  offline: { cx: 6, cy: 6, r: 2.8 },
  dnd: { cx: 6, cy: 6, r: 0 },
};

const MOBILE_HOLE: Record<PresenceStatus, { y: number; height: number }> = {
  online: { y: 6, height: 0 },
  idle: { y: 1.4, height: 9.2 },
  offline: { y: 1.4, height: 9.2 },
  dnd: { y: 1.4, height: 9.2 },
};

const GEOMETRY_TRANSITION = "cx 180ms ease-out, cy 180ms ease-out, r 180ms ease-out, y 180ms ease-out, height 180ms ease-out, opacity 180ms ease-out";

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
  const circle = CIRCLE_CUTOUT[status];
  const hole = MOBILE_HOLE[status];
  const barOpacity = status === "dnd" ? 1 : 0;
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
            <rect
              x="4.3"
              y={hole.y}
              width="3.4"
              height={hole.height}
              rx="0.7"
              fill="black"
              style={{ transition: GEOMETRY_TRANSITION }}
            />
            <rect
              x="2.1"
              y="4.9"
              width="7.8"
              height="2.2"
              rx="1.1"
              fill="white"
              opacity={barOpacity}
              style={{ transition: GEOMETRY_TRANSITION }}
            />
          </>
        ) : (
          <>
            <circle cx="6" cy="6" r="6" fill="white" />
            <circle
              cx={circle.cx}
              cy={circle.cy}
              r={circle.r}
              fill="black"
              style={{ transition: GEOMETRY_TRANSITION }}
            />
            <rect
              x="1.4"
              y="4.9"
              width="9.2"
              height="2.2"
              rx="1.1"
              fill="black"
              opacity={barOpacity}
              style={{ transition: GEOMETRY_TRANSITION }}
            />
          </>
        )}
      </mask>
      <rect width="12" height="12" fill="currentColor" mask={`url(#${maskId})`} />
    </svg>
  );
}
