import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Download, Trash2 } from "lucide-react";
import type { MarketplaceTheme } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { useAuthStore } from "../../stores/auth";
import {
  createTheme,
  deleteTheme,
  installTheme,
  listMyThemes,
  listThemes,
  updateTheme,
  useInstalledTheme,
} from "../plugins/themes";

/** The variables a theme may set, grouped for the editor. Mirrors the server allow-list. */
const THEME_VARS: { key: string; label: string }[] = [
  { key: "--oc-primary", label: "Accent" },
  { key: "--oc-primary-hover", label: "Accent (hover)" },
  { key: "--oc-primary-active", label: "Accent (active)" },
  { key: "--oc-surface-0", label: "Background" },
  { key: "--oc-surface-1", label: "Surface 1" },
  { key: "--oc-surface-2", label: "Surface 2" },
  { key: "--oc-surface-3", label: "Surface 3" },
  { key: "--oc-ink", label: "Text" },
  { key: "--oc-ink-secondary", label: "Text (secondary)" },
  { key: "--oc-ink-muted", label: "Text (muted)" },
  { key: "--oc-border", label: "Border" },
  { key: "--oc-success", label: "Success" },
  { key: "--oc-warning", label: "Warning" },
  { key: "--oc-danger", label: "Danger" },
];

/** Current computed value of a CSS variable, as a hex fallback for the pickers. */
function currentVar(key: string): string {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(key).trim();
  return /^#[0-9a-fA-F]{3,8}$/.test(raw) ? raw : "#000000";
}

function Swatches({ vars }: { vars: Record<string, string> }) {
  const colors = Object.values(vars).slice(0, 10);
  return (
    <div className="flex flex-wrap gap-1">
      {colors.map((c, i) => (
        <span
          key={i}
          className="size-5 rounded border border-border"
          style={{ background: c }}
        />
      ))}
    </div>
  );
}

