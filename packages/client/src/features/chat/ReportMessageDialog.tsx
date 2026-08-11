import { useState } from "react";
import { Flag, ShieldCheck } from "lucide-react";
import type { Message } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { reportMessage } from "../messages/api";
import { t } from "../../lib/i18n";

interface ReportMessageDialogProps {
  message: Message;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ReportMessageDialog({
  message,
  open,
  onOpenChange,
}: ReportMessageDialogProps) {
  const [reason, setReason] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [received, setReceived] = useState(false);

  const submit = async () => {
    setSending(true);
    setError(null);
    try {
      await reportMessage(message, reason);
      setReceived(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The report could not be sent.");
    } finally {
      setSending(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) {
          setReason("");
          setError(null);
          setReceived(false);
        }
      }}
    >
      <DialogContent
        title={received ? "Report received" : "Report message"}
        description={
          received
            ? "The message and its cryptographic proof were submitted."
            : `Report ${message.author.displayName}'s message for review.`
        }
      >
        {received ? (
          <div className="space-y-4">
            <div className="flex gap-3 rounded-xl border border-success/40 bg-success/10 p-3">
              <ShieldCheck aria-hidden className="mt-0.5 size-5 shrink-0 text-success" />
              <p className="text-sm text-ink-secondary">
                {message.ciphertext
                  ? "Only this message was disclosed. OrangChat verified its encryption tag and sender-device signature; the rest of the conversation remains unreadable to the server."
                  : "This message has been preserved for review."}
              </p>
            </div>
            <Button className="w-full" onClick={() => onOpenChange(false)}>
              {t("common.done")}
            </Button>
          </div>
        ) : (
          <div className="space-y-4">
            <blockquote className="max-h-28 overflow-y-auto rounded-xl border border-border bg-surface-1 p-3 text-sm text-ink-secondary">
              {message.content || "Attachment-only message"}
            </blockquote>
            {message.ciphertext && (
              <div className="rounded-xl border border-warning/40 bg-warning/10 p-3 text-xs text-ink-secondary">
                {t("reportMessageDialog.reportingDeliberatelyRevealsThisOneDecrypted")}
              </div>
            )}
            <label className="block">
              <span className="text-sm font-medium">{t("reportMessageDialog.whatHappenedOptional")}</span>
              <textarea
                value={reason}
                onChange={(event) => setReason(event.target.value.slice(0, 1000))}
                rows={4}
                placeholder={t("reportMessageDialog.addContextThatWillHelpReview")}
                className="mt-1.5 w-full resize-y rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm outline-none focus:border-primary"
              />
              <span className="mt-1 block text-right text-xs text-ink-muted">
                {reason.length}/1000
              </span>
            </label>
            {error && <p role="alert" className="text-sm text-danger">{error}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={sending}>
                {t("common.cancel")}
              </Button>
              <Button variant="danger" loading={sending} onClick={() => void submit()}>
                <Flag aria-hidden className="size-4" />
                {t("reportMessageDialog.sendReport")}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
