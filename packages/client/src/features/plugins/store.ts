import { useMemo } from "react";
import { create } from "zustand";
import { PLUGINS, pluginById } from "./registry";
import {
  pluginDefaults,
  type PluginContext,
  type PluginMessageAction,
  type PluginSettingValues,
} from "./types";


interface PersistedState {
  enabled: string[];
  settings: Record<string, PluginSettingValues>;
}

const STORAGE_KEY = "oc-plugins";

function read(): PersistedState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { enabled: [], settings: {} };
    const parsed = JSON.parse(raw) as Partial<PersistedState>;
    return {
      enabled: Array.isArray(parsed.enabled) ? parsed.enabled : [],
      settings: typeof parsed.settings === "object" && parsed.settings ? parsed.settings : {},
    };
  } catch {
    return { enabled: [], settings: {} };
  }
}

function write(state: PersistedState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
  }
}


const running = new Map<string, () => void>();
let active = false;


function resolvedSettings(pluginId: string, stored: PersistedState): PluginSettingValues {
  const plugin = pluginById(pluginId);
  if (!plugin) return {};
  return { ...pluginDefaults(plugin), ...(stored.settings[pluginId] ?? {}) };
}

function startPlugin(pluginId: string, stored: PersistedState): void {
  const plugin = pluginById(pluginId);
  if (!plugin || running.has(pluginId)) return;

  const values = resolvedSettings(pluginId, stored);
  const disposers: (() => void)[] = [];
  const ctx: PluginContext = {
    css: (text) => {
      const el = document.createElement("style");
      el.dataset.plugin = pluginId;
      el.textContent = text;
      document.head.appendChild(el);
      const dispose = () => el.remove();
      disposers.push(dispose);
      return dispose;
    },
    setting: <T extends string | boolean>(key: string) => values[key] as T | undefined,
  };

  try {
    const teardown = plugin.start(ctx);
    if (teardown) disposers.push(teardown);
  } catch (err) {
    console.error(`[plugins] ${pluginId} failed to start`, err);
  }
  running.set(pluginId, () => {
    for (const d of disposers) {
      try {
        d();
      } catch (err) {
        console.error(`[plugins] ${pluginId} failed to stop`, err);
      }
    }
  });
}

function stopPlugin(pluginId: string): void {
  running.get(pluginId)?.();
  running.delete(pluginId);
}

interface PluginStore {
  enabled: string[];
  settings: Record<string, PluginSettingValues>;
  isEnabled: (id: string) => boolean;
  setEnabled: (id: string, on: boolean) => void;
  getSetting: (id: string, key: string) => string | boolean | undefined;
  setSetting: (id: string, key: string, value: string | boolean) => void;
}

export const usePlugins = create<PluginStore>((set, get) => ({
  ...read(),

  isEnabled: (id) => get().enabled.includes(id),

  setEnabled: (id, on) => {
    const state = { enabled: get().enabled, settings: get().settings };
    const next: PersistedState = {
      enabled: on ? [...new Set([...state.enabled, id])] : state.enabled.filter((x) => x !== id),
      settings: state.settings,
    };
    if (on && active) startPlugin(id, next);
    else stopPlugin(id);
    write(next);
    set({ enabled: next.enabled });
  },

  getSetting: (id, key) => {
    const plugin = pluginById(id);
    const fallback = plugin?.settings?.find((s) => s.key === key)?.default;
    return get().settings[id]?.[key] ?? fallback;
  },

  setSetting: (id, key, value) => {
    const settings = {
      ...get().settings,
      [id]: { ...get().settings[id], [key]: value },
    };
    const next: PersistedState = { enabled: get().enabled, settings };
    // Restart so the new value takes effect: stop tears down the old CSS,
    // start reads the fresh setting. No plugin has to handle live updates.
    if (running.has(id)) {
      stopPlugin(id);
      startPlugin(id, next);
    }
    write(next);
    set({ settings });
  },
}));

/**
 * Message-menu entries contributed by enabled plugins. The context passed to a
 * handler is the same narrow one `start` gets, so an action can inject CSS or
 * read its own settings and nothing more.
 */
export function usePluginMessageActions(): {
  pluginId: string;
  pluginName: string;
  action: PluginMessageAction;
  ctx: PluginContext;
}[] {
  const enabled = usePlugins((s) => s.enabled);
  const settings = usePlugins((s) => s.settings);
  return useMemo(
    () =>
      enabled.flatMap((id) => {
        const plugin = pluginById(id);
        if (!plugin?.messageActions?.length) return [];
        const values = { ...pluginDefaults(plugin), ...(settings[id] ?? {}) };
        const ctx: PluginContext = {
          css: (text) => {
            const el = document.createElement("style");
            el.dataset.plugin = id;
            el.textContent = text;
            document.head.appendChild(el);
            return () => el.remove();
          },
          setting: <T extends string | boolean>(key: string) => values[key] as T | undefined,
        };
        return plugin.messageActions.map((action) => ({
          pluginId: id,
          pluginName: plugin.name,
          action,
          ctx,
        }));
      }),
    [enabled, settings],
  );
}

/** Start or temporarily stop installed plugins without changing their saved toggles. */
export function setPluginsActive(next: boolean): void {
  active = next;
  if (!next) {
    for (const pluginId of [...running.keys()]) stopPlugin(pluginId);
    return;
  }

  const stored = read();
  for (const plugin of PLUGINS) {
    if (stored.enabled.includes(plugin.id)) startPlugin(plugin.id, stored);
  }
}

/** Start every enabled plugin. Called once at app boot. */
export function initPlugins(initiallyActive = true): void {
  setPluginsActive(initiallyActive);
}
