import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Folder, Hash, Volume2 } from "lucide-react";
import { cn } from "../../lib/cn";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import type { Channel } from "@orangchat/shared";
import { createChannel } from "../servers/api";
import { serverKeys } from "../servers/queries";
import { t } from "../../lib/i18n";

interface CreateChannelDialogProps {
  serverId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;

  only?: ChannelKind;

  categories?: Channel[];
}

type ChannelKind = "text" | "voice" | "category";

export function CreateChannelDialog({
  serverId,
  open,
  onOpenChange,
  only,
  categories = [],
}: CreateChannelDialogProps) {
  const [name, setName] = useState("");
  const [type, setType] = useState<ChannelKind>(only ?? "text");
  const [parentCategoryId, setParentCategoryId] = useState("");
  const kind = only ?? type;
  const client = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: () =>
      createChannel(serverId, {
        name: kind === "category" ? name.trim() : normalizeChannelName(name),
        type: kind,
        ...(kind !== "category" && parentCategoryId ? { parentCategoryId } : {}),
      }),
    onSuccess: (channel) => {
      client.invalidateQueries({ queryKey: serverKeys.detail(serverId) });
      onOpenChange(false);
      setName("");
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
        title={only === "category" ? "Create category" : "Create channel"}
        description={
          only === "category"
            ? "Categories group channels in the sidebar."
            : "Text channels are where your members talk."
        }
      >
        <form onSubmit={onSubmit} className="space-y-4">
          {!only && (
            <div
              role="radiogroup"
              aria-label={t("createChannelDialog.channelType")}
              className="grid grid-cols-3 gap-1 rounded-lg bg-surface-1 p-1"
            >
              {(
                [
                  ["text", Hash, "Text"],
                  ["voice", Volume2, "Voice"],
                  ["category", Folder, "Category"],
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
          )}
          {kind !== "category" && categories.length > 0 && (
            <div>
              <label className="mb-1.5 block text-sm font-medium text-ink-secondary">
                {t("createChannelDialog.category")}
              </label>
              <select
                value={parentCategoryId}
                onChange={(e) => setParentCategoryId(e.target.value)}
                className="h-10 w-full rounded-lg border border-border bg-surface-1 px-2 text-sm"
              >
                <option value="">{t("createChannelDialog.noCategory")}</option>
                {categories.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <TextField
            label={kind === "category" ? "Category name" : "Channel name"}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={kind === "category" ? "Text channels" : "general"}
            hint={kind === "category" ? undefined : "Lowercase, spaces become dashes."}
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
            {kind === "category" ? "Create category" : "Create channel"}
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
