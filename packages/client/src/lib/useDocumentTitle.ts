import { useEffect } from "react";

/** What the tab reads outside any conversation - matches index.html. */
export const DEFAULT_TITLE = "orangchat";

/**
 * Names the browser tab after whatever is open, e.g. "general - orangchat".
 *
 * Pass `null` to leave the title alone; the last mounted caller wins, and the
 * default is restored on unmount so navigating away never strands a stale name.
 */
export function useDocumentTitle(name: string | null) {
  useEffect(() => {
    if (!name) return;
    document.title = `${name} - ${DEFAULT_TITLE}`;
    return () => {
      document.title = DEFAULT_TITLE;
    };
  }, [name]);
}
