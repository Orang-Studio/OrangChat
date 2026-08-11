import { Globe, Monitor, Smartphone } from "lucide-react";
import type { PresenceDevice, PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";

export const DEVICE_META = {
  mobile: { label: "Mobile", Icon: Smartphone },
  browser: { label: "Browser", Icon: Globe },
  desktop: { label: "Desktop app", Icon: Monitor },
} satisfies Record<PresenceDevice, { label: string; Icon: typeof Smartphone }>;
const DEVICE_PRIORITY: PresenceDevice[] = ["desktop", "browser", "mobile"];

/**
 * The one client kind worth naming when there is only room for one badge.
 *
 * Ordered so "mobile" comes out only when it is the *whole* answer: someone
 * signed in at their desk with a phone in their pocket is at their desk, and
 * showing a phone there would be a worse answer than showing none.
 */
export function primaryDevice(devices?: PresenceDevice[]): PresenceDevice | undefined {
  return DEVICE_PRIORITY.find((kind) => devices?.includes(kind));
}

/** Compact, accessible indicators for every client kind keeping a user online. */
export function DeviceIndicators({
  status,
  devices,
  className,
  itemClassName,
}: {
  status: PresenceStatus;
  devices?: PresenceDevice[];
  className?: string;
  /** Extra classes on each per-device icon wrapper. */
  itemClassName?: string;
}) {
  if (status === "offline" || !devices?.length) return null;

  const color = status === "online"
    ? "text-success"
    : status === "idle"
      ? "text-warning"
      : "text-danger";

  return (
    <span className={cn("inline-flex shrink-0 items-center gap-1", color, className)}>
      {DEVICE_PRIORITY.filter((device) => devices.includes(device)).map((device) => {
        const { label, Icon } = DEVICE_META[device];
        return (
          <span
            key={device}
            title={label}
            aria-label={label}
            data-device={device}
            className={itemClassName}
          >
            <Icon aria-hidden className="size-3.5" />
          </span>
        );
      })}
    </span>
  );
}
