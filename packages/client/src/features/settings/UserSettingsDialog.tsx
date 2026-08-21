import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Accessibility,
  Download,
  Gamepad2,
  Info,
  Link2,
  Lock,
  LogOut,
  Moon,
  Monitor,
  Paintbrush,
  Palette,
  Puzzle,
  ShieldCheck,
  ScanLine,
  Sliders,
  Sun,
  Upload,
  User as UserIcon,
  Video,
  X,
} from "lucide-react";
import { type PresenceStatus, type ProfileWidget } from "@orangchat/shared";
import { statusLabel } from "../../components/Avatar";
import { StatusIcon } from "../../components/StatusIcon";
import { Button } from "../../components/ui/Button";
import { ImageField } from "../../components/ImageField";
import { Dialog, DialogClose, DialogFullScreenContent } from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { errorMessage } from "../../lib/errors";
import { LANGUAGES, endonymOf, setLanguage, t, useLanguage } from "../../lib/i18n";
import { getTheme, setTheme, type Theme } from "../../lib/theme";
import { useInstalledTheme } from "../plugins/themes";
import { socket } from "../../lib/socket";
import defaultCss from "../../styles/index.css?raw";
import { toast } from "../../stores/toasts";
import { uploadImage, type UploadKind } from "../uploads/api";
import { getMyConnections } from "../connections/api";
import { ProfileCard } from "../profile/ProfileCard";
import { FieldTokensSection } from "../profile/FieldTokensSection";
import { WidgetEditor } from "../profile/WidgetEditor";
import { useWidgetCatalogMap } from "../profile/widgetCatalog";
import { resolveLayout } from "../profile/widgets";
import { useAuthStore, authStoreActions } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { logout } from "../auth/session";
import { SectionTitle } from "./controls";
import { ActivityTab } from "./ActivityTab";
import { ConnectionsTab } from "./ConnectionsTab";
import { PrivacyTab } from "./PrivacyTab";
import { SharingTab } from "./SharingTab";
import { SecurityTab } from "./SecurityTab";
import { AccessibilityTab } from "./AccessibilityTab";
import { SystemTab } from "./SystemTab";
import { DownloadTab } from "./DownloadTab";
import { AboutTab } from "./AboutTab";
import { DevicesTab } from "./DevicesTab";
import { EncryptionTab } from "./EncryptionTab";
import { PluginsTab } from "./PluginsTab";
import { MarketplaceThemes } from "./MarketplaceThemes";
import { ProfileThemesTab } from "./ProfileThemesTab";
import { QrSignInTab } from "./QrSignInTab";

interface UserSettingsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;

  initialSection?: SettingsSection;
}

const STATUS_OPTIONS: PresenceStatus[] = ["online", "idle", "dnd"];

const intToHex = (n: number | null) =>
  n != null ? `#${n.toString(16).padStart(6, "0")}` : "#ff6a1a";
const hexToInt = (hex: string) => parseInt(hex.replace("#", ""), 16);

