export interface ReleaseNotes {
  version: string;
  changelogUrl: string;
}

interface UpdateManifest {
  versionName?: unknown;
  changelogUrl?: unknown;
}


export function shouldShowReleaseNotes(version: string, acknowledgedVersion: string | null): boolean {
  return version.trim().length > 0 && version !== acknowledgedVersion;
}

export function releaseNotesStorageKey(userId: string): string {
  return `oc-release-notes-seen:${userId}`;
}

/** Reads the same no-cache release manifest that Android uses. */
export async function fetchLatestReleaseNotes(signal?: AbortSignal): Promise<ReleaseNotes | null> {
  const response = await fetch("/download/android/update.json", { cache: "no-store", signal });
  if (!response.ok) return null;
  const manifest = (await response.json()) as UpdateManifest;
  if (typeof manifest.versionName !== "string" || typeof manifest.changelogUrl !== "string") return null;

  const url = new URL(manifest.changelogUrl, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.endsWith(".txt")) return null;
  return { version: manifest.versionName, changelogUrl: url.href };
}
