import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { AtSign, Hash, Loader2, Menu, Search, Users } from "lucide-react";
import { Permissions, hasPermission, type Channel, type Message, type ServerMember } from "@orangchat/shared";
import { useAuthStore } from "../../stores/auth";
import { panelActions } from "../../stores/panels";
import { useMyPermissions } from "../servers/queries";
import { useMessages } from "../messages/queries";
import { SearchDialog } from "../search/SearchDialog";
import { setActiveChannel } from "../unread/active";
import { markChannelRead } from "../unread/api";
import { unreadActions } from "../../stores/unread";
import { clearConversationNotifications } from "../../lib/notifications";
import { useChannelRoom } from "./socket-actions";
import { MessageList } from "./MessageList";
import { Composer } from "./Composer";
import { TypingIndicator } from "./TypingIndicator";

interface ChatViewProps {
  channel: Channel;
  members: ServerMember[];
  /** Extra header controls (e.g. group-DM "add people"). */
  headerActions?: ReactNode;
  /** DM avatar/group image shown beside the conversation name. */
  headerIcon?: ReactNode;
  /** Live activity beneath a DM conversation name. */
  headerSubtitle?: ReactNode;
  /** Start-of-history block; DMs pass their own instead of the #channel welcome. */
  intro?: ReactNode;
}

const HEADER_ICON = { dm: AtSign, group_dm: Users } as const;

/** Main column: channel header, message history, typing line, composer. */
export function ChatView({ channel, members, headerActions, headerIcon, headerSubtitle, intro }: ChatViewProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const [replyTo, setReplyTo] = useState<Message | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);

  useChannelRoom(channel.id, channel.serverId === null);

  // Opening a channel reads it: clear its badge and persist the read cursor.
  useEffect(() => {
    setActiveChannel(channel.id);
    unreadActions.clear(channel.id);
    void clearConversationNotifications(channel.id).catch(() => {});
    void markChannelRead(channel.id).catch(() => {});
    return () => setActiveChannel(null);
  }, [channel.id]);

  const { messages, pendingMessageIds, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useMessages(channel.id);
  const { data: perms } = useMyPermissions(channel.serverId ?? undefined);
  const canManage = perms !== undefined && hasPermission(perms, Permissions.MANAGE_MESSAGES);

  const loadOlder = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  // userId → display name (nickname wins) for rendering + autocompleting mentions.
  const mentionNames = useMemo(() => {
    const map: Record<string, string> = {};
    for (const m of members) map[m.userId] = m.nickname ?? m.user.displayName;
    return map;
  }, [members]);

  // username (lowercased) → { id, label } for resolving @username mentions.
  const mentionUsers = useMemo(() => {
    const map: Record<string, { id: string; name: string }> = {};
    for (const m of members) {
      map[m.user.username.toLowerCase()] = {
        id: m.userId,
        name: m.nickname ?? m.user.displayName,
      };
    }
    return map;
  }, [members]);

  const channelName = channel.name ?? "channel";
  const HeaderIcon =
    HEADER_ICON[channel.type as keyof typeof HEADER_ICON] ?? Hash;

  return (
    <section className="flex min-w-0 flex-1 flex-col bg-surface-2">
      {/* Header */}
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3 md:px-4">
        <button
          type="button"
          onClick={panelActions.openLeft}
          aria-label="Open navigation"
          className="-ml-1 rounded-lg p-2 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink md:hidden"
        >
          <Menu aria-hidden className="size-5" />
        </button>
        {headerIcon ?? <HeaderIcon aria-hidden className="size-5 shrink-0 text-ink-muted" />}
        <div className="min-w-0">
          <h1 className="truncate font-semibold leading-tight">{channelName}</h1>
          {headerSubtitle}
        </div>
        {channel.topic && (
          <>
            <span aria-hidden className="mx-1 hidden h-4 w-px bg-border md:block" />
            <p className="hidden truncate text-sm text-ink-secondary md:block">
              {channel.topic}
            </p>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          {headerActions}
          {channel.serverId && (
            <button
              type="button"
              onClick={() => setSearchOpen(true)}
              aria-label="Search messages"
              className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink"
            >
              <Search aria-hidden className="size-5" />
            </button>
          )}
          {channel.serverId && (
            <button
              type="button"
              onClick={panelActions.toggleRight}
              aria-label="Toggle member list"
              className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink lg:hidden"
            >
              <Users aria-hidden className="size-5" />
            </button>
          )}
        </div>
      </header>

      {channel.serverId && (
        <SearchDialog
          serverId={channel.serverId}
          currentChannelId={channel.id}
          open={searchOpen}
          onOpenChange={setSearchOpen}
        />
      )}

      {isLoading && messages.length === 0 ? (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 aria-hidden className="size-6 animate-spin text-ink-muted" />
        </div>
      ) : (
        <MessageList
          messages={messages}
          pendingMessageIds={pendingMessageIds}
          channelName={channelName}
          hasOlder={!!hasNextPage}
          isLoadingOlder={isFetchingNextPage}
          onLoadOlder={loadOlder}
          selfId={selfId}
          canManage={canManage}
          onReply={setReplyTo}
          mentionNames={mentionNames}
          mentionUsers={mentionUsers}
          intro={intro}
        />
      )}

      <TypingIndicator channelId={channel.id} members={members} />
      <Composer
        channelId={channel.id}
        channelName={channelName}
        replyTo={replyTo}
        onClearReply={() => setReplyTo(null)}
        members={members}
      />
    </section>
  );
}
