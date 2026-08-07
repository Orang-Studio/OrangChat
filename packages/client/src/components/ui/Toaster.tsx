import { useEffect } from "react";
import { CircleAlert, CircleCheck, Info, X, type LucideIcon } from "lucide-react";
import { toastActions, useToastsStore, type ToastType } from "../../stores/toasts";

const ICONS: Record<ToastType, LucideIcon> = {
  info: Info,
  success: CircleCheck,
  error: CircleAlert,
};

const ICON_COLOR: Record<ToastType, string> = {
  info: "text-info",
  success: "text-success",
  error: "text-danger",
};

const DURATION: Record<ToastType, number> = {
  info: 4000,
  success: 4000,
  error: 6000,
};

function ToastItem({ id, message, type }: { id: number; message: string; type: ToastType }) {
  const Icon = ICONS[type];

  useEffect(() => {
    const timer = setTimeout(() => toastActions.dismiss(id), DURATION[type]);
    return () => clearTimeout(timer);
  }, [id, type]);

  return (
    <div
      role={type === "error" ? "alert" : "status"}
      className="pointer-events-auto flex items-center gap-2 rounded-lg border border-border bg-surface-3 px-3 py-2 shadow-lg"
    >
      <Icon aria-hidden className={`size-4 shrink-0 ${ICON_COLOR[type]}`} />
      <span className="min-w-0 flex-1 text-sm text-ink">{message}</span>
      <button
        type="button"
        onClick={() => toastActions.dismiss(id)}
        aria-label="Dismiss notification"
        className="rounded-lg p-2 text-ink-muted transition-colors hover:text-ink"
      >
        <X aria-hidden className="size-4" />
      </button>
    </div>
  );
}

/** Global toast surface. Mounted once in App; see stores/toasts for the API. */
export function Toaster() {
  const toasts = useToastsStore((s) => s.toasts);

  if (toasts.length === 0) return null;

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-[60] flex flex-col items-center gap-2 px-4">
      {toasts.map((toastItem) => (
        <ToastItem key={toastItem.id} {...toastItem} />
      ))}
    </div>
  );
}
