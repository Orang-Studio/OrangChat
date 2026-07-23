import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Check, Menu, MessageSquare, UserPlus, UserX, X } from "lucide-react";
import type { Friend, FriendRequest, User } from "@orangchat/shared";
import { Avatar, STATUS_LABEL } from "../../components/Avatar";
import { DeviceIndicators } from "../../components/DeviceIndicators";
import { ActivityStatus } from "../../components/ActivityStatus";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/Tabs";
import { cn } from "../../lib/cn";
import { panelActions } from "../../stores/panels";
import { createDm } from "../dms/api";
import { upsertConversation } from "../dms/queries";
import { ProfileDialog } from "../profile/ProfileDialog";
import {
  acceptFriendRequest,
  removeFriend,
  removeFriendRequest,
  sendFriendRequest,
} from "./api";
import {
  addFriend,
  removeFriendFromCache,
  removeRequest,
  useFriendRequests,
  useFriends,
} from "./queries";

function useMessageAction(onDone?: () => void) {
  const client = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: (userId: string) => createDm([userId]),
    onSuccess: (conversation) => {
      upsertConversation(client, conversation);
      onDone?.();
      navigate(`/dms/${conversation.id}`);
    },
  });
}

function RowShell({
  user,
  onOpenProfile,
  children,
}: {
  user: User;
  onOpenProfile: () => void;
  children: React.ReactNode;
}) {
  return (
    <li className="flex items-center gap-3 border-t border-border px-2 py-2.5 first:border-t-0 hover:bg-surface-2">
      <button
        type="button"
        onClick={onOpenProfile}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
      >
        <Avatar user={user} className="size-9" />
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold">{user.displayName}</span>
          <span className="flex min-w-0 items-center gap-1.5 text-xs text-ink-muted">
            <span className="truncate">@{user.username} · {STATUS_LABEL[user.status]}</span>
            <DeviceIndicators status={user.status} devices={user.devices} />
          </span>
          <ActivityStatus activities={user.activities} className="mt-0.5" />
        </span>
      </button>
      <div className="flex shrink-0 items-center gap-1.5">{children}</div>
    </li>
  );
}

function IconBtn({
  label,
  onClick,
  danger,
  children,
}: {
  label: string;
  onClick: () => void;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className={cn(
        "rounded-md bg-surface-3 p-2 text-ink-secondary transition-colors hover:text-ink",
        danger && "hover:text-danger",
      )}
    >
      {children}
    </button>
  );
}

function FriendsList({ onOpenProfile }: { onOpenProfile: (u: User) => void }) {
  const client = useQueryClient();
  const { data: friends } = useFriends();
  const message = useMessageAction();
  const remove = useMutation({
    mutationFn: (userId: string) => removeFriend(userId),
    onSuccess: (_v, userId) => removeFriendFromCache(client, userId),
  });

  if (!friends) return null;
  if (friends.length === 0) {
    return <p className="px-2 py-8 text-center text-sm text-ink-muted">No friends yet.</p>;
  }

  return (
    <ul>
      {friends.map((f: Friend) => (
        <RowShell key={f.id} user={f.user} onOpenProfile={() => onOpenProfile(f.user)}>
          <IconBtn label="Message" onClick={() => message.mutate(f.user.id)}>
            <MessageSquare aria-hidden className="size-4" />
          </IconBtn>
          <IconBtn label="Remove friend" danger onClick={() => remove.mutate(f.user.id)}>
            <UserX aria-hidden className="size-4" />
          </IconBtn>
        </RowShell>
      ))}
    </ul>
  );
}

