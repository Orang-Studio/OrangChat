import { useEffect } from "react";
import { AlertTriangle, Download, RefreshCw, X } from "lucide-react";

import { Button } from "../../components/ui/Button";
import { desktop } from "../../lib/desktop";
import { fetchUpdatePolicy, shouldPrompt, useUpgradeGate } from "./upgradeGate";

/** Re-ask periodically so a long-running window notices a release. */
const POLL_MS = 6 * 60 * 60 * 1000;

/**
 * The three update severities, rendered.
 *
 * `optional` and `recommended` differ only in insistence - both are dismissible
 * and neither blocks anything. `required` is different in kind: the server has
 * already stopped answering this build, so the overlay is not a warning about a
 * future problem but an explanation of a present one, and there is nothing
 * useful behind it to dismiss it back to.
 */
export function UpdateGate() {
  const severity = useUpgradeGate((s) => s.severity);
  const latest = useUpgradeGate((s) => s.latest);
  const dismiss = useUpgradeGate((s) => s.dismiss);
  const prompt = useUpgradeGate(shouldPrompt);

  useEffect(() => {
    void fetchUpdatePolicy();
    const timer = window.setInterval(() => void fetchUpdatePolicy(), POLL_MS);
    return () => window.clearInterval(timer);
  }, []);

  if (severity === "required") return <UpgradeWall latest={latest} />;
  if (!prompt) return null;

  const urgent = severity === "recommended";
  return (
    <div
      role="status"
      className="fixed inset-x-0 bottom-0 z-40 flex items-center gap-3 border-t border-border bg-surface-2 px-4 py-3 text-sm text-ink shadow-lg md:inset-x-auto md:bottom-4 md:right-4 md:max-w-sm md:rounded-lg md:border"
    >
      <Download aria-hidden className="size-4 shrink-0 text-primary" />
      <p className="flex-1">
        {urgent
          ? `OrangChat ${latest} fixes problems in the version you are running.`
          : `OrangChat ${latest} is available.`}
      </p>
      <UpdateAction />
      <button
        type="button"
        onClick={dismiss}
        aria-label="Dismiss update notice"
        className="rounded-lg p-2.5 text-ink-muted hover:bg-surface-3 hover:text-ink"
      >
        <X aria-hidden className="size-5" />
      </button>
    </div>
  );
}

function UpgradeWall({ latest }: { latest: string | null }) {
  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="update-required-title"
      className="fixed inset-0 z-[100] flex items-center justify-center bg-surface-0/95 p-6 backdrop-blur"
    >
      <div className="w-full max-w-sm rounded-xl border border-border bg-surface-1 p-6 text-center shadow-xl">
        <AlertTriangle aria-hidden className="mx-auto size-8 text-warning" />
        <h2 id="update-required-title" className="mt-4 text-lg font-semibold text-ink">
          Update required
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          This version of OrangChat is no longer supported and can no longer connect.
          {latest ? ` Update to ${latest} to continue.` : " Update to continue."}
        </p>
        <div className="mt-5 flex justify-center">
          <UpdateAction />
        </div>
      </div>
    </div>
  );
}

/**
 * The desktop shell can fetch and stage the update itself; anywhere else the
 * only honest action is to reload and take whatever the server now serves.
 */
function UpdateAction() {
  const check = desktop?.checkForUpdates;
  if (check) {
    return (
      <Button size="sm" onClick={() => void check()}>
        <Download aria-hidden className="size-4" />
        Update
      </Button>
    );
  }
  return (
    <Button size="sm" onClick={() => window.location.reload()}>
      <RefreshCw aria-hidden className="size-4" />
      Reload
    </Button>
  );
}
