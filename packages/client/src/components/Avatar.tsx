import type { PresenceStatus, User } from "@orangchat/shared";
import { Globe, Monitor, Smartphone } from "lucide-react";
import { cn } from "../lib/cn";

export const STATUS_COLOR: Record<PresenceStatus, string> = {
  online: "bg-success",
  idle: "bg-warning",
  dnd: "bg-danger",
  offline: "bg-ink-muted",
};

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
}

/** User avatar with initial fallback and optional presence dot. */
export function Avatar({ user, status, className }: AvatarProps) {
  const device = user.devices?.find((kind) => kind === "mobile")
    ?? user.devices?.find((kind) => kind === "desktop")
    ?? user.devices?.[0];
  const DeviceIcon = device === "mobile" ? Smartphone : device === "desktop" ? Monitor : Globe;
  return (
    // rounded-full on the wrapper too: it has no fill of its own, but a ring or
    // outline from a caller follows the wrapper's radius rather than the image's,
    // and a square ring around a round avatar is the giveaway.
    <span className={cn("relative inline-block size-9 shrink-0 rounded-full", className)}>
      {user.avatarUrl ? (
        <img
          src={user.avatarUrl}
          alt=""
          className="size-full rounded-full object-cover"
        />
      ) : (
        <span
          aria-hidden
          className="flex size-full items-center justify-center rounded-full bg-primary-soft font-semibold text-primary"
        >
          {user.displayName.charAt(0).toUpperCase()}
        </span>
      )}
      {status && device ? (
        <span
          title={`${device} · ${STATUS_LABEL[status]}`}
          className="absolute -bottom-1 -right-1 flex size-4 items-center justify-center rounded-full bg-surface-2"
        >
          <DeviceIcon aria-hidden className={cn("size-3", STATUS_COLOR[status].replace("bg-", "text-"))} />
        </span>
      ) : status ? (
        <span
          title={STATUS_LABEL[status]}
          className={cn(
            "absolute -bottom-0.5 -right-0.5 size-3 rounded-full border-2 border-surface-2",
            STATUS_COLOR[status],
          )}
        />
      ) : null}
    </span>
  );
}
