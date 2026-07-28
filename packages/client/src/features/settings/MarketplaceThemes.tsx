import { useMemo, useState } from "react";
import { Check, Download, Palette, Search } from "lucide-react";
import { THEMES, type Theme } from "@orangchat/marketplace";
import { Button } from "../../components/ui/Button";
import { useInstalledTheme } from "../plugins/themes";

function Swatches({ vars }: { vars: Record<string, string> }) {
  return (
    <div className="flex flex-wrap gap-1">
      {Object.values(vars)
        .slice(0, 10)
        .map((color, index) => (
          <span
            key={`${color}-${index}`}
            className="size-5 rounded border border-border"
            style={{ background: color }}
          />
        ))}
    </div>
  );
}

function ThemeCard({ theme }: { theme: Theme }) {
  const installed = useInstalledTheme((state) => state.installed);
  const install = useInstalledTheme((state) => state.install);
  const uninstall = useInstalledTheme((state) => state.uninstall);
  const active = installed?.id === theme.id;

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
          onClick={() => (active ? uninstall() : install(theme))}
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
      <div className="mt-3">
        <Swatches vars={theme.vars} />
      </div>
    </li>
  );
}

/** PR-reviewed app themes bundled from the marketplace repository. */
export function MarketplaceThemes() {
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return THEMES;
    return THEMES.filter(
      (theme) =>
        theme.name.toLowerCase().includes(normalized) ||
        theme.description?.toLowerCase().includes(normalized) ||
        theme.authors.some((author) => author.toLowerCase().includes(normalized)),
    );
  }, [query]);

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border border-border bg-surface-1 px-3 py-2.5">
        <Palette aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-secondary" />
        <p className="text-xs text-ink-secondary">
          App themes override OrangChat color variables. Every entry is reviewed through a pull
          request and bundled with the client. Want to contribute?{" "}
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
          aria-label="Search themes"
          placeholder="Search themes"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          className="h-10 w-full rounded-lg border border-border bg-surface-1 pl-9 pr-3 text-sm outline-none focus:border-primary"
        />
      </div>

      {filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">No themes match “{query}”.</p>
      ) : (
        <ul className="space-y-2">
          {filtered.map((theme) => (
            <ThemeCard key={theme.id} theme={theme} />
          ))}
        </ul>
      )}
    </div>
  );
}
