import type { PresenceStatus, User } from "@orangchat/shared";
import { cn } from "../lib/cn";
import { DEVICE_META, primaryDevice } from "./DeviceIndicators";
import { StatusIcon } from "./StatusIcon";

export const STATUS_LABEL: Record<PresenceStatus, string> = {
  online: "Online",
  idle: "Idle",
  dnd: "Do not disturb",
  offline: "Offline",
};

interface AvatarProps {
  user: Pick<User, "displayName" | "avatarUrl"> & Partial<Pick<User, "devices">>;
  /** Presence dot; omit to hide (e.g. in message rows). */
  status?: PresenceStatus;
  className?: string;
  /** Extra classes on the <img>; lets callers hang CSS hooks off it. */
  imgClassName?: string;
  /** Extra classes on the initial-letter stand-in shown when there is no image. */
  fallbackClassName?: string;
}

/** User avatar with initial fallback and optional presence dot. */
export function Avatar({ user, status, className, imgClassName, fallbackClassName }: AvatarProps) {
  const device = primaryDevice(user.devices);
  const label = status
    ? device
      ? `${STATUS_LABEL[status]} · ${DEVICE_META[device].label}`
      : STATUS_LABEL[status]
    : "";
  return (
    // rounded-full on the wrapper too: it has no fill of its own, but a ring or
    // outline from a caller follows the wrapper's radius rather than the image's,
    // and a square ring around a round avatar is the giveaway.
    <span className={cn("relative inline-block size-9 shrink-0 rounded-full", className)}>
      {user.avatarUrl ? (
        <img
          src={user.avatarUrl}
          alt=""
          className={cn("size-full rounded-full object-cover", imgClassName)}
        />
      ) : (
        <span
          aria-hidden
          className={cn(
            "flex size-full items-center justify-center rounded-full bg-primary-soft font-semibold text-primary",
            fallbackClassName,
          )}
        >
          {user.displayName.charAt(0).toUpperCase()}
        </span>
      )}
      {status ? (
        // The badge scales with the avatar - a 16px dot pinned to an 80px
        // profile avatar reads as a rendering mistake - and the surface-2 disc
        // behind it is what shows through the shape's cut-outs. The floor is
        // only there to keep the shape legible; anything higher and it stops
        // scaling on the small avatars (the 28px DM header one) entirely.
        <span
          title={label}
          className="absolute -bottom-0.5 -right-0.5 flex size-[34%] min-h-2.5 min-w-2.5 items-center justify-center rounded-full bg-surface-2"
        >
          <StatusIcon
            status={status}
            mobile={device === "mobile"}
            // The phone is narrower than the disc, so it needs the extra room
            // to end up the same visual weight inside the same backdrop.
            className={device === "mobile" ? "size-[92%]" : "size-[75%]"}
            label={label}
          />
        </span>
      ) : null}
    </span>
  );
}