function MarketplaceCard({ theme }: { theme: MarketplaceTheme }) {
  const installed = useInstalledTheme((s) => s.installed);
  const install = useInstalledTheme((s) => s.install);
  const uninstall = useInstalledTheme((s) => s.uninstall);
  const isOn = installed?.id === theme.id;

  const mutation = useMutation({
    mutationFn: () => installTheme(theme.id),
    onSuccess: (full) => install(full),
  });

  return (
    <li className="rounded-lg border border-border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium">{theme.name}</p>
          <p className="text-xs text-ink-muted">
            by {theme.authorName ?? "unknown"} · {theme.installs} install
            {theme.installs === 1 ? "" : "s"}
          </p>
        </div>
        {isOn ? (
          <Button type="button" size="sm" variant="secondary" onClick={() => uninstall()}>
            <Check aria-hidden className="size-4" />
            Installed
          </Button>
        ) : (
          <Button
            type="button"
            size="sm"
            loading={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            <Download aria-hidden className="size-4" />
            Install
          </Button>
        )}
      </div>
      <div className="mt-2">
        <Swatches vars={theme.vars} />
      </div>
    </li>
  );
}

/** Build-and-submit form. Seeds from the current theme's colours. */
function ThemeCreator({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState("");
  const [vars, setVars] = useState<Record<string, string>>(() =>
    Object.fromEntries(THEME_VARS.map((v) => [v.key, currentVar(v.key)])),
  );

  const create = useMutation({
    mutationFn: (submit: boolean) => createTheme(name.trim(), vars, submit),
    onSuccess: onCreated,
  });

  return (
    <form
      className="space-y-3 rounded-lg border border-border p-3"
      onSubmit={(e) => e.preventDefault()}
    >
      <input
        aria-label="Theme name"
        placeholder="Theme name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={60}
        className="h-10 w-full rounded-lg border border-border bg-surface-1 px-3 text-sm outline-none focus:border-primary"
      />
      <div className="grid grid-cols-2 gap-2">
        {THEME_VARS.map((v) => (
          <label key={v.key} className="flex items-center justify-between gap-2 text-xs">
            <span className="truncate text-ink-secondary">{v.label}</span>
            <input
              type="color"
              value={vars[v.key]}
              onChange={(e) => setVars((prev) => ({ ...prev, [v.key]: e.target.value }))}
              className="size-6 shrink-0 cursor-pointer rounded border border-border bg-transparent"
            />
          </label>
        ))}
      </div>
      {create.isError && <p className="text-sm text-danger">{create.error.message}</p>}
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          variant="secondary"
          loading={create.isPending}
          disabled={!name.trim()}
          onClick={() => create.mutate(false)}
        >
          Save private
        </Button>
        <Button
          type="button"
          size="sm"
          loading={create.isPending}
          disabled={!name.trim()}
          onClick={() => create.mutate(true)}
        >
          Submit to marketplace
        </Button>
      </div>
      <p className="text-xs text-ink-muted">
        Submitting sends it for review. It appears in the marketplace only once
        approved.
      </p>
    </form>
  );
}

function MyThemeRow({ theme, onChanged }: { theme: MarketplaceTheme; onChanged: () => void }) {
  const install = useInstalledTheme((s) => s.install);

  const submit = useMutation({
    mutationFn: (submitted: boolean) => updateTheme(theme.id, { submitted }),
    onSuccess: onChanged,
  });
  const remove = useMutation({
    mutationFn: () => deleteTheme(theme.id),
    onSuccess: onChanged,
  });

  const status = theme.published
    ? "Published"
    : theme.submitted
      ? "In review"
      : "Private";

  return (
    <li className="flex items-center justify-between gap-3 rounded-lg border border-border p-3">
      <div className="min-w-0">
        <p className="truncate text-sm font-medium">{theme.name}</p>
        <p className="text-xs text-ink-muted">{status}</p>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        <Button type="button" size="sm" variant="ghost" onClick={() => install(theme)}>
          Preview
        </Button>
        {!theme.published &&
          (theme.submitted ? (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              loading={submit.isPending}
              onClick={() => submit.mutate(false)}
            >
              Withdraw
            </Button>
          ) : (
            <Button
              type="button"
              size="sm"
              variant="secondary"
              loading={submit.isPending}
              onClick={() => submit.mutate(true)}
            >
              Submit
            </Button>
          ))}
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="text-danger hover:text-danger"
          loading={remove.isPending}
          onClick={() => remove.mutate()}
        >
          <Trash2 aria-hidden className="size-4" />
        </Button>
      </div>
    </li>
  );
}

/**
 * The community theme marketplace: browse and install published colour themes,
 * or build your own and submit it for review. A theme is only colour-variable
 * overrides - validated server-side - so installing one can never run code.
 */
export function MarketplaceThemes() {
  const queryClient = useQueryClient();
  const signedIn = useAuthStore((s) => Boolean(s.user));
  const [creating, setCreating] = useState(false);

  const installed = useInstalledTheme((s) => s.installed);
  const uninstall = useInstalledTheme((s) => s.uninstall);

  const market = useQuery({ queryKey: ["themes"], queryFn: listThemes, enabled: signedIn });
  const mine = useQuery({ queryKey: ["themes", "mine"], queryFn: listMyThemes, enabled: signedIn });

  const refreshMine = () => {
    void queryClient.invalidateQueries({ queryKey: ["themes"] });
  };

  const themes = useMemo(() => market.data?.themes ?? [], [market.data]);

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-semibold">Theme marketplace</p>
        <p className="text-xs text-ink-secondary">
          Community colour themes. Installing one recolours the app on this
          device - themes carry colours only, never code.
        </p>
      </div>

      {installed && (
        <div className="flex items-center justify-between gap-3 rounded-lg bg-primary-soft px-3 py-2">
          <p className="text-sm text-primary">
            Using <span className="font-medium">{installed.name}</span>
          </p>
          <Button type="button" size="sm" variant="ghost" onClick={() => uninstall()}>
            Reset
          </Button>
        </div>
      )}

      {market.isPending ? (
        <div className="h-20 animate-pulse rounded-lg bg-surface-3" />
      ) : themes.length === 0 ? (
        <p className="py-4 text-center text-sm text-ink-muted">
          No published themes yet. Be the first to submit one.
        </p>
      ) : (
        <ul className="space-y-2">
          {themes.map((theme) => (
            <MarketplaceCard key={theme.id} theme={theme} />
          ))}
        </ul>
      )}

      <div className="border-t border-border pt-4">
        <div className="flex items-center justify-between">
          <p className="text-sm font-semibold">Your themes</p>
          <Button
            type="button"
            size="sm"
            variant={creating ? "ghost" : "secondary"}
            onClick={() => setCreating((c) => !c)}
          >
            {creating ? "Cancel" : "Create theme"}
          </Button>
        </div>

        {creating && (
          <div className="mt-3">
            <ThemeCreator
              onCreated={() => {
                setCreating(false);
                refreshMine();
              }}
            />
          </div>
        )}

        {(mine.data?.themes.length ?? 0) > 0 && (
          <ul className="mt-3 space-y-2">
            {mine.data?.themes.map((theme) => (
              <MyThemeRow key={theme.id} theme={theme} onChanged={refreshMine} />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
