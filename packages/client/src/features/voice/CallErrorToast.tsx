import { useEffect, useMemo } from "react";
import { PhoneOff, VideoOff, X, type LucideIcon } from "lucide-react";
import { useConversations } from "../dms/queries";
import { callActions, useCallStore, type CallNotice } from "./callStore";
import { useVoiceStore, voiceActions } from "./store";
import { t } from "../../lib/i18n";

function Toast({
  message,
  icon: Icon = PhoneOff,
  onDismiss,
}: {
  message: string;
  icon?: LucideIcon;
  onDismiss: () => void;
}) {
  return (
    <div
      role="status"
      className="fixed bottom-4 left-1/2 z-50 flex -translate-x-1/2 items-center gap-2 rounded-lg border border-border bg-surface-3 px-3 py-2 text-sm shadow-lg"
    >
      <Icon aria-hidden className="size-4 shrink-0 text-danger" />
      <span className="text-ink">{message}</span>
      <button
        type="button"
        onClick={onDismiss}
        aria-label={t("callErrorToast.dismiss")}
        className="rounded-lg p-2 text-ink-muted transition-colors hover:text-ink"
      >
        <X aria-hidden className="size-4" />
      </button>
    </div>
  );
}

function noticeMessage(notice: CallNotice, name: string): string {
  switch (notice.reason) {
    case "declined":
      return `${name} declined the call`;
    case "timeout":
      return `${name} did not answer`;
    case "busy":
      return `${name} is on another call`;
    default:
      return `${name} left the call`;
  }
}

/**
 * Surfaces why a call could not start - "already on a call", "everyone else is
 * busy" - and how an outgoing call ended: declined, unanswered, busy. Without
 * this the ack error is swallowed and the call button just looks broken.
 * Auto-clears, since a stale call error is noise.
 */
export function CallErrorToast() {
  const error = useCallStore((s) => s.error);
  const notice = useCallStore((s) => s.notice);
  const mediaError = useVoiceStore((s) => s.mediaError);
  const { data: conversations } = useConversations();

  useEffect(() => {
    if (!error) return;
    const id = setTimeout(() => callActions.dismissError(), 6000);
    return () => clearTimeout(id);
  }, [error]);

  useEffect(() => {
    if (!mediaError) return;
    const id = setTimeout(() => voiceActions.dismissMediaError(), 6000);
    return () => clearTimeout(id);
  }, [mediaError]);

  useEffect(() => {
    if (!notice) return;
    const id = setTimeout(() => callActions.dismissNotice(), 6000);
    return () => clearTimeout(id);
  }, [notice]);

  const name = useMemo(() => {
    if (!notice) return "";
    for (const conversation of conversations ?? []) {
      const match = conversation.participants.find((p) => p.id === notice.userId);
      if (match) return match.displayName;
    }
    // The conversation list can lag a brand-new DM; a generic subject still
    // beats swallowing the outcome.
    return "They";
  }, [conversations, notice]);

  // An outright failure to start is the most actionable of the three, so it wins
  // the single toast slot; a camera that would not open is the next most.
  if (error) return <Toast message={error} onDismiss={() => callActions.dismissError()} />;
  if (mediaError) {
    return (
      <Toast
        message={mediaError}
        icon={VideoOff}
        onDismiss={() => voiceActions.dismissMediaError()}
      />
    );
  }
  if (notice) {
    return (
      <Toast message={noticeMessage(notice, name)} onDismiss={() => callActions.dismissNotice()} />
    );
  }
  return null;
}
