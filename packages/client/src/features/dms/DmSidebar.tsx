import { useState } from 'react';
import { Link, useLocation, useMatch, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  BellOff,
  BellRing,
  Bot,
  Check,
  Copy,
  LogOut,
  Phone,
  Plus,
  User as UserIcon,
  UserX,
  Users,
  X,
} from 'lucide-react';
import type { Conversation, Message } from '@orangchat/shared';
import { cn } from '../../lib/cn';
import { Avatar } from '../../components/Avatar';
import { UnreadBadge } from '../../components/UnreadBadge';
import { UserFooter } from '../../components/UserFooter';
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
  ContextMenuTrigger,
} from '../../components/ui/ContextMenu';
import {
  MUTE_DURATIONS,
  dmNotificationActions,
  useDmMuted,
} from '../servers/notificationPrefs';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { VoicePanel } from '../voice/VoicePanel';
import { callActions } from '../voice/callStore';
import { useAuthStore } from '../../stores/auth';
import { unreadActions, useChannelUnread } from '../../stores/unread';
import { markChannelRead } from '../unread/api';
import { removeFriend } from '../friends/api';
import { removeFriendFromCache, useFriendRequests, useFriends } from '../friends/queries';
import { ProfileDialog } from '../profile/ProfileDialog';
import {
  conversationName,
  conversationToChannel,
  otherParticipants,
  dmKeys,
  useConversations,
} from './queries';
import { useTypingUserIds } from '../chat/typing';
import { GroupIcon } from './GroupIcon';
import { NewDmDialog } from './NewDmDialog';
import { leaveDm } from './api';
import { ActivityStatus } from '../../components/ActivityStatus';
import { t } from "../../lib/i18n";
import { formatShortRelativeTime } from '../../lib/time';
import { useMinuteTick } from '../../lib/useNow';

const copyText = (text: string) => void navigator.clipboard?.writeText(text);

function latestMessagePreview(message: Message | null | undefined, selfId: string | undefined) {
  if (!message) return null;
  const author = message.author.id === selfId ? 'You' : message.author.displayName;
  const content =
    message.content.trim() || (message.attachments.length > 0 ? 'Sent an attachment' : 'Message');
  return `${author}: ${content.replace(/\s+/g, ' ')}`;
}

/**
 * Who to name in the row's typing line. A one-on-one has a single candidate, so
 * the name adds nothing the row title does not already say; a group needs it to
 * be useful, and past two people the list is longer than the row is wide.
 */
function typingPreview(typingIds: string[], conversation: Conversation, isGroup: boolean) {
  if (typingIds.length === 0) return null;
  if (!isGroup) return 'typing…';
  const names = typingIds
    .map((id) => conversation.participants.find((p) => p.id === id)?.displayName)
    .filter((name): name is string => !!name);
  if (names.length === 0) return 'typing…';
  if (names.length === 1) return `${names[0]} is typing…`;
  if (names.length === 2) return `${names[0]} and ${names[1]} are typing…`;
  return 'Several people are typing…';
}