/** Documented starter template listing the stable hook classes users can target. */
const PROFILE_CSS_TEMPLATE = `/* OrangChat profile theme - this CSS is sandboxed:
 * it only styles YOUR profile card, can't load external URLs,
 * and can't escape the card box. Every element of the card has a
 * hook class, so anything you can see, you can restyle.
 *
 * LAYOUT
 *   .oc-profile-card        the whole card
 *   .oc-pf-banner           the top banner strip
 *   .oc-pf-banner-img       your banner image (only when you have one)
 *   .oc-pf-inner            padding wrapper below the banner
 *   .oc-pf-avatar           the avatar slot
 *   .oc-pf-avatar-frame     the padded square behind the avatar
 *   .oc-pf-avatar-img       your avatar image
 *   .oc-pf-avatar-fallback  the initial shown when you have no avatar
 *   .oc-pf-body             the info panel
 *   .oc-pf-widgets          the widget stack
 *   .oc-pf-widget           one widget ([data-widget="bio"|"links"|…])
 *   .oc-pf-heading          the small uppercase heading inside a widget
 *
 * IDENTITY
 *   .oc-pf-head             the name + pronouns row
 *   .oc-pf-name             display name
 *   .oc-pf-pronouns         pronouns
 *   .oc-pf-identity         the @username + device row
 *   .oc-pf-username         @username
 *   .oc-pf-devices          the device-icon group
 *   .oc-pf-device           one device icon ([data-device="mobile|browser|desktop"])
 *
 * ACTIVITY (what you are listening to / playing)
 *   .oc-pf-activity         the whole line ([data-kind="listening"|"game"])
 *   .oc-pf-activity-artwork its artwork (or icon container when none is set)
 *   .oc-pf-activity-icon    its icon
 *   .oc-pf-activity-text    "Listening to <name> - <details>"
 *   .oc-pf-activity-meta    the rich profile-card text column
 *   .oc-pf-activity-name    just the track / game name
 *   .oc-pf-activity-details the optional detail line
 *   .oc-pf-activity-elapsed the live "for 12:34" clock
 *
 * BADGES
 *   .oc-pf-badges           the badge row
 *   .oc-pf-badge            one badge image ([data-badge="early_member"] …)
 *   .oc-pf-badge-<slug>     one specific badge, e.g. .oc-pf-badge-bonfire
 *
 * BIO + MEMBER SINCE
 *   .oc-pf-bio              the About-me block
 *   .oc-pf-bio-text         its text
 *   .oc-pf-member           the Member-since block
 *   .oc-pf-member-text      the date
 *
 * FREE TEXT, LISTS, LINKS, IMAGES
 *   .oc-pf-text             a free-text widget's body
 *   .oc-pf-rows             a details widget's grid
 *   .oc-pf-row-label        one row's label
 *   .oc-pf-row-value        one row's value
 *   .oc-pf-links            a links widget's list
 *   .oc-pf-link             one link
 *   .oc-pf-image            an image widget
 *   .oc-pf-spacer           a space widget
 *   .oc-pf-divider          a divider widget
 *
 * CONNECTIONS
 *   .oc-pf-connections      the whole block
 *   .oc-pf-connections-grid the two-column grid
 *   .oc-pf-connection       one card ([data-provider="steam"|"github"|…])
 *   .oc-pf-connection-<provider>  one provider, e.g. .oc-pf-connection-steam
 *   .oc-pf-connection-icon  its provider glyph
 *   .oc-pf-connection-name  the account name
 *   .oc-pf-connection-verified  the verified check
 *   .oc-pf-connection-sub   the subtitle line
 *
 * STATE HOOKS on .oc-profile-card
 *   [data-status="online"|"idle"|"dnd"|"offline"]
 *   [data-has-banner="true"|"false"], [data-has-avatar="true"|"false"]
 *   --oc-pf-accent          your accent colour, usable anywhere in here
 *
 * ALLOWED: any selector combination (:hover, :nth-child, ::before, …),
 * transitions, transforms, filters, gradients, data: URL images,
 * @media, @supports, @container, @starting-style, @layer, @keyframes.
 * NOT ALLOWED (silently stripped): external url(), @import, @font-face,
 * position: fixed/sticky, and anything outside the card. Write flat
 * selectors - nested rules (&) are not parsed.
 */

.oc-pf-body {
  background: #1a1030;
  color: #e9d5ff;
}
.oc-pf-name {
  color: #c084fc;
  letter-spacing: 0.02em;
}
.oc-pf-banner {

  background: repeating-linear-gradient(45deg, #7c3aed, #7c3aed 10px, #5b21b6 10px, #5b21b6 20px);
}
.oc-pf-avatar-frame {
  background: #1a1030;
  box-shadow: 0 0 0 2px var(--oc-pf-accent);
}
.oc-pf-heading {
  color: #a78bfa;
}
.oc-pf-badge {
  transition: transform 120ms ease;
}
.oc-pf-badge:hover {
  transform: translateY(-2px) scale(1.1);
}
.oc-profile-card[data-status="dnd"] .oc-pf-username {
  color: #fca5a5;
}
`;

/** Trigger a client-side download of text as a file. */
function downloadText(filename: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/css" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

const ACCENT_PRESETS = [
  0xff6a1a, 0xf43f5e, 0xec4899, 0xa855f7, 0x6366f1, 0x3b82f6, 0x06b6d4, 0x10b981, 0x84cc16,
  0xeab308, 0xf97316, 0x64748b,
];

type ProfilePane = "identity" | "widgets" | "theme";

/**
 * Pick an image file → server compresses & stores it → returns its URL, plus a
 * `blob:` url of the bytes just picked. The stored url may point at Cloudinary,
 * which `img-src 'self'` blocks; it only becomes same-origin once it is saved
 * and served back through /api/media/asset, so the local blob is what the
 * preview can actually render in between.
 */
function useImagePicker(kind: UploadKind, onPicked: (url: string, preview: string) => void) {
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);

  const element = (
    <input
      ref={ref}
      type="file"
      accept="image/png,image/jpeg,image/gif,image/webp"
      className="hidden"
      onChange={(e) => {
        const file = e.target.files?.[0];
        e.target.value = "";
        if (!file) return;
        setBusy(true);
        void uploadImage(file, kind)
          .then((url) => onPicked(url, URL.createObjectURL(file)))
          .catch((error: unknown) => toast.error(errorMessage(error, t("common.error"))))
          .finally(() => setBusy(false));
      }}
    />
  );

  return { element, busy, open: () => ref.current?.click() };
}

function PaneTabs({ value, onChange }: { value: ProfilePane; onChange: (next: ProfilePane) => void }) {
  const panes: { id: ProfilePane; label: string }[] = [
    { id: "identity", label: t("userSettingsDialog.identity") },
    { id: "widgets", label: t("userSettingsDialog.widgets") },
    { id: "theme", label: t("userSettingsDialog.theme") },
  ];
  return (
    <div className="flex gap-1 rounded-xl border border-border bg-surface-2 p-1">
      {panes.map((pane) => (
        <button
          key={pane.id}
          type="button"
          aria-pressed={value === pane.id}
          onClick={() => onChange(pane.id)}
          className={cn(
            "flex-1 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors",
            value === pane.id
              ? "bg-surface-4 text-ink shadow-sm"
              : "text-ink-secondary hover:text-ink",
          )}
        >
          {pane.label}
        </button>
      ))}
    </div>
  );
}

