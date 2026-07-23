import { useMemo, useState } from "react";
import { useMutation, useQueries } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { X } from "lucide-react";
import type { Conversation, User } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { getServerDetail } from "../servers/api";
import { serverKeys, useServers } from "../servers/queries";
import { addDmParticipants, createDm } from "./api";
import { upsertConversation } from "./queries";
import { useQueryClient } from "@tanstack/react-query";

interface NewDmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** When set, adds the picked people to this group DM instead of creating one. */
  addTo?: Conversation;
  /** Start framed as a group: needs at least two people to create. */
  groupMode?: boolean;
}

// A group tops out at 15 people: you plus 14 others.
const MAX_RECIPIENTS = 14;

/** Pick people you share a server with - start a DM/group DM or grow one. */
export function NewDmDialog({ open, onOpenChange, addTo, groupMode = false }: NewDmDialogProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const navigate = useNavigate();
  const client = useQueryClient();
  const [filter, setFilter] = useState("");
  const [selected, setSelected] = useState<User[]>([]);

  const { data: servers } = useServers();
  const detailQueries = useQueries({
    queries: (servers ?? []).map((server) => ({
      queryKey: serverKeys.detail(server.id),
      queryFn: () => getServerDetail(server.id),
      enabled: open,
      staleTime: 60_000,
    })),
  });

  // Everyone you share a server with, deduped, minus yourself and anyone
  // already in the conversation being grown.
  const knownUsers = useMemo(() => {
    const excluded = new Set([selfId, ...(addTo?.participants.map((p) => p.id) ?? [])]);
    const byId = new Map<string, User>();
    for (const q of detailQueries) {
      for (const member of q.data?.members ?? []) {
        if (!excluded.has(member.userId)) byId.set(member.userId, member.user);
      }
    }
    return [...byId.values()].sort((a, b) =>
      a.displayName.localeCompare(b.displayName),
    );
  }, [detailQueries, selfId, addTo]);

  const visible = knownUsers.filter((u) => {
    const q = filter.trim().toLowerCase();
    if (!q) return true;
    return (
      u.displayName.toLowerCase().includes(q) || u.username.toLowerCase().includes(q)
    );
  });

  const toggle = (user: User) => {
    setSelected((prev) =>
      prev.some((u) => u.id === user.id)
        ? prev.filter((u) => u.id !== user.id)
        : prev.length < MAX_RECIPIENTS
          ? [...prev, user]
          : prev,
    );
  };

  const mutation = useMutation({
    mutationFn: () =>
      addTo
        ? addDmParticipants(addTo.id, selected.map((u) => u.id))
        : createDm(selected.map((u) => u.id)),
    onSuccess: (conversation) => {
      upsertConversation(client, conversation);
      onOpenChange(false);
      setSelected([]);
      setFilter("");
      if (!addTo) navigate(`/dms/${conversation.id}`);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title={addTo ? "Add people" : groupMode ? "New group" : "New direct message"}
        description={
          groupMode
            ? `Pick 2 to ${MAX_RECIPIENTS} people to start a group (15 max, including you).`
            : `Pick up to ${MAX_RECIPIENTS} people you share a server with.`
        }
      >
        <div className="space-y-3">
          <TextField
            label="Find people"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Search by name or username"
            autoFocus
          />

          {selected.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {selected.map((user) => (
                <button
                  key={user.id}
                  type="button"
                  onClick={() => toggle(user)}
                  title="Remove"
                  className="flex items-center gap-1.5 rounded-md bg-primary-soft px-2.5 py-1 text-sm text-primary"
                >
                  {user.displayName}
                  <X aria-hidden className="size-3.5" />
                </button>
              ))}
            </div>
          )}

          <ul className="max-h-56 space-y-0.5 overflow-y-auto">
            {visible.map((user) => {
              const isSelected = selected.some((u) => u.id === user.id);
              return (
                <li key={user.id}>
                  <button
                    type="button"
                    onClick={() => toggle(user)}
                    aria-pressed={isSelected}
                    className={cn(
                      "flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors",
                      isSelected ? "bg-primary-soft" : "hover:bg-surface-3",
                    )}
                  >
                    <Avatar user={user} status={user.status} className="size-8" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">
                        {user.displayName}
                      </span>
                      <span className="block truncate text-xs text-ink-muted">
                        @{user.username}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
            {visible.length === 0 && (
              <li className="px-2 py-6 text-center text-sm text-ink-muted">
                {knownUsers.length === 0
                  ? "No one to message yet - join a server first."
                  : "No matches."}
              </li>
            )}
          </ul>

          {mutation.isError && (
            <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
              {mutation.error.message}
            </p>
          )}
          <Button
            className="w-full"
            disabled={selected.length === 0 || (groupMode && !addTo && selected.length < 2)}
            loading={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {addTo
              ? `Add ${selected.length || ""} ${selected.length === 1 ? "person" : "people"}`
              : groupMode
                ? `Create group (${selected.length})`
                : selected.length > 1
                  ? `Create group DM (${selected.length})`
                  : "Start conversation"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
