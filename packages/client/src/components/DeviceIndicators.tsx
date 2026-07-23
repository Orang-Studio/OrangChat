import { Globe, Monitor, Smartphone } from "lucide-react";
import type { PresenceDevice, PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";

const DEVICE_META = {
  mobile: { label: "Mobile", Icon: Smartphone },
  browser: { label: "Browser", Icon: Globe },
  desktop: { label: "Desktop app", Icon: Monitor },
} satisfies Record<PresenceDevice, { label: string; Icon: typeof Smartphone }>;

/** Compact, accessible indicators for every client kind keeping a user online. */
export function DeviceIndicators({
  status,
  devices,
}: {
  status: PresenceStatus;
  devices?: PresenceDevice[];
}) {
  if (status === "offline" || !devices?.length) return null;

  const color = status === "online"
    ? "text-success"
    : status === "idle"
      ? "text-warning"
      : "text-danger";

  return (
    <span className={cn("inline-flex shrink-0 items-center gap-1", color)}>
      {devices.map((device) => {
        const { label, Icon } = DEVICE_META[device];
        return (
          <span key={device} title={label} aria-label={label}>
            <Icon aria-hidden className="size-3.5" />
          </span>
        );
      })}
    </span>
  );
}
