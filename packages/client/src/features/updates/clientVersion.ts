import { desktop } from "../../lib/desktop";


export function clientVersionHeaders(): Record<string, string> {
  if (!desktop?.version) return {};
  return {
    "X-Client-Platform": "desktop",
    "X-Client-Version": desktop.version,
  };
}
