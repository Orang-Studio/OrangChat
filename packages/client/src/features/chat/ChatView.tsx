import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import {
  AtSign,
  Hash,
  Loader2,
  Lock,
  Menu,
  MoreVertical,
  Search,
  ShieldAlert,
  Users,
  type LucideIcon,
} from "lucide-react";
import {
  Permissions,
  hasPermission,
  type Channel,
  type Message,
  type ServerMember,
  type User,
} from "@orangchat/shared";
import { useAuthStore } from "../../stores/auth";
import { panelActions } from "../../stores/panels";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "../../components/ui/DropdownMenu";
import { useMyPermissions } from "../servers/queries";
import { useMessages } from "../messages/queries";
import { SearchDialog } from "../search/SearchDialog";
import { setActiveChannel } from "../unread/active";
import { markChannelRead } from "../unread/api";
import { unreadActions } from "../../stores/unread";
import { clearConversationNotifications } from "../../lib/notifications";
import { useDocumentTitle } from "../../lib/useDocumentTitle";
import { useE2eeChannel } from "../e2ee/useE2eeChannel";
import { useChannelRoom } from "./socket-actions";
import { MessageList } from "./MessageList";
import { Composer } from "./Composer";
import { TypingIndicator } from "./TypingIndicator";

/** A header action that lives in the overflow menu rather than as its own icon. */
export interface HeaderMenuItem {
  label: string;
  icon: LucideIcon;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

interface ChatViewProps {
  channel: Channel;
  members: ServerMember[];
  /** Extra header controls (e.g. calls). Kept for the few that must stay one tap. */
  headerActions?: ReactNode;
  /**
   * Secondary actions, folded into the header's overflow menu. Everything that
   * is not time-sensitive belongs here - a header of eight anonymous icons is
   * how the DM header got unreadable.
   */
  headerMenu?: HeaderMenuItem[];
  /** DM avatar/group image shown beside the conversation name. */
  headerIcon?: ReactNode;
  /** Live activity beneath a DM conversation name. */
  headerSubtitle?: ReactNode;
  /** Start-of-history block; DMs pass their own instead of the #channel welcome. */
  intro?: ReactNode;
  /**
   * The verification affordance (§6.6). DMs pass one because only they know who
   * the conversation is with; without it the header falls back to a plain
   * "Encrypted" label, which is still true, just not actionable.
   */
  encryptionBadge?: ReactNode;
  /** Strict-mode blocked-state copy; DMs pass one for the same reason. */
  encryptionNotice?: ReactNode;
}

const HEADER_ICON = { dm: AtSign, group_dm: Users } as const;

/** Main column: channel header, message history, typing line, composer. */
export function ChatView({
  channel,
  members,
  headerActions,
  headerMenu,
  headerIcon,
  headerSubtitle,
  intro,
  encryptionBadge,
  encryptionNotice,
}: ChatViewProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const [replyTo, setReplyTo] = useState<Message | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  // `?m=<id>` - what a copied message link carries. The list scrolls to it once
  // history is in, then the param is dropped so a reload doesn't re-jump.
  const [searchParams, setSearchParams] = useSearchParams();
  const jumpToId = searchParams.get("m");

  useChannelRoom(channel.id, channel.serverId === null);
  const encryption = useE2eeChannel(channel.id, channel.type);

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

  const mentionProfiles = useMemo(() => {
    const map: Record<string, User> = {};
    for (const m of members) map[m.userId] = m.user;
    return map;
  }, [members]);

  const channelName = channel.name ?? "channel";
  const HeaderIcon = HEADER_ICON[channel.type as keyof typeof HEADER_ICON] ?? Hash;
  const menuItems: HeaderMenuItem[] = [
    { label: "Search messages", icon: Search, onSelect: () => setSearchOpen(true) },
    ...(headerMenu ?? []),
  ];

  useDocumentTitle(channelName);

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
            <p className="hidden truncate text-sm text-ink-secondary md:block">{channel.topic}</p>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          {encryption.encrypted &&
            (encryptionBadge ?? (
              <span
                title="Messages in this conversation are end-to-end encrypted"
                className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-ink-muted"
              >
                <Lock aria-hidden className="size-4" />
                <span className="hidden sm:inline">Encrypted</span>
              </span>
            ))}
          {headerActions}
          {/* Search leads the overflow, and stands on its own when nothing else
              is in there - a channel header has room, a DM header does not. */}
          {menuItems.length === 1 ? (
            <button
              type="button"
              onClick={() => setSearchOpen(true)}
              aria-label="Search messages"
              className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink"
            >
              <Search aria-hidden className="size-5" />
            </button>
          ) : (
            <DropdownMenu>
              <DropdownMenuTrigger
                aria-label="More"
                title="More"
                className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink"
              >
                <MoreVertical aria-hidden className="size-5" />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {menuItems.map(({ label, icon: Icon, onSelect, danger, disabled }) => (
                  <DropdownMenuItem
                    key={label}
                    danger={danger}
                    disabled={disabled}
                    onSelect={onSelect}
                  >
                    <Icon aria-hidden className="size-4" />
                    {label}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
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

      {/* Encrypted conversations are searched on this device: the server's
          `content ILIKE` cannot see them at all, so routing a DM search through
          it would return nothing and call that an answer. */}
      <SearchDialog
        serverId={channel.serverId ?? undefined}
        currentChannelId={channel.id}
        authors={members.map((m) => m.user)}
        open={searchOpen}
        onOpenChange={setSearchOpen}
      />

      {isLoading && messages.length === 0 ? (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 aria-hidden className="size-6 animate-spin text-ink-muted" />
        </div>
      ) : (
        <MessageList
          messages={messages}
          pendingMessageIds={pendingMessageIds}
          channelName={channelName}
          backgroundUrl={channel.backgroundUrl}
          hasOlder={!!hasNextPage}
          isLoadingOlder={isFetchingNextPage}
          onLoadOlder={loadOlder}
          selfId={selfId}
          canManage={canManage}
          onReply={setReplyTo}
          replyToId={replyTo?.id}
          mentionNames={mentionNames}
          mentionUsers={mentionUsers}
          mentionProfiles={mentionProfiles}
          intro={intro}
          jumpToId={jumpToId}
          onJumpHandled={() =>
            setSearchParams(
              (params) => {
                params.delete("m");
                return params;
              },
              { replace: true },
            )
          }
        />
      )}

      {encryptionNotice}

      {encryption.blocker && (
        <div className="mx-4 mb-2 flex items-start gap-2 rounded-lg border border-border px-3 py-2">
          <ShieldAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-muted" />
          <p className="text-xs text-ink-secondary">{encryption.blocker}</p>
        </div>
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
