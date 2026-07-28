import { useState } from "react";
import { Link, useLocation, useMatch } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Phone, Plus, User as UserIcon, UserX, Users } from "lucide-react";
import type { Conversation } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { Avatar } from "../../components/Avatar";
import { UnreadBadge } from "../../components/UnreadBadge";
import { UserFooter } from "../../components/UserFooter";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "../../components/ui/ContextMenu";
import { VoicePanel } from "../voice/VoicePanel";
import { callActions } from "../voice/callStore";
import { useAuthStore } from "../../stores/auth";
import { unreadActions, useChannelUnread } from "../../stores/unread";
import { markChannelRead } from "../unread/api";
import { removeFriend } from "../friends/api";
import { removeFriendFromCache, useFriendRequests, useFriends } from "../friends/queries";
import { ProfileDialog } from "../profile/ProfileDialog";
import {
  conversationName,
  conversationToChannel,
  otherParticipants,
  useConversations,
} from "./queries";
import { NewDmDialog } from "./NewDmDialog";
import { ActivityStatus } from "../../components/ActivityStatus";

const copyText = (text: string) => void navigator.clipboard?.writeText(text);

function ConversationRow({
  conversation,
  active,
}: {
  conversation: Conversation;
  active: boolean;
}) {
  const client = useQueryClient();
  const selfId = useAuthStore((s) => s.user?.id);
  const name = conversationName(conversation, selfId);
  const others = otherParticipants(conversation, selfId);
  const isGroup = conversation.type === "group_dm";
  // A one-on-one DM has exactly one counterpart; menu actions that target a
  // person (profile, remove friend, copy user ID) only make sense then.
  const other = !isGroup ? others[0] : undefined;
  const { data: friends } = useFriends();
  const isFriend = other ? !!friends?.some((f) => f.user.id === other.id) : false;
  const { unreadCount } = useChannelUnread(conversation.id);
  // The open conversation is being read right now, so its own badge would just
  // be noise racing the read receipt.
  const unread = !active && unreadCount > 0;
  const [profileOpen, setProfileOpen] = useState(false);

  const markRead = useMutation({
    mutationFn: () => markChannelRead(conversation.id),
    onSuccess: () => unreadActions.clear(conversation.id),
  });
  const remove = useMutation({
    mutationFn: (userId: string) => removeFriend(userId),
    onSuccess: (_v, userId) => removeFriendFromCache(client, userId),
  });

  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild>
          <Link
            to={`/dms/${conversation.id}`}
            aria-current={active ? "page" : undefined}
            className={cn(
              "group relative flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors md:py-1.5",
              active
                ? "bg-surface-3 text-ink"
                : unread
                  ? "text-ink hover:bg-surface-2"
                  : "text-ink-secondary hover:bg-surface-2 hover:text-ink",
            )}
          >
            {/* Discord-style pip: the row is legible as unread even when the count
                badge is off-screen on a narrow sidebar. */}
            {unread && (
              <span
                aria-hidden
                className="absolute -left-1 top-1/2 h-2 w-1 -translate-y-1/2 rounded-r-full bg-ink"
              />
            )}
            {isGroup ? (
              <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-3 text-ink-secondary">
                <Users aria-hidden className="size-4" />
              </span>
            ) : (
              <Avatar
                user={other ?? { displayName: name, avatarUrl: null }}
                status={other?.status}
                className="size-8"
              />
            )}
            <span className="min-w-0 flex-1">
              <span className={cn("block truncate text-sm", unread ? "font-semibold" : "font-medium")}>
                {name}
              </span>
              {isGroup && (
                <span className="block truncate text-xs text-ink-muted">
                  {conversation.participants.length} members
                </span>
              )}
              {other && <ActivityStatus activities={other.activities} linked={false} />}
            </span>
            <UnreadBadge count={active ? 0 : unreadCount} label="unread messages" />
          </Link>
        </ContextMenuTrigger>

        <ContextMenuContent>
          <ContextMenuItem
            disabled={!unread}
            onSelect={() => markRead.mutate()}
          >
            <Check aria-hidden className="size-4" />
            Mark As Read
          </ContextMenuItem>

          {other && (
            <ContextMenuItem onSelect={() => setProfileOpen(true)}>
              <UserIcon aria-hidden className="size-4" />
              Profile
            </ContextMenuItem>
          )}

          <ContextMenuItem
            onSelect={() =>
              void callActions.start(conversationToChannel(conversation, selfId))
            }
          >
            <Phone aria-hidden className="size-4" />
            Start a Call
          </ContextMenuItem>

          {other && isFriend && (
            <>
              <ContextMenuSeparator />
              <ContextMenuItem danger onSelect={() => remove.mutate(other.id)}>
                <UserX aria-hidden className="size-4" />
                Remove Friend
              </ContextMenuItem>
            </>
          )}

          <ContextMenuSeparator />
          {other && (
            <ContextMenuItem onSelect={() => copyText(other.id)}>
              <Copy aria-hidden className="size-4" />
              Copy User ID
            </ContextMenuItem>
          )}
          <ContextMenuItem onSelect={() => copyText(conversation.id)}>
            <Copy aria-hidden className="size-4" />
            Copy Channel ID
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>

      {other && (
        <ProfileDialog
          user={other}
          open={profileOpen}
          onOpenChange={setProfileOpen}
        />
      )}
    </>
  );
}

