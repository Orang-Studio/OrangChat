import { useState } from "react";
import { Link, useLocation, useMatch } from "react-router-dom";
import { Plus, Users } from "lucide-react";
import type { Conversation } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { Avatar } from "../../components/Avatar";
import { UnreadBadge } from "../../components/UnreadBadge";
import { UserFooter } from "../../components/UserFooter";
import { VoicePanel } from "../voice/VoicePanel";
import { useAuthStore } from "../../stores/auth";
import { useChannelUnread } from "../../stores/unread";
import { useFriendRequests } from "../friends/queries";
import { conversationName, otherParticipants, useConversations } from "./queries";
import { NewDmDialog } from "./NewDmDialog";

function ConversationRow({
  conversation,
  active,
}: {
  conversation: Conversation;
  active: boolean;
}) {
  const selfId = useAuthStore((s) => s.user?.id);
  const name = conversationName(conversation, selfId);
  const others = otherParticipants(conversation, selfId);
  const isGroup = conversation.type === "group_dm";
  const { unreadCount } = useChannelUnread(conversation.id);
  // The open conversation is being read right now, so its own badge would just
  // be noise racing the read receipt.
  const unread = !active && unreadCount > 0;

  return (
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
          user={others[0] ?? { displayName: name, avatarUrl: null }}
          status={others[0]?.status}
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
      </span>
      <UnreadBadge count={active ? 0 : unreadCount} label="unread messages" />
    </Link>
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
    </div>
  );
}
