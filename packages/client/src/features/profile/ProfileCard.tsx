import { useId, useMemo } from "react";
import type { Connection, PresenceDevice, PresenceStatus, UserActivity } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { DeviceIndicators } from "../../components/DeviceIndicators";
import { ActivityStatus } from "../../components/ActivityStatus";
import { cn } from "../../lib/cn";
import { formatFullTime } from "../../lib/time";
import { sanitizeProfileCss } from "../../lib/profileCss";
import { ConnectionCards } from "../connections/ConnectionCards";
import { ProfileBadges } from "./ProfileBadges";

export interface ProfileCardData {
  displayName: string;
  username: string;
  avatarUrl: string | null;
  bannerUrl: string | null;
  accentColor: number | null;
  pronouns: string | null;
  bio: string | null;
  status?: PresenceStatus;
  devices?: PresenceDevice[];
  activities?: UserActivity[];
  createdAt?: string;
  /** Awarded badge slugs; unknown ones are dropped at render. */
  badges?: readonly string[];
  /** Public user CSS; sanitized + scoped to this card before it's applied. */
  profileCss?: string | null;
  /** Linked external accounts. Already filtered to the visible ones. */
  connections?: Connection[];
}

const hex = (color: number) => `#${color.toString(16).padStart(6, "0")}`;

/**
 * Presentational Discord-style profile card. Shared by the profile popup and
 * the live preview in settings. Elements carry stable `oc-pf-*` hook classes so
 * users can target them from their (sandboxed) profile CSS.
 */
export function ProfileCard({ data }: { data: ProfileCardData }) {
  const accent = data.accentColor != null ? hex(data.accentColor) : undefined;

  // Unique scope per card instance → one card's theme never leaks onto another.
  const scopeClass = `oc-pf-${useId().replace(/:/g, "-")}`;
  const themeCss = useMemo(
    () => sanitizeProfileCss(data.profileCss, scopeClass),
    [data.profileCss, scopeClass],
  );

  return (
    <div
      className={cn(
        // `contain` + `isolation` + `overflow-hidden` trap any user positioning
        // inside the card box - it can't overlay or reach the rest of the app.
        "oc-profile-card relative isolate overflow-hidden rounded-lg border border-border bg-surface-2 [contain:layout_paint_style]",
        scopeClass,
      )}
    >
      {themeCss && <style dangerouslySetInnerHTML={{ __html: themeCss }} />}
      <div
        className="oc-pf-banner h-20 w-full"
        style={{ backgroundColor: accent ?? "var(--oc-surface-4)" }}
      >
        {data.bannerUrl && (
          <img src={data.bannerUrl} alt="" className="size-full object-cover" />
        )}
      </div>
      <div className="px-4 pb-4">
        <div className="oc-pf-avatar -mt-9 mb-2">
          <span className="inline-block rounded-md bg-surface-2 p-1.5">
            <Avatar
              user={{ displayName: data.displayName, avatarUrl: data.avatarUrl }}
              className="size-14 [&_img]:rounded-md [&>span:first-child]:rounded-md"
            />
          </span>
        </div>

        <div className="oc-pf-body rounded-lg bg-surface-1 p-3">
          <div className="flex items-baseline gap-2">
            <h2 className="oc-pf-name truncate text-base font-bold">
              {data.displayName || "-"}
            </h2>
            {data.pronouns && (
              <span className="oc-pf-pronouns text-xs text-ink-muted">{data.pronouns}</span>
            )}
          </div>
          <div className="flex min-w-0 items-center gap-1.5">
            <p className="oc-pf-username truncate text-sm text-ink-secondary">
              @{data.username || "username"}
            </p>
            {data.status && (
              <DeviceIndicators status={data.status} devices={data.devices ?? []} />
            )}
          </div>
          <ActivityStatus activities={data.activities ?? []} className="mt-1" />

          <ProfileBadges badges={data.badges ?? []} className="mt-2" />

          {data.bio && (
            <div className="oc-pf-bio mt-2.5 border-t border-border pt-2.5">
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                About me
              </h3>
              <p className="whitespace-pre-wrap break-words text-sm">{data.bio}</p>
            </div>
          )}

          <ConnectionCards connections={data.connections ?? []} />

          {data.createdAt && (
            <div className="oc-pf-member mt-2.5 border-t border-border pt-2.5">
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                Member since
              </h3>
              <p className="text-sm">{formatFullTime(data.createdAt)}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