/** Home middle column: direct-message conversation list. */
export function DmSidebar() {
  // Layout-level component: child-route params aren't visible via useParams.
  const channelId = useMatch("/dms/:channelId")?.params.channelId;
  const onFriends = useLocation().pathname === "/friends";
  const { data: conversations } = useConversations();
  const { data: requests } = useFriendRequests();
  const [newDmOpen, setNewDmOpen] = useState(false);
  const [newGroupOpen, setNewGroupOpen] = useState(false);
  const incomingCount = requests?.incoming.length ?? 0;

  return (
    <div className="flex w-60 shrink-0 flex-col bg-surface-1">
      <header className="flex h-12 items-center border-b border-border px-4 font-semibold">
        Direct Messages
      </header>

      <nav aria-label="Conversations" className="flex-1 space-y-0.5 overflow-y-auto p-2">
        <Link
          to="/friends"
          aria-current={onFriends ? "page" : undefined}
          className={cn(
            "flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors md:py-1.5",
            onFriends
              ? "bg-surface-3 text-ink"
              : "text-ink-secondary hover:bg-surface-2 hover:text-ink",
          )}
        >
          <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-3 text-ink-secondary">
            <Users aria-hidden className="size-4" />
          </span>
          <span className="flex-1 text-sm font-medium">Friends</span>
          {incomingCount > 0 && (
            <span className="min-w-5 rounded-md bg-danger px-1.5 text-center text-xs font-semibold text-white">
              {incomingCount}
            </span>
          )}
        </Link>

        <div className="flex items-center justify-between px-2 pb-1 pt-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
            Conversations
          </span>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setNewGroupOpen(true)}
              aria-label="New group"
              title="New group"
              className="rounded p-0.5 text-ink-muted transition-colors hover:text-ink"
            >
              <Users aria-hidden className="size-4" />
            </button>
            <button
              type="button"
              onClick={() => setNewDmOpen(true)}
              aria-label="New direct message"
              title="New direct message"
              className="rounded p-0.5 text-ink-muted transition-colors hover:text-ink"
            >
              <Plus aria-hidden className="size-4" />
            </button>
          </div>
        </div>

        {conversations?.map((c) => (
          <ConversationRow key={c.id} conversation={c} active={c.id === channelId} />
        ))}
        {conversations?.length === 0 && (
          <p className="px-2 py-4 text-sm text-ink-muted">
            No conversations yet - hit + to message someone.
          </p>
        )}
      </nav>

      <VoicePanel />
      <UserFooter />
      <NewDmDialog open={newDmOpen} onOpenChange={setNewDmOpen} />
      <NewDmDialog open={newGroupOpen} onOpenChange={setNewGroupOpen} groupMode />
    </div>
  );
}
