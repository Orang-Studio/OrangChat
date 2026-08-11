import { Users } from "lucide-react";
import { cn } from "../../lib/cn";


export function GroupIcon({
  iconUrl,
  name,
  className,
}: {
  iconUrl: string | null;

  name?: string;
  className?: string;
}) {
  if (iconUrl) {
    return (
      <img
        src={iconUrl}
        alt={name ? `${name} icon` : ""}
        className={cn("size-8 shrink-0 rounded-full object-cover", className)}
      />
    );
  }
  return (
    <span
      className={cn(
        "flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-3 text-ink-secondary",
        className,
      )}
    >
      <Users aria-hidden className="size-[50%]" />
    </span>
  );
}
