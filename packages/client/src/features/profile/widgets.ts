import type {
  ProfileWidget,
  ProfileWidgetBlock,
  ProfileWidgetDefinition,
} from "@orangchat/shared";

export const BUILTIN_LAYOUT: readonly string[] = [
  "pronouns",
  "now-playing",
  "badges",
  "bio",
  "connections",
  "member-since",
];

const NATIVE_RENDER: Record<string, ProfileWidgetBlock> = {
  bio: { block: "section", heading: "{config.title}", body: { block: "native", component: "bio" } },
  pronouns: { block: "native", component: "pronouns" },
  badges: { block: "native", component: "badges" },
  "now-playing": { block: "native", component: "activity" },
  connections: { block: "native", component: "connections" },
  "member-since": {
    block: "section",
    heading: "{config.title}",
    body: { block: "native", component: "member-since" },
  },
};

export function fallbackDefinition(type: string): ProfileWidgetDefinition | null {
  const render = NATIVE_RENDER[type];
  return render ? { type, label: type, render } : null;
}

export function defaultLayout(): ProfileWidget[] {
  return BUILTIN_LAYOUT.map((type, i) => ({ id: `w${i}`, type }));
}

export function resolveLayout(widgets: unknown): ProfileWidget[] {
  if (!Array.isArray(widgets) || widgets.length === 0) return defaultLayout();
  return widgets.filter(
    (w): w is ProfileWidget =>
      !!w && typeof w === "object" && typeof (w as ProfileWidget).type === "string",
  );
}

export interface PlaceholderSource {
  username?: string | null;
  displayName?: string | null;
  pronouns?: string | null;
  createdAt?: string | null;
  fields?: Record<string, string> | null;
  config?: Record<string, unknown> | null;
}

const PLACEHOLDER = /\{([a-zA-Z0-9_.-]+)\}/g;

export function lookupPlaceholder(name: string, source: PlaceholderSource): string | null {
  if (name.startsWith("config.")) {
    const value = source.config?.[name.slice(7)];
    return typeof value === "string" || typeof value === "number" ? String(value) : null;
  }
  if (name.startsWith("field.")) {
    const value = source.fields?.[name.slice(6)];
    return typeof value === "string" ? value : null;
  }
  switch (name) {
    case "username":
      return source.username ?? null;
    case "displayName":
      return source.displayName ?? null;
    case "pronouns":
      return source.pronouns ?? null;
    case "joinedYear": {
      const year = source.createdAt ? new Date(source.createdAt).getFullYear() : NaN;
      return Number.isNaN(year) ? null : String(year);
    }
    default:
      return null;
  }
}

/**
 * Substitutes `{name}` tokens against the widget's own config, the owner's
 * pushed fields and a few built-ins. An unknown token is left exactly as
 * written rather than blanked, so a typo is visible instead of silent.
 *
 * Deliberately not the i18n `t()` interpolation, which uses the same brace
 * shape: this is user data and must never be treated as a catalogue key.
 */
export function substitute(template: string, source: PlaceholderSource): string {
  return template.replace(PLACEHOLDER, (whole, name: string) => {
    const value = lookupPlaceholder(name, source);
    return value ?? whole;
  });
}

export function isBlank(value: string | null | undefined): boolean {
  return !value || value.trim().length === 0;
}

export function listFrom(source: PlaceholderSource, path: string): Record<string, unknown>[] {
  const raw = path.startsWith("config.") ? source.config?.[path.slice(7)] : undefined;
  if (!Array.isArray(raw)) return [];
  return raw.filter((item): item is Record<string, unknown> => !!item && typeof item === "object");
}
