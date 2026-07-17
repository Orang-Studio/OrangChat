import { UNREAD_COUNT_CAP } from "../stores/unread";
import { cn } from "../lib/cn";

/** The server stops counting at the cap, so anything at it is an "at least". */
export function formatUnreadCount(count: number): string {
  return count >= UNREAD_COUNT_CAP ? `${UNREAD_COUNT_CAP - 1}+` : String(count);
}

/**
 * Pill showing how many unread messages a conversation or channel holds.
 * Renders nothing at zero so callers can drop it in unconditionally.
 */
export function UnreadBadge({
  count,
  label,
  className,
}: {
  count: number;
  /** What the count is of, for screen readers (e.g. "unread messages"). */
  label?: string;
  className?: string;
}) {
  if (count <= 0) return null;
  const text = formatUnreadCount(count);
  return (
    <span
      aria-label={label ? `${text} ${label}` : undefined}
      className={cn(
        "min-w-5 shrink-0 rounded-md bg-danger px-1.5 text-center text-xs font-semibold leading-5 text-white",
        className,
      )}
    >
      {text}
    </span>
  );
}
