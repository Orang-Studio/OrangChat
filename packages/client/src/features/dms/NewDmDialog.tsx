import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { X } from "lucide-react";
import type { Conversation, User } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { useFriends } from "../friends/queries";
import { addDmParticipants, createDm } from "./api";
import { upsertConversation } from "./queries";

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

/** Pick friends - start a DM/group DM or grow an existing group. */
export function NewDmDialog({ open, onOpenChange, addTo, groupMode = false }: NewDmDialogProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const navigate = useNavigate();
  const client = useQueryClient();
  const [filter, setFilter] = useState("");
  const [selected, setSelected] = useState<User[]>([]);

  const { data: friends } = useFriends();

  // Your friends, minus yourself and anyone already in the conversation being
  // grown, sorted by display name.
  const candidates = useMemo(() => {
    const excluded = new Set([selfId, ...(addTo?.participants.map((p) => p.id) ?? [])]);
    return (friends ?? [])
      .map((f) => f.user)
      .filter((u) => !excluded.has(u.id))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }, [friends, selfId, addTo]);

  const visible = candidates.filter((u) => {
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
        title={addTo ? "Add friends" : groupMode ? "New group" : "New direct message"}
        description={
          groupMode
            ? `Pick 2 to ${MAX_RECIPIENTS} friends to start a group (15 max, including you).`
            : `Pick a friend to message, or a few to start a group.`
        }
      >
        <div className="space-y-3">
          <TextField
            label="Find friends"
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
                {candidates.length === 0
                  ? addTo
                    ? "All your friends are already here."
                    : "No friends yet - add some from the Friends tab first."
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
              ? `Add ${selected.length || ""} ${selected.length === 1 ? "friend" : "friends"}`
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
