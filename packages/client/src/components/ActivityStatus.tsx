import { Gamepad2, Music2 } from "lucide-react";
import type { UserActivity } from "@orangchat/shared";
import { cn } from "../lib/cn";
import { t } from "../lib/i18n";
import { useNow } from "../lib/useNow";

function formatElapsed(startedAt: string, now: number): string | null {
  const started = Date.parse(startedAt);
  if (!Number.isFinite(started)) return null;
  const total = Math.max(0, Math.floor((now - started) / 1_000));
  const seconds = total % 60;
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3_600);
  return hours > 0
    ? `${hours}:${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
    : `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function ElapsedTime({ startedAt, compact }: { startedAt: string; compact: boolean }) {
  const now = useNow();
  const elapsed = formatElapsed(startedAt, now);
  if (!elapsed) return null;
  const label = t("activityStatus.forDuration", { duration: elapsed });
  return compact ? (
    <span className="oc-pf-activity-elapsed"> · {label}</span>
  ) : (
    <span className="oc-pf-activity-elapsed block text-xs">{label}</span>
  );
}

/** Rich presence shared by member/friend rows (`compact`) and the profile card,
 * where it becomes a Discord-style activity card. */
export function ActivityStatus({
  activities,
  className,
  linked = true,
  compact = true,
}: {
  activities?: UserActivity[];
  className?: string;
  /** False when rendered inside another link, such as a conversation row. */
  linked?: boolean;
  /** Compact single-line row; the profile card variant is a card with artwork. */
  compact?: boolean;
}) {
  const activity = activities?.find((item) => item.kind === "spotify") ?? activities?.[0];
  if (!activity) return null;
  const Icon = activity.kind === "spotify" ? Music2 : Gamepad2;
  const label = t(activity.kind === "spotify" ? "common.listeningTo" : "common.playing");
  const artwork = activity.imageUrl ? (
    <img
      src={activity.imageUrl}
      alt=""
      className={cn(
        "oc-pf-activity-artwork shrink-0 rounded object-cover",
        compact ? "size-5" : "size-12",
      )}
    />
  ) : (
    <span
      className={cn(
        "oc-pf-activity-artwork flex shrink-0 items-center justify-center rounded",
        compact ? "size-5 bg-surface-3" : "size-12 bg-surface-2",
      )}
    >
      <Icon aria-hidden className={cn("oc-pf-activity-icon", compact ? "size-3" : "size-6")} />
    </span>
  );
  const content = compact ? (
    <>
      {artwork}
      <span className="oc-pf-activity-text truncate">
        {label}{" "}
        <span className="oc-pf-activity-name font-medium text-ink-secondary">{activity.name}</span>
        {activity.details ? ` - ${activity.details}` : ""}
        {activity.startedAt && <ElapsedTime startedAt={activity.startedAt} compact />}
      </span>
    </>
  ) : (
    <>
      {artwork}
      <span className="oc-pf-activity-text oc-pf-activity-meta min-w-0 flex-1">
        <span className="oc-pf-activity-label block text-[11px] font-medium uppercase tracking-wide text-ink-muted">
          {label}
        </span>
        <span className="oc-pf-activity-name block truncate text-sm font-semibold text-ink-secondary">
          {activity.name}
        </span>
        {activity.details && (
          <span className="oc-pf-activity-details block truncate text-xs text-ink-muted">
            {activity.details}
          </span>
        )}
        {activity.startedAt && <ElapsedTime startedAt={activity.startedAt} compact={false} />}
      </span>
    </>
  );

  const rootClass = cn(
    "flex min-w-0 items-center text-xs text-ink-muted",
    compact ? "gap-1.5" : "gap-3 rounded-lg border border-border bg-surface-3 p-3",
    className,
  );

  return activity.url && linked ? (
    <a
      href={activity.url}
      target="_blank"
      rel="noreferrer"
      title={`${label} ${activity.name}`}
      data-kind={activity.kind}
      className={cn(rootClass, "hover:text-ink")}
    >
      {content}
    </a>
  ) : (
    <span data-kind={activity.kind} className={rootClass}>
      {content}
    </span>
  );
}
