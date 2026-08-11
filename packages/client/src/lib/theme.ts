export type Theme = "dark" | "light";

const STORAGE_KEY = "oc-theme";

export function getTheme(): Theme {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}

export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
  }
}


export function initTheme(): void {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem(STORAGE_KEY);
  } catch {

  }
  if (stored === "light" || stored === "dark") {
    document.documentElement.dataset.theme = stored;
  }
}
