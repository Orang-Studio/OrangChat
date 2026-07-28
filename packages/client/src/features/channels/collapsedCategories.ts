import { create } from "zustand";

const STORAGE_KEY = "oc-collapsed-categories";

type CollapsedMap = Record<string, string[]>;

function read(): CollapsedMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as CollapsedMap) : {};
  } catch {
    return {};
  }
}

export const useCollapsedCategories = create<{ byServer: CollapsedMap }>(() => ({
  byServer: read(),
}));

export function toggleCategoryCollapsed(serverId: string, categoryId: string): void {
  const { byServer } = useCollapsedCategories.getState();
  const current = byServer[serverId] ?? [];
  const next = {
    ...byServer,
    [serverId]: current.includes(categoryId)
      ? current.filter((id) => id !== categoryId)
      : [...current, categoryId],
  };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    /* storage unavailable - collapse just won't persist */
  }
  useCollapsedCategories.setState({ byServer: next });
}