function ProfileTab() {
  const user = useAuthStore((s) => s.user);
  const [pane, setPane] = useState<ProfilePane>("identity");
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [username, setUsername] = useState(user?.username ?? "");
  const [avatarUrl, setAvatarUrl] = useState(user?.avatarUrl ?? "");
  const [avatarPreview, setAvatarPreview] = useState("");
  const [bannerPreview, setBannerPreview] = useState("");
  const [pronouns, setPronouns] = useState(user?.pronouns ?? "");
  const [bio, setBio] = useState(user?.bio ?? "");
  const [bannerUrl, setBannerUrl] = useState(user?.bannerUrl ?? "");
  const [accentColor, setAccentColor] = useState<number | null>(user?.accentColor ?? null);
  const [profileCss, setProfileCss] = useState(user?.profileCss ?? "");
  const [widgets, setWidgets] = useState<ProfileWidget[]>(() =>
    resolveLayout(user?.profileWidgets),
  );
  const [status, setStatus] = useState<PresenceStatus>(
    user?.status && user.status !== "offline" ? user.status : "online",
  );
  const [saved, setSaved] = useState(false);

  // Preview what others actually see, so hidden connections stay out of it.
  const { data: connections } = useQuery({
    queryKey: ["connections", "mine"],
    queryFn: getMyConnections,
  });
  const visibleConnections = useMemo(
    () => connections?.filter((c) => c.visible) ?? [],
    [connections],
  );
  const widgetCatalog = useWidgetCatalogMap();

  const avatarPicker = useImagePicker("avatar", (url, preview) => {
    setAvatarUrl(url);
    setAvatarPreview(preview);
  });
  const bannerPicker = useImagePicker("banner", (url, preview) => {
    setBannerUrl(url);
    setBannerPreview(preview);
  });

  // For an image stored off-origin the api hands back its own `/api/media/asset`
  // route, and that is what seeds these fields. Sending an untouched one back
  // would ask the server to store a url pointing at itself, so omit these unless
  // they were actually edited - `undefined` leaves the field alone.
  const ifEdited = (next: string, current: string | null) =>
    (next.trim() || null) === current ? undefined : next.trim() || null;

  const mutation = useMutation({
    mutationFn: () =>
      updateProfile({
        displayName: displayName.trim() || undefined,
        username: username.trim() || undefined,
        avatarUrl: ifEdited(avatarUrl, user?.avatarUrl ?? null),
        pronouns: pronouns.trim() ? pronouns.trim() : null,
        bio: bio.trim() ? bio.trim() : null,
        bannerUrl: ifEdited(bannerUrl, user?.bannerUrl ?? null),
        accentColor,
        profileCss: profileCss.length ? profileCss : null,
        profileWidgets: widgets,
      }),
    onSuccess: (updated) => {
      authStoreActions.setUser(updated);
      // Saved: the server now serves these same-origin, so drop the blobs.
      setAvatarPreview("");
      setBannerPreview("");
      setWidgets(resolveLayout(updated.profileWidgets));
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    },
  });

  const pickStatus = (next: PresenceStatus) => {
    setStatus(next);
    socket.emit("presence:update", next);
  };

  const revert = () => {
    if (!user) return;
    setDisplayName(user.displayName);
    setUsername(user.username);
    setAvatarUrl(user.avatarUrl ?? "");
    setBannerUrl(user.bannerUrl ?? "");
    setAvatarPreview("");
    setBannerPreview("");
    setPronouns(user.pronouns ?? "");
    setBio(user.bio ?? "");
    setAccentColor(user.accentColor);
    setProfileCss(user.profileCss ?? "");
    setWidgets(resolveLayout(user.profileWidgets));
  };

  if (!user) return null;

  const draft = {
    displayName: displayName.trim(),
    username: username.trim(),
    avatarUrl: avatarUrl.trim() || null,
    bannerUrl: bannerUrl.trim() || null,
    pronouns: pronouns.trim() || null,
    bio: bio.trim() || null,
    accentColor,
    profileCss: profileCss.length ? profileCss : null,
    widgets,
  };
  const baseline = {
    displayName: user.displayName,
    username: user.username,
    avatarUrl: user.avatarUrl,
    bannerUrl: user.bannerUrl,
    pronouns: user.pronouns,
    bio: user.bio,
    accentColor: user.accentColor,
    profileCss: user.profileCss,
    widgets: resolveLayout(user.profileWidgets),
  };
  const dirty = JSON.stringify(draft) !== JSON.stringify(baseline);

  const fieldClass =
    "w-full rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm placeholder:text-ink-muted hover:border-border-strong";

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (dirty) mutation.mutate();
      }}
      className="space-y-4"
    >
      {avatarPicker.element}
      {bannerPicker.element}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
        <div className="order-2 space-y-4 lg:order-1">
          <PaneTabs value={pane} onChange={setPane} />

          {pane === "identity" && (
            <div className="space-y-4">
              <TextField
                label={t("userSettingsDialog.displayName")}
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                maxLength={64}
              />
              <TextField
                label={t("userSettingsDialog.username")}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                maxLength={32}
                hint={t("userSettingsDialog.lowercaseLettersNumbersUnderscoresAndDots")}
              />
              <TextField
                label={t("userSettingsDialog.pronouns")}
                value={pronouns}
                onChange={(e) => setPronouns(e.target.value)}
                maxLength={40}
                placeholder={t("userSettingsDialog.theyThem")}
              />
              <div className="space-y-1.5">
                <div className="flex items-baseline justify-between">
                  <label
                    htmlFor="oc-bio"
                    className="block text-sm font-medium text-ink-secondary"
                  >
                    {t("userSettingsDialog.aboutMe")}
                  </label>
                  <span className="text-xs tabular-nums text-ink-muted">{bio.length}/4000</span>
                </div>
                <textarea
                  id="oc-bio"
                  value={bio}
                  onChange={(e) => setBio(e.target.value)}
                  maxLength={4000}
                  rows={5}
                  placeholder={t("userSettingsDialog.tellPeopleAboutYourself")}
                  className={cn(fieldClass, "resize-y")}
                />
                <p className="text-xs text-ink-muted">
                  {t("userSettingsDialog.markdownAndLinksWork")}
                </p>
              </div>

              <div className="space-y-2">
                <label className="block text-sm font-medium text-ink-secondary">
                  {t("userSettingsDialog.accentColor")}
                </label>
                <div className="flex flex-wrap items-center gap-1.5">
                  {ACCENT_PRESETS.map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setAccentColor(preset)}
                      aria-label={intToHex(preset)}
                      aria-pressed={accentColor === preset}
                      style={{ background: intToHex(preset) }}
                      className={cn(
                        "size-7 rounded-full border-2 transition-transform hover:scale-110",
                        accentColor === preset ? "border-ink" : "border-transparent",
                      )}
                    />
                  ))}
                  <label
                    className="relative size-7 cursor-pointer overflow-hidden rounded-full border border-dashed border-border-strong"
                    title={t("userSettingsDialog.customColor")}
                  >
                    <input
                      type="color"
                      aria-label={t("userSettingsDialog.customColor")}
                      value={intToHex(accentColor)}
                      onChange={(e) => setAccentColor(hexToInt(e.target.value))}
                      className="absolute -inset-2 cursor-pointer opacity-0"
                    />
                    <Palette
                      aria-hidden
                      className="pointer-events-none absolute inset-0 m-auto size-3.5 text-ink-muted"
                    />
                  </label>
                  {accentColor != null && (
                    <button
                      type="button"
                      onClick={() => setAccentColor(null)}
                      className="text-sm text-ink-muted transition-colors hover:text-ink"
                    >
                      {t("userSettingsDialog.clear")}
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}

          {pane === "widgets" && (
            <div className="space-y-5">
              <div className="space-y-2">
                <p className="text-xs text-ink-muted">{t("userSettingsDialog.widgetsIntro")}</p>
                <WidgetEditor value={widgets} onChange={setWidgets} />
              </div>
              <div className="space-y-2 border-t border-border pt-4">
                <SectionTitle>{t("userSettingsDialog.pushedFields")}</SectionTitle>
                <FieldTokensSection fields={user.profileFields} />
              </div>
            </div>
          )}

          {pane === "theme" && (
            <div className="space-y-2">
              <p className="text-xs text-ink-muted">
                {t("userSettingsDialog.styleYourProfileCardHoweverYou")}
              </p>
              <textarea
                value={profileCss}
                onChange={(e) => setProfileCss(e.target.value)}
                maxLength={100_000}
                rows={14}
                spellCheck={false}
                placeholder={t("userSettingsDialog.ocPfBodyBackground1a1030")}
                className={cn(fieldClass, "resize-y font-mono text-xs")}
              />
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setProfileCss(PROFILE_CSS_TEMPLATE)}
                >
                  {t("userSettingsDialog.loadStarter")}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() =>
                    downloadText("orangchat-profile-template.css", PROFILE_CSS_TEMPLATE)
                  }
                >
                  <Download aria-hidden className="size-4" />
                  {t("userSettingsDialog.downloadTemplate")}
                </Button>
                {profileCss.length > 0 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-danger hover:text-danger"
                    onClick={() => setProfileCss("")}
                  >
                    {t("userSettingsDialog.clear")}
                  </Button>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="order-1 space-y-3 lg:sticky lg:top-0 lg:order-2">
          <ProfileCard
            data={{
              displayName: displayName || user.displayName,
              username,
              avatarUrl: avatarPreview || avatarUrl.trim() || null,
              bannerUrl: bannerPreview || bannerUrl.trim() || null,
              accentColor,
              pronouns: pronouns.trim() || null,
              bio: bio.trim() || null,
              status,
              createdAt: user.createdAt,
              badges: user.badges,
              profileCss: profileCss.length ? profileCss : null,
              profileWidgets: widgets,
              profileFields: user.profileFields,
              widgetCatalog,
              connections: visibleConnections,
            }}
            edit={{
              onPickAvatar: avatarPicker.open,
              onPickBanner: bannerPicker.open,
              busy: avatarPicker.busy ? "avatar" : bannerPicker.busy ? "banner" : null,
            }}
          />

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-muted">
            <span className="flex items-center gap-1">
              <Upload aria-hidden className="size-3.5" />
              {t("userSettingsDialog.clickThePictureToChangeIt")}
            </span>
            {(avatarPreview || avatarUrl) && (
              <button
                type="button"
                onClick={() => {
                  setAvatarUrl("");
                  setAvatarPreview("");
                }}
                className="transition-colors hover:text-danger"
              >
                {t("userSettingsDialog.removeAvatar")}
              </button>
            )}
            {(bannerPreview || bannerUrl) && (
              <button
                type="button"
                onClick={() => {
                  setBannerUrl("");
                  setBannerPreview("");
                }}
                className="transition-colors hover:text-danger"
              >
                {t("userSettingsDialog.removeBanner")}
              </button>
            )}
          </div>

          <div>
            <SectionTitle>{t("userSettingsDialog.status")}</SectionTitle>
            <div className="flex gap-1.5">
              {STATUS_OPTIONS.map((option) => (
                <button
                  key={option}
                  type="button"
                  aria-pressed={status === option}
                  onClick={() => pickStatus(option)}
                  className={cn(
                    "flex flex-1 items-center justify-center gap-1.5 rounded-lg border px-2 py-2 text-xs transition-colors",
                    status === option
                      ? "border-primary bg-primary-soft"
                      : "border-border hover:border-border-strong",
                  )}
                >
                  <StatusIcon status={option} label={null} className="size-3" />
                  {statusLabel(option)}
                </button>
              ))}
            </div>
          </div>

          <p className="text-xs text-ink-muted">
            {t("userSettingsDialog.signedInAs")}{" "}
            <span className="text-ink-secondary">{user.email}</span>
          </p>
        </div>
      </div>

      {mutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {mutation.error.message}
        </p>
      )}

      <div className="sticky bottom-0 -mx-1 flex items-center gap-2 border-t border-border bg-surface-2/95 px-1 py-2.5 backdrop-blur">
        <span className="min-w-0 flex-1 truncate text-xs text-ink-muted">
          {dirty ? t("userSettingsDialog.unsavedChanges") : saved ? t("userSettingsDialog.savedExclamation") : ""}
        </span>
        {dirty && (
          <Button type="button" variant="ghost" size="sm" onClick={revert}>
            {t("common.reset")}
          </Button>
        )}
        <Button type="submit" loading={mutation.isPending} disabled={!dirty} size="sm">
          {t("userSettingsDialog.saveProfile")}
        </Button>
      </div>
    </form>
  );
}

