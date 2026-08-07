import { useEffect, useState } from "react";
import { Download, Smartphone, X } from "lucide-react";

interface AndroidRelease {
  versionName: string;
  apkUrl: string;
  size: number;
}

const DISMISSED_VERSION_KEY = "oc-android-app-banner-dismissed";

function isAndroidBrowser(): boolean {
  return typeof navigator !== "undefined" && /android/i.test(navigator.userAgent);
}

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "APK";
  return `${(bytes / 1024 / 1024).toFixed(1)} MB APK`;
}

/** Advertise the current signed APK only to visitors using an Android browser. */
export function AndroidAppBanner() {
  const [release, setRelease] = useState<AndroidRelease | null>(null);

  useEffect(() => {
    if (!isAndroidBrowser()) return;
    const controller = new AbortController();

    void fetch("/download/android/update.json", {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) throw new Error("Android release unavailable");
        return response.json() as Promise<Partial<AndroidRelease>>;
      })
      .then((manifest) => {
        if (
          typeof manifest.versionName !== "string" ||
          typeof manifest.apkUrl !== "string" ||
          typeof manifest.size !== "number"
        ) return;

        const apk = new URL(manifest.apkUrl, window.location.origin);
        if (apk.protocol !== "https:" && apk.origin !== window.location.origin) return;
        if (!apk.pathname.toLowerCase().endsWith(".apk")) return;
        const dismissed = (() => {
          try {
            return localStorage.getItem(DISMISSED_VERSION_KEY);
          } catch {
            return null;
          }
        })();
        if (dismissed === manifest.versionName) return;

        setRelease({
          versionName: manifest.versionName,
          apkUrl: apk.href,
          size: manifest.size,
        });
      })
      .catch(() => {});

    return () => controller.abort();
  }, []);

  if (!release) return null;

  const dismiss = () => {
    try {
      localStorage.setItem(DISMISSED_VERSION_KEY, release.versionName);
    } catch {
      // Private browsing/storage restrictions should not block the download.
    }
    setRelease(null);
  };

  return (
    <aside
      aria-label="OrangChat Android app"
      className="fixed inset-x-3 bottom-[calc(env(safe-area-inset-bottom)+0.75rem)] z-50 mx-auto flex max-w-md items-center gap-3 rounded-xl border border-border-strong bg-surface-3 p-3 shadow-2xl"
    >
      <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-primary-soft text-primary">
        <Smartphone aria-hidden className="size-5" />
      </span>
      <span className="min-w-0 flex-1">
        <strong className="block text-sm">Get OrangChat for Android</strong>
        <span className="block truncate text-xs text-ink-secondary">
          Version {release.versionName} · {formatSize(release.size)}
        </span>
      </span>
      <a
        href={release.apkUrl}
        onClick={dismiss}
        className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-semibold text-ink-on-primary transition-colors hover:bg-primary-hover"
      >
        <Download aria-hidden className="size-4" />
        Download
      </a>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Dismiss Android app download"
        className="shrink-0 rounded-lg p-2.5 text-ink-muted transition-colors hover:bg-surface-4 hover:text-ink"
      >
        <X aria-hidden className="size-5" />
      </button>
    </aside>
  );
}
