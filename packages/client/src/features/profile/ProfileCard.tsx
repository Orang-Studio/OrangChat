import { useId, useMemo, type CSSProperties, type ReactNode } from "react";
import { Camera, Loader2, Pencil } from "lucide-react";
import type {
  Connection,
  PresenceDevice,
  PresenceStatus,
  ProfileWidget,
  ProfileWidgetDefinition,
  UserActivity,
} from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { DeviceIndicators } from "../../components/DeviceIndicators";
import { cn } from "../../lib/cn";
import { sanitizeProfileCss } from "../../lib/profileCss";
import { t } from "../../lib/i18n";
import { ProfileWidgets } from "./ProfileWidgetRenderer";

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

  profileWidgets?: ProfileWidget[] | null;

  profileFields?: Record<string, string> | null;

  widgetCatalog?: Record<string, ProfileWidgetDefinition> | null;

  profileCss?: string | null;

  connections?: Connection[];
}

/**
 * Turns the preview into the editor: the banner and the avatar become the
 * upload buttons, so there is nothing to change about your picture that isn't
 * on the picture itself.
 */
export interface ProfileCardEdit {
  onPickAvatar: () => void;
  onPickBanner: () => void;
  busy?: "avatar" | "banner" | null;
}

const hex = (color: number) => `#${color.toString(16).padStart(6, "0")}`;

function EditOverlay({
  label,
  rounded,
  busy,
  onClick,
}: {
  label: string;
  rounded: "full" | "md";
  busy?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={cn(
        "absolute inset-0 z-10 flex items-center justify-center bg-black/45 text-white opacity-0 transition-opacity",
        "hover:opacity-100 focus-visible:opacity-100 focus-visible:outline-none",
        busy && "opacity-100",
        rounded === "full" ? "rounded-full" : "",
      )}
    >
      {busy ? (
        <Loader2 aria-hidden className="size-5 animate-spin" />
      ) : (
        <Camera aria-hidden className="size-5" />
      )}
    </button>
  );
}

function EditBadge() {
  return (
    <span
      aria-hidden
      className="pointer-events-none absolute right-1 top-1 z-20 flex size-5 items-center justify-center rounded-full bg-surface-2 text-ink shadow ring-1 ring-border"
    >
      <Pencil aria-hidden className="size-3" />
    </span>
  );
}

/**
 * Presentational Discord-style profile card. Shared by the profile popup and
 * the live preview in settings. Elements carry stable `oc-pf-*` hook classes so
 * users can target them from their (sandboxed) profile CSS.
 */
export function ProfileCard({
  data,
  actions,
  edit,
}: {
  data: ProfileCardData;
  actions?: ReactNode;
  edit?: ProfileCardEdit;
}) {
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
      <div
        className={cn(
          "oc-pf-banner h-20 w-full bg-[var(--oc-pf-accent)]",
          edit && "relative cursor-pointer",
        )}
      >
        {data.bannerUrl && (
          <img
            src={data.bannerUrl}
            alt=""
            className="oc-pf-banner-img size-full object-cover"
          />
        )}
        {edit && (
          <>
            <EditOverlay
              label={t("profileCard.changeBanner")}
              rounded="md"
              busy={edit.busy === "banner"}
              onClick={edit.onPickBanner}
            />
            <EditBadge />
          </>
        )}
      </div>
      <div className="oc-pf-inner px-4 pb-4">
        <div className="oc-pf-avatar -mt-9 mb-2">
          <span
            className={cn(
              "oc-pf-avatar-frame inline-block rounded-full bg-surface-2 p-1.5",
              edit && "relative cursor-pointer",
            )}
          >
            <Avatar
              user={{
                displayName: data.displayName,
                avatarUrl: data.avatarUrl,
                devices: data.devices,
              }}
              status={data.status}
              className="size-14"
              imgClassName="oc-pf-avatar-img rounded-full"
              fallbackClassName="oc-pf-avatar-fallback rounded-full"
            />
            {edit && (
              <>
                <EditOverlay
                  label={t("profileCard.changeAvatar")}
                  rounded="full"
                  busy={edit.busy === "avatar"}
                  onClick={edit.onPickAvatar}
                />
                <EditBadge />
              </>
            )}
          </span>
        </div>

        <div className="oc-pf-body rounded-lg bg-surface-1 p-3">
          <div className="oc-pf-head flex items-baseline gap-2">
            <h2 className="oc-pf-name truncate text-base font-bold">
              {data.displayName || "-"}
            </h2>
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
          <div className="mt-2">
            <ProfileWidgets data={data} />
          </div>

          {actions && <div className="oc-pf-actions mt-2.5 flex gap-2">{actions}</div>}
        </div>
      </div>
    </div>
  );
}
