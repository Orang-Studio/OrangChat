import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Check, Download, Search, UserRound } from "lucide-react";
import { PROFILE_THEMES, type ProfileTheme } from "@orangchat/marketplace";
import { Button } from "../../components/ui/Button";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { getMyConnections } from "../connections/api";
import { ProfileCard, type ProfileCardData } from "../profile/ProfileCard";

function ProfileThemeCard({
  theme,
  preview,
}: {
  theme: ProfileTheme;
  /** The viewer's own card data; this theme's CSS is layered on top of it. */
  preview: ProfileCardData;
}) {
  const user = useAuthStore((state) => state.user);
  const active = user?.profileCss === theme.css;
  const mutation = useMutation({
    mutationFn: () => updateProfile({ profileCss: active ? null : theme.css }),
    onSuccess: (updated) => authStoreActions.setUser(updated),
  });

  return (
    <li className="rounded-lg border border-border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium">{theme.name}</p>
          <p className="text-xs text-ink-muted">by {theme.authors.join(", ")}</p>
        </div>
        <Button
          type="button"
          size="sm"
          variant={active ? "secondary" : "primary"}
          loading={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {active ? (
            <>
              <Check aria-hidden className="size-4" />
              Installed
            </>
          ) : (
            <>
              <Download aria-hidden className="size-4" />
              Install
            </>
          )}
        </Button>
      </div>
      {theme.description && <p className="mt-2 text-sm text-ink-secondary">{theme.description}</p>}
      {/* The card is the preview: a wall of CSS told nobody what they were
          about to install. Same component the profile popup renders, so what
          shows here is exactly what other people will see. */}
      <div className="mt-3">
        <ProfileCard data={{ ...preview, profileCss: theme.css }} />
      </div>
    </li>
  );
}

/** PR-reviewed profile-card CSS bundled from the marketplace repository. */
export function ProfileThemesTab() {
  const user = useAuthStore((state) => state.user);
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return PROFILE_THEMES;
    return PROFILE_THEMES.filter(
      (theme) =>
        theme.name.toLowerCase().includes(normalized) ||
        theme.description?.toLowerCase().includes(normalized) ||
        theme.authors.some((author) => author.toLowerCase().includes(normalized)),
    );
  }, [query]);

  // Preview on the viewer's real card, hidden connections excluded - the same
  // filtering the profile popup does.
  const { data: connections } = useQuery({
    queryKey: ["connections", "mine"],
    queryFn: getMyConnections,
  });

  const preview = useMemo<ProfileCardData>(
    () => ({
      displayName: user?.displayName ?? "Your name",
      username: user?.username ?? "username",
      avatarUrl: user?.avatarUrl ?? null,
      bannerUrl: user?.bannerUrl ?? null,
      accentColor: user?.accentColor ?? null,
      pronouns: user?.pronouns ?? null,
      bio: user?.bio ?? "This is how your About me reads with this theme applied.",
      status: user?.status,
      createdAt: user?.createdAt,
      badges: user?.badges,
      connections: connections?.filter((c) => c.visible) ?? [],
    }),
    [user, connections],
  );

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border border-border bg-surface-1 px-3 py-2.5">
        <UserRound aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-secondary" />
        <p className="text-xs text-ink-secondary">
          Profile themes style your public profile card - each preview below is your own card with
          that theme applied. Every entry is reviewed through a pull request, bundled with the
          client, and sanitized before it renders. Want to contribute?{" "}
          <a
            href="https://github.com/Orang-Studio/orangchat-marketplace"
            target="_blank"
            rel="noreferrer"
            className="oc-link"
          >
            See the marketplace repo
          </a>
          .
        </p>
      </div>

      <div className="relative">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-muted"
        />
        <input
          aria-label="Search profile themes"
          placeholder="Search profile themes"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          className="h-10 w-full rounded-lg border border-border bg-surface-1 pl-9 pr-3 text-sm outline-none focus:border-primary"
        />
      </div>

      {filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">
          No profile themes match “{query}”.
        </p>
      ) : (
        <ul className="space-y-2">
          {filtered.map((theme) => (
            <ProfileThemeCard key={theme.id} theme={theme} preview={preview} />
          ))}
        </ul>
      )}
    </div>
  );
}
