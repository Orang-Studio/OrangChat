import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import type { ProfileWidgetCatalog, ProfileWidgetDefinition } from "@orangchat/shared";
import { ApiError, api } from "../../lib/api";

const CACHE_KEY = "oc:profile-widget-catalog";

export const widgetCatalogKey = ["profile", "widgets", "catalog"] as const;

function readCache(): ProfileWidgetCatalog | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ProfileWidgetCatalog;
    return typeof parsed?.rev === "string" && Array.isArray(parsed.widgets) ? parsed : null;
  } catch {
    return null;
  }
}

function writeCache(catalog: ProfileWidgetCatalog) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(catalog));
  } catch {
  }
}

/**
 * The catalogue is server-owned so we can ship new widget types without a
 * client release. It changes rarely, so the last one is kept in localStorage
 * and revalidated with `?rev=`; a `304` means the cached copy is still current.
 */
async function fetchCatalog(): Promise<ProfileWidgetCatalog> {
  const cached = readCache();
  const path = cached ? `/profile/widgets/catalog?rev=${encodeURIComponent(cached.rev)}` : "/profile/widgets/catalog";
  try {
    const fresh = await api<ProfileWidgetCatalog>(path);
    writeCache(fresh);
    return fresh;
  } catch (error) {
    if (cached && error instanceof ApiError && (error.status === 304 || error.status >= 500)) {
      return cached;
    }
    throw error;
  }
}

export function useWidgetCatalog() {
  const { data } = useQuery({
    queryKey: widgetCatalogKey,
    queryFn: fetchCatalog,
    placeholderData: () => readCache() ?? undefined,
    staleTime: 30 * 60_000,
    gcTime: Infinity,
    retry: 1,
  });
  return data ?? null;
}

/**
 * `type -> definition` for the renderer, which resolves one widget at a time.
 */
export function useWidgetCatalogMap(): Record<string, ProfileWidgetDefinition> | null {
  const catalog = useWidgetCatalog();
  return useMemo(() => {
    if (!catalog) return null;
    return Object.fromEntries(catalog.widgets.map((widget) => [widget.type, widget]));
  }, [catalog]);
}
