import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Accessibility,
  Download,
  Info,
  Link2,
  LogOut,
  Moon,
  Monitor,
  Paintbrush,
  ShieldCheck,
  Sliders,
  Sun,
  Upload,
  User as UserIcon,
  Video,
  X,
} from "lucide-react";
import { BADGES, BADGE_ORDER, type PresenceStatus } from "@orangchat/shared";
import { STATUS_LABEL } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import {
  Dialog,
  DialogClose,
  DialogFullScreenContent,
} from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { getTheme, setTheme, type Theme } from "../../lib/theme";
import { socket } from "../../lib/socket";
import defaultCss from "../../styles/index.css?raw";
import { getMyConnections } from "../connections/api";
import { ProfileCard } from "../profile/ProfileCard";
import { BADGE_ICON } from "../profile/ProfileBadges";
import { uploadImage, type UploadKind } from "../uploads/api";
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
 * and can't escape the card box. Target these hook classes:
 *
 *   .oc-profile-card   the whole card
 *   .oc-pf-banner      the top banner strip
 *   .oc-pf-avatar      the avatar holder
 *   .oc-pf-body        the info panel
 *   .oc-pf-name        display name
 *   .oc-pf-username    @username
 *   .oc-pf-pronouns    pronouns
 *   .oc-pf-bio         the About-me block
 *   .oc-pf-member      the Member-since block
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

/** Pick an image file → server compresses & stores it → returns its URL. */
function ImageUploadButton({
  kind,
  label,
  onUploaded,
}: {
  kind: UploadKind;
  label: string;
  onUploaded: (url: string) => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFile = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    setBusy(true);
    try {
      onUploaded(await uploadImage(file, kind));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setBusy(false);
      if (ref.current) ref.current.value = "";
    }
  };

  return (
    <div className="mt-1.5">
      <input
        ref={ref}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="hidden"
        onChange={(e) => void onFile(e.target.files?.[0] ?? undefined)}
      />
      <Button
        type="button"
        variant="secondary"
        size="sm"
        loading={busy}
        onClick={() => ref.current?.click()}
      >
        <Upload aria-hidden className="size-4" />
        {label}
      </Button>
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
    </div>
  );
}

/**
 * Upload-only image field: shows a preview of the current image with Upload and
 * Remove actions. No URL text box - images come from the uploader, which returns
 * a server path (e.g. `/uploads/x.gif`), never hand-typed.
 */
function ImageField({
  label,
  kind,
  value,
  onChange,
  hint,
  rounded = "full",
}: {
  label: string;
  kind: UploadKind;
  value: string;
  onChange: (url: string) => void;
  hint?: string;
  rounded?: "full" | "md";
}) {
  return (
    <div>
      <label className="mb-1.5 block text-sm font-medium text-ink-secondary">
        {label}
      </label>
      <div className="flex items-center gap-3">
        {value ? (
          <img
            src={value}
            alt=""
            className={cn(
              "size-14 shrink-0 border border-border object-cover",
              rounded === "full" ? "rounded-full" : "rounded-lg",
            )}
          />
        ) : (
          <span
            aria-hidden
            className={cn(
              "flex size-14 shrink-0 items-center justify-center border border-dashed border-border bg-surface-1 text-ink-muted",
              rounded === "full" ? "rounded-full" : "rounded-lg",
            )}
          >
            <Upload className="size-5" />
          </span>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <ImageUploadButton kind={kind} label="Upload" onUploaded={onChange} />
          {value && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => onChange("")}
            >
              Remove
            </Button>
          )}
        </div>
      </div>
      {hint && <p className="mt-1.5 text-xs text-ink-muted">{hint}</p>}
    </div>
  );
}

/**
 * The full badge catalog, with the ones you hold lit up and the rest dimmed.
 * Badges are awarded server-side and can't be changed from here, so this is
 * read-only - it exists to explain what each badge is and how it's earned.
 */
