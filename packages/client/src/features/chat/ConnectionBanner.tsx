import { WifiOff } from "lucide-react";
import { useConnectionStore } from "../../stores/connection";


export function ConnectionBanner() {
  const status = useConnectionStore((s) => s.status);

  if (status === "connected") return null;

  return (
    <div
      role="status"
      className="flex shrink-0 items-center justify-center gap-2 border-b border-border bg-primary-soft px-3 py-1.5 text-center text-xs font-medium text-warning"
    >
      <WifiOff aria-hidden className="size-3.5 shrink-0" />
      <span>
        {status === "connecting"
          ? "Connecting…"
          : "Offline - messages are saved and will be sent when you reconnect"}
      </span>
    </div>
  );
}
