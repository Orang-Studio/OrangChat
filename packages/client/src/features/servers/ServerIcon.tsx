import { cn } from "../../lib/cn";

/** Up to two leading letters, the stand-in when a server has no icon. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("");
}

interface ServerIconProps {
  name: string;
  iconUrl?: string | null;
  className?: string;
  fallbackClassName?: string;
}

/** A server's icon, or its initials when it has none. */
export function ServerIcon({ name, iconUrl, className, fallbackClassName }: ServerIconProps) {
  if (iconUrl) {
    return (
      <img src={iconUrl} alt="" className={cn("rounded-squircle object-cover", className)} />
    );
  }
  return (
    <span
      aria-hidden
      className={cn(
        "flex items-center justify-center rounded-squircle font-semibold",
        className,
        fallbackClassName,
      )}
    >
      {initials(name)}
    </span>
  );
}