function ConversationRow({
  conversation,
  active,
}: {
  conversation: Conversation;
  active: boolean;
}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const selfId = useAuthStore((s) => s.user?.id);
  const name = conversationName(conversation, selfId);
  const others = otherParticipants(conversation, selfId);
  const isGroup = conversation.type === 'group_dm';
  const latestPreview = latestMessagePreview(conversation.latestMessage, selfId);
  // Someone mid-sentence is newer news than the message before it, so the
  // typing line takes the preview's slot rather than adding a third row.
  const typingLine = typingPreview(
    useTypingUserIds(conversation.id, selfId),
    conversation,
    isGroup,
  );
  // A one-on-one DM has exactly one counterpart; menu actions that target a
  // person (profile, remove friend, copy user ID) only make sense then.
  const other = !isGroup ? others[0] : undefined;
  const { data: friends } = useFriends();
  const isFriend = other ? !!friends?.some((f) => f.user.id === other.id) : false;
  const { unreadCount } = useChannelUnread(conversation.id);
  // The open conversation is being read right now, so its own badge would just
  // be noise racing the read receipt.
  const unread = !active && unreadCount > 0;
  const muted = useDmMuted(conversation.id);
  const now = useMinuteTick();
  const lastActivity = conversation.latestMessage
    ? formatShortRelativeTime(conversation.latestMessage.createdAt, now)
    : null;
  const [profileOpen, setProfileOpen] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);

  const markRead = useMutation({
    mutationFn: () => markChannelRead(conversation.id),
    onSuccess: () => unreadActions.clear(conversation.id),
  });
  // Both confirms stay open across their mutation, so the dialog's own spinner
  // and inline error are what report the outcome - closing on click would have
  // left `loading` and `error` below unreachable, and a toast is a poor place
  // to learn that the thing you just confirmed did not happen.
  const remove = useMutation({
    mutationFn: (userId: string) => removeFriend(userId),
    onSuccess: (_v, userId) => {
      removeFriendFromCache(client, userId);
      setConfirmRemove(false);
    },
  });
  const leave = useMutation({
    mutationFn: () => leaveDm(conversation.id),
    onSuccess: () => {
      unreadActions.clear(conversation.id);
      void client.invalidateQueries({ queryKey: dmKeys.list });
      setConfirmLeave(false);
      // Standing in a conversation that is no longer listed leaves the user on
      // a dead route, so step out of it first.
      if (active) navigate('/app');
    },
  });

  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild>
          <Link
            to={`/dms/${conversation.id}`}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'group relative flex items-start gap-2.5 rounded-lg px-2 py-2 transition-colors md:py-1.5',
              active
                ? 'bg-surface-3 text-ink'
                : unread
                  ? 'text-ink hover:bg-surface-2'
                  : 'text-ink-secondary hover:bg-surface-2 hover:text-ink',
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
            <span className="relative mt-0.5 shrink-0">
              {isGroup ? (
                <GroupIcon iconUrl={conversation.iconUrl} name={name} />
              ) : (
                <Avatar
                  user={other ?? { displayName: name, avatarUrl: null }}
                  status={other?.status}
                  className="size-8"
                />
              )}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex min-w-0 items-center gap-1.5">
                <span
                  className={cn(
                    'truncate text-sm leading-tight',
                    unread || active ? 'text-ink' : 'text-ink-secondary',
                    unread ? 'font-semibold' : 'font-medium',
                  )}
                >
                  {name}
                </span>
                {muted && (
                  <BellOff
                    aria-label={t("dmSidebar.muted")}
                    className="size-3.5 shrink-0 text-ink-muted"
                  />
                )}
                {lastActivity && (
                  <span
                    aria-hidden
                    className="ml-auto shrink-0 text-[11px] font-medium leading-tight text-ink-muted"
                  >
                    {lastActivity}
                  </span>
                )}
              </span>
              {typingLine ? (
                <span className="block truncate text-xs font-semibold leading-4 text-ink-secondary">
                  {typingLine}
                </span>
              ) : latestPreview ? (
                <span className="block truncate text-xs leading-4 text-ink-muted">
                  {latestPreview}
                </span>
              ) : isGroup ? (
                <span className="block truncate text-xs text-ink-muted">
                  {conversation.participants.length} members
                </span>
              ) : (
                other && <ActivityStatus activities={other.activities} linked={false} />
              )}
            </span>
            <UnreadBadge
              count={active ? 0 : unreadCount}
              label={t("dmSidebar.unreadMessages")}
              className="mt-0.5"
            />
          </Link>
        </ContextMenuTrigger>

        <ContextMenuContent>
          <ContextMenuItem disabled={!unread} onSelect={() => markRead.mutate()}>
            <Check aria-hidden className="size-4" />
            {t("dmSidebar.markAsRead")}
          </ContextMenuItem>

          {other && (
            <ContextMenuItem onSelect={() => setProfileOpen(true)}>
              <UserIcon aria-hidden className="size-4" />
              {t("dmSidebar.profile")}
            </ContextMenuItem>
          )}

          <ContextMenuItem
            onSelect={() => void callActions.start(conversationToChannel(conversation, selfId))}
          >
            <Phone aria-hidden className="size-4" />
            {t("dmSidebar.startACall")}
          </ContextMenuItem>

          <ContextMenuSeparator />

          {muted ? (
            <ContextMenuItem onSelect={() => dmNotificationActions.unmute(conversation.id)}>
              <BellRing aria-hidden className="size-4" />
              {t("dmSidebar.unmuteConversation")}
            </ContextMenuItem>
          ) : (
            <ContextMenuSub>
              <ContextMenuSubTrigger>
                <BellOff aria-hidden className="size-4" />
                {t("dmSidebar.muteConversation")}
              </ContextMenuSubTrigger>
              <ContextMenuSubContent>
                {MUTE_DURATIONS.map(({ labelKey, ms }) => (
                  <ContextMenuItem
                    key={labelKey}
                    onSelect={() => dmNotificationActions.mute(conversation.id, ms)}
                  >
                    {t(labelKey)}
                  </ContextMenuItem>
                ))}
              </ContextMenuSubContent>
            </ContextMenuSub>
          )}

          {other && isFriend && (
            <>
              <ContextMenuSeparator />
              <ContextMenuItem danger onSelect={() => setConfirmRemove(true)}>
                <UserX aria-hidden className="size-4" />
                {t("dmSidebar.removeFriend")}
              </ContextMenuItem>
            </>
          )}

          <ContextMenuSeparator />
          <ContextMenuItem danger onSelect={() => setConfirmLeave(true)}>
            {isGroup ? (
              <LogOut aria-hidden className="size-4" />
            ) : (
              <X aria-hidden className="size-4" />
            )}
            {isGroup ? 'Leave Group' : 'Close DM'}
          </ContextMenuItem>

          <ContextMenuSeparator />
          {other && (
            <ContextMenuItem onSelect={() => copyText(other.id)}>
              <Copy aria-hidden className="size-4" />
              {t("dmSidebar.copyUserId")}
            </ContextMenuItem>
          )}
          <ContextMenuItem onSelect={() => copyText(conversation.id)}>
            <Copy aria-hidden className="size-4" />
            {t("dmSidebar.copyChannelId")}
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>

      {other && <ProfileDialog user={other} open={profileOpen} onOpenChange={setProfileOpen} />}

      {/* Both irreversible, neither should fire on a menu tap alone. */}
      <ConfirmDialog
        open={confirmRemove}
        // Reset on close, or a failure the user backed out of greets them again
        // the next time they open the dialog.
        onOpenChange={(next) => {
          setConfirmRemove(next);
          if (!next) remove.reset();
        }}
        title={`Remove ${other?.displayName ?? "this user"}?`}
        description={t("dmSidebar.theyllBeRemovedFromYourFriends")}
        confirmLabel={t("dmSidebar.removeFriend")}
        danger
        loading={remove.isPending}
        error={remove.isError ? remove.error.message : undefined}
        onConfirm={() => other && remove.mutate(other.id)}
      />
      <ConfirmDialog
        open={confirmLeave}
        onOpenChange={(next) => {
          setConfirmLeave(next);
          if (!next) leave.reset();
        }}
        title={isGroup ? `Leave ${name}?` : "Close this conversation?"}
        description={
          isGroup
            ? "You'll need to be re-invited to come back, and you'll stop seeing new messages."
            : "You can start a new conversation with this person any time, but the message list here will be closed."
        }
        confirmLabel={isGroup ? "Leave Group" : "Close DM"}
        danger
        loading={leave.isPending}
        error={leave.isError ? leave.error.message : undefined}
        onConfirm={() => leave.mutate()}
      />
    </>
  );
}