/** A copy-pasteable override that proves the mechanism works end to end. */
const CUSTOM_CSS_EXAMPLE = `/* Overrides the app's design tokens. Unlayered rules like these
 * outrank the built-in theme, so a plain :root block is enough. */
:root {
  --oc-primary: #22c55e;
  --oc-primary-hover: #4ade80;
  --oc-primary-soft: rgba(34, 197, 94, 0.16);
  --oc-surface-1: #0d1512;
  --oc-surface-2: #101a16;
}
`;

/** Upload/override CSS to theme the app for yourself. */
function CustomCssSection() {
  const user = useAuthStore((s) => s.user);
  const fileRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState(user?.customCss ?? "");

  const mutation = useMutation({
    mutationFn: (css: string | null) => updateProfile({ customCss: css }),
    onSuccess: (updated) => {
      authStoreActions.setUser(updated);
      setDraft(updated.customCss ?? "");
    },
  });

  const onFile = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    if (file.size > 100_000) {
      setError(t("userSettingsDialog.cssFileIsTooLarge"));
      return;
    }
    const text = await file.text();
    setDraft(text);
    mutation.mutate(text);
  };

  const hasCss = Boolean(user?.customCss);
  const dirty = draft !== (user?.customCss ?? "");

  return (
    <div className="space-y-3">
      <SectionTitle>{t("userSettingsDialog.customCssTheme")}</SectionTitle>
      <p className="text-xs text-ink-muted">
        {t("userSettingsDialog.restyleTheAppForYourselfPaste")}
      </p>
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        maxLength={100_000}
        rows={6}
        spellCheck={false}
        aria-label={t("userSettingsDialog.customCss")}
        placeholder={t("userSettingsDialog.rootOcPrimary22c55e")}
        className="w-full resize-y rounded-lg border border-border bg-surface-1 px-3 py-2 font-mono text-xs"
      />
      <input
        ref={fileRef}
        type="file"
        accept=".css,text/css"
        className="hidden"
        onChange={(e) => void onFile(e.target.files?.[0] ?? undefined)}
      />
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          disabled={!dirty}
          loading={mutation.isPending}
          onClick={() => mutation.mutate(draft.trim() ? draft : null)}
        >
          {dirty ? t("userSettingsDialog.applyCss") : t("userSettingsDialog.applied")}
        </Button>
        <Button variant="secondary" size="sm" onClick={() => fileRef.current?.click()}>
          <Upload aria-hidden className="size-4" />
          {t("userSettingsDialog.uploadCss")}
        </Button>
        <Button variant="secondary" size="sm" onClick={() => setDraft(CUSTOM_CSS_EXAMPLE)}>
          {t("userSettingsDialog.loadExample")}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => downloadText("orangchat-default.css", defaultCss)}
        >
          <Download aria-hidden className="size-4" />
          {t("userSettingsDialog.downloadDefault")}
        </Button>
        {hasCss && (
          <Button
            variant="ghost"
            size="sm"
            className="text-danger hover:text-danger"
            loading={mutation.isPending}
            onClick={() => mutation.mutate(null)}
          >
            {t("common.remove")}
          </Button>
        )}
      </div>
      {hasCss && !dirty && <p className="text-xs text-success">{t("userSettingsDialog.customCssIsActive")}</p>}
      {error && <p className="text-xs text-danger">{error}</p>}
      {mutation.isError && <p className="text-xs text-danger">{mutation.error.message}</p>}
    </div>
  );
}