function PendingList({ onOpenProfile }: { onOpenProfile: (u: User) => void }) {
  const client = useQueryClient();
  const { data: requests } = useFriendRequests();

  const accept = useMutation({
    mutationFn: (req: FriendRequest) => acceptFriendRequest(req.id),
    onSuccess: (friend, req) => {
      addFriend(client, friend);
      removeRequest(client, req.id);
    },
  });
  const decline = useMutation({
    mutationFn: (id: string) => removeFriendRequest(id),
    onSuccess: (_v, id) => removeRequest(client, id),
  });

  if (!requests) return null;
  const { incoming, outgoing } = requests;
  if (incoming.length === 0 && outgoing.length === 0) {
    return <p className="px-2 py-8 text-center text-sm text-ink-muted">No pending requests.</p>;
  }

  return (
    <>
      {incoming.length > 0 && (
        <>
          <h3 className="px-2 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">
            Incoming - {incoming.length}
          </h3>
          <ul>
            {incoming.map((r) => (
              <RowShell key={r.id} user={r.user} onOpenProfile={() => onOpenProfile(r.user)}>
                <IconBtn label="Accept" onClick={() => accept.mutate(r)}>
                  <Check aria-hidden className="size-4" />
                </IconBtn>
                <IconBtn label="Decline" danger onClick={() => decline.mutate(r.id)}>
                  <X aria-hidden className="size-4" />
                </IconBtn>
              </RowShell>
            ))}
          </ul>
        </>
      )}
      {outgoing.length > 0 && (
        <>
          <h3 className="px-2 pb-1 pt-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">
            Outgoing - {outgoing.length}
          </h3>
          <ul>
            {outgoing.map((r) => (
              <RowShell key={r.id} user={r.user} onOpenProfile={() => onOpenProfile(r.user)}>
                <IconBtn label="Cancel request" danger onClick={() => decline.mutate(r.id)}>
                  <X aria-hidden className="size-4" />
                </IconBtn>
              </RowShell>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function AddFriend() {
  const client = useQueryClient();
  const [username, setUsername] = useState("");
  const [notice, setNotice] = useState<string | null>(null);

  const send = useMutation({
    mutationFn: () => sendFriendRequest(username.trim()),
    onSuccess: (result) => {
      setUsername("");
      setNotice(
        result.accepted
          ? "You're now friends!"
          : "Friend request sent.",
      );
      void client.invalidateQueries({ queryKey: ["friends"] });
    },
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setNotice(null);
        if (username.trim()) send.mutate();
      }}
      className="space-y-3 pt-2"
    >
      <p className="text-sm text-ink-secondary">
        Add a friend by their username.
      </p>
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <TextField
            label="Username"
            value={username}
            onChange={(e) => {
              setUsername(e.target.value);
              setNotice(null);
            }}
            placeholder="username"
            maxLength={32}
          />
        </div>
        <Button type="submit" loading={send.isPending} disabled={!username.trim()}>
          <UserPlus aria-hidden className="size-4" />
          Send
        </Button>
      </div>
      {send.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {send.error.message}
        </p>
      )}
      {notice && (
        <p className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-success">{notice}</p>
      )}
    </form>
  );
}

/** The Friends hub, rendered in the home area beside the DM list. */
export function FriendsPage() {
  const [profileUser, setProfileUser] = useState<User | null>(null);

  return (
    <div className="flex flex-1 flex-col bg-surface-2">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-4">
        <button
          type="button"
          onClick={panelActions.openLeft}
          aria-label="Open navigation"
          className="rounded-lg p-1.5 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink md:hidden"
        >
          <Menu aria-hidden className="size-5" />
        </button>
        <span className="font-semibold">Friends</span>
      </header>

      <div className="mx-auto w-full max-w-2xl flex-1 overflow-y-auto p-4">
        <Tabs defaultValue="all">
          <TabsList>
            <TabsTrigger value="all">All</TabsTrigger>
            <TabsTrigger value="pending">Pending</TabsTrigger>
            <TabsTrigger value="add">Add Friend</TabsTrigger>
          </TabsList>
          <TabsContent value="all">
            <FriendsList onOpenProfile={setProfileUser} />
          </TabsContent>
          <TabsContent value="pending">
            <PendingList onOpenProfile={setProfileUser} />
          </TabsContent>
          <TabsContent value="add">
            <AddFriend />
          </TabsContent>
        </Tabs>
      </div>

      {profileUser && (
        <ProfileDialog
          user={profileUser}
          open={profileUser !== null}
          onOpenChange={(open) => !open && setProfileUser(null)}
        />
      )}
    </div>
  );
}
