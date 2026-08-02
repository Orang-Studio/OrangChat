import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Accessibility,
  Download,
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
import { type PresenceStatus } from "@orangchat/shared";
import { STATUS_LABEL } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { ImageField } from "../../components/ImageField";
import { Dialog, DialogClose, DialogFullScreenContent } from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { getTheme, setTheme, type Theme } from "../../lib/theme";
import { useInstalledTheme } from "../plugins/themes";
import { socket } from "../../lib/socket";
import defaultCss from "../../styles/index.css?raw";
import { getMyConnections } from "../connections/api";
import { ProfileCard } from "../profile/ProfileCard";
import { useAuthStore, authStoreActions } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { logout } from "../auth/session";
import { SectionTitle } from "./controls";
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
  /** Section to land on. Only read at first mount. */
  initialSection?: SettingsSection;
}

const STATUS_OPTIONS: PresenceStatus[] = ["online", "idle", "dnd"];
const STATUS_DOT: Record<PresenceStatus, string> = {
  online: "bg-success",
  idle: "bg-warning",
  dnd: "bg-danger",
  offline: "bg-ink-muted",
};

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
 *   .oc-pf-section          every divided block (bio / connections / member)
 *   .oc-pf-heading          the small uppercase heading in those blocks
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
 * ACTIVITY (Spotify / games)
 *   .oc-pf-activity         the whole line ([data-kind="spotify"|…])
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
  /* data: URLs are allowed; external url() is stripped */
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

/**
 * Pick an image file → server compresses & stores it → returns its URL, plus a
 * `blob:` url of the bytes just picked. The stored url may point at Cloudinary,
 * which `img-src 'self'` blocks; it only becomes same-origin once it is saved
 * and served back through /api/media/asset, so the local blob is what the
 * preview can actually render in between.
 */