/** Home middle column: direct-message conversation list. */
export function DmSidebar() {
  // Layout-level component: child-route params aren't visible via useParams.
  const channelId = useMatch('/dms/:channelId')?.params.channelId;
  const pathname = useLocation().pathname;
  const onFriends = pathname === '/friends';
  const onDevelopers = pathname === '/developers';
  const { data: conversations } = useConversations();
  const { data: requests } = useFriendRequests();
  const [newDmOpen, setNewDmOpen] = useState(false);
  const [newGroupOpen, setNewGroupOpen] = useState(false);
  const incomingCount = requests?.incoming.length ?? 0;

  return (
    <div className="flex w-60 shrink-0 flex-col bg-surface-1">
      <header className="flex h-12 items-center border-b border-border px-4 font-semibold">
        {t("dmSidebar.directMessages")}
      </header>

      <nav aria-label={t("dmSidebar.conversations")} className="flex-1 space-y-0.5 overflow-y-auto p-2">
        <Link
          to="/friends"
          aria-current={onFriends ? 'page' : undefined}
          className={cn(
            'flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors md:py-1.5',
            onFriends
              ? 'bg-surface-3 text-ink'
              : 'text-ink-secondary hover:bg-surface-2 hover:text-ink',
          )}
        >
          <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-3 text-ink-secondary">
            <Users aria-hidden className="size-4" />
          </span>
          <span className="flex-1 text-sm font-medium">{t("dmSidebar.friends")}</span>
          {incomingCount > 0 && (
            <span className="min-w-5 rounded-md bg-danger px-1.5 text-center text-xs font-semibold text-white">
              {incomingCount}
            </span>
          )}
        </Link>

        <Link
          to="/developers"
          aria-current={onDevelopers ? 'page' : undefined}
          className={cn(
            'flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors md:py-1.5',
            onDevelopers
              ? 'bg-surface-3 text-ink'
              : 'text-ink-secondary hover:bg-surface-2 hover:text-ink',
          )}
        >
          <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-3 text-ink-secondary">
            <Bot aria-hidden className="size-4" />
          </span>
          <span className="flex-1 text-sm font-medium">{t("dmSidebar.developers")}</span>
        </Link>

        <div className="flex items-center justify-between px-2 pb-1 pt-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
            {t("dmSidebar.conversations")}
          </span>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setNewGroupOpen(true)}
              aria-label={t("dmSidebar.newGroup")}
              title={t("dmSidebar.newGroup")}
              className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink"
            >
              <Users aria-hidden className="size-5" />
            </button>
            <button
              type="button"
              onClick={() => setNewDmOpen(true)}
              aria-label={t("dmSidebar.newDirectMessage")}
              title={t("dmSidebar.newDirectMessage")}
              className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink"
            >
              <Plus aria-hidden className="size-5" />
            </button>
          </div>
        </div>

        {conversations?.map((c) => (
          <ConversationRow key={c.id} conversation={c} active={c.id === channelId} />
        ))}
        {conversations?.length === 0 && (
          <p className="px-2 py-4 text-sm text-ink-muted">
            {t("dmSidebar.noConversationsYetHitToMessage")}
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
