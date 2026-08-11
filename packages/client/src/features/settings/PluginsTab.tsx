import { useMemo, useState } from "react";
import { Puzzle, Search } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { PLUGINS } from "../plugins/registry";
import { usePlugins } from "../plugins/store";
import type { Plugin, PluginSetting } from "../plugins/types";
import { t, tNodes } from "../../lib/i18n";

function SettingControl({ plugin, setting }: { plugin: Plugin; setting: PluginSetting }) {
  const value = usePlugins((s) => s.getSetting(plugin.id, setting.key));
  const setSetting = usePlugins((s) => s.setSetting);

  const label = <label className="text-xs font-medium text-ink-secondary">{setting.label}</label>;

  if (setting.type === "boolean") {
    return (
      <div className="flex items-center justify-between gap-3">
        {label}
        <input
          type="checkbox"
          checked={Boolean(value)}
          onChange={(e) => setSetting(plugin.id, setting.key, e.target.checked)}
          className="size-4 accent-primary"
        />
      </div>
    );
  }

  if (setting.type === "color") {
    return (
      <div className="flex items-center justify-between gap-3">
        {label}
        <input
          type="color"
          value={String(value ?? setting.default)}
          onChange={(e) => setSetting(plugin.id, setting.key, e.target.value)}
          className="size-7 cursor-pointer rounded border border-border bg-transparent"
        />
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between gap-3">
      {label}
      <select
        value={String(value ?? setting.default)}
        onChange={(e) => setSetting(plugin.id, setting.key, e.target.value)}
        className="rounded-lg border border-border bg-surface-1 px-2 py-1 text-sm outline-none focus:border-primary"
      >
        {setting.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}

function PluginCard({ plugin }: { plugin: Plugin }) {
  const enabled = usePlugins((s) => s.isEnabled(plugin.id));
  const setEnabled = usePlugins((s) => s.setEnabled);

  return (
    <li className="rounded-lg border border-border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium">{plugin.name}</p>
          <p className="text-xs text-ink-muted">by {plugin.authors.join(", ")}</p>
        </div>
        <Button
          type="button"
          size="sm"
          variant={enabled ? "primary" : "secondary"}
          onClick={() => setEnabled(plugin.id, !enabled)}
        >
          {enabled ? t("pluginsTab.enabled") : t("pluginsTab.enable")}
        </Button>
      </div>
      <p className="mt-2 text-sm text-ink-secondary">{plugin.description}</p>

      {/* Settings only matter once the plugin runs, so they appear with it. */}
      {enabled && plugin.settings && plugin.settings.length > 0 && (
        <div className="mt-3 space-y-2 border-t border-border pt-3">
          {plugin.settings.map((setting) => (
            <SettingControl key={setting.key} plugin={plugin} setting={setting} />
          ))}
        </div>
      )}
    </li>
  );
}

/**
 * The plugin marketplace: the bundled catalog, each toggleable per device.
 * Plugins ship with the build - nothing is fetched or executed from elsewhere -
 * so this is a list of what's available, not a store that installs code.
 */
export function PluginsTab() {
  const [query, setQuery] = useState("");
  const enabledCount = usePlugins((s) => s.enabled.length);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return PLUGINS;
    return PLUGINS.filter(
      (p) => p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q),
    );
  }, [query]);

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border border-border bg-surface-1 px-3 py-2.5">
        <Puzzle aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-secondary" />
        <p className="text-xs text-ink-secondary">
          {tNodes(
            "pluginsTab.intro",
            {
              repo: (
                <a
                  href="https://github.com/Orang-Studio/orangchat-marketplace"
                  target="_blank"
                  rel="noreferrer"
                  className="oc-link"
                >
                  {t("pluginsTab.seeTheMarketplaceRepo")}
                </a>
              ),
            },
            { count: enabledCount },
          )}
        </p>
      </div>

      <div className="relative">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-muted"
        />
        <input
          aria-label={t("pluginsTab.searchPlugins")}
          placeholder={t("pluginsTab.searchPlugins")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="h-10 w-full rounded-lg border border-border bg-surface-1 pl-9 pr-3 text-sm outline-none focus:border-primary"
        />
      </div>

      {filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">
          {t("pluginsTab.noPluginsMatch", { query })}
        </p>
      ) : (
        <ul className="space-y-2">
          {filtered.map((plugin) => (
            <PluginCard key={plugin.id} plugin={plugin} />
          ))}
        </ul>
      )}
    </div>
  );
}
