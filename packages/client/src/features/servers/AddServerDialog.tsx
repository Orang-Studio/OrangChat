import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { cn } from "../../lib/cn";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { createServer, joinViaInvite } from "./api";
import { parseInviteInput } from "./invite-url";
import { serverKeys } from "./queries";

interface AddServerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Create a new server or join an existing one via invite link. */
export function AddServerDialog({ open, onOpenChange }: AddServerDialogProps) {
  const [mode, setMode] = useState<"create" | "join">("create");
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const client = useQueryClient();
  const navigate = useNavigate();
  const parsedCode = parseInviteInput(code);

  const finish = (serverId: string) => {
    client.invalidateQueries({ queryKey: serverKeys.list });
    onOpenChange(false);
    setName("");
    setCode("");
    navigate(`/servers/${serverId}`);
  };

  const createMutation = useMutation({
    mutationFn: createServer,
    onSuccess: (server) => finish(server.id),
  });
  const joinMutation = useMutation({
    mutationFn: joinViaInvite,
    onSuccess: (server) => finish(server.id),
  });

  const mutation = mode === "create" ? createMutation : joinMutation;

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (mode === "create") {
      if (name.trim()) createMutation.mutate({ name: name.trim() });
    } else if (parsedCode) {
      joinMutation.mutate(parsedCode);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title={mode === "create" ? "Create a server" : "Join a server"}
        description={
          mode === "create"
            ? "Your server is where you and your friends hang out."
            : "Paste an invite link to join an existing server."
        }
      >
        <div
          role="tablist"
          aria-label="Add server mode"
          className="mb-4 grid grid-cols-2 gap-1 rounded-lg bg-surface-1 p-1"
        >
          {(["create", "join"] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={mode === m}
              onClick={() => setMode(m)}
              className={cn(
                "rounded-md px-3 py-1.5 text-sm font-medium capitalize transition-colors",
                mode === m
                  ? "bg-surface-3 text-ink"
                  : "text-ink-muted hover:text-ink-secondary",
              )}
            >
              {m}
            </button>
          ))}
        </div>

        <form onSubmit={onSubmit} className="space-y-4">
          {mode === "create" ? (
            <TextField
              label="Server name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="My orange grove"
              maxLength={100}
              autoFocus
            />
          ) : (
            <TextField
              label="Invite link"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="https://chat.oranges.lt/invite/dQw4w9Wg"
              autoFocus
            />
          )}
          {mutation.isError && (
            <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
              {mutation.error.message}
            </p>
          )}
          <Button
            type="submit"
            loading={mutation.isPending}
            disabled={mode === "create" ? !name.trim() : !parsedCode}
            className="w-full"
          >
            {mode === "create" ? "Create server" : "Join server"}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
