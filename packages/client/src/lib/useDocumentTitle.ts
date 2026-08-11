import { useEffect } from "react";


export const DEFAULT_TITLE = "orangchat";


export function useDocumentTitle(name: string | null) {
  useEffect(() => {
    if (!name) return;
    document.title = `${name} - ${DEFAULT_TITLE}`;
    return () => {
      document.title = DEFAULT_TITLE;
    };
  }, [name]);
}
