import { Gamepad2, Music2 } from "lucide-react";
import type { UserActivity } from "@orangchat/shared";
import { cn } from "../lib/cn";

/** Rich-presence line shared by member/friend rows and future game activity. */
export function ActivityStatus({
  activities,
  className,
  linked = true,
}: {
  activities?: UserActivity[];
  className?: string;
  /** False when rendered inside another link, such as a conversation row. */
  linked?: boolean;
}) {
  const activity = activities?.find((item) => item.kind === "spotify") ?? activities?.[0];
  if (!activity) return null;
  const Icon = activity.kind === "spotify" ? Music2 : Gamepad2;
  const label = activity.kind === "spotify" ? "Listening to" : "Playing";
  const content = (
    <>
      <Icon aria-hidden className="size-3 shrink-0" />
      <span className="truncate">
        {label} <span className="font-medium text-ink-secondary">{activity.name}</span>
        {activity.details ? ` - ${activity.details}` : ""}
      </span>
    </>
  );

  return activity.url && linked ? (
    <a
      href={activity.url}
      target="_blank"
      rel="noreferrer"
      title={`${label} ${activity.name}`}
      className={cn("flex min-w-0 items-center gap-1 text-xs text-ink-muted hover:text-ink", className)}
    >
      {content}
    </a>
  ) : (
    <span className={cn("flex min-w-0 items-center gap-1 text-xs text-ink-muted", className)}>
      {content}
    </span>
  );
}
