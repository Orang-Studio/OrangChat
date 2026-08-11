import { Globe, Monitor, Smartphone } from "lucide-react";
import type { PresenceDevice, PresenceStatus } from "@orangchat/shared";
import { cn } from "../lib/cn";
import { t } from "../lib/i18n";

export const DEVICE_META = {
  mobile: { labelKey: "common.mobile", Icon: Smartphone },
  browser: { labelKey: "common.browser", Icon: Globe },
  desktop: { labelKey: "common.desktopApp", Icon: Monitor },
} satisfies Record<PresenceDevice, { labelKey: keyof typeof import("../lib/i18n/en").en; Icon: typeof Smartphone }>;
const DEVICE_PRIORITY: PresenceDevice[] = ["desktop", "browser", "mobile"];


export function primaryDevice(devices?: PresenceDevice[]): PresenceDevice | undefined {
  return DEVICE_PRIORITY.find((kind) => devices?.includes(kind));
}

export function deviceLabel(device: PresenceDevice): string {
  return t(DEVICE_META[device].labelKey);
}


export function DeviceIndicators({
  status,
  devices,
  className,
  itemClassName,
}: {
  status: PresenceStatus;
  devices?: PresenceDevice[];
  className?: string;

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
        const { Icon } = DEVICE_META[device];
        const label = deviceLabel(device);
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