/** App look & feel: theme + custom CSS. */
function AppearanceTab({ onNavigate }: { onNavigate: (to: SettingsSection) => void }) {
  const [theme, setThemeState] = useState<Theme>(getTheme);
  const installedTheme = useInstalledTheme((s) => s.installed);

  const pick = (next: Theme) => {
    setTheme(next);
    setThemeState(next);
  };

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>{t("userSettingsDialog.theme")}</SectionTitle>
        <div className="flex gap-2">
          {(
            [
              ["dark", Moon, t("userSettingsDialog.dark")],
              ["light", Sun, t("userSettingsDialog.light")],
            ] as const
          ).map(([value, Icon, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={theme === value}
              onClick={() => pick(value)}
              className={cn(
                "flex flex-1 items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm transition-colors",
                theme === value
                  ? "border-primary bg-primary-soft"
                  : "border-border hover:border-border-strong",
              )}
            >
              <Icon aria-hidden className="size-4" />
              {label}
            </button>
          ))}
        </div>
      </div>
      <div className="border-t border-border pt-5">
        <LanguageSection />
      </div>
      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("userSettingsDialog.communityThemes")}</SectionTitle>
        <p className="text-xs text-ink-muted">
          {t("userSettingsDialog.prebuiltColourThemesAndProfileCard")}
        </p>
        {installedTheme && (
          <p className="text-xs text-success">
            {t("userSettingsDialog.themeNameIsInstalled", { name: installedTheme.name })}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={() => onNavigate("themes")}>
            <Palette aria-hidden className="size-4" />
            {t("userSettingsDialog.browseAppThemes")}
          </Button>
          <Button variant="secondary" size="sm" onClick={() => onNavigate("profile_themes")}>
            <Paintbrush aria-hidden className="size-4" />
            {t("userSettingsDialog.browseProfileThemes")}
          </Button>
        </div>
      </div>

      <div className="border-t border-border pt-5">
        <AppIconSection />
      </div>

      <div className="border-t border-border pt-5">
        <CustomCssSection />
      </div>
    </div>
  );
}

/**
 * Replace the OrangChat mark on this account's own clients. Saves immediately -
 * there is nothing else on the form to batch it with, and the result is visible
 * the moment it lands (the tab favicon changes under you).
 */
function AppIconSection() {
  const user = useAuthStore((s) => s.user);
  const [preview, setPreview] = useState("");
  const [error, setError] = useState<string | null>(null);

  const save = async (url: string, blob: string) => {
    setError(null);
    setPreview(blob);
    try {
      const updated = await updateProfile({ appIconUrl: url || null });
      authStoreActions.setUser(updated);
      setPreview("");
    } catch (e) {
      setPreview("");
      setError(e instanceof Error ? e.message : t("userSettingsDialog.couldNotSaveTheIcon"));
    }
  };

  return (
    <div className="space-y-3">
      <SectionTitle>{t("userSettingsDialog.appIcon")}</SectionTitle>
      <p className="text-xs text-ink-muted">
        {t("userSettingsDialog.replacesTheOrangchatMarkForYou")}
      </p>
      <ImageField
        label={t("userSettingsDialog.icon")}
        kind="app-icon"
        rounded="md"
        value={user?.appIconUrl ?? ""}
        preview={preview}
        onChange={(url, blob) => void save(url, blob)}
        hint={t("userSettingsDialog.squareImagesWorkBestLeaveEmpty")}
      />
      {error && <p className="text-xs text-danger">{error}</p>}
    </div>
  );
}

/**
 * The interface language. Device-local, like the theme above it: the account
 * has no language, so signing in somewhere else does not carry this along.
 */
function LanguageSection() {
  const pref = useLanguage((s) => s.pref);
  const language = useLanguage((s) => s.language);

  const option = (value: typeof pref, label: string) => (
    <button
      key={value}
      type="button"
      aria-pressed={pref === value}
      onClick={() => setLanguage(value)}
      className={cn(
        "flex items-center gap-2 rounded-lg border px-3 py-2 text-sm transition-colors",
        pref === value ? "border-primary bg-primary-soft" : "border-border hover:border-border-strong",
      )}
    >
      {label}
    </button>
  );

  return (
    <div className="space-y-3">
      <SectionTitle>{t("language.title")}</SectionTitle>
      <p className="text-xs text-ink-muted">{t("language.description")}</p>
      <div className="flex flex-wrap gap-2">
        {option("system", t("language.system"))}
        {LANGUAGES.map(({ code, endonym }) => option(code, endonym))}
      </div>
      {pref === "system" && (
        <p className="text-xs text-ink-muted">
          {t("language.systemHint", { language: endonymOf(language) })}
        </p>
      )}
      {/* Said plainly rather than hidden: a reader who meets an English
          sentence in a Lithuanian menu should know it is unfinished work and
          not a bug in their own reading. */}
      {language !== "en" && <p className="text-xs text-warning">{t("language.incomplete")}</p>}
    </div>
  );
}

type SettingsSection =
  | "profile"
  | "connections"
  | "activity"
  | "privacy"
  | "sharing"
  | "security"
  | "qr_sign_in"
  | "devices"
  | "encryption"
  | "plugins"
  | "themes"
  | "profile_themes"
  | "accessibility"
  | "appearance"
  | "system"
  | "download"
  | "about";

interface SettingsNavItem {
  id: SettingsSection;
  label: string;
  icon: typeof UserIcon;
}

// Functions, not module-level consts: `t()` reads the currently active
// language, and this module is only ever evaluated once. A plain const here
// would freeze these strings in whichever language was active on first
// import, and never update when the user switches languages.
function navGroups(): { title: string; items: SettingsNavItem[] }[] {
  return [
    {
      title: t("userSettingsDialog.navGroupAccount"),
      items: [
        { id: "profile", label: t("userSettingsDialog.navProfile"), icon: UserIcon },
        { id: "connections", label: t("userSettingsDialog.navConnections"), icon: Link2 },
        { id: "activity", label: t("userSettingsDialog.navActivity"), icon: Gamepad2 },
      ],
    },
    {
      title: t("userSettingsDialog.navGroupPrivacySecurity"),
      items: [
        { id: "privacy", label: t("userSettingsDialog.navPrivacy"), icon: Sliders },
        { id: "security", label: t("userSettingsDialog.navSecurity"), icon: ShieldCheck },
        { id: "encryption", label: t("userSettingsDialog.navEncryption"), icon: Lock },
        { id: "devices", label: t("userSettingsDialog.navDevices"), icon: Monitor },
        { id: "qr_sign_in", label: t("userSettingsDialog.navScanSignInQr"), icon: ScanLine },
      ],
    },
    {
      title: t("userSettingsDialog.navGroupAppearance"),
      items: [
        { id: "themes", label: t("userSettingsDialog.navTheme"), icon: Palette },
        { id: "profile_themes", label: t("userSettingsDialog.navProfileTheme"), icon: Paintbrush },
        { id: "appearance", label: t("userSettingsDialog.navAppearance"), icon: Paintbrush },
        { id: "accessibility", label: t("userSettingsDialog.navAccessibility"), icon: Accessibility },
        { id: "plugins", label: t("userSettingsDialog.navPlugins"), icon: Puzzle },
      ],
    },
    {
      title: t("userSettingsDialog.navGroupApp"),
      items: [
        { id: "sharing", label: t("userSettingsDialog.navCameraMic"), icon: Video },
        { id: "system", label: t("userSettingsDialog.navSystem"), icon: Monitor },
        { id: "download", label: t("userSettingsDialog.navDownloadApp"), icon: Download },
        { id: "about", label: t("userSettingsDialog.navAbout"), icon: Info },
      ],
    },
  ];
}

function sectionTitle(section: SettingsSection): string {
  const titles: Record<SettingsSection, string> = {
    profile: t("userSettingsDialog.sectionTitleProfile"),
    connections: t("userSettingsDialog.sectionTitleConnections"),
    activity: t("userSettingsDialog.sectionTitleActivity"),
    privacy: t("userSettingsDialog.sectionTitlePrivacy"),
    sharing: t("userSettingsDialog.sectionTitleCameraMicrophone"),
    security: t("userSettingsDialog.sectionTitleSecurity"),
    qr_sign_in: t("userSettingsDialog.sectionTitleScanSignInQr"),
    devices: t("userSettingsDialog.sectionTitleDevices"),
    encryption: t("userSettingsDialog.sectionTitleEncryption"),
    plugins: t("userSettingsDialog.sectionTitlePlugins"),
    themes: t("userSettingsDialog.sectionTitleTheme"),
    profile_themes: t("userSettingsDialog.sectionTitleProfileTheme"),
    accessibility: t("userSettingsDialog.sectionTitleAccessibility"),
    appearance: t("userSettingsDialog.sectionTitleAppearance"),
    system: t("userSettingsDialog.sectionTitleSystem"),
    download: t("userSettingsDialog.sectionTitleDownloadApp"),
    about: t("userSettingsDialog.sectionTitleAbout"),
  };
  return titles[section];
}

function SectionBody({
  section,
  onNavigate,
}: {
  section: SettingsSection;
  onNavigate: (to: SettingsSection) => void;
}) {
  switch (section) {
    case "profile":
      return <ProfileTab />;
    case "connections":
      return <ConnectionsTab />;
    case "privacy":
      return <PrivacyTab />;
    case "activity":
      return <ActivityTab />;
    case "sharing":
      return <SharingTab />;
    case "security":
      return <SecurityTab />;
    case "qr_sign_in":
      return <QrSignInTab />;
    case "devices":
      return <DevicesTab />;
    case "encryption":
      return <EncryptionTab />;
    case "plugins":
      return <PluginsTab />;
    case "themes":
      return <MarketplaceThemes />;
    case "profile_themes":
      return <ProfileThemesTab />;
    case "accessibility":
      return <AccessibilityTab />;
    case "appearance":
      return <AppearanceTab onNavigate={onNavigate} />;
    case "system":
      return <SystemTab />;
    case "download":
      return <DownloadTab />;
    case "about":
      return <AboutTab />;
  }
}

export function UserSettingsDialog({
  open,
  onOpenChange,
  initialSection,
}: UserSettingsDialogProps) {
  const [section, setSection] = useState<SettingsSection>(initialSection ?? "profile");
  const logoutMutation = useMutation({ mutationFn: logout });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogFullScreenContent title={sectionTitle(section)}>
        <div className="flex min-h-0 flex-1 flex-col md:flex-row">
          <nav
            aria-label={t("userSettingsDialog.settingsSections")}
            className="flex shrink-0 gap-1 overflow-x-auto border-b border-border bg-surface-0/40 p-2 md:w-60 md:flex-col md:overflow-y-auto md:border-b-0 md:border-r md:p-3"
          >
            {navGroups().map((group) => (
              <div key={group.title} className="shrink-0 md:shrink">
                <p
                  aria-hidden
                  className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-ink-muted first:pt-0"
                >
                  {group.title}
                </p>
                {group.items.map(({ id, label, icon: Icon }) => (
                  <button
                    key={id}
                    type="button"
                    aria-current={section === id}
                    onClick={() => setSection(id)}
                    className={cn(
                      "flex w-full shrink-0 items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
                      "md:w-full",
                      section === id
                        ? "bg-primary-soft text-primary"
                        : "text-ink-secondary hover:bg-surface-3 hover:text-ink",
                    )}
                  >
                    <Icon aria-hidden className="size-4 shrink-0" />
                    {label}
                  </button>
                ))}
              </div>
            ))}
            <div className="mt-auto hidden border-t border-border pt-1 md:block">
              <button
                type="button"
                onClick={() => logoutMutation.mutate()}
                disabled={logoutMutation.isPending}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium text-danger transition-colors hover:bg-danger/10"
              >
                <LogOut aria-hidden className="size-4 shrink-0" />
                {t("userSettingsDialog.signOut")}
              </button>
            </div>
          </nav>

          <div className="flex min-h-0 min-w-0 flex-1 flex-col">
            <header className="flex shrink-0 items-center justify-between gap-4 border-b border-border px-4 py-3 md:px-8">
              <h1 className="truncate text-lg font-semibold">{sectionTitle(section)}</h1>
              <DialogClose
                aria-label={t("userSettingsDialog.closeSettings")}
                className="flex shrink-0 items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink"
              >
                <X aria-hidden className="size-4" />
                <kbd className="hidden text-xs font-medium md:inline">Esc</kbd>
              </DialogClose>
            </header>

            <div className="min-h-0 flex-1 overflow-y-auto px-4 py-5 md:px-8">
              <div className="mx-auto max-w-2xl">
                <SectionBody section={section} onNavigate={setSection} />
                <div className="mt-6 border-t border-border pt-4 md:hidden">
                  <Button
                    variant="ghost"
                    className="w-full justify-start text-danger hover:text-danger"
                    loading={logoutMutation.isPending}
                    onClick={() => logoutMutation.mutate()}
                  >
                    <LogOut aria-hidden className="size-4" />
                    {t("userSettingsDialog.signOut")}
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </DialogFullScreenContent>
    </Dialog>
  );
}