function BadgesSection({ badges }: { badges: readonly string[] }) {
  const owned = new Set(badges);

  return (
    <div className="space-y-2">
      <SectionTitle>Badges</SectionTitle>
      <div className="space-y-1.5">
        {BADGE_ORDER.map((id) => {
          const badge = BADGES[id];
          const Icon = BADGE_ICON[id];
          const has = owned.has(id);
          const color = `#${badge.color.toString(16).padStart(6, "0")}`;
          return (
            <div
              key={id}
              className={cn(
                "flex items-center gap-3 rounded-lg border border-border px-3 py-2",
                !has && "opacity-50",
              )}
            >
              <Icon
                aria-hidden
                className="size-5 shrink-0"
                style={{ color: has ? color : undefined }}
              />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{badge.label}</p>
                <p className="text-xs text-ink-muted">{badge.description}</p>
              </div>
              {has && (
                <span className="shrink-0 text-xs font-medium text-success">Earned</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** Everything about you: identity, status, info - with a live preview. */
function ProfileTab() {
  const user = useAuthStore((s) => s.user);
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [username, setUsername] = useState(user?.username ?? "");
  const [avatarUrl, setAvatarUrl] = useState(user?.avatarUrl ?? "");
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

  const mutation = useMutation({
    mutationFn: () =>
      updateProfile({
        displayName: displayName.trim() || undefined,
        username: username.trim() || undefined,
        avatarUrl: avatarUrl.trim() ? avatarUrl.trim() : null,
        pronouns: pronouns.trim() ? pronouns.trim() : null,
        bio: bio.trim() ? bio.trim() : null,
        bannerUrl: bannerUrl.trim() ? bannerUrl.trim() : null,
        accentColor,
        profileCss: profileCss.length ? profileCss : null,
      }),
    onSuccess: (updated) => {
      authStoreActions.setUser(updated);
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
            avatarUrl: avatarUrl.trim() || null,
            bannerUrl: bannerUrl.trim() || null,
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
          onChange={setAvatarUrl}
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
          onChange={setBannerUrl}
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

      <BadgesSection badges={user.badges} />

      <div className="space-y-2">
        <SectionTitle>Profile theme (CSS)</SectionTitle>
        <p className="text-xs text-ink-muted">
          Style your profile card however you like - everyone sees it. It's sandboxed:
          scoped to your card only, no external URLs, and it can't cover or escape the
          card. The preview above updates live.
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

/** Upload/override CSS to theme the app for yourself. */
function CustomCssSection() {
  const user = useAuthStore((s) => s.user);
  const fileRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (css: string | null) => updateProfile({ customCss: css }),
    onSuccess: (updated) => authStoreActions.setUser(updated),
  });

  const onFile = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    if (file.size > 100_000) {
      setError("CSS file is too large (max 100 KB).");
      return;
    }
    mutation.mutate(await file.text());
  };

  const hasCss = Boolean(user?.customCss);

  return (
    <div className="space-y-3">
      <SectionTitle>Custom CSS theme</SectionTitle>
      <p className="text-xs text-ink-muted">
        Upload a .css file to restyle the app for yourself. Download the default
        stylesheet to see the design tokens (the --oc-* variables) you can override.
      </p>
      <input
        ref={fileRef}
        type="file"
        accept=".css,text/css"
        className="hidden"
        onChange={(e) => void onFile(e.target.files?.[0] ?? undefined)}
      />
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => fileRef.current?.click()}>
          <Upload aria-hidden className="size-4" />
          Upload CSS
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
      {hasCss && <p className="text-xs text-success">Custom CSS is active.</p>}
      {error && <p className="text-xs text-danger">{error}</p>}
      {mutation.isError && <p className="text-xs text-danger">{mutation.error.message}</p>}
    </div>
  );
}

/** App look & feel: theme + custom CSS. */
function AppearanceTab() {
  const [theme, setThemeState] = useState<Theme>(getTheme);

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
      <div className="border-t border-border pt-5">
        <CustomCssSection />
      </div>
    </div>
  );
}

type SettingsSection =
  | "profile"
  | "connections"
  | "privacy"
  | "sharing"
  | "security"
  | "devices"
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
  { id: "devices", label: "Devices", icon: Monitor },
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
  devices: "Devices",
  accessibility: "Accessibility",
  appearance: "Appearance",
  system: "System",
  download: "Download app",
  about: "About",
};

function SectionBody({ section }: { section: SettingsSection }) {
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
    case "devices":
      return <DevicesTab />;
    case "accessibility":
      return <AccessibilityTab />;
    case "appearance":
      return <AppearanceTab />;
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

          <div className="flex min-w-0 flex-1 flex-col">
            <header className="flex shrink-0 items-center justify-between gap-4 border-b border-border px-4 py-3 md:px-8">
              <h1 className="truncate text-lg font-semibold">
                {SECTION_TITLE[section]}
              </h1>
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
                <SectionBody section={section} />
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
