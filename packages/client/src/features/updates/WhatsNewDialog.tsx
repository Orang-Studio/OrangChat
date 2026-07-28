import { useEffect, useState } from "react";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { useAuthStore } from "../../stores/auth";
import {
  fetchLatestReleaseNotes,
  releaseNotesStorageKey,
  shouldShowReleaseNotes,
  type ReleaseNotes,
} from "./releaseNotes";

interface VisibleReleaseNotes extends ReleaseNotes {
  text: string;
}

/** Shows each published release note once per signed-in browser user. */
export function WhatsNewDialog() {
  const userId = useAuthStore((state) => state.user?.id);
  const [notes, setNotes] = useState<VisibleReleaseNotes | null>(null);

  useEffect(() => {
    if (!userId) return;
    const controller = new AbortController();
    void (async () => {
      try {
        const release = await fetchLatestReleaseNotes(controller.signal);
        if (!release || controller.signal.aborted) return;
        const key = releaseNotesStorageKey(userId);
        const seen = localStorage.getItem(key);
        if (!shouldShowReleaseNotes(release.version, seen)) return;
        const response = await fetch(release.changelogUrl, { cache: "no-store", signal: controller.signal });
        if (!response.ok || controller.signal.aborted) return;
        setNotes({ ...release, text: await response.text() });
      } catch {
        // Release notes are informational: a temporary static-file failure must
        // never interrupt loading chat.
      }
    })();
    return () => controller.abort();
  }, [userId]);

  const close = () => {
    if (notes && userId) {
      try {
        localStorage.setItem(releaseNotesStorageKey(userId), notes.version);
      } catch {
        // Private browsing may block storage; still let the user dismiss it.
      }
    }
    setNotes(null);
  };

  return (
    <Dialog open={notes !== null} onOpenChange={(open) => !open && close()}>
      <DialogContent title="What's new" description={notes ? `OrangChat ${notes.version}` : undefined}>
        <div className="max-h-[50dvh] overflow-y-auto rounded-lg bg-surface-1 p-3">
          <p className="whitespace-pre-wrap break-words text-sm leading-6 text-ink-secondary">
            {notes?.text}
          </p>
        </div>
        <div className="mt-4 flex justify-end">
          <button
            type="button"
            onClick={close}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-ink-on-primary transition-colors hover:bg-primary-hover"
          >
            Got it
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
