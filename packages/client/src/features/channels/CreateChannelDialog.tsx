import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Hash, Volume2 } from "lucide-react";
import { cn } from "../../lib/cn";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { createChannel } from "../servers/api";
import { serverKeys } from "../servers/queries";

interface CreateChannelDialogProps {
  serverId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateChannelDialog({
  serverId,
  open,
  onOpenChange,
}: CreateChannelDialogProps) {
  const [name, setName] = useState("");
  const [type, setType] = useState<"text" | "voice">("text");
  const client = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: () =>
      createChannel(serverId, { name: normalizeChannelName(name), type }),
    onSuccess: (channel) => {
      // The channel:created broadcast also updates the cache; invalidate as a fallback.
      client.invalidateQueries({ queryKey: serverKeys.detail(serverId) });
      onOpenChange(false);
      setName("");
      // Voice channels have no chat route - they're joined from the sidebar.
      if (channel.type === "text") {
        navigate(`/servers/${serverId}/channels/${channel.id}`);
      }
    },
  });

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (name.trim()) mutation.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Create channel"
        description="Text channels are where your members talk."
      >
        <form onSubmit={onSubmit} className="space-y-4">
          <div
            role="radiogroup"
            aria-label="Channel type"
            className="grid grid-cols-2 gap-1 rounded-lg bg-surface-1 p-1"
          >
            {(
              [
                ["text", Hash, "Text"],
                ["voice", Volume2, "Voice"],
              ] as const
            ).map(([value, Icon, label]) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={type === value}
                onClick={() => setType(value)}
                className={cn(
                  "flex items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                  type === value
                    ? "bg-surface-3 text-ink"
                    : "text-ink-muted hover:text-ink-secondary",
                )}
              >
                <Icon aria-hidden className="size-4" />
                {label}
              </button>
            ))}
          </div>
          <TextField
            label="Channel name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="general"
            hint="Lowercase, spaces become dashes."
            maxLength={100}
            autoFocus
          />
          {mutation.isError && (
            <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
              {mutation.error.message}
            </p>
          )}
          <Button
            type="submit"
            loading={mutation.isPending}
            disabled={!name.trim()}
            className="w-full"
          >
            Create channel
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Discord-style channel naming: lowercase, spaces → dashes. */
function normalizeChannelName(raw: string): string {
  return raw.trim().toLowerCase().replace(/\s+/g, "-");
}
