import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Check, Copy } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { createInvite } from "../servers/api";
import { inviteUrl } from "../servers/invite-url";
import { t } from "../../lib/i18n";

interface InviteDialogProps {
  serverId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}


export function InviteDialog({ serverId, open, onOpenChange }: InviteDialogProps) {
  const [copied, setCopied] = useState(false);

  const mutation = useMutation({
    mutationFn: () => createInvite(serverId, { expiresInSeconds: 7 * 24 * 3600 }),
  });

  const { data, isPending, mutate } = mutation;
  useEffect(() => {
    if (open && !data && !isPending) mutate();
  }, [open, data, isPending, mutate]);

  const handleOpenChange = (next: boolean) => {
    onOpenChange(next);
    if (!next) setCopied(false);
  };

  const link = data ? inviteUrl(data.code) : null;

  const copy = async () => {
    if (!link) return;
    await navigator.clipboard.writeText(link);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        title={t("inviteDialog.invitePeople")}
        description={t("inviteDialog.shareThisLinkItUnfurlsInto")}
      >
        {mutation.isError ? (
          <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
            {mutation.error.message}
          </p>
        ) : (
          <div className="flex items-center gap-2">
            <code className="flex-1 truncate rounded-lg border border-border bg-surface-1 px-3 py-2.5 font-mono text-sm">
              {link ?? "Generating…"}
            </code>
            <Button
              variant="secondary"
              size="icon"
              onClick={copy}
              disabled={!link}
              aria-label={copied ? "Copied" : "Copy invite link"}
            >
              {copied ? (
                <Check aria-hidden className="size-4 text-success" />
              ) : (
                <Copy aria-hidden className="size-4" />
              )}
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
