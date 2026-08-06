import { useLayoutEffect } from "react";
import { useLocation } from "react-router-dom";
import { setPluginsActive } from "../features/plugins/store";
import { setInstalledThemeActive } from "../features/plugins/themes";
import { applyCustomCss } from "../lib/customCss";

const FIXED_DESIGN_PATHS = new Set([
  "/",
  "/terms",
  "/privacy",
  "/cookies",
  "/guidelines",
  "/legal-notice",
]);

/** Landing and legal pages always use the shipped OrangChat design tokens. */
export function allowsCssOverrides(pathname: string): boolean {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  return !FIXED_DESIGN_PATHS.has(normalized);
}

export function setCssOverridesForPath(pathname: string): void {
  const enabled = allowsCssOverrides(pathname);
  setInstalledThemeActive(enabled);
  setPluginsActive(enabled);
  if (!enabled) applyCustomCss(null);
}

/** Keep global style injectors aligned with the route before the browser paints. */
export function CssOverrideScope() {
  const { pathname } = useLocation();

  useLayoutEffect(() => {
    setCssOverridesForPath(pathname);
  }, [pathname]);

  return null;
}
