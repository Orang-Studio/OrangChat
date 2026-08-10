import { Users } from "lucide-react";
import { cn } from "../../lib/cn";

/**
 * A group DM's picture. Falls back to the members glyph, which is what every
 * group looked like before icons existed and what one without an icon still
 * looks like - so the two must line up at the same size in the same slot.
 */
export function GroupIcon({
  iconUrl,
  name,
  className,
}: {
  iconUrl: string | null;
  /** Only used for the alt text; the picture is decorative next to the name. */
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
