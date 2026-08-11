import { useId, useMemo, type CSSProperties } from "react";
import type { Connection, PresenceDevice, PresenceStatus, UserActivity } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { DeviceIndicators } from "../../components/DeviceIndicators";
import { ActivityStatus } from "../../components/ActivityStatus";
import { cn } from "../../lib/cn";
import { formatFullTime } from "../../lib/time";
import { sanitizeProfileCss } from "../../lib/profileCss";
import { ConnectionCards } from "../connections/ConnectionCards";
import { ProfileBadges } from "./ProfileBadges";
import { t } from "../../lib/i18n";

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

  badges?: readonly string[];

  profileCss?: string | null;

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
      // Attribute hooks let a theme react to state it can't otherwise see, e.g.
      // `.oc-profile-card[data-status="dnd"] .oc-pf-name { color: … }`.
      data-status={data.status ?? "offline"}
      data-has-banner={data.bannerUrl ? "true" : "false"}
      data-has-avatar={data.avatarUrl ? "true" : "false"}
      style={{ "--oc-pf-accent": accent ?? "var(--oc-surface-4)" } as CSSProperties}
    >
      {themeCss && <style dangerouslySetInnerHTML={{ __html: themeCss }} />}
      <div className="oc-pf-banner h-20 w-full bg-[var(--oc-pf-accent)]">
        {data.bannerUrl && (
          <img
            src={data.bannerUrl}
            alt=""
            className="oc-pf-banner-img size-full object-cover"
          />
        )}
      </div>
      <div className="oc-pf-inner px-4 pb-4">
        <div className="oc-pf-avatar -mt-9 mb-2">
          <span className="oc-pf-avatar-frame inline-block rounded-md bg-surface-2 p-1.5">
            <Avatar
              user={{
                displayName: data.displayName,
                avatarUrl: data.avatarUrl,
                devices: data.devices,
              }}
              status={data.status}
              className="size-14"
              imgClassName="oc-pf-avatar-img rounded-md"
              fallbackClassName="oc-pf-avatar-fallback rounded-md"
            />
          </span>
        </div>

        <div className="oc-pf-body rounded-lg bg-surface-1 p-3">
          <div className="oc-pf-head flex items-baseline gap-2">
            <h2 className="oc-pf-name truncate text-base font-bold">
              {data.displayName || "-"}
            </h2>
            {data.pronouns && (
              <span className="oc-pf-pronouns text-xs text-ink-muted">{data.pronouns}</span>
            )}
          </div>
          <div className="oc-pf-identity flex min-w-0 items-center gap-1.5">
            <p className="oc-pf-username truncate text-sm text-ink-secondary">
              @{data.username || "username"}
            </p>
            {data.status && (
              <DeviceIndicators
                status={data.status}
                devices={data.devices ?? []}
                className="oc-pf-devices"
                itemClassName="oc-pf-device"
              />
            )}
          </div>
          <ActivityStatus
            activities={data.activities ?? []}
            className="oc-pf-activity mt-2"
            compact={false}
          />

          <ProfileBadges badges={data.badges ?? []} className="mt-2" />

          {data.bio && (
            <div className="oc-pf-bio oc-pf-section mt-2.5 border-t border-border pt-2.5">
              <h3 className="oc-pf-heading mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                {t("profileCard.aboutMe")}
              </h3>
              <p className="oc-pf-bio-text whitespace-pre-wrap break-words text-sm">
                {data.bio}
              </p>
            </div>
          )}

          <ConnectionCards connections={data.connections ?? []} />

          {data.createdAt && (
            <div className="oc-pf-member oc-pf-section mt-2.5 border-t border-border pt-2.5">
              <h3 className="oc-pf-heading mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                {t("profileCard.memberSince")}
              </h3>
              <p className="oc-pf-member-text text-sm">{formatFullTime(data.createdAt)}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