/** Everything about you: identity, status, info - with a live preview. */
function ProfileTab() {
  const user = useAuthStore((s) => s.user);
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [username, setUsername] = useState(user?.username ?? "");
  const [avatarUrl, setAvatarUrl] = useState(user?.avatarUrl ?? "");
  // Blob previews of just-uploaded images; see ImageUploadButton.
  const [avatarPreview, setAvatarPreview] = useState("");
  const [bannerPreview, setBannerPreview] = useState("");
  const [pronouns, setPronouns] = useState(user?.pronouns ?? "");
  const [bio, setBio] = useState(user?.bio ?? "");
  const [bannerUrl, setBannerUrl] = useState(user?.bannerUrl ?? "");
  const [accentColor, setAccentColor] = useState<number | null>(user?.accentColor ?? null);
  const [profileCss, setProfileCss] = useState(user?.profileCss ?? "");
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
      }),
    onSuccess: (updated) => {
      authStoreActions.setUser(updated);
      // Saved: the server now serves these same-origin, so drop the blobs.
      setAvatarPreview("");
      setBannerPreview("");
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    },
  });

  const pickStatus = (next: PresenceStatus) => {
    setStatus(next);
    socket.emit("presence:update", next);
  };

  if (!user) return null;

  const dirty =
    displayName.trim() !== user.displayName ||
    username.trim() !== user.username ||
    (avatarUrl.trim() || null) !== user.avatarUrl ||
    (pronouns.trim() || null) !== user.pronouns ||
    (bio.trim() || null) !== user.bio ||
    (bannerUrl.trim() || null) !== user.bannerUrl ||
    accentColor !== user.accentColor ||
    (profileCss.length ? profileCss : null) !== user.profileCss;

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (dirty) mutation.mutate();
      }}
      className="space-y-5"
    >
      <div>
        <SectionTitle>Preview</SectionTitle>
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
            connections: visibleConnections,
          }}
        />
      </div>

      <div>
        <SectionTitle>Status</SectionTitle>
        <div className="flex gap-2">
          {STATUS_OPTIONS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={status === option}
              onClick={() => pickStatus(option)}
              className={cn(
                "flex flex-1 items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm transition-colors",
                status === option
                  ? "border-primary bg-primary-soft"
                  : "border-border hover:border-border-strong",
              )}
            >
              <span className={cn("size-2.5 rounded-full", STATUS_DOT[option])} />
              {STATUS_LABEL[option]}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-4">
        <SectionTitle>Identity</SectionTitle>
        <TextField
          label="Display name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          maxLength={64}
        />
        <TextField
          label="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          maxLength={32}
          hint="Lowercase letters, numbers, underscores, and dots."
        />
        <ImageField
          label="Avatar"
          kind="avatar"
          value={avatarUrl}
          preview={avatarPreview}
          onChange={(url, preview) => {
            setAvatarUrl(url);
            setAvatarPreview(preview);
          }}
          hint="PNG, JPEG, WebP, or animated GIF."
        />
      </div>

      <div className="space-y-4">
        <SectionTitle>About</SectionTitle>
        <TextField
          label="Pronouns"
          value={pronouns}
          onChange={(e) => setPronouns(e.target.value)}
          maxLength={40}
          placeholder="they/them"
        />
        <div>
          <label className="mb-1 block text-sm font-medium text-ink-secondary">About me</label>
          <textarea
            value={bio}
            onChange={(e) => setBio(e.target.value)}
            maxLength={4000}
            rows={4}
            placeholder="Tell people about yourself…"
            className="w-full resize-none rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm"
          />
        </div>
        <ImageField
          label="Banner"
          kind="banner"
          value={bannerUrl}
          preview={bannerPreview}
          onChange={(url, preview) => {
            setBannerUrl(url);
            setBannerPreview(preview);
          }}
          rounded="md"
          hint="Leave empty to use your accent color."
        />
        <div>
          <label className="mb-1 block text-sm font-medium text-ink-secondary">Accent color</label>
          <div className="flex items-center gap-3">
            <input
              type="color"
              aria-label="Accent color"
              value={intToHex(accentColor)}
              onChange={(e) => setAccentColor(hexToInt(e.target.value))}
              className="h-9 w-14 cursor-pointer rounded-md border border-border bg-surface-1"
            />
            {accentColor != null && (
              <button
                type="button"
                onClick={() => setAccentColor(null)}
                className="text-sm text-ink-muted transition-colors hover:text-ink"
              >
                Clear
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="space-y-2">
        <SectionTitle>Profile theme (CSS)</SectionTitle>
        <p className="text-xs text-ink-muted">
          Style your profile card however you like - everyone sees it. It's sandboxed: scoped to
          your card only, no external URLs, and it can't cover or escape the card. The preview above
          updates live.
        </p>
        <textarea
          value={profileCss}
          onChange={(e) => setProfileCss(e.target.value)}
          maxLength={100_000}
          rows={6}
          spellCheck={false}
          placeholder=".oc-pf-body { background: #1a1030; }"
          className="w-full resize-y rounded-lg border border-border bg-surface-1 px-3 py-2 font-mono text-xs"
        />
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => setProfileCss(PROFILE_CSS_TEMPLATE)}
          >
            Load starter
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => downloadText("orangchat-profile-template.css", PROFILE_CSS_TEMPLATE)}
          >
            <Download aria-hidden className="size-4" />
            Download template
          </Button>
          {profileCss.length > 0 && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="text-danger hover:text-danger"
              onClick={() => setProfileCss("")}
            >
              Clear
            </Button>
          )}
        </div>
      </div>

      <p className="text-xs text-ink-muted">
        Signed in as <span className="text-ink-secondary">{user.email}</span>
      </p>

      {mutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {mutation.error.message}
        </p>
      )}
      <Button type="submit" loading={mutation.isPending} disabled={!dirty} className="w-full">
        {saved ? "Saved!" : "Save profile"}
      </Button>
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
      setError("CSS file is too large (max 100 KB).");
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
      <SectionTitle>Custom CSS theme</SectionTitle>
      <p className="text-xs text-ink-muted">
        Restyle the app for yourself - paste CSS below or upload a .css file. It applies the moment
        you save, and it outranks the built-in theme, so overriding a design token (the --oc-*
        variables) is enough to recolour everything. Download the default stylesheet to see the full
        list.
      </p>
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        maxLength={100_000}
        rows={6}
        spellCheck={false}
        aria-label="Custom CSS"
        placeholder=":root { --oc-primary: #22c55e; }"
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
          {dirty ? "Apply CSS" : "Applied"}
        </Button>
        <Button variant="secondary" size="sm" onClick={() => fileRef.current?.click()}>
          <Upload aria-hidden className="size-4" />
          Upload CSS
        </Button>
        <Button variant="secondary" size="sm" onClick={() => setDraft(CUSTOM_CSS_EXAMPLE)}>
          Load example
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => downloadText("orangchat-default.css", defaultCss)}
        >
          <Download aria-hidden className="size-4" />
          Download default
        </Button>
        {hasCss && (
          <Button
            variant="ghost"
            size="sm"
            className="text-danger hover:text-danger"
            loading={mutation.isPending}
            onClick={() => mutation.mutate(null)}
          >
            Remove
          </Button>
        )}
      </div>
      {hasCss && !dirty && <p className="text-xs text-success">Custom CSS is active.</p>}
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
        <SectionTitle>Theme</SectionTitle>
        <div className="flex gap-2">
          {(
            [
              ["dark", Moon, "Dark"],
              ["light", Sun, "Light"],
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
      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>Community themes</SectionTitle>
        <p className="text-xs text-ink-muted">
          Prebuilt colour themes and profile-card themes, reviewed and bundled with the app.
          Installing one recolours everything without writing any CSS.
        </p>
        {installedTheme && (
          <p className="text-xs text-success">“{installedTheme.name}” is installed.</p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={() => onNavigate("themes")}>
            <Palette aria-hidden className="size-4" />
            Browse app themes
          </Button>
          <Button variant="secondary" size="sm" onClick={() => onNavigate("profile_themes")}>
            <Paintbrush aria-hidden className="size-4" />
            Browse profile themes
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
      setError(e instanceof Error ? e.message : "Could not save the icon");
    }
  };

  return (
    <div className="space-y-3">
      <SectionTitle>App icon</SectionTitle>
      <p className="text-xs text-ink-muted">
        Replaces the OrangChat mark for you everywhere you are signed in - browser tab, in-app
        branding, and the desktop window and tray. Nobody else sees it.
      </p>
      <ImageField
        label="Icon"
        kind="app-icon"
        rounded="md"
        value={user?.appIconUrl ?? ""}
        preview={preview}
        onChange={(url, blob) => void save(url, blob)}
        hint="Square images work best. Leave empty for the OrangChat mark."
      />
      {error && <p className="text-xs text-danger">{error}</p>}
    </div>
  );
}

type SettingsSection =
  | "profile"
  | "connections"
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

const NAV: { id: SettingsSection; label: string; icon: typeof UserIcon }[] = [
  { id: "profile", label: "Profile", icon: UserIcon },
  { id: "connections", label: "Connections", icon: Link2 },
  { id: "privacy", label: "Privacy", icon: Sliders },
  { id: "sharing", label: "Camera & Mic", icon: Video },
  { id: "security", label: "Security", icon: ShieldCheck },
  { id: "qr_sign_in", label: "Scan sign-in QR", icon: ScanLine },
  { id: "devices", label: "Devices", icon: Monitor },
  { id: "encryption", label: "Encryption", icon: Lock },
  { id: "plugins", label: "Plugins", icon: Puzzle },
  { id: "themes", label: "Theme", icon: Palette },
  { id: "profile_themes", label: "Profile theme", icon: Paintbrush },
  { id: "accessibility", label: "Accessibility", icon: Accessibility },
  { id: "appearance", label: "Appearance", icon: Paintbrush },
  { id: "system", label: "System", icon: Monitor },
  { id: "download", label: "Download app", icon: Download },
  { id: "about", label: "About", icon: Info },
];

const SECTION_TITLE: Record<SettingsSection, string> = {
  profile: "Profile",
  connections: "Connections",
  privacy: "Privacy",
  sharing: "Camera & Microphone",
  security: "Security",
  qr_sign_in: "Scan sign-in QR",
  devices: "Devices",
  encryption: "Encryption",
  plugins: "Plugins",
  themes: "Theme",
  profile_themes: "Profile theme",
  accessibility: "Accessibility",
  appearance: "Appearance",
  system: "System",
  download: "Download app",
  about: "About",
};

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
      <DialogFullScreenContent title={SECTION_TITLE[section]}>
        <div className="flex min-h-0 flex-1 flex-col md:flex-row">
          <nav
            aria-label="Settings sections"
            className="flex shrink-0 gap-1 overflow-x-auto border-b border-border bg-surface-0/40 p-2 md:w-60 md:flex-col md:overflow-y-auto md:border-b-0 md:border-r md:p-3"
          >
            {NAV.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                type="button"
                aria-current={section === id}
                onClick={() => setSection(id)}
                className={cn(
                  "flex shrink-0 items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
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
            <div className="mt-auto hidden border-t border-border pt-1 md:block">
              <button
                type="button"
                onClick={() => logoutMutation.mutate()}
                disabled={logoutMutation.isPending}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium text-danger transition-colors hover:bg-danger/10"
              >
                <LogOut aria-hidden className="size-4 shrink-0" />
                Sign out
              </button>
            </div>
          </nav>

          <div className="flex min-h-0 min-w-0 flex-1 flex-col">
            <header className="flex shrink-0 items-center justify-between gap-4 border-b border-border px-4 py-3 md:px-8">
              <h1 className="truncate text-lg font-semibold">{SECTION_TITLE[section]}</h1>
              <DialogClose
                aria-label="Close settings"
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
                    Sign out
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
